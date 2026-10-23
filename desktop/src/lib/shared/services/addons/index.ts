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
export { AddonFanoutCatalogProvider } from './AddonCatalogProvider';
export type { AddonFanoutCatalogOptions, AddonFailureListener } from './AddonCatalogProvider';
