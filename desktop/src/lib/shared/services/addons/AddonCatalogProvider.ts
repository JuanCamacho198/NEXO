/**
 * Two addon catalog providers share this module.
 *
 * `AddonFanoutCatalogProvider` is the v3 path: it asks the protocol router
 * which installed addons can answer, fans the search out through each addon's
 * memoized `createAddonClient()`, and maps `MetaPreview` onto `CatalogBook`.
 * One addon failing is a non-blocking notice, never a failed search.
 *
 * `AddonCatalogProvider` is the retained v2 single-manifest adapter (one
 * provider per installed addon): it renders the deprecated `searchUrl` /
 * `detailsUrl` / `resolveUrl` endpoint templates. A v3 manifest handed to it
 * stays browse-only, because those fields do not exist in v3.
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
import {
  TYPE_BOOK,
  selectAddons,
  type AddonClient,
  type AddonDescriptor,
  type AddonFailure,
  type AddonManifestV3,
  type MetaPreview,
} from '@nexo/addon-protocol';
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

/**
 * Both placeholder vocabularies reach this adapter: v2 endpoints use
 * `{query}`/`{page}`/`{bookId}`, and the v2→v3 adapter rewrites them to
 * `{search}`/`{skip}`/`{id}`. Supplying both keeps one rendering path.
 */
const PLACEHOLDER_ALIASES: ReadonlyArray<readonly [string, string]> = [
  ['search', 'query'],
  ['skip', 'page'],
  ['id', 'bookId'],
];

function templateParams(params: Record<string, string>): Record<string, string> {
  const aliased: Record<string, string> = { ...params };
  for (const [canonical, alias] of PLACEHOLDER_ALIASES) {
    const value = params[canonical] ?? params[alias];
    if (value === undefined) continue;
    aliased[canonical] = value;
    aliased[alias] = value;
  }
  return aliased;
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

/**
 * v3 manifests are the ones carrying `types`; v2 never declares it.
 *
 * A stored v2 row is adapted to v3 on read, and the adapter keeps the v2
 * endpoint templates under `nexo.templates` with `nexo.shape: "legacy-v2"` —
 * that shape is the signal that this class, not the v3 router, owns the addon's
 * catalog/details/resolve requests.
 */
function legacyViewOf(manifest: AddonManifestV3 | AddonManifest): AddonManifest | null {
  return Array.isArray((manifest as AddonManifestV3).types) ? null : (manifest as AddonManifest);
}

/**
 * Endpoint templates for an adapted v2 row. A raw (or Tier B) v3 manifest keeps
 * no endpoints here: the v3 router serves it, so answering it here too would
 * duplicate every result.
 */
function adaptedTemplates(manifest: AddonManifestV3 | AddonManifest): Record<string, string> {
  const v3 = manifest as AddonManifestV3;
  if (legacyViewOf(manifest) !== null) return {};
  if (v3.nexo?.shape !== 'legacy-v2') return {};
  return v3.nexo.templates ?? {};
}

export class AddonCatalogProvider implements CatalogProvider {
  private readonly source: CatalogSourceInfo;
  private readonly addonId: string;
  private readonly legacy: AddonManifest | null;
  private readonly searchUrl?: string;
  private readonly detailsUrl?: string;
  private readonly resolveUrl?: string;
  private readonly consent: AddonConsentGate;

  constructor(
    manifest: AddonManifestV3 | AddonManifest,
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
    this.legacy = legacyViewOf(manifest);
    const templates = adaptedTemplates(manifest);
    this.searchUrl = this.legacy?.searchUrl ?? templates.catalog;
    this.detailsUrl = this.legacy?.detailsUrl ?? templates.meta;
    // Consent gating is unchanged; the template only supplies the URL, so an
    // adapted v2 row keeps resolving exactly as it did as a v2 row.
    this.resolveUrl = this.legacy?.resolveUrl ?? templates.stream;
    this.consent = consent;
  }

  listSources(): CatalogSourceInfo[] {
    return [this.source];
  }

  async search(query: string, page: number): Promise<PagedResult> {
    if (!this.searchUrl) return EMPTY_PAGE;
    const url = renderTemplate(
      this.searchUrl,
      templateParams({ search: encodeURIComponent(query), skip: String(page) }),
    );
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
    const url = renderTemplate(
      this.detailsUrl,
      templateParams({ id: encodeURIComponent(id.slice(prefix.length)) }),
    );
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
    const resolveUrl = this.resolveUrl;
    if (typeof resolveUrl !== 'string' || resolveUrl.length === 0) {
      return { ...EMPTY_ADDON_ACCESS, options: [] };
    }
    if (
      this.legacy?.capabilities !== undefined &&
      !declaredCapabilities(this.legacy).includes(RESOLVE_CAPABILITY)
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

/** One addon's failure sink: additive, so no caller has to break. */
export type AddonFailureListener = (failures: readonly AddonFailure[]) => void;

/**
 * Payload shapes this build has no adapter for. Those addons are served by the
 * v2 adapter, which still understands their wire format, so the router skips
 * them rather than sending a request whose answer it would discard.
 */
const UNREADABLE_CATALOG_SHAPES: ReadonlySet<string> = new Set([
  'standard-ebooks',
  'librivox',
  'legacy-v2',
]);

export interface AddonFanoutCatalogOptions {
  /** Installed addons as protocol descriptors, in install order. */
  loadAddons: () => Promise<AddonDescriptor[]>;
  /** The registry's memoized protocol client for one addon. */
  clientFor: (addonId: string) => AddonClient;
  /**
   * Non-blocking per-addon failure notice. Called once per failing addon and
   * never allowed to fail the search.
   */
  onFailure?: AddonFailureListener;
}

/** `MetaPreview` → the internal catalog shape. Ids stay namespaced. */
function toCatalogBook(meta: MetaPreview, provider: CatalogSource): CatalogBook {
  return {
    // `gutenberg:1342` stays intact: `meta` and `stream` route on this id.
    id: meta.id,
    provider,
    title: meta.name,
    authors: meta.authors,
    coverUrl: httpsOrNull(meta.poster),
    languages: meta.languages,
    subjects: meta.subjects,
    downloadUrl: null,
    description: meta.description ?? null,
    isbn13: meta.isbn13 ?? null,
  };
}

interface AddonSearchOutcome {
  books: CatalogBook[];
  failure?: AddonFailure;
}

/**
 * One provider for every installed addon. The router decides candidacy
 * (`selectAddons`), each addon answers through its own memoized client, and the
 * pages union in install order with the namespaced ids preserved.
 *
 * Sources are listed synchronously from the snapshot loaded by `ready()`;
 * the composite is rebuilt whenever the registry mutates, which is what keeps
 * that snapshot fresh.
 */
export class AddonFanoutCatalogProvider implements CatalogProvider {
  private addons: AddonDescriptor[] | null = null;
  private loading: Promise<void> | null = null;

  constructor(private readonly options: AddonFanoutCatalogOptions) {}

  /** Load the installed addons once per provider instance. */
  ready(): Promise<void> {
    this.loading ??= this.options.loadAddons().then(
      (addons) => {
        this.addons = addons;
      },
      () => {
        // A registry read failure is an empty addon set, never a broken screen.
        this.addons = [];
      },
    );
    return this.loading;
  }

  listSources(): CatalogSourceInfo[] {
    return this.enabledAddons().map((addon) => ({
      sourceId: addonSource(addon.id),
      name: addon.manifest.name,
      kind: 'addon' as const,
    }));
  }

  async search(query: string, page: number): Promise<PagedResult> {
    if (!Number.isInteger(page) || page < 1) {
      throw catalogError('INVALID_PAGE', `page must be >= 1, got ${page}`);
    }
    await this.ready();
    return this.fanOut(this.catalogCandidates(), query, page);
  }

  /**
   * Per-source search: the source chip filter. An unknown or disabled source
   * fails closed with an empty page and zero I/O.
   */
  async searchSource(sourceId: CatalogSource, query: string, page: number): Promise<PagedResult> {
    await this.ready();
    const target = this.catalogCandidates().filter((addon) => addonSource(addon.id) === sourceId);
    if (target.length === 0) return EMPTY_PAGE;
    return this.fanOut(target, query, page);
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
   * Detail routing belongs to the `meta` resource, which lands with the rest of
   * the access-resolution slice. Catalog ids are namespaced (`gutenberg:1342`),
   * so the composite's `addon:<id>:` route does not address them either.
   */
  async getDetails(id: string): Promise<CatalogBook> {
    throw catalogError('NOT_FOUND', `unknown catalog id ${id}`);
  }

  resolveDownloadUrl(formats: Record<string, string>, preferEpub: boolean): string {
    return resolveDownloadUrl(formats, preferEpub);
  }

  private enabledAddons(): AddonDescriptor[] {
    return (this.addons ?? []).filter((addon) => addon.enabled);
  }

  /** Enabled addons that declare the catalog resource for `book`. */
  private catalogCandidates(): AddonDescriptor[] {
    return selectAddons(this.enabledAddons(), { resource: 'catalog', type: TYPE_BOOK });
  }

  private async fanOut(
    candidates: AddonDescriptor[],
    query: string,
    page: number,
  ): Promise<PagedResult> {
    const outcomes = await Promise.all(
      candidates.map((addon) => this.searchAddon(addon, query, page)),
    );

    const failures = new Map<string, AddonFailure>();
    const results: CatalogBook[] = [];
    const seenIds = new Set<string>();
    let truncated = false;

    for (const outcome of outcomes) {
      if (outcome.failure !== undefined) failures.set(outcome.failure.addonId, outcome.failure);
      for (const book of outcome.books) {
        if (seenIds.has(book.id)) continue;
        seenIds.add(book.id);
        if (results.length >= MAX_PAGE_SIZE) {
          truncated = true;
          continue;
        }
        results.push(book);
      }
    }

    if (failures.size > 0) {
      try {
        this.options.onFailure?.([...failures.values()]);
      } catch {
        // A notice sink must never fail a search that already has results.
      }
    }

    return {
      results,
      nexoPage: truncated ? page + 1 : null,
      totalCount: results.length,
    };
  }

  /**
   * One addon, every book catalog it declares. `search` is always sent;
   * `skip` only past the first page, and the package drops any argument the
   * catalog did not declare.
   */
  private async searchAddon(
    addon: AddonDescriptor,
    query: string,
    page: number,
  ): Promise<AddonSearchOutcome> {
    const catalogs = addon.manifest.catalogs;
    if (catalogs.length === 0) return { books: [] };
    // A shape this build cannot parse is served by the v2 adapter instead;
    // requesting it here would only discard the answer.
    if (UNREADABLE_CATALOG_SHAPES.has(addon.manifest.nexo?.shape ?? 'raw')) return { books: [] };

    const client = this.options.clientFor(addon.id);
    const extra: Record<string, string | number> =
      page > 1 ? { search: query, skip: (page - 1) * MAX_PAGE_SIZE } : { search: query };
    const provider = addonSource(addon.id);
    const settled = await Promise.allSettled(
      catalogs.map((catalog) => client.searchCatalog([addon], { catalogId: catalog.id, extra })),
    );

    const books: CatalogBook[] = [];
    let failure: AddonFailure | undefined;
    for (const outcome of settled) {
      if (outcome.status === 'rejected') {
        failure ??= { addonId: addon.id, reason: 'payload' };
        continue;
      }
      failure ??= outcome.value.failures[0];
      for (const item of outcome.value.items) books.push(toCatalogBook(item, provider));
    }
    return failure === undefined ? { books } : { books, failure };
  }
}
