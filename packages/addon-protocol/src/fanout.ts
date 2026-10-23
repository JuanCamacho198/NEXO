/**
 * Isolated fan-out over every addon that can answer a request.
 *
 * One addon failing — timeout, non-2xx, malformed JSON, an oversized body, an
 * unbuildable request URL — never fails the aggregate. Addons whose circuit is
 * open are skipped silently so a dead addon cannot light up the UI on every
 * search.
 */

import { CircuitBreaker } from './breaker';
import { TtlCache } from './cache';
import { TYPE_BOOK } from './constants';
import type { AddonManifestV3, AddonShape } from './manifest';
import {
  orderStreams,
  parseCatalogPayload,
  parseMetaPayload,
  parseStreamPayload,
  type BookStream,
  type MetaPreview,
} from './payload';
import { encodeExtra, type AddonResourceName, type ResourceRequest } from './resourceUrl';
import { declaredExtras, selectAddons, type AddonDescriptor } from './router';
import { buildRequestUrl } from './templates';

/** Response bound from the protocol contract: 512 KiB per payload body. */
const MAX_BODY_BYTES = 512 * 1024;
const DEFAULT_TIMEOUT_MS = 8_000;

/** Seconds, per resource, from the protocol contract. */
const DEFAULT_TTL_MS: Record<AddonResourceName, number> = {
  catalog: 300_000,
  meta: 1_800_000,
  stream: 600_000,
};

export type ResourceTransport = (
  url: string,
  opts: { timeoutMs: number },
) => Promise<{ status: number; body: unknown; cacheControl?: string }>;

export type FailureReason = 'timeout' | 'network' | 'status' | 'payload';

export interface AddonFailure {
  addonId: string;
  reason: FailureReason;
}

export interface FanoutResult<T> {
  items: T[];
  failures: AddonFailure[];
  /** Addons not attempted because their circuit is open. Never a failure. */
  skipped: number;
}

/** A payload unit plus the addon that produced it. */
export type Attributed<T> = T & { addonId: string; addedAt: number };
export type CatalogItem = Attributed<MetaPreview>;
export type MetaItem = Attributed<MetaPreview>;
export type StreamItem = Attributed<BookStream>;

export type Extra = Record<string, string | number | null | undefined>;

export interface CatalogRequest {
  catalogId: string;
  type?: string;
  extra?: Extra;
}

export interface IdRequest {
  id: string;
  type?: string;
}

export interface AddonClient {
  searchCatalog(addons: AddonDescriptor[], req: CatalogRequest): Promise<FanoutResult<CatalogItem>>;
  getMeta(addons: AddonDescriptor[], req: IdRequest): Promise<FanoutResult<MetaItem>>;
  getStreams(addons: AddonDescriptor[], req: IdRequest): Promise<FanoutResult<StreamItem>>;
}

export interface AddonClientOptions {
  transport: ResourceTransport;
  cache?: TtlCache;
  breaker?: CircuitBreaker;
  /** Passed to the transport, which owns cancellation. Defaults to 8000 ms. */
  timeoutMs?: number;
  now?: () => number;
}

type PayloadReader<T> = (body: unknown, manifest: AddonManifestV3) => T[];

interface Job<T> {
  addon: AddonDescriptor;
  url: string;
  cacheKey: string;
  ttlMs: number;
  read: PayloadReader<T>;
}

interface FanOutSpec<T> {
  addons: AddonDescriptor[];
  resource: AddonResourceName;
  type: string;
  id: string;
  /** Per addon, because each addon declares its own allow-list. */
  extraFor: (manifest: AddonManifestV3) => Extra;
  read: PayloadReader<T>;
}

interface ClientContext {
  transport: ResourceTransport;
  cache: TtlCache;
  breaker: CircuitBreaker;
  timeoutMs: number;
}

function shapeOf(manifest: AddonManifestV3): AddonShape {
  return manifest.nexo?.shape ?? 'raw';
}

const readCatalog: PayloadReader<MetaPreview> = (body, manifest) =>
  parseCatalogPayload(body, shapeOf(manifest)).metas;

const readMeta: PayloadReader<MetaPreview> = (body, manifest) => {
  const meta = parseMetaPayload(body, shapeOf(manifest));
  return meta === null ? [] : [meta];
};

const readStreams: PayloadReader<BookStream> = (body) => parseStreamPayload(body).streams;

const MAX_AGE = /(?:^|[\s,])max-age\s*=\s*(\d+)/i;

/** An upstream `max-age` may shorten the client ttl, never extend it. */
function effectiveTtlMs(defaultTtlMs: number, cacheControl?: string): number {
  if (cacheControl === undefined) return defaultTtlMs;
  const match = MAX_AGE.exec(cacheControl);
  if (match === null) return defaultTtlMs;
  return Math.min(defaultTtlMs, Number(match[1]) * 1000);
}

/**
 * Byte size of the parsed body, measured on its JSON serialization. A body
 * that cannot be serialized at all is over the cap by definition.
 */
function exceedsBodyCap(body: unknown): boolean {
  let serialized: string;
  try {
    serialized = JSON.stringify(body) ?? '';
  } catch {
    return true;
  }
  if (serialized.length > MAX_BODY_BYTES) return true;
  return new TextEncoder().encode(serialized).byteLength > MAX_BODY_BYTES;
}

/**
 * The transport owns the deadline, so it must reject with `TimeoutError` or
 * `AbortError`. A JSON parse failure inside the transport is a payload problem;
 * anything else is treated as a network problem.
 */
function classifyTransportError(error: unknown): FailureReason {
  if (error instanceof SyntaxError) return 'payload';
  const name =
    typeof error === 'object' && error !== null && 'name' in error
      ? String((error as { name: unknown }).name)
      : '';
  return name === 'TimeoutError' || name === 'AbortError' ? 'timeout' : 'network';
}

/** Lowercase scheme and host, no trailing slash, no fragment. */
export function normalizeUrl(raw: string): string {
  let url: URL;
  try {
    url = new URL(raw);
  } catch {
    return raw.trim();
  }
  const path = url.pathname.replace(/\/+$/, '');
  return `${url.protocol.toLowerCase()}//${url.host.toLowerCase()}${path}${url.search}`;
}

function cacheKeyOf(
  addonId: string,
  request: { resource: string; type: string; id: string },
  extra: Extra,
): string {
  return `${addonId}|${request.resource}|${request.type}|${request.id}|${encodeExtra(extra)}`;
}

function declaredOnly(manifest: AddonManifestV3, catalogId: string, extra: Extra): Extra {
  const allowed = new Set(declaredExtras(manifest, catalogId));
  const sent: Extra = {};
  for (const [key, value] of Object.entries(extra)) {
    if (allowed.has(key)) sent[key] = value;
  }
  return sent;
}

type Attempt<T> = { items: T[] } | { reason: FailureReason };

async function readAddonResource<T>(
  ctx: ClientContext,
  job: Job<T>,
): Promise<Attempt<T>> {
  const cached = ctx.cache.get(job.cacheKey) as T[] | undefined;
  if (cached !== undefined) return { items: cached };

  let response: Awaited<ReturnType<ResourceTransport>>;
  try {
    response = await ctx.transport(job.url, { timeoutMs: ctx.timeoutMs });
  } catch (error) {
    return { reason: classifyTransportError(error) };
  }
  if (response.status < 200 || response.status > 299) return { reason: 'status' };
  if (exceedsBodyCap(response.body)) return { reason: 'payload' };

  const items = job.read(response.body, job.addon.manifest);
  ctx.cache.set(job.cacheKey, items, effectiveTtlMs(job.ttlMs, response.cacheControl));
  return { items };
}

function stamp<T>(item: T, addon: AddonDescriptor): Attributed<T> {
  // A new object per read keeps the cached copy free of presentation fields.
  return { ...item, addonId: addon.id, addedAt: addon.addedAt };
}

async function collect<T>(
  ctx: ClientContext,
  spec: FanOutSpec<T>,
): Promise<FanoutResult<Attributed<T>>> {
  const jobs: Job<T>[] = [];
  const failures: AddonFailure[] = [];
  const seenUrls = new Set<string>();
  let skipped = 0;

  for (const addon of selectAddons(spec.addons, {
    resource: spec.resource,
    type: spec.type,
    id: spec.id,
  })) {
    const extra = spec.extraFor(addon.manifest);
    const request: ResourceRequest = {
      resource: spec.resource,
      type: spec.type,
      id: spec.id,
      extra,
    };

    let url: string;
    try {
      url = buildRequestUrl(addon, request);
    } catch {
      // An unbuildable request is this addon's defect alone.
      failures.push({ addonId: addon.id, reason: 'payload' });
      continue;
    }

    const normalized = normalizeUrl(url);
    if (seenUrls.has(normalized)) continue;
    seenUrls.add(normalized);

    if (!ctx.breaker.canRequest(addon.id)) {
      skipped += 1;
      continue;
    }

    jobs.push({
      addon,
      url,
      cacheKey: cacheKeyOf(addon.id, request, extra),
      ttlMs: DEFAULT_TTL_MS[spec.resource],
      read: spec.read,
    });
  }

  const settled = await Promise.allSettled(jobs.map((job) => readAddonResource(ctx, job)));
  const items: Array<Attributed<T>> = [];

  settled.forEach((outcome, index) => {
    const job = jobs[index] as Job<T>;
    // `readAddonResource` only rejects when a payload adapter throws; the
    // isolation guarantee has to survive that too.
    const attempt: Attempt<T> =
      outcome.status === 'rejected' ? { reason: 'payload' } : outcome.value;

    if ('reason' in attempt) {
      ctx.breaker.recordFailure(job.addon.id);
      failures.push({ addonId: job.addon.id, reason: attempt.reason });
      return;
    }
    ctx.breaker.recordSuccess(job.addon.id);
    for (const item of attempt.items) items.push(stamp(item, job.addon));
  });

  return { items, failures, skipped };
}

export function createAddonClient(opts: AddonClientOptions): AddonClient {
  const now = opts.now ?? Date.now;
  const ctx: ClientContext = {
    transport: opts.transport,
    cache: opts.cache ?? new TtlCache({ now }),
    breaker: opts.breaker ?? new CircuitBreaker({ now }),
    timeoutMs: opts.timeoutMs ?? DEFAULT_TIMEOUT_MS,
  };

  return {
    async searchCatalog(addons, req) {
      const extra = req.extra ?? {};
      return collect(ctx, {
        addons,
        resource: 'catalog',
        type: req.type ?? TYPE_BOOK,
        id: req.catalogId,
        extraFor: (manifest) => declaredOnly(manifest, req.catalogId, extra),
        read: readCatalog,
      });
    },

    async getMeta(addons, req) {
      return collect(ctx, {
        addons,
        resource: 'meta',
        type: req.type ?? TYPE_BOOK,
        id: req.id,
        extraFor: () => ({}),
        read: readMeta,
      });
    },

    async getStreams(addons, req) {
      const result = await collect(ctx, {
        addons,
        resource: 'stream',
        type: req.type ?? TYPE_BOOK,
        id: req.id,
        extraFor: () => ({}),
        read: readStreams,
      });
      return { ...result, items: orderStreams(result.items) as StreamItem[] };
    },
  };
}
