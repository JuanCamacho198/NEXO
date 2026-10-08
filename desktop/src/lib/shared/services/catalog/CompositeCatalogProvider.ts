/**
 * CompositeCatalogProvider — ordered dynamic composite behind the port.
 * Providers are searched in construction order (built-ins first, curated,
 * then enabled addons in install order); results concat-merge in that order.
 * The keyword-search fan-out only includes providers that do not opt out via
 * `supportsCompositeSearch` (details, per-source search, rails and download
 * resolution still see every provider). Page/details caching is per-source
 * with v2 keys carrying the full source string, and reads enforce the
 * existence check (design A1): entries whose source id is not in the active
 * source set at read time are never served. Burst searches are trailing-edge
 * debounced; page < 1 rejects before I/O.
 */
import { catalogError } from './errors';
import {
  BUILTIN_GUTENDEX,
  type CatalogBook,
  type CatalogFeaturedSort,
  type CatalogProvider,
  type CatalogSource,
  type CatalogSourceInfo,
  type PagedResult,
} from './CatalogProvider';
import {
  DETAIL_TTL_S,
  FEATURED_SORTS,
  FEATURED_TTL_S,
  PAGE_TTL_S,
  detailCacheKey,
  featuredCacheKey,
  pageCacheKey,
  type DiscoverCacheStore,
  type PreloadableCache,
} from './DiscoverCache';
import { GutendexCatalogProvider } from './BuiltInCatalogProviders';
import { GoogleBooksCatalogProvider, googleBooksProviderOrNull } from './BuiltInCatalogProviders';
import { OpenLibraryCatalogProvider } from './BuiltInCatalogProviders';
import { mergeResults, resolveDownloadUrl, resolveTotalCount, toPagedResult } from './mappers';
import { DEBOUNCE_MS, createSearchDebouncer } from './policy';
import {
  hasResolvedAuthorities,
  resolveBookAuthorities as resolveBookAuthorityFields,
  type OpenLibraryIdentity,
} from './bookAuthorityResolver';

export interface CompositeOptions {
  debounceMs?: number;
  cache?: DiscoverCacheStore | null;
  nowEpochSecs?: () => number;
}

/**
 * Book-id prefix owned by a source: built-ins drop the `builtin:` namespace
 * (`builtin:gutendex` -> `gutendex:`); addons use the source id itself
 * (`addon:<id>` -> `addon:<id>:`).
 */
function bookIdPrefixForSource(sourceId: string): string | null {
  if (sourceId.startsWith('builtin:')) return `${sourceId.slice('builtin:'.length)}:`;
  if (sourceId.startsWith('addon:')) return `${sourceId}:`;
  return null;
}

/** Providers exposing exactly one source are page-cacheable under that source. */
function singleSource(provider: CatalogProvider): CatalogSourceInfo | null {
  const sources = provider.listSources();
  return sources.length === 1 ? sources[0] : null;
}

/**
 * Resident payload read: uses the store's additive `read()` when present
 * (fresh-or-stale, non-mutating) and falls back to the fresh-only `get()` for
 * stores that only implement the original contract. A throwing or half-broken
 * store is treated as a miss — a cache must never fail a fetch.
 */
function readCachedPayload(
  store: DiscoverCacheStore,
  key: string,
  nowEpochSecs: number,
): { payload: string; stale: boolean } | null {
  const read = store.read;
  try {
    if (read) {
      const hit = read.call(store, key, nowEpochSecs);
      return hit ? { payload: hit.payload, stale: hit.stale } : null;
    }
    const fresh = store.get(key, nowEpochSecs);
    return fresh === null ? null : { payload: fresh, stale: false };
  } catch {
    return null;
  }
}

function isPreloadable(
  cache: DiscoverCacheStore | null,
): cache is DiscoverCacheStore & PreloadableCache {
  return typeof (cache as Partial<PreloadableCache> | null)?.preload === 'function';
}

import { CuratedCatalogProvider } from '../addons/CuratedCatalogProvider';
import { AddonCatalogProvider, EMPTY_ADDON_ACCESS } from '../addons/AddonCatalogProvider';
import type { AddonAccessResolution } from '../addons/AddonCatalogProvider';
import type { AddonConsentGate } from '../addons/AddonConsent';
import type { AddonTransport, InstalledAddonRow } from '../addons/AddonRegistry';

/**
 * Default provider list: built-ins first (Gutendex, Open Library, then the
 * key-gated Google Books provider), then the curated bundle, then enabled
 * addons in install order (disabled rows are excluded).
 *
 * `googleBooksKey` defaults to blank, which omits Google Books entirely
 * (fail-closed); the app composition root passes
 * `googleBooksKeyFromEnv()` so the ambient environment never leaks into
 * provider construction made by tests. When Google Books is absent, Gutendex
 * is left in the composite keyword-search fan-out as the fail-safe source, so
 * a keyless build never fans out to nothing.
 *
 * `consent` is the per-addon network-consent gate handed to every addon
 * provider (resolve-only gating; search/getDetails stay ungated). Omitted ⇒
 * each provider denies every resolve (fail-closed default).
 */
export function defaultCatalogProviders(
  installedAddons: InstalledAddonRow[] = [],
  addonTransport?: AddonTransport,
  googleBooksKey = '',
  consent?: AddonConsentGate,
): CatalogProvider[] {
  const addonProviders = installedAddons
    .filter((row) => row.enabled)
    .map(
      (row) => new AddonCatalogProvider(row.manifest, row.id, addonTransport, undefined, consent),
    );
  const googleBooks: GoogleBooksCatalogProvider | null = googleBooksProviderOrNull(googleBooksKey);
  return [
    // Gutendex only joins the composite search fan-out when Google Books is
    // absent (keyless build): it is then the sole keyword-search source, so
    // search still returns results instead of fanning out to nothing.
    new GutendexCatalogProvider(undefined, googleBooks === null),
    new OpenLibraryCatalogProvider(),
    ...(googleBooks ? [googleBooks] : []),
    new CuratedCatalogProvider(),
    ...addonProviders,
  ];
}

/**
 * Live composite supplier: rebuilds the ordered provider list from fresh
 * registry rows whenever it is invalidated (design A1/A4 wiring — install,
 * enable/disable, and uninstall immediately change the active source set).
 */
export interface CatalogProviderSupplier {
  /** Current composite; rebuilds from registry rows after invalidate(). */
  current(): Promise<CompositeCatalogProvider>;
  /** Already-built composite, or null before the first build. */
  peek(): CompositeCatalogProvider | null;
  /** Drop the cached composite; the next current() rebuilds from fresh rows. */
  invalidate(): void;
}

/** Rebuild-time options: the Discover cache the composite reads and writes. */
export interface RebuildingCatalogProviderOptions {
  cache?: DiscoverCacheStore | null;
  /** Per-addon network-consent gate forwarded to every addon provider. */
  consent?: AddonConsentGate;
  /**
   * Extra deterministic preload keys owned by the feature layer (e.g. today's
   * author rail pages). Called once per composite build; the returned list
   * MUST stay bounded. A throw degrades to no extra keys.
   */
  preloadPageKeys?: () => readonly string[];
}

/**
 * Reads the feature-owned preload keys defensively: a throwing provider means
 * an unseeded extra key, never a failed composite build.
 */
function safePreloadPageKeys(provider?: () => readonly string[]): readonly string[] {
  if (!provider) return [];
  try {
    return provider();
  } catch {
    return [];
  }
}

/**
 * Seeds the durable cache mirror once per composite build with the caller's
 * explicit feature-owned keys (today's three author rail pages). The featured
 * keyspace is deliberately NOT preloaded any more: the rails stopped reading
 * `f:v2:` keys in DISC-04b, so warming them would read durable rows nothing in
 * the shipping app consumes. Absent or non-preloadable caches are a no-op, and
 * a failed preload degrades to an unseeded mirror (the first rail simply
 * refetches) — never a build failure.
 */
async function preloadDiscoverCache(
  cache: DiscoverCacheStore | null,
  extraKeys: readonly string[],
): Promise<void> {
  if (!isPreloadable(cache)) return;
  try {
    await cache.preload([], extraKeys);
  } catch {
    // Best-effort: an unseeded mirror is an empty cache, not an error.
  }
}

export function createRebuildingCatalogProvider(
  loadRows: () => Promise<InstalledAddonRow[]>,
  addonTransport?: AddonTransport,
  googleBooksKey = '',
  options: RebuildingCatalogProviderOptions = {},
): CatalogProviderSupplier {
  const cache = options.cache ?? null;
  const consent = options.consent;
  let current: Promise<CompositeCatalogProvider> | null = null;
  let built: CompositeCatalogProvider | null = null;
  let generation = 0;
  return {
    current(): Promise<CompositeCatalogProvider> {
      const gen = generation;
      return (current ??= loadRows().then(async (rows) => {
        const composite = new CompositeCatalogProvider(
          defaultCatalogProviders(rows, addonTransport, googleBooksKey, consent),
          { cache },
        );
        if (gen === generation) built = composite;
        // The supplier's current() is already async, so the preload stays off
        // the composite's synchronous read path. Every invalidate() rebuild
        // re-preloads, so addon install/enable/disable/uninstall re-seed it.
        await preloadDiscoverCache(cache, safePreloadPageKeys(options.preloadPageKeys));
        return composite;
      }));
    },
    peek(): CompositeCatalogProvider | null {
      return built;
    },
    invalidate(): void {
      generation += 1;
      current = null;
      built = null;
    },
  };
}

interface RoutedDetails {
  provider: CatalogProvider;
  source: CatalogSourceInfo;
}

export class CompositeCatalogProvider implements CatalogProvider {
  private readonly debounced: { search: (query: string, page: number) => Promise<PagedResult> };
  private readonly cache: DiscoverCacheStore | null;
  private readonly nowEpochSecs: () => number;
  /** Featured cache keys with a stale-while-revalidate refresh already in flight. */
  private readonly featuredRefreshes = new Set<string>();

  constructor(
    private readonly providers: CatalogProvider[] = defaultCatalogProviders(),
    options: CompositeOptions = {},
  ) {
    this.cache = options.cache ?? null;
    this.nowEpochSecs = options.nowEpochSecs ?? (() => Math.floor(Date.now() / 1000));
    this.debounced = createSearchDebouncer(
      (query, page) => this.executeSearch(query, page),
      options.debounceMs ?? DEBOUNCE_MS,
    );
  }

  /** Sources in provider order, deduped by sourceId (first occurrence wins). */
  listSources(): CatalogSourceInfo[] {
    const seen = new Set<string>();
    const out: CatalogSourceInfo[] = [];
    for (const provider of this.providers) {
      for (const source of provider.listSources()) {
        if (!seen.has(source.sourceId)) {
          seen.add(source.sourceId);
          out.push(source);
        }
      }
    }
    return out;
  }

  /**
   * True when at least one active provider opts in, so the shell can decide
   * whether any featured work is possible at all. Mirrors Android.
   */
  supportsFeatured(sort: CatalogFeaturedSort): boolean {
    return this.searchableProviders().some((p) => p.supportsFeatured(sort));
  }

  /**
   * Source ids whose owning provider opts into featured for at least one sort.
   * Evaluated from the live provider list, never a hardcoded provider list, so
   * an addon that declares featured support is included the moment it installs.
   *
   * Retained as a capability query for the `featured` port path (Android
   * parity); DISC-04b removed its only production caller, the durable preload,
   * because the rails no longer read `f:v2:` keys.
   */
  featuredSourceIds(): CatalogSource[] {
    const ids: CatalogSource[] = [];
    for (const provider of this.searchableProviders()) {
      if (!FEATURED_SORTS.some((sort) => provider.supportsFeatured(sort))) continue;
      for (const source of provider.listSources()) ids.push(source.sourceId);
    }
    return ids;
  }

  /**
   * Featured rails fan out over the providers that opt in via
   * `supportsFeatured`, then merge with the same `mergePaged` left-fold used
   * by search. Each opted-in provider reads through the Discover cache under
   * its `f:v2:{sourceId}:{sort}` key with the 6h featured TTL:
   *
   * - a fresh entry is served with zero I/O;
   * - a stale-but-resident entry is served immediately while a guarded
   *   background refresh replaces it (stale-while-revalidate);
   * - a miss fetches and caches.
   *
   * Sources that are not active at read time are never served (design A1).
   * A provider that does not opt in is never called, so its rail can only ever
   * come back empty (fail-closed) and be hidden. A provider failure PROPAGATES:
   * the rail settles `Error` instead of silently degrading to `Hidden`. A genuinely empty successful response still
   * merges to an empty page, which the rail renders as `Hidden`.
   */
  async featured(sort: CatalogFeaturedSort, limit: number): Promise<PagedResult> {
    if (!Number.isInteger(limit) || limit < 1) {
      throw catalogError('INVALID_PAGE', `limit must be >= 1, got ${limit}`);
    }
    const active = this.activeSourceIds();
    const pages = await Promise.all(
      this.searchableProviders()
        .filter((provider) => provider.supportsFeatured(sort))
        .map(async (provider) => {
          const hit = this.readFeaturedHit(provider, sort, active);
          if (hit) {
            if (hit.stale) this.startFeaturedRefresh(provider, sort, limit, active, hit.key);
            return hit.page;
          }
          const result = await provider.featured(sort, limit);
          this.cacheFeatured(provider, sort, result, active);
          return result;
        }),
    );
    return this.mergePaged(pages, 1);
  }

  /**
   * Per-source search: exact match over the active source set, routed to the
   * single provider that owns `sourceId`, with the same page-cache read-through
   * as `search` (the author rails resolve through here, so their pages are
   * cached too). An unknown or inactive source fails closed with an empty page — never
   * a crash, never a silent composite search. Mirrors Android.
   */
  async searchSource(sourceId: CatalogSource, query: string, page: number): Promise<PagedResult> {
    if (!Number.isInteger(page) || page < 1) {
      throw catalogError('INVALID_PAGE', `page must be >= 1, got ${page}`);
    }
    const owner = this.searchableProviders().find((provider) =>
      provider.listSources().some((source) => source.sourceId === sourceId),
    );
    if (!owner) {
      return { results: [], nexoPage: null, totalCount: 0 };
    }
    const active = this.activeSourceIds();
    const cached = this.readPageHit(owner, query, page, active);
    if (cached !== null) return cached;
    const result = await owner.search(query, page);
    this.cachePage(owner, query, page, result, active);
    return result;
  }

  /**
   * Debounced entry point: only the latest burst query issues network I/O.
   * When every I/O-capable provider has a fresh cache entry, the merged page
   * returns synchronously without waiting for debounce.
   */
  search(query: string, page: number): Promise<PagedResult> {
    if (!Number.isInteger(page) || page < 1) {
      return Promise.reject(catalogError('INVALID_PAGE', `page must be >= 1, got ${page}`));
    }
    const active = this.activeSourceIds();
    const parts = this.compositeSearchProviders().map((provider) => {
      const cached = this.readPageHit(provider, query, page, active);
      return cached !== null
        ? { cached: true as const, value: cached }
        : { cached: false as const, provider };
    });
    const needsFetch = parts.some((part) => {
      if (part.cached) return false;
      const source = singleSource(part.provider);
      return source !== null && active.has(source.sourceId);
    });
    if (!needsFetch) {
      // Only I/O-free providers (no single routable source) remain to call.
      return Promise.all(
        parts.map((part) =>
          part.cached ? Promise.resolve(part.value) : part.provider.search(query, page),
        ),
      ).then((pages) => this.mergePaged(pages, page));
    }
    return this.debounced.search(query, page);
  }

  private async executeSearch(query: string, page: number): Promise<PagedResult> {
    // Re-check inside the debounce window: a concurrent caller may have filled it.
    const active = this.activeSourceIds();
    const pages = await Promise.all(
      this.compositeSearchProviders().map(async (provider) => {
        const cached = this.readPageHit(provider, query, page, active);
        if (cached !== null) return cached;
        const result = await provider.search(query, page);
        this.cachePage(provider, query, page, result, active);
        return result;
      }),
    );
    return this.mergePaged(pages, page);
  }

  /** Providers without sources (disabled) contribute nothing and are never called. */
  private searchableProviders(): CatalogProvider[] {
    return this.providers.filter((p) => p.listSources().length > 0);
  }

  /**
   * Composite keyword-search fan-out set: providers that do not opt out via
   * the fail-closed `supportsCompositeSearch` probe (absent ⇒ participating).
   * `defaultCatalogProviders` keeps the slow Gutendex and Open Library
   * searches out when Google Books is registered, but leaves Gutendex in when
   * it is not, so a keyless build always has a search source. Details,
   * `searchSource`, featured rails and download resolution still see every
   * provider through `searchableProviders`.
   */
  private compositeSearchProviders(): CatalogProvider[] {
    return this.searchableProviders().filter((p) => p.supportsCompositeSearch?.() ?? true);
  }

  private activeSourceIds(): Set<string> {
    const ids = new Set<string>();
    for (const provider of this.searchableProviders()) {
      for (const source of provider.listSources()) ids.add(source.sourceId);
    }
    return ids;
  }

  /** Existence check (design A1): only sources active at read time may hit. */
  private readPageHit(
    provider: CatalogProvider,
    query: string,
    page: number,
    active: Set<string>,
  ): PagedResult | null {
    if (!this.cache) return null;
    const source = singleSource(provider);
    if (!source || !active.has(source.sourceId)) return null;
    const hit = this.cache.get(pageCacheKey(source.sourceId, query, page), this.nowEpochSecs());
    return hit ? (JSON.parse(hit) as PagedResult) : null;
  }

  private cachePage(
    provider: CatalogProvider,
    query: string,
    page: number,
    result: PagedResult,
    active: Set<string>,
  ): void {
    if (!this.cache) return;
    const source = singleSource(provider);
    if (!source || !active.has(source.sourceId)) return;
    this.cache.put(
      pageCacheKey(source.sourceId, query, page),
      JSON.stringify(result),
      this.nowEpochSecs(),
      PAGE_TTL_S,
    );
  }

  /**
   * Featured read-through under `f:v2:{sourceId}:{sort}`. Returns the resident
   * page plus whether it is stale; a corrupt payload is a miss (refetch), and
   * an inactive or unroutable source is never served.
   */
  private readFeaturedHit(
    provider: CatalogProvider,
    sort: CatalogFeaturedSort,
    active: Set<string>,
  ): { key: string; page: PagedResult; stale: boolean } | null {
    if (!this.cache) return null;
    const source = singleSource(provider);
    if (!source || !active.has(source.sourceId)) return null;
    const key = featuredCacheKey(source.sourceId, sort);
    const cached = readCachedPayload(this.cache, key, this.nowEpochSecs());
    if (!cached) return null;
    try {
      return { key, page: JSON.parse(cached.payload) as PagedResult, stale: cached.stale };
    } catch {
      return null;
    }
  }

  private cacheFeatured(
    provider: CatalogProvider,
    sort: CatalogFeaturedSort,
    result: PagedResult,
    active: Set<string>,
  ): void {
    if (!this.cache) return;
    const source = singleSource(provider);
    if (!source || !active.has(source.sourceId)) return;
    this.cache.put(
      featuredCacheKey(source.sourceId, sort),
      JSON.stringify(result),
      this.nowEpochSecs(),
      FEATURED_TTL_S,
    );
  }

  /**
   * Stale-while-revalidate refresh: at most one in flight per cache key. A
   * success replaces the resident entry; a failure is swallowed so the stale
   * value already served stays the answer and the rail never depends on a
   * refresh nobody awaited.
   */
  private startFeaturedRefresh(
    provider: CatalogProvider,
    sort: CatalogFeaturedSort,
    limit: number,
    active: Set<string>,
    key: string,
  ): void {
    if (this.featuredRefreshes.has(key)) return;
    this.featuredRefreshes.add(key);
    void (async () => {
      try {
        const result = await provider.featured(sort, limit);
        this.cacheFeatured(provider, sort, result, active);
      } catch {
        // Swallowed: the served stale page remains the best answer available.
      } finally {
        this.featuredRefreshes.delete(key);
      }
    })();
  }

  /**
   * Ordered merge: left-fold the provider pages — earlier providers win fields,
   * later ones fill cover gaps and append unmatched books. Under the default
   * list the first fan-out page is Google Books (or Gutendex in a keyless
   * build), followed by curated/addon pages.
   */
  private mergePaged(pages: PagedResult[], page: number): PagedResult {
    if (pages.length === 0) return toPagedResult([], page, 0);
    let results = pages[0]!.results;
    let totalCount = pages[0]!.totalCount;
    for (let i = 1; i < pages.length; i++) {
      results = mergeResults(results, pages[i]!.results);
      totalCount = resolveTotalCount(totalCount, pages[i]!.totalCount);
    }
    return toPagedResult(results, page, totalCount);
  }

  /**
   * Exact-prefix routing over the active source set: `gutendex:`/`openlibrary:`
   * book ids hit the built-ins, `addon:<addonId>:<bookId>` the owning addon.
   * Unroutable ids reject NOT_FOUND without any I/O.
   */
  async getDetails(id: string): Promise<CatalogBook> {
    const route = this.routeDetails(id);
    if (!route) {
      throw catalogError('NOT_FOUND', `unknown catalog id ${id}`);
    }
    const { provider, source } = route;
    const active = this.activeSourceIds();
    const cache = this.cache;
    if (cache !== null && active.has(source.sourceId)) {
      return this.readOrFetchDetail(cache, provider, source.sourceId, id);
    }
    return provider.getDetails(id);
  }

  /**
   * Detail read-through: the synchronous mirror first, then a single-key
   * durable read (so a detail fetched in a previous session is reachable
   * again, including offline), then the provider. Only a fresh durable row is
   * served; a stale one is refetched and replaced under `DETAIL_TTL_S`.
   */
  private async readOrFetchDetail(
    cache: DiscoverCacheStore,
    provider: CatalogProvider,
    sourceId: string,
    id: string,
  ): Promise<CatalogBook> {
    const key = detailCacheKey(sourceId, id);
    const hit = cache.get(key, this.nowEpochSecs());
    if (hit) return JSON.parse(hit) as CatalogBook;
    const durable = await this.readDurableDetail(cache, key);
    if (durable !== null) return durable;
    const book = await provider.getDetails(id);
    cache.put(key, JSON.stringify(book), this.nowEpochSecs(), DETAIL_TTL_S);
    return book;
  }

  /** Bounded one-row durable fallback; absent support or a failure is a miss. */
  private async readDurableDetail(
    cache: DiscoverCacheStore,
    key: string,
  ): Promise<CatalogBook | null> {
    const readDurable = cache.readDurable;
    if (typeof readDurable !== 'function') return null;
    try {
      const hit = await readDurable.call(cache, key, this.nowEpochSecs());
      if (!hit || hit.stale) return null;
      return JSON.parse(hit.payload) as CatalogBook;
    } catch {
      return null;
    }
  }

  private routeDetails(id: string): RoutedDetails | null {
    let best: { provider: CatalogProvider; source: CatalogSourceInfo; prefix: string } | null =
      null;
    for (const provider of this.searchableProviders()) {
      for (const source of provider.listSources()) {
        const prefix = bookIdPrefixForSource(source.sourceId);
        if (!prefix || !id.startsWith(prefix) || id.length <= prefix.length) continue;
        if (!best || prefix.length > best.prefix.length) {
          best = { provider, source, prefix };
        }
      }
    }
    return best ? { provider: best.provider, source: best.source } : null;
  }

  resolveDownloadUrl(formats: Record<string, string>, preferEpub: boolean): string {
    return resolveDownloadUrl(formats, preferEpub);
  }

  /**
   * Addon access resolve (slice 9): route by the existing
   * `bookIdPrefixForSource` over the active source set (longest-prefix wins,
   * same as `getDetails`) and delegate to the owning provider that implements
   * `resolveAddonAccess`. No owner or no implementation ⇒ empty result.
   * `getDetails` prefix routing and `NOT_FOUND` zero-I/O are unchanged.
   */
  async resolveAddonAccess(book: CatalogBook): Promise<AddonAccessResolution> {
    const route = this.routeDetails(book.id);
    if (!route) {
      return { ...EMPTY_ADDON_ACCESS, options: [] };
    }
    const resolve = route.provider.resolveAddonAccess;
    if (typeof resolve !== 'function') {
      return { ...EMPTY_ADDON_ACCESS, options: [] };
    }
    return resolve.call(route.provider, book);
  }

  /**
   * DISC-04c lazy authority resolve, cached under the existing detail key so it
   * runs once per book. A fresh resident detail that already carries an
   * authority is served with zero I/O; otherwise Gutendex is reached through
   * `searchSource` (never the composite fan-out, which Gutendex now opts out
   * of) and Open Library through its ISBN lookup (skipped, not failed, when the
   * book has no ISBN). The merged book replaces the detail-key entry, so the
   * next open serves the resolved payload from cache without re-resolving.
   */
  async resolveBookAuthorities(book: CatalogBook): Promise<CatalogBook> {
    const route = this.routeDetails(book.id);
    if (!route) return book;
    const key = detailCacheKey(route.source.sourceId, book.id);
    const now = this.nowEpochSecs();
    const resident = this.readResolvedDetail(key, now);
    if (resident) return resident;

    const resolved = await resolveBookAuthorityFields(book, {
      gutendex: (query) =>
        this.searchSource(BUILTIN_GUTENDEX, query, 1).then((page) => page.results),
      openLibraryByIsbn: (isbn) => this.openLibraryIdentity(isbn),
    });

    if (resolved !== book && this.cache) {
      this.cache.put(key, JSON.stringify(resolved), now, DETAIL_TTL_S);
    }
    return resolved;
  }

  /** A fresh resident detail that already carries an authority, else null. */
  private readResolvedDetail(key: string, now: number): CatalogBook | null {
    const hit = this.cache?.get(key, now) ?? null;
    if (!hit) return null;
    try {
      const book = JSON.parse(hit) as CatalogBook;
      return hasResolvedAuthorities(book) ? book : null;
    } catch {
      return null;
    }
  }

  /** Open Library identity lookup through the registered OL provider, if any. */
  private async openLibraryIdentity(isbn: string): Promise<OpenLibraryIdentity | null> {
    const provider = this.providers.find(
      (candidate): candidate is OpenLibraryCatalogProvider =>
        candidate instanceof OpenLibraryCatalogProvider,
    );
    if (!provider) return null;
    return provider.enrichByIsbn(isbn);
  }
}
