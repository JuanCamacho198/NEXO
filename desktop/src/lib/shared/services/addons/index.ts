export {
  MAX_MANIFEST_BYTES,
  AddonFetchErrorCode,
  AddonFetchError,
  assertHttpsInstallUrl,
  validateManifest,
} from '@nexo/manifest-validator';
export type {
  AddonManifest,
  AddonCatalogEntry,
  AddonFetchErrorCode as AddonFetchErrorCodeValue,
} from '@nexo/manifest-validator';
export {
  AddonRegistry,
  DEFAULT_ADDON_TIMEOUT_MS,
  MAX_CONCURRENT_PER_HOST,
  createAddonResourceTransport,
  defaultAddonTransport,
  getAddonRegistry,
} from './AddonRegistry';
export type {
  AddonFetchResult,
  AddonRegistryStore,
  AddonResourceTransportOptions,
  AddonTransport,
  InstalledAddonRow,
} from './AddonRegistry';
export { addonIdFromUrl } from './addonId';
export { CuratedCatalogProvider } from './CuratedCatalogProvider';
export {
  MAX_COLLECTION_ENTRIES,
  fetchCollection,
  installCollection,
  parseCollection,
  requestsStream,
  serializeCollection,
} from './collection';
export type {
  CollectionEntry,
  CollectionInstallRegistry,
  CollectionInstallResult,
  ParsedCollection,
} from './collection';
export { AddonFanoutCatalogProvider } from './AddonCatalogProvider';
export type { AddonFanoutCatalogOptions, AddonFailureListener } from './AddonCatalogProvider';
export {
  LIBRARY_META_CACHE_TTL_MS,
  LibraryMetadataService,
  TauriLibraryMetadataPersistence,
  enrichLibraryBookAfterImport,
  libraryMetaCacheKey,
  libraryMetadata,
} from './libraryMetadata';
export type {
  LibraryCoverBytes,
  LibraryMetadataBook,
  LibraryMetadataOptions,
  LibraryMetadataPersistence,
  LibraryMetadataResult,
} from './libraryMetadata';
