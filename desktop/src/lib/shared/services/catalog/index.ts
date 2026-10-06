export {
  BUILTIN_GUTENDEX,
  BUILTIN_GOOGLEBOOKS,
  BUILTIN_OPENLIBRARY,
  addonSource,
  addonSourceIdOf,
  parseCatalogSource,
} from './CatalogProvider';
export type {
  CatalogBook,
  CatalogFeaturedSort,
  CatalogProvider,
  CatalogSource,
  CatalogSourceInfo,
  CatalogSourceKind,
  PagedResult,
  AddonAccessResolution,
} from './CatalogProvider';
export {
  CatalogError,
  catalogError,
  isCatalogError,
  isRetryableCatalogCode,
  mapHttpStatusToCode,
} from './errors';
export type { CatalogErrorCode } from './errors';
export { GutendexDataSource, GUTENDEX_BASE_URL } from './GutendexDataSource';
export {
  GoogleBooksDataSource,
  GOOGLE_BOOKS_BASE_URL,
  GOOGLE_BOOKS_VOLUME_FIELDS,
  buildGoogleBooksQuery,
} from './GoogleBooksDataSource';
export {
  GoogleBooksCatalogProvider,
  googleBooksKeyFromEnv,
  googleBooksProviderOrNull,
} from './BuiltInCatalogProviders';
export { resolveAccess, isHttpsUrl } from './accessResolver';
export type { AccessGroup, AccessOption, LegalAccess } from './accessResolver';
export {
  OpenLibraryDataSource,
  OPEN_LIBRARY_BASE_URL,
  OPEN_LIBRARY_ISBN_FIELDS,
} from './OpenLibraryDataSource';
export {
  authorityQuery,
  hasResolvedAuthorities,
  isbnOf,
  resolveBookAuthorities,
} from './bookAuthorityResolver';
export type { BookAuthorityLookups, OpenLibraryIdentity } from './bookAuthorityResolver';
export {
  CompositeCatalogProvider,
  createRebuildingCatalogProvider,
  defaultCatalogProviders,
} from './CompositeCatalogProvider';
export type { CatalogProviderSupplier, CompositeOptions } from './CompositeCatalogProvider';
export { liveCatalogProvider } from './liveComposite';
export {
  DETAIL_TTL_S,
  InMemoryDiscoverCache,
  PAGE_TTL_S,
  detailCacheKey,
  pageCacheKey,
} from './DiscoverCache';
export type { DiscoverCacheStore } from './DiscoverCache';
export {
  backoffDelayMs,
  clampPageSize,
  composeDeadline,
  createRateLimiter,
  createSearchDebouncer,
  fetchWithRetry,
  shouldRetryStatus,
  toCatalogError,
  buildUserAgent,
  DEBOUNCE_MS,
  DEFAULT_PAGE_SIZE,
  DESKTOP_USER_AGENT,
  DETAIL_DEADLINE_MS,
  MAX_DELAYED_RETRIES,
  MAX_PAGE_SIZE,
  MIN_PAGE_SIZE,
  OL_MIN_GAP_MS,
  REQUEST_DEADLINE_MS,
  RETRY_BASE_DELAY_MS,
  SEARCH_DEADLINE_MS,
} from './policy';
export type { ComposedDeadline } from './policy';
export {
  computeNextPage,
  firstIsbn10,
  firstIsbn13,
  googleBooksCoverUrl,
  googleIndustryIdentifier,
  isGutendexPublicDomain,
  isOpenLibraryPublic,
  mapGoogleBooksVolume,
  mapGutendexBook,
  mapOpenLibraryDoc,
  mergeResults,
  normalizeMatchKey,
  openLibraryCoverUrl,
  resolveDownloadUrl,
  resolveTotalCount,
  toPagedResult,
} from './mappers';
export type {
  GoogleBooksImageLinks,
  GoogleBooksIndustryIdentifier,
  GoogleBooksSearchResponse,
  GoogleBooksVolumeInfo,
  GoogleBooksVolumeItem,
  GutendexRecord,
  OpenLibraryDoc,
} from './mappers';
