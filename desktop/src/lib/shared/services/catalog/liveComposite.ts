/**
 * LiveCatalogProvider — desktop composition seam for Discover: the composite
 * rebuilds from installed addon rows whenever the registry mutates, so
 * install/enable/disable/uninstall change the active source set immediately,
 * and rows are re-read after restart (design A1/A4 wiring; no Discover UI
 * changes needed — DiscoverDomainState just consumes this provider).
 *
 * Installed addons are served by ONE `AddonFanoutCatalogProvider`: the v3
 * router decides which addons answer a catalog request, each addon answers
 * through its memoized protocol client, and the pages union in install order.
 * A failing addon becomes a non-blocking notice instead of an empty screen.
 */
import { get } from 'svelte/store';

import type { AddonFailure } from '@nexo/addon-protocol';
import {
  CompositeCatalogProvider,
  defaultCatalogProviders,
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
import { AddonFanoutCatalogProvider } from '../addons/AddonCatalogProvider';
import { addonConsent } from '../addons/AddonConsent';
import { TauriLibraryAdapter } from '$lib/shared/ports/adapters/tauri/TauriLibraryAdapter';
import type { LibraryPort } from '$lib/shared/ports/LibraryPort';
import { i18n } from '$lib/shared/i18n';
import { pushToast } from '$lib/shared/stores/ToastQueue.svelte';
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

/**
 * Addons already reported this session. A dead addon keeps failing every
 * search; the user is told once, not once per keystroke.
 */
const reportedAddonFailures = new Set<string>();

/** Non-blocking notice for addon failures: the healthy results still render. */
function surfaceAddonFailures(failures: readonly AddonFailure[]): void {
  const unreported = failures.filter((failure) => !reportedAddonFailures.has(failure.addonId));
  if (unreported.length === 0) return;
  for (const failure of unreported) reportedAddonFailures.add(failure.addonId);
  pushToast('error', i18n.t(get(i18n.locale), 'addons.search.unavailable'));
}

/** Best-effort mirror seeding: an unseeded cache is a miss, never an error. */
async function preloadAuthorRailPages(): Promise<void> {
  try {
    await discoverCache.preload([], discoverPreloadPageKeys());
  } catch {
    // Unseeded is not broken: the first rail read simply refetches.
  }
}

/** Local catalog, for the "do I already own this?" question. */
const library: LibraryPort = new TauriLibraryAdapter();

/**
 * True when a book row already carries this addon item id, so the read sheet
 * flags it instead of downloading it twice. A library read failure is "not
 * owned": the flag is an optimization, never a gate on the resolve.
 */
async function isOwnedSourceItem(sourceItemId: string): Promise<boolean> {
  try {
    const books = await library.listBooks();
    return books.some((book) => book.sourceItemId === sourceItemId);
  } catch {
    return false;
  }
}

/**
 * One composite per rebuild: built-ins and the curated bundle first, then the
 * installed addons, then the v3 addon fan-out provider.
 *
 * A per-row provider and the fan-out can both answer one addon today: the
 * per-row provider owns the consent-gated resolve and the templated routes,
 * while the fan-out reads every payload through its `nexo.shape` adapter
 * (`legacy-v2` included). Their results merge by title and author, so one book
 * is never listed twice.
 *
 * Google Books is registered only when `VITE_GOOGLE_BOOKS_KEY` is non-blank; a
 * blank key omits the provider and the app keeps working on Gutendex + OL.
 */
async function buildComposite(): Promise<CompositeCatalogProvider> {
  const rows = await registry.listInstalled();
  const addons = new AddonFanoutCatalogProvider({
    loadAddons: () => registry.listDescriptors(),
    clientFor: (addonId) => registry.addonClient(addonId),
    onFailure: surfaceAddonFailures,
    // `stream` is the one consent-gated resource; the same per-addon gate the
    // v2 adapters receive, so a grant covers both paths for one addon.
    consent: addonConsent,
    isOwned: isOwnedSourceItem,
  });
  // `listSources` is a synchronous port method, so the roster is loaded once
  // per build; a registry mutation rebuilds the whole composite.
  await addons.ready();
  const providers: CatalogProvider[] = [
    ...defaultCatalogProviders(rows, undefined, googleBooksKeyFromEnv(), addonConsent),
    addons,
  ];
  return new CompositeCatalogProvider(providers, { cache: discoverCache });
}

function createLiveSupplier(): CatalogProviderSupplier {
  let current: Promise<CompositeCatalogProvider> | null = null;
  let built: CompositeCatalogProvider | null = null;
  let generation = 0;
  return {
    current(): Promise<CompositeCatalogProvider> {
      const gen = generation;
      return (current ??= buildComposite().then(async (composite) => {
        if (gen === generation) built = composite;
        // Every rebuild re-runs the preload, so addon changes re-seed the mirror.
        await preloadAuthorRailPages();
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

const supplier: CatalogProviderSupplier = createLiveSupplier();
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
