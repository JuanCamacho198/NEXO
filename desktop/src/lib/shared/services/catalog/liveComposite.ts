/**
 * LiveCatalogProvider — desktop composition seam for Discover: the composite
 * rebuilds from installed addon rows whenever the registry mutates, so
 * install/enable/disable/uninstall change the active source set immediately,
 * and rows are re-read after restart (design A1/A4 wiring; no Discover UI
 * changes needed — DiscoverDomainState just consumes this provider).
 */
import {
  createRebuildingCatalogProvider,
  type CatalogProviderSupplier,
} from './CompositeCatalogProvider';
import { PersistentDiscoverCache, TauriDiscoverCachePort, pageCacheKey } from './DiscoverCache';
import {
  BUILTIN_GOOGLEBOOKS,
  type CatalogBook,
  type CatalogFeaturedSort,
  type CatalogProvider,
  type CatalogSource,
  type CatalogSourceInfo,
  type PagedResult,
} from './CatalogProvider';
import type { AddonAccessResolution } from './CatalogProvider';
import { googleBooksKeyFromEnv } from './BuiltInCatalogProviders';
import { resolveDownloadUrl } from './mappers';
import { AddonRegistry, getAddonRegistry } from '../addons/AddonRegistry';
import { addonConsent } from '../addons/AddonConsent';
import { authorEntriesFor } from '$lib/features/discover/railRotation';

// Slice 7 single-state-source: the live composite listens on the SHARED
// registry instance (the same one the addons singleton mutates), so screen
// install/enable/disable/uninstall drive the existing invalidation below.
const registry: AddonRegistry = getAddonRegistry();

/**
 * Production Discover cache: an in-memory mirror over the durable
 * `discover_cache` table (best-effort write-through; preload is bounded to the
 * feature-owned keys below). Constructed once so every composite rebuild shares
 * one mirror — this is what makes rail caching real in production instead of
 * test-only.
 */
export const discoverCache = new PersistentDiscoverCache(new TauriDiscoverCachePort());

/**
 * Deterministic startup preload for the author rails: exactly the current day's
 * three page-1 keys, one per rail, all under Google Books. The rotation terms
 * are the only enumerable rail queries, so this stays three keys and disk
 * growth remains bounded. Reads stay fresh-only — preloading only makes an
 * already-valid page reachable sooner.
 */
export function discoverPreloadPageKeys(now: Date = new Date()): readonly string[] {
  return authorEntriesFor(now).map((entry) => pageCacheKey(BUILTIN_GOOGLEBOOKS, entry.term, 1));
}

// Google Books is registered only when `VITE_GOOGLE_BOOKS_KEY` is non-blank;
// a blank key omits the provider and the app keeps working on Gutendex + OL.
// Every rebuild re-runs the cache preload, so addon changes re-seed the mirror.
// The shared consent singleton gates every addon provider's resolve (slice 8);
// search/getDetails stay ungated.
const supplier: CatalogProviderSupplier = createRebuildingCatalogProvider(
  () => registry.listInstalled(),
  undefined,
  googleBooksKeyFromEnv(),
  {
    cache: discoverCache,
    consent: addonConsent,
    preloadPageKeys: discoverPreloadPageKeys,
  },
);
registry.onChanged(() => supplier.invalidate());

class LiveCatalogProvider implements CatalogProvider {
  constructor(private readonly supplier: CatalogProviderSupplier) {}

  search(query: string, page: number): Promise<PagedResult> {
    return supplier.current().then((c) => c.search(query, page));
  }

  getDetails(id: string): Promise<CatalogBook> {
    return supplier.current().then((c) => c.getDetails(id));
  }

  featured(sort: CatalogFeaturedSort, limit: number): Promise<PagedResult> {
    return supplier.current().then((c) => c.featured(sort, limit));
  }

  supportsFeatured(sort: CatalogFeaturedSort): boolean {
    return supplier.peek()?.supportsFeatured(sort) ?? false;
  }

  searchSource(sourceId: CatalogSource, query: string, page: number): Promise<PagedResult> {
    return supplier.current().then((c) => c.searchSource(sourceId, query, page));
  }

  resolveDownloadUrl(formats: Record<string, string>, preferEpub: boolean): string {
    return resolveDownloadUrl(formats, preferEpub);
  }

  resolveAddonAccess(book: CatalogBook): Promise<AddonAccessResolution> {
    return supplier.current().then((c) => c.resolveAddonAccess(book));
  }

  resolveBookAuthorities(book: CatalogBook): Promise<CatalogBook> {
    return supplier.current().then((c) => c.resolveBookAuthorities(book));
  }

  listSources(): CatalogSourceInfo[] {
    return supplier.peek()?.listSources() ?? [];
  }
}

export const liveCatalogProvider: CatalogProvider = new LiveCatalogProvider(supplier);
