/**
 * AddonCatalogProvider — one CatalogProvider per installed addon manifest.
 * Sources are `addon:<addonId>`. Manifests MAY declare endpoint templates
 * (searchUrl/detailsUrl, validated https by the manifest validator); when
 * present, search/getDetails fetch the addon's own catalog payloads through
 * the platform network layer (desktop: the fetchAddonResource Rust command)
 * and parse them into CatalogBooks with provider = the addon source id.
 * Endpoint-less manifests stay browse-only: stable empty page / NOT_FOUND,
 * zero I/O. Malformed payloads reject the whole page with a stable error.
 */
import { catalogError, mapHttpStatusToCode } from '../catalog/errors';
import { backoffDelayMs, MAX_PAGE_SIZE, shouldRetryStatus } from '../catalog/policy';
import type { AddonConsentGate } from './AddonConsent';
import type {
  CatalogBook,
  CatalogFeaturedSort,
  CatalogProvider,
  CatalogSource,
  CatalogSourceInfo,
  PagedResult,
} from '../catalog/CatalogProvider';
import type { AddonAccessResolution } from '../catalog/CatalogProvider';
import type { AccessGroup, AccessOption } from '../catalog/accessResolver';
import { addonSource } from '../catalog/CatalogProvider';
import { computeNextPage, resolveDownloadUrl } from '../catalog/mappers';
import {
  MAX_MANIFEST_BYTES,
  declaredCapabilities,
  type AddonManifest,
} from '@nexo/manifest-validator';
import { defaultAddonTransport, type AddonTransport } from './AddonRegistry';
import type { AddonFetchResult } from './AddonRegistry';

const EMPTY_PAGE: PagedResult = { results: [], nexoPage: null, totalCount: 0 };

function isNonEmptyString(value: unknown): value is string {
  return typeof value === 'string' && value.length > 0;
}

function isPlainObject(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

function malformed(detail: string): never {
  throw catalogError('UPSTREAM_ERROR', `malformed addon payload: ${detail}`);
}

/** Substitute `{placeholders}` in a validated endpoint template. */
function renderTemplate(template: string, params: Record<string, string>): string {
  return template.replace(/\{(\w+)\}/g, (match, key: string) => params[key] ?? match);
}

/** Optional cover/download URLs: https strings pass, anything else maps to null. */
function httpsOrNull(value: unknown): string | null {
  return typeof value === 'string' && value.startsWith('https://') ? value : null;
}

/** Optional string-array field: absent → []; anything else → stable malformed error. */
function stringArray(value: unknown, field: string): string[] {
  if (value === undefined) return [];
  if (!Array.isArray(value) || !value.every((v) => typeof v === 'string')) {
    malformed(`${field} must be an array of strings`);
  }
  return value as string[];
}

function parseAddonBook(value: unknown, bookId: string, sourceId: string): CatalogBook {
  if (!isPlainObject(value)) malformed('book must be an object');
  if (!isNonEmptyString(value.id) || !isNonEmptyString(value.title)) {
    malformed('book missing id/title');
  }
  return {
    id: bookId,
    provider: sourceId,
    title: value.title,
    authors: stringArray(value.authors, 'authors'),
    coverUrl: httpsOrNull(value.coverUrl),
    languages: stringArray(value.languages, 'languages'),
    subjects: stringArray(value.subjects, 'subjects'),
    downloadUrl: httpsOrNull(value.downloadUrl),
  };
}

function parseSearchPayload(payload: unknown, sourceId: string, page: number): PagedResult {
  if (!isPlainObject(payload) || !Array.isArray(payload.results)) {
    malformed('payload must be an object with a results array');
  }
  let totalCount = payload.results.length;
  if (payload.totalCount !== undefined) {
    if (
      typeof payload.totalCount !== 'number' ||
      !Number.isInteger(payload.totalCount) ||
      payload.totalCount < 0
    ) {
      malformed('totalCount must be a non-negative integer');
    }
    totalCount = payload.totalCount;
  }
  const books = payload.results.map((entry, i) => {
    if (!isPlainObject(entry) || !isNonEmptyString(entry.id)) malformed(`book ${i} missing id`);
    return parseAddonBook(entry, `${sourceId}:${entry.id as string}`, sourceId);
  });
  const clamped = books.slice(0, MAX_PAGE_SIZE);
  return {
    results: clamped,
    nexoPage: computeNextPage(page, clamped.length, totalCount),
    totalCount,
  };
}

/**
 * Fail-closed default gate: a provider built without an explicit consent gate
 * (tests, one-off constructions) denies every resolve. Production providers
 * always receive the shared `addonConsent` singleton via `liveComposite`.
 */
const denyAllAddonConsent: AddonConsentGate = {
  ensureLoaded: () => Promise.resolve(),
  hasConsent: () => false,
};

/**
 * Addon access resolution (slice 9): the consent gate, the no-endpoint and
 * no-capability empty results (all zero I/O), then the `resolveUrl` fetch
 * with access-item parsing, the in-app download gate, and the external
 * options. Re-exports the port types so resolution consumers import one
 * module.
 */
export type { AddonAccessResolution };
export type { AccessOption };

/** The only known v2 capability id: the addon may resolve reading access. */
const RESOLVE_CAPABILITY = 'resolve';

/** Per-item `accessType` wire values (open set on the wire; unknown ⇒ null). */
export type AddonAccessType = 'free' | 'buy' | 'subscribe';

/**
 * Parse a v2 per-item `accessType` wire value (Android
 * `ManifestValidator.parseAccessType` parity: trimmed, case-insensitive).
 * Unknown or missing values return null so resolve callers fail closed
 * (never free).
 */
function parseAccessType(raw: unknown): AddonAccessType | null {
  if (typeof raw !== 'string') return null;
  switch (raw.trim().toLowerCase()) {
    case 'free':
      return 'free';
    case 'buy':
      return 'buy';
    case 'subscribe':
      return 'subscribe';
    default:
      return null;
  }
}

/** License tokens cleared for the in-app download path (closed set, Android parity). */
const LICENSE_CLEARED_TOKENS: ReadonlySet<string> = new Set([
  'public-domain',
  'public domain',
  'cc0',
  'cc0-1.0',
  'pd',
]);

/** True only for license tokens in the closed cleared set (case-insensitive). */
function isLicenseCleared(license: string | null): boolean {
  return license !== null && LICENSE_CLEARED_TOKENS.has(license.trim().toLowerCase());
}

/**
 * One parsed resolve item. `readUrl`/`downloadUrl` are https-only (anything
 * else parsed to null at the boundary); `accessType` is null for unknown
 * wire values (fail closed for the download path at `mayDownloadInApp`).
 */
export interface AddonResolveItem {
  accessType: AddonAccessType | null;
  license: string | null;
  readUrl: string | null;
  downloadUrl: string | null;
  /** Only `free` (or license-cleared) items with an https download may flow in-app. */
  mayDownloadInApp: boolean;
}

function accessGroupOf(accessType: AddonAccessType | null): AccessGroup {
  switch (accessType) {
    case 'buy':
      return 'BUY';
    case 'subscribe':
      return 'SUBSCRIBE';
    default:
      return 'FREE';
  }
}

/** Identity params for a `resolveUrl` template (missing identity ⇒ empty). */
function resolveParams(book: CatalogBook): Record<string, string> {
  return {
    isbn: encodeURIComponent(book.isbn13 ?? book.isbn10 ?? ''),
    title: encodeURIComponent(book.title),
    author: encodeURIComponent(book.authors[0] ?? ''),
    openLibraryId: encodeURIComponent(book.openLibraryWorkId ?? ''),
    googleBooksId: encodeURIComponent(book.googleBooksId ?? ''),
  };
}

/**
 * Parse a resolve payload (`{results: [...]}`) into access items
 * (Android `parseResolvePayload` parity). `accessType`/`license`/`readUrl`/
 * `downloadUrl` are all optional per item; non-https URLs are dropped per
 * item (never fail the whole payload); unknown `accessType` parses to null.
 */
function parseResolvePayload(payload: unknown): AddonResolveItem[] {
  if (!isPlainObject(payload) || !Array.isArray(payload.results)) {
    malformed('resolve payload must be an object with a results array');
  }
  return payload.results.map((entry, i) => {
    if (!isPlainObject(entry)) malformed(`resolve item ${i} must be an object`);
    const accessType = parseAccessType(entry.accessType);
    const license = typeof entry.license === 'string' ? entry.license : null;
    const readUrl = httpsOrNull(entry.readUrl);
    const downloadUrl = httpsOrNull(entry.downloadUrl);
    return {
      accessType,
      license,
      readUrl,
      downloadUrl,
      mayDownloadInApp:
        (accessType === 'free' || isLicenseCleared(license)) && downloadUrl !== null,
    } satisfies AddonResolveItem;
  });
}

/** Empty resolution: no in-app download, no external options, zero I/O. */
export const EMPTY_ADDON_ACCESS: AddonAccessResolution = {
  canDownloadInApp: false,
  downloadUrl: null,
  options: [],
};

export class AddonCatalogProvider implements CatalogProvider {
  private readonly source: CatalogSourceInfo;
  private readonly addonId: string;
  private readonly manifest: AddonManifest;
  private readonly searchUrl?: string;
  private readonly detailsUrl?: string;
  private readonly consent: AddonConsentGate;

  constructor(
    manifest: AddonManifest,
    addonId: string,
    private readonly transport: AddonTransport = defaultAddonTransport(),
    private readonly retryDelayMs: number = backoffDelayMs(0),
    consent: AddonConsentGate = denyAllAddonConsent,
  ) {
    this.source = {
      sourceId: addonSource(addonId),
      name: manifest.name,
      kind: 'addon' as const,
    };
    this.addonId = addonId;
    this.manifest = manifest;
    this.searchUrl = manifest.searchUrl;
    this.detailsUrl = manifest.detailsUrl;
    this.consent = consent;
  }

  listSources(): CatalogSourceInfo[] {
    return [this.source];
  }

  async search(query: string, page: number): Promise<PagedResult> {
    if (!this.searchUrl) return EMPTY_PAGE;
    const url = renderTemplate(this.searchUrl, {
      query: encodeURIComponent(query),
      page: String(page),
    });
    const payload = await this.fetchJson(url);
    return parseSearchPayload(payload, this.source.sourceId, page);
  }

  async getDetails(id: string): Promise<CatalogBook> {
    const prefix = `${this.source.sourceId}:`;
    if (!id.startsWith(prefix) || id.length <= prefix.length) {
      throw catalogError('NOT_FOUND', `unknown catalog id ${id}`);
    }
    if (!this.detailsUrl) {
      throw catalogError('NOT_FOUND', `addon source has no details endpoint: ${id}`);
    }
    const url = renderTemplate(this.detailsUrl, {
      bookId: encodeURIComponent(id.slice(prefix.length)),
    });
    return parseAddonBook(await this.fetchJson(url), id, this.source.sourceId);
  }

  /** Addons opt out of featured rails; the rail auto-hides (fail-closed). */
  async featured(_sort: CatalogFeaturedSort, limit: number): Promise<PagedResult> {
    if (!Number.isInteger(limit) || limit < 1) {
      throw catalogError('INVALID_PAGE', `limit must be >= 1, got ${limit}`);
    }
    return EMPTY_PAGE;
  }

  supportsFeatured(_sort: CatalogFeaturedSort): boolean {
    return false;
  }

  /**
   * Per-source search scoped to this addon's own source; anything else fails
   * closed with an empty page (never a crash, never a silent composite search).
   */
  async searchSource(sourceId: CatalogSource, query: string, page: number): Promise<PagedResult> {
    if (sourceId !== this.source.sourceId) {
      return EMPTY_PAGE;
    }
    return this.search(query, page);
  }

  /**
   * Consent-gated resolve (slice 9). Consent gates RESOLVE ONLY:
   * `search` / `getDetails` stay ungated (Android parity + the spec's
   * "resolve operation" phrasing).
   *
   * - No consent ⇒ rejects `CONSENT_REQUIRED` with ZERO transport calls.
   * - No `resolveUrl` (every v1 manifest) ⇒ empty resolution, zero I/O.
   * - Declared `capabilities` without `resolve` ⇒ same empty result, zero
   *   I/O. An UNDECLARED list leaves `resolveUrl` authoritative, preserving
   *   v1-style manifests.
   * - Otherwise the `resolveUrl` renders from the book identity and fetches
   *   through the existing transport (retry + 64 KiB cap + JSON parse).
   */
  async resolveAddonAccess(book: CatalogBook): Promise<AddonAccessResolution> {
    await this.consent.ensureLoaded();
    if (!this.consent.hasConsent(this.addonId)) {
      throw catalogError(
        'CONSENT_REQUIRED',
        `addon has no network consent: ${this.source.sourceId}`,
      );
    }
    const resolveUrl = this.manifest.resolveUrl;
    if (typeof resolveUrl !== 'string' || resolveUrl.length === 0) {
      return { ...EMPTY_ADDON_ACCESS, options: [] };
    }
    if (
      this.manifest.capabilities !== undefined &&
      !declaredCapabilities(this.manifest).includes(RESOLVE_CAPABILITY)
    ) {
      return { ...EMPTY_ADDON_ACCESS, options: [] };
    }
    const items = parseResolvePayload(
      await this.fetchJson(renderTemplate(resolveUrl, resolveParams(book))),
    );
    const candidate = items.find((item) => item.mayDownloadInApp)?.downloadUrl ?? null;
    const canDownloadInApp = candidate !== null && book.isPublicDomain === true;
    return {
      canDownloadInApp,
      downloadUrl: canDownloadInApp ? candidate : null,
      options: items.flatMap((item) =>
        item.readUrl === null
          ? []
          : [
              {
                group: accessGroupOf(item.accessType),
                titleKey: 'discover.accessOpen',
                url: item.readUrl,
                opensInApp: false,
              } satisfies AccessOption,
            ],
      ),
    };
  }

  private async fetchJson(url: string): Promise<unknown> {
    let res = await this.callTransport(url);
    if (shouldRetryStatus(res.status)) {
      await new Promise((resolve) => setTimeout(resolve, this.retryDelayMs));
      res = await this.callTransport(url);
    }
    if (res.status < 200 || res.status >= 300) {
      throw catalogError(mapHttpStatusToCode(res.status), `addon payload status ${res.status}`);
    }
    if (res.body.byteLength > MAX_MANIFEST_BYTES) {
      throw catalogError('UPSTREAM_ERROR', 'addon payload exceeds size cap');
    }
    try {
      return JSON.parse(new TextDecoder('utf-8', { fatal: true }).decode(res.body));
    } catch {
      throw catalogError('UPSTREAM_ERROR', 'addon payload is not valid JSON');
    }
  }

  private async callTransport(url: string): Promise<AddonFetchResult> {
    try {
      return await this.transport(url);
    } catch {
      throw catalogError('NETWORK_ERROR', 'addon payload request failed');
    }
  }

  resolveDownloadUrl(formats: Record<string, string>, preferEpub: boolean): string {
    return resolveDownloadUrl(formats, preferEpub);
  }
}
