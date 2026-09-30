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
export { addonIdFromUrl } from './addonId';
export { CuratedCatalogProvider } from './CuratedCatalogProvider';
