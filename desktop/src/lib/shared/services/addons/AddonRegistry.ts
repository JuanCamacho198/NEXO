/**
 * AddonRegistry — install-by-URL service over the desktop registry store.
 *
 * `manifest_json` keeps whatever the addon served (v2 or v3) and every read
 * goes through the protocol package's `loadManifest`, so a stored v2 row is
 * adapted to `AddonManifestV3` in memory. No SQL migration and no Rust change:
 * the column format never moves.
 *
 * `install(url)`: HTTPS check → platform fetch → `loadManifest` (size cap +
 * content type + shape) → addonId → UPSERT preserving enabled. enable/disable/
 * uninstall mutate rows; the composite's existence-check cache guard (design
 * A1) keeps uninstalled or disabled addon sources from ever serving cached
 * entries.
 *
 * Addon resources never reach the webview: `createAddonResourceTransport`
 * adapts the Rust `fetchAddonResource` command to the protocol package's
 * `ResourceTransport`, adding the per-host concurrency budget and the request
 * deadline the package delegates to each platform.
 */
import { invoke } from '@tauri-apps/api/core';

import { addonIdFromUrl } from './addonId';
import {
  AddonFetchError,
  AddonFetchErrorCode,
  assertHttpsInstallUrl,
  type AddonManifest,
} from '@nexo/manifest-validator';
import {
  createAddonClient,
  loadManifest,
  type AddonClient,
  type AddonDescriptor,
  type AddonManifestV3,
  type ResourceTransport,
} from '@nexo/addon-protocol';

/** Closed set of built-in source names addon manifests must not claim. */
const BUILTIN_SOURCE_NAMES = new Set(['gutendex', 'openlibrary']);

/** The stored column is JSON; the parser reloads the row under this type. */
const STORED_MANIFEST_CONTENT_TYPE = 'application/json';

/** Protocol deadline per addon request (the transport owns cancellation). */
export const DEFAULT_ADDON_TIMEOUT_MS = 8_000;

/** "Max 3 concurrent requests per host" — owned here, where the sockets live. */
export const MAX_CONCURRENT_PER_HOST = 3;

export { AddonFetchErrorCode, AddonFetchError };
export type { AddonManifest };

/**
 * A registry entry. `manifest` is the parsed v3 view of `manifestJson`:
 * `loadManifest` adapts a stored v2 row on read, so nothing rewrites the column.
 * The v2 member of the union only exists for callers that hand-build a row from
 * a v2 manifest; every row the registry itself produces is v3.
 */
export interface InstalledAddonRow {
  id: string;
  url: string;
  manifest: AddonManifestV3 | AddonManifest;
  enabled: boolean;
  addedAt: number;
}

/** Raw persistence row; mirrors Rust `InstalledAddonDto` / Room `AddonEntity`. */
interface RegistryStoreRow {
  id: string;
  url: string;
  manifestJson: string;
  enabled: boolean;
  addedAt: number;
}

export interface AddonRegistryStore {
  listInstalled(): Promise<RegistryStoreRow[]>;
  upsert(row: RegistryStoreRow): Promise<void>;
  setEnabled(id: string, enabled: boolean): Promise<void>;
  uninstall(id: string): Promise<void>;
}

/** Default store: the thin Rust CRUD commands (desktop only). */
class TauriAddonRegistryStore implements AddonRegistryStore {
  async listInstalled(): Promise<RegistryStoreRow[]> {
    return invoke<RegistryStoreRow[]>('listInstalledAddons');
  }

  async upsert(row: RegistryStoreRow): Promise<void> {
    await invoke('upsertInstalledAddon', { addon: row });
  }

  async setEnabled(id: string, enabled: boolean): Promise<void> {
    await invoke('setAddonEnabled', { id, enabled });
  }

  async uninstall(id: string): Promise<void> {
    await invoke('deleteInstalledAddon', { id });
  }
}

export interface AddonFetchResult {
  status: number;
  contentType: string | null;
  body: Uint8Array;
}

/** Platform network layer seam (desktop: the Rust fetchAddonResource command). */
export type AddonTransport = (url: string) => Promise<AddonFetchResult>;

/**
 * Platform network layer seam (desktop: the Rust fetchAddonResource command
 * - addon hosts are never fetched from the webview). Used by the install
 * flow and AddonCatalogProvider; tests inject fakes via the constructors.
 */
export function defaultAddonTransport(): AddonTransport {
  return async (url) => {
    const res = await invoke<{ status: number; contentType: string | null; body: number[] }>(
      'fetchAddonResource',
      { url },
    );
    return { status: res.status, contentType: res.contentType, body: new Uint8Array(res.body) };
  };
}

/**
 * Rejection name the protocol package classifies as `timeout`; the Tauri
 * command has no cancellable socket, so the adapter owns the deadline.
 */
export class AddonTimeoutError extends Error {
  constructor(timeoutMs: number) {
    super(`addon request deadline of ${timeoutMs}ms elapsed`);
    this.name = 'TimeoutError';
  }
}

export interface AddonResourceTransportOptions {
  /** Platform fetch seam; defaults to the Rust command. */
  fetch?: AddonTransport;
  /** Fallback deadline when the caller passes none. */
  timeoutMs?: number;
  maxConcurrentPerHost?: number;
}

/** Per-host gate: at most `limit` requests in flight, FIFO beyond that. */
class HostSlots {
  private active = 0;
  private readonly waiting: Array<() => void> = [];

  constructor(private readonly limit: number) {}

  async run<T>(task: () => Promise<T>): Promise<T> {
    while (this.active >= this.limit) {
      await new Promise<void>((resolve) => this.waiting.push(resolve));
    }
    this.active += 1;
    try {
      return await task();
    } finally {
      this.active -= 1;
      this.waiting.shift()?.();
    }
  }
}

function hostOf(url: string): string | null {
  try {
    return new URL(url).host.toLowerCase();
  } catch {
    return null;
  }
}

function withDeadline<T>(promise: Promise<T>, timeoutMs: number): Promise<T> {
  if (!Number.isFinite(timeoutMs) || timeoutMs <= 0) return promise;
  return new Promise<T>((resolve, reject) => {
    const timer = setTimeout(() => reject(new AddonTimeoutError(timeoutMs)), timeoutMs);
    promise.then(
      (value) => {
        clearTimeout(timer);
        resolve(value);
      },
      (error: unknown) => {
        clearTimeout(timer);
        reject(error);
      },
    );
  });
}

/**
 * Bytes → JSON. A decode failure is reported as a `SyntaxError` so the package
 * classifies it as a payload defect rather than a connectivity one.
 */
function decodeJson(body: Uint8Array): unknown {
  let text: string;
  try {
    text = new TextDecoder('utf-8', { fatal: true }).decode(body);
  } catch {
    throw new SyntaxError('addon payload is not valid UTF-8');
  }
  return JSON.parse(text);
}

/**
 * Protocol transport over the platform fetch. Non-2xx responses return their
 * status with no body (the package reports `status`, not a parse error), every
 * request is bounded by its deadline, and each host is capped at
 * `maxConcurrentPerHost` in-flight requests.
 */
export function createAddonResourceTransport(
  options: AddonResourceTransportOptions = {},
): ResourceTransport {
  const fetchResource = options.fetch ?? defaultAddonTransport();
  const defaultTimeoutMs = options.timeoutMs ?? DEFAULT_ADDON_TIMEOUT_MS;
  const limit = Math.max(1, Math.floor(options.maxConcurrentPerHost ?? MAX_CONCURRENT_PER_HOST));
  const hosts = new Map<string, HostSlots>();

  const slotsFor = (host: string): HostSlots => {
    let slots = hosts.get(host);
    if (slots === undefined) {
      slots = new HostSlots(limit);
      hosts.set(host, slots);
    }
    return slots;
  };

  return async (url, opts) => {
    const timeoutMs = opts?.timeoutMs ?? defaultTimeoutMs;
    const request = async (): Promise<{ status: number; body: unknown }> => {
      const response = await withDeadline(fetchResource(url), timeoutMs);
      if (response.status < 200 || response.status > 299) {
        return { status: response.status, body: undefined };
      }
      return { status: response.status, body: decodeJson(response.body) };
    };
    const host = hostOf(url);
    return host === null ? request() : slotsFor(host).run(request);
  };
}

interface ParsedRow {
  row: RegistryStoreRow;
  manifest: AddonManifestV3;
}

function parseStoredManifest(manifestJson: string): AddonManifestV3 | null {
  try {
    return loadManifest(new TextEncoder().encode(manifestJson), STORED_MANIFEST_CONTENT_TYPE);
  } catch {
    // A row this build cannot parse contributes nothing; it is never rewritten.
    return null;
  }
}

function compareRows(left: ParsedRow, right: ParsedRow): number {
  if (left.row.addedAt !== right.row.addedAt) return left.row.addedAt - right.row.addedAt;
  return left.row.id.localeCompare(right.row.id);
}

export class AddonRegistry {
  private readonly store: AddonRegistryStore;
  private readonly transport: AddonTransport;
  private readonly resourceTransport: ResourceTransport;
  private readonly now: () => number;
  private readonly listeners = new Set<(version: number) => void>();
  private readonly clients = new Map<string, AddonClient>();
  private mutationVersion = 0;

  constructor(
    options: {
      store?: AddonRegistryStore;
      transport?: AddonTransport;
      /** Protocol-level transport (timeout + per-host budget already applied). */
      resourceTransport?: ResourceTransport;
      now?: () => number;
    } = {},
  ) {
    this.store = options.store ?? new TauriAddonRegistryStore();
    this.transport = options.transport ?? defaultAddonTransport();
    this.resourceTransport = options.resourceTransport ?? createAddonResourceTransport();
    this.now = options.now ?? (() => Date.now());
  }

  async install(url: string): Promise<AddonManifestV3> {
    assertHttpsInstallUrl(url);
    const fetched = await this.transport(url);
    if (fetched.status < 200 || fetched.status >= 300) {
      throw new AddonFetchError(
        AddonFetchErrorCode.NETWORK,
        `addon fetch status ${fetched.status}`,
      );
    }
    // Cap, content type and shape are all enforced before anything is stored.
    const manifest = loadManifest(fetched.body, fetched.contentType);
    const raw = new TextDecoder('utf-8').decode(fetched.body);
    return this.persist(url, raw, manifest);
  }

  /** Install with a manifest already fetched + validated (deep-link preview flow). */
  async installManifest(
    url: string,
    manifest: AddonManifestV3 | AddonManifest,
  ): Promise<AddonManifestV3> {
    const json = JSON.stringify(manifest);
    const parsed = loadManifest(new TextEncoder().encode(json), STORED_MANIFEST_CONTENT_TYPE);
    return this.persist(url, json, parsed);
  }

  async listInstalled(): Promise<InstalledAddonRow[]> {
    const rows = await this.readRows();
    return rows.map(({ row, manifest }) => ({
      id: row.id,
      url: row.url,
      manifest,
      enabled: row.enabled,
      addedAt: row.addedAt,
    }));
  }

  /**
   * The rows as protocol descriptors, in install order. This is the only shape
   * the router and the fan-out client ever see.
   */
  async listDescriptors(): Promise<AddonDescriptor[]> {
    const rows = await this.readRows();
    return rows.map(({ row, manifest }) => ({
      id: row.id,
      installUrl: row.url,
      addedAt: row.addedAt,
      enabled: row.enabled,
      manifest,
    }));
  }

  /**
   * Memoized protocol client per addon: one cache and one circuit breaker per
   * addon, so a dead addon never cools a healthy one.
   */
  addonClient(addonId: string): AddonClient {
    let client = this.clients.get(addonId);
    if (client === undefined) {
      client = createAddonClient({ transport: this.resourceTransport });
      this.clients.set(addonId, client);
    }
    return client;
  }

  async setEnabled(id: string, enabled: boolean): Promise<void> {
    try {
      await this.store.setEnabled(id, enabled);
    } finally {
      this.notifyChanged();
    }
  }

  async uninstall(id: string): Promise<void> {
    try {
      await this.store.uninstall(id);
    } finally {
      this.clients.delete(id);
      this.notifyChanged();
    }
  }

  /**
   * Live-composite hook: fires after every registry mutation (install,
   * enable/disable, uninstall) with a monotonically increasing version.
   * Returns an unsubscribe function.
   */
  onChanged(listener: (version: number) => void): () => void {
    this.listeners.add(listener);
    return () => {
      this.listeners.delete(listener);
    };
  }

  private async persist(
    url: string,
    manifestJson: string,
    manifest: AddonManifestV3,
  ): Promise<AddonManifestV3> {
    if (
      BUILTIN_SOURCE_NAMES.has(manifest.id) ||
      manifest.id.includes(':') ||
      manifest.id.startsWith('builtin')
    ) {
      throw new AddonFetchError(
        AddonFetchErrorCode.INVALID_MANIFEST,
        `addon id must not collide with built-in source ids: ${manifest.id}`,
      );
    }
    const addonId = await addonIdFromUrl(url);
    const existing = (await this.store.listInstalled()).find((row) => row.id === addonId);
    await this.store.upsert({
      id: addonId,
      url,
      manifestJson,
      enabled: existing ? existing.enabled : true,
      addedAt: existing ? existing.addedAt : this.now(),
    });
    // A reinstalled manifest invalidates that addon's cached pages and breaker.
    this.clients.delete(addonId);
    this.notifyChanged();
    return manifest;
  }

  private async readRows(): Promise<ParsedRow[]> {
    const rows = await this.store.listInstalled();
    const parsed: ParsedRow[] = [];
    for (const row of rows) {
      const manifest = parseStoredManifest(row.manifestJson);
      if (manifest !== null) parsed.push({ row, manifest });
    }
    return parsed.sort(compareRows);
  }

  private notifyChanged(): void {
    this.mutationVersion += 1;
    for (const listener of [...this.listeners]) {
      try {
        listener(this.mutationVersion);
      } catch {
        // listener failures must never break the mutation flow
      }
    }
  }
}

/** Shared singleton so onChanged listeners stay consistent across consumers */
let sharedRegistry: AddonRegistry | null = null;
export function getAddonRegistry(): AddonRegistry {
  sharedRegistry ??= new AddonRegistry();
  return sharedRegistry;
}
