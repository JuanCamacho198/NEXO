/**
 * Addon payload parsing: pure, no I/O.
 *
 * Every payload is untrusted. The parsers are total: malformed input yields an
 * empty result with the rejected unit counted in `skipped`, never a throw.
 *
 * `assertDeliverableStream` is the single place a stream is accepted or
 * rejected. Torrents are dropped here and nowhere else, so no later layer can
 * reintroduce one.
 */

import { TYPE_BOOK } from './constants';
import type { AddonShape } from './manifest';
import { shapeGutendexCatalog, shapeGutendexMeta } from './shapes/gutendex';
import { shapeLegacyV2Catalog, shapeLegacyV2Meta } from './shapes/legacy-v2';
import { shapeOpenlibraryCatalog, shapeOpenlibraryMeta } from './shapes/openlibrary';

const MAX_STRING_CHARS = 2048;
const MAX_METAS = 200;
const MAX_STREAMS = 100;

const ACCESS_TYPES = ['public-domain', 'borrow', 'purchase'] as const;
const HTTP_PROTOCOLS = ['http:', 'https:'];

export type StreamSource = 'download' | 'external';
export type StreamAccessType = 'public-domain' | 'borrow' | 'purchase' | 'unknown';
export type StreamFormat = 'epub' | 'pdf';

export interface MetaPreview {
  id: string;
  type: string;
  name: string;
  poster?: string;
  description?: string;
  releaseInfo?: string;
  authors: string[];
  languages: string[];
  subjects: string[];
  isbn13?: string;
}

export interface BookStream {
  addonId?: string;
  source: StreamSource;
  url: string;
  name?: string;
  title?: string;
  accessType: StreamAccessType;
  format: StreamFormat;
  /**
   * Addon install time in epoch milliseconds, stamped by the caller. It is the
   * third ordering tier, so it rides on the stream: the ordering function never
   * reads the registry.
   */
  addedAt?: number;
}

export interface CatalogPayload {
  metas: MetaPreview[];
  skipped: number;
}

export interface StreamPayload {
  streams: BookStream[];
  skipped: number;
}

function isPlainObject(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

function truncate(value: string): string {
  return value.length > MAX_STRING_CHARS ? value.slice(0, MAX_STRING_CHARS) : value;
}

/** A non-empty string, truncated rather than rejected. */
function readString(value: unknown): string | undefined {
  if (typeof value !== 'string' || value.length === 0) return undefined;
  return truncate(value);
}

/**
 * Reads a string list, tolerating the two shapes addons send: bare strings and
 * `{ name }` objects. Unreadable entries are dropped, not fatal.
 */
function readStringArray(value: unknown): string[] {
  if (!Array.isArray(value)) return [];
  const entries: string[] = [];
  for (const entry of value) {
    const text =
      typeof entry === 'string'
        ? readString(entry)
        : isPlainObject(entry)
          ? readString(entry.name)
          : undefined;
    if (text !== undefined) entries.push(text);
  }
  return entries;
}

function isIsbn13(value: string): boolean {
  return /^\d{13}$/.test(value);
}

/** `isbn13` from the field, else derived from an `isbn:` id prefix. */
function readIsbn13(value: unknown, id: string): string | undefined {
  const explicit = readString(value);
  if (explicit !== undefined) return explicit;
  if (!id.startsWith('isbn:')) return undefined;
  const derived = id.slice('isbn:'.length);
  return isIsbn13(derived) ? derived : undefined;
}

/** The envelope list a resource answers with, or `null` when there is none. */
function readEnvelopeList(body: unknown, key: string): unknown[] | null {
  if (!isPlainObject(body)) return null;
  const value = body[key];
  return Array.isArray(value) ? value : null;
}

function toMetaPreview(value: unknown): MetaPreview | null {
  if (!isPlainObject(value)) return null;
  const id = readString(value.id);
  const name = readString(value.name);
  if (id === undefined || name === undefined) return null;

  const meta: MetaPreview = {
    id,
    type: readString(value.type) ?? TYPE_BOOK,
    name,
    authors: readStringArray(value.authors),
    languages: readStringArray(value.languages),
    subjects: readStringArray(value.subjects),
  };
  const poster = readString(value.poster);
  if (poster !== undefined) meta.poster = poster;
  const description = readString(value.description);
  if (description !== undefined) meta.description = description;
  const releaseInfo = readString(value.releaseInfo);
  if (releaseInfo !== undefined) meta.releaseInfo = releaseInfo;
  const isbn13 = readIsbn13(value.isbn13, id);
  if (isbn13 !== undefined) meta.isbn13 = isbn13;
  return meta;
}

function capMetas(candidates: MetaPreview[], skipped: number): CatalogPayload {
  if (candidates.length <= MAX_METAS) return { metas: candidates, skipped };
  return {
    metas: candidates.slice(0, MAX_METAS),
    skipped: skipped + (candidates.length - MAX_METAS),
  };
}

/**
 * `shape` selects the named adapter. `"raw"` is the Stremio envelope. A shape
 * outside the closed set counts one unreadable payload, the same as a malformed
 * envelope.
 */
export function parseCatalogPayload(body: unknown, shape: AddonShape): CatalogPayload {
  if (shape === 'gutendex') {
    const shaped = shapeGutendexCatalog(body);
    return capMetas(shaped.metas, shaped.skipped);
  }
  if (shape === 'openlibrary') {
    const shaped = shapeOpenlibraryCatalog(body);
    return capMetas(shaped.metas, shaped.skipped);
  }
  if (shape === 'legacy-v2') {
    const shaped = shapeLegacyV2Catalog(body);
    return capMetas(shaped.metas, shaped.skipped);
  }
  if (shape !== 'raw') return { metas: [], skipped: 1 };

  const entries = readEnvelopeList(body, 'metas');
  if (entries === null) return { metas: [], skipped: 1 };

  const metas: MetaPreview[] = [];
  let skipped = 0;
  for (const entry of entries) {
    const meta = toMetaPreview(entry);
    if (meta === null || metas.length >= MAX_METAS) {
      skipped += 1;
      continue;
    }
    metas.push(meta);
  }
  return { metas, skipped };
}

export function parseMetaPayload(body: unknown, shape: AddonShape): MetaPreview | null {
  if (shape === 'gutendex') return shapeGutendexMeta(body);
  if (shape === 'openlibrary') return shapeOpenlibraryMeta(body);
  if (shape === 'legacy-v2') return shapeLegacyV2Meta(body);
  if (shape !== 'raw') return null;
  if (!isPlainObject(body)) return null;
  return toMetaPreview(body.meta);
}

/** Torrent identity under a non-`infoHash` name. */
function hasTorrentSource(sources: unknown[]): boolean {
  return sources.some(
    (entry) =>
      isPlainObject(entry) &&
      typeof entry.type === 'string' &&
      entry.type.trim().toLowerCase() === 'torrent',
  );
}

/**
 * A payload may only be a link a browser can open. `magnet:` and `data:` are
 * torrent and script vectors, not books, so they never reach the option list.
 */
function isHttpUrl(value: string): boolean {
  try {
    return HTTP_PROTOCOLS.includes(new URL(value).protocol);
  } catch {
    return false;
  }
}

/**
 * The only decision point for a stream. Returns `null` — never throws — for:
 * an `infoHash`, `behaviorHints.bittorrent`, a `sources` entry of type
 * `torrent`, neither `url` nor `externalUrl`, or a link that is not HTTP(S).
 */
export function assertDeliverableStream(raw: unknown): BookStream | null {
  if (!isPlainObject(raw)) return null;

  // A torrent identity makes the entry undeliverable whatever else it carries.
  if ('infoHash' in raw) return null;

  const hints = isPlainObject(raw.behaviorHints) ? raw.behaviorHints : undefined;
  if (hints !== undefined && 'bittorrent' in hints) return null;

  // A source set can point at a magnet link without an `infoHash` field.
  if (Array.isArray(raw.sources) && hasTorrentSource(raw.sources)) return null;

  const url = readString(raw.url);
  const externalUrl = readString(raw.externalUrl);
  const source: StreamSource = url !== undefined ? 'download' : 'external';
  const target = url ?? externalUrl;
  if (target === undefined || !isHttpUrl(target)) return null;

  const stream: BookStream = {
    source,
    url: target,
    accessType: readAccessType(hints),
    format: readFormat(hints, target),
  };
  const name = readString(raw.name);
  if (name !== undefined) stream.name = name;
  const title = readString(raw.title);
  if (title !== undefined) stream.title = title;
  return stream;
}

function readAccessType(hints: Record<string, unknown> | undefined): StreamAccessType {
  const declared = hints?.accessType;
  if (typeof declared !== 'string') return 'unknown';
  return (ACCESS_TYPES as readonly string[]).includes(declared)
    ? (declared as StreamAccessType)
    : 'unknown';
}

function readFormat(hints: Record<string, unknown> | undefined, url: string): StreamFormat {
  const declared = hints?.format;
  if (declared === 'epub' || declared === 'pdf') return declared;
  return inferFormat(url);
}

/** Path extension, ignoring query and fragment. Any other extension is `epub`. */
function inferFormat(url: string): StreamFormat {
  const path = url.split(/[?#]/)[0] ?? url;
  return path.toLowerCase().endsWith('.pdf') ? 'pdf' : 'epub';
}

export function parseStreamPayload(body: unknown): StreamPayload {
  const entries = readEnvelopeList(body, 'streams');
  if (entries === null) return { streams: [], skipped: 1 };

  const streams: BookStream[] = [];
  let skipped = 0;
  for (const entry of entries) {
    const stream = assertDeliverableStream(entry);
    if (stream === null || streams.length >= MAX_STREAMS) {
      skipped += 1;
      continue;
    }
    streams.push(stream);
  }
  return { streams, skipped };
}

const ACCESS_RANK: Record<StreamAccessType, number> = {
  'public-domain': 0,
  borrow: 1,
  purchase: 2,
  unknown: 3,
};

const FORMAT_RANK: Record<StreamFormat, number> = { epub: 0, pdf: 1 };

/** An unstamped addon sorts after every stamped one. */
const UNSTAMPED_ADDON_ADDED_AT = Number.MAX_SAFE_INTEGER;

function compareAddonIds(a: string | undefined, b: string | undefined): number {
  const left = a ?? '';
  const right = b ?? '';
  if (left === right) return 0;
  // Code-unit order, not locale order: the result must not depend on the host.
  return left < right ? -1 : 1;
}

function compareStreams(a: BookStream, b: BookStream): number {
  const byAccess = ACCESS_RANK[a.accessType] - ACCESS_RANK[b.accessType];
  if (byAccess !== 0) return byAccess;

  const byFormat = FORMAT_RANK[a.format] - FORMAT_RANK[b.format];
  if (byFormat !== 0) return byFormat;

  const byAddedAt =
    (a.addedAt ?? UNSTAMPED_ADDON_ADDED_AT) - (b.addedAt ?? UNSTAMPED_ADDON_ADDED_AT);
  if (byAddedAt !== 0) return byAddedAt;

  return compareAddonIds(a.addonId, b.addonId);
}

/**
 * The product order of the option list, not install order: `public-domain` →
 * `borrow` → `purchase` → `unknown`, then `epub` → `pdf`, then addon `addedAt`,
 * then addon `id`. Stable, so ties keep the fan-out order.
 */
export function orderStreams(streams: BookStream[]): BookStream[] {
  return [...streams].sort(compareStreams);
}
