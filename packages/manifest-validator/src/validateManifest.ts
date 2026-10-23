/**
 * Pure manifest validation for the addon registry.
 * No I/O: callers fetch bytes via the platform network layer, then validate here.
 * Rules mirror the Android ManifestValidator and the shared parity fixtures.
 */

export const MAX_MANIFEST_BYTES = 64 * 1024;

/** Additive, stable error codes — distinct from catalog transport codes. */
export const AddonFetchErrorCode = {
  HTTPS_REQUIRED: 'ADDON_FETCH_HTTPS_REQUIRED',
  TOO_LARGE: 'ADDON_FETCH_TOO_LARGE',
  BAD_CONTENT_TYPE: 'ADDON_FETCH_BAD_CONTENT_TYPE',
  INVALID_MANIFEST: 'ADDON_FETCH_INVALID_MANIFEST',
  NETWORK: 'ADDON_FETCH_NETWORK',
  /** Reserved protocol types (`audiobook`, `comic`) are declared but not implemented. */
  UNSUPPORTED_TYPE: 'ADDON_FETCH_UNSUPPORTED_TYPE',
} as const;

export type AddonFetchErrorCode = (typeof AddonFetchErrorCode)[keyof typeof AddonFetchErrorCode];

export class AddonFetchError extends Error {
  readonly code: AddonFetchErrorCode;

  constructor(code: AddonFetchErrorCode, message: string) {
    super(`${code}: ${message}`);
    this.name = 'AddonFetchError';
    this.code = code;
  }
}

export interface AddonCatalogEntry {
  type: string;
  id: string;
  name: string;
}

export interface AddonManifest {
  id: string;
  name: string;
  version: string;
  catalogs: AddonCatalogEntry[];
  resources: string[];
  /** Optional catalog endpoint template: `{query}`, `{page}` placeholders. */
  searchUrl?: string;
  /** Optional detail endpoint template: `{bookId}` placeholder. */
  detailsUrl?: string;
  /**
   * Optional v2 resolve endpoint template: `{isbn}`, `{title}`, `{author}`,
   * `{openLibraryId}`, `{googleBooksId}` placeholders. Validated https by
   * `parseEndpoint`, so a non-https value is rejected before any resolve I/O.
   */
  resolveUrl?: string;
  /** Optional v2 capability ids (open set; `resolve` is the known id). */
  capabilities?: string[];
}

function addonFetchError(code: AddonFetchErrorCode, detail?: string): AddonFetchError {
  return new AddonFetchError(code, detail ?? code);
}

/** HTTPS-only install URLs: reject before any network I/O. */
export function assertHttpsInstallUrl(url: string): boolean {
  let parsed: URL;
  try {
    parsed = new URL(url);
  } catch {
    throw addonFetchError(AddonFetchErrorCode.HTTPS_REQUIRED, `install URL must be https: ${url}`);
  }
  if (parsed.protocol !== 'https:') {
    throw addonFetchError(AddonFetchErrorCode.HTTPS_REQUIRED, `install URL must be https: ${url}`);
  }
  return true;
}

export function isJsonContentType(contentType: string | null | undefined): boolean {
  if (!contentType) return false;
  const base = contentType.split(';')[0]!.trim().toLowerCase();
  return base === 'application/json' || base.endsWith('+json');
}

function isPlainObject(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

function isNonEmptyString(value: unknown): value is string {
  return typeof value === 'string' && value.length > 0;
}

const MAX_CATALOGS = 16;
const MAX_CATALOG_ENTRY_CHARS = 512;
const MAX_RESOURCES = 16;
const MAX_RESOURCE_CHARS = 64;

function parseCatalogEntry(value: unknown): AddonCatalogEntry {
  if (!isPlainObject(value)) {
    throw addonFetchError(AddonFetchErrorCode.INVALID_MANIFEST, 'catalog entry must be an object');
  }
  for (const key of ['type', 'id', 'name'] as const) {
    const entryValue = value[key];
    if (!isNonEmptyString(entryValue) || entryValue.length > MAX_CATALOG_ENTRY_CHARS) {
      throw addonFetchError(AddonFetchErrorCode.INVALID_MANIFEST, `catalog entry missing ${key}`);
    }
  }
  return {
    type: value.type as string,
    id: value.id as string,
    name: value.name as string,
  };
}

function parseResources(value: unknown): string[] {
  if (!Array.isArray(value) || value.length === 0) {
    throw addonFetchError(
      AddonFetchErrorCode.INVALID_MANIFEST,
      'resources must be a non-empty array',
    );
  }
  if (value.length > MAX_RESOURCES) {
    throw addonFetchError(AddonFetchErrorCode.INVALID_MANIFEST, 'too many resources');
  }
  return value.map((entry) => {
    if (!isNonEmptyString(entry) || entry.length > MAX_RESOURCE_CHARS) {
      throw addonFetchError(AddonFetchErrorCode.INVALID_MANIFEST, 'invalid resource entry');
    }
    return entry;
  });
}

const MAX_ENDPOINT_CHARS = 2048;

function parseEndpoint(value: unknown): string | undefined {
  if (value === undefined) return undefined;
  if (!isNonEmptyString(value) || value.length > MAX_ENDPOINT_CHARS) {
    throw addonFetchError(
      AddonFetchErrorCode.INVALID_MANIFEST,
      'endpoint must be a non-empty https string',
    );
  }
  let parsed: URL;
  try {
    parsed = new URL(value);
  } catch {
    throw addonFetchError(AddonFetchErrorCode.INVALID_MANIFEST, `endpoint must be https: ${value}`);
  }
  if (parsed.protocol !== 'https:') {
    throw addonFetchError(AddonFetchErrorCode.INVALID_MANIFEST, `endpoint must be https: ${value}`);
  }
  return value;
}

export const MAX_CAPABILITIES = 16;
export const MAX_CAPABILITY_CHARS = 64;

/**
 * Optional v2 capability list: absent ⇒ `undefined` (the endpoint fields
 * stay authoritative for v1-style manifests); present ⇒ a string array with
 * `1 ≤ length ≤ MAX_CAPABILITIES`, each entry non-empty and
 * `≤ MAX_CAPABILITY_CHARS`. Anything else ⇒ `ADDON_FETCH_INVALID_MANIFEST`.
 */
function parseCapabilities(value: unknown): string[] | undefined {
  if (value === undefined) return undefined;
  if (!Array.isArray(value) || value.length === 0 || value.length > MAX_CAPABILITIES) {
    throw addonFetchError(
      AddonFetchErrorCode.INVALID_MANIFEST,
      'capabilities must be a non-empty array of at most 16 entries',
    );
  }
  return value.map((entry) => {
    if (!isNonEmptyString(entry) || entry.length > MAX_CAPABILITY_CHARS) {
      throw addonFetchError(AddonFetchErrorCode.INVALID_MANIFEST, 'invalid capability entry');
    }
    return entry;
  });
}

function parseManifestObject(value: unknown): AddonManifest {
  if (!isPlainObject(value)) {
    throw addonFetchError(AddonFetchErrorCode.INVALID_MANIFEST, 'manifest must be a JSON object');
  }
  for (const key of ['id', 'name', 'version'] as const) {
    if (!isNonEmptyString(value[key])) {
      throw addonFetchError(AddonFetchErrorCode.INVALID_MANIFEST, `missing required field ${key}`);
    }
  }
  if (!Array.isArray(value.catalogs) || value.catalogs.length === 0) {
    throw addonFetchError(
      AddonFetchErrorCode.INVALID_MANIFEST,
      'catalogs must be a non-empty array',
    );
  }
  if (value.catalogs.length > MAX_CATALOGS) {
    throw addonFetchError(AddonFetchErrorCode.INVALID_MANIFEST, 'too many catalogs');
  }
  return {
    id: value.id as string,
    name: value.name as string,
    version: value.version as string,
    catalogs: value.catalogs.map(parseCatalogEntry),
    resources: parseResources(value.resources),
    searchUrl: parseEndpoint(value.searchUrl),
    detailsUrl: parseEndpoint(value.detailsUrl),
    resolveUrl: parseEndpoint(value.resolveUrl),
    capabilities: parseCapabilities(value.capabilities),
  };
}

/**
 * Declared v2 capability ids. An absent list leaves endpoint fields
 * authoritative (v1-style manifests); an empty capability array therefore
 * normalizes to `[]`, not `undefined`.
 */
export function declaredCapabilities(manifest: AddonManifest): string[] {
  return manifest.capabilities ?? [];
}

/**
 * Validate manifest bytes + upstream content type.
 * Order: size cap (pre-parse) → content type → JSON parse → shape.
 * Unknown top-level/entry fields are ignored.
 */
export function validateManifest(bytes: Uint8Array, contentType: string | null): AddonManifest {
  if (bytes.byteLength > MAX_MANIFEST_BYTES) {
    throw addonFetchError(
      AddonFetchErrorCode.TOO_LARGE,
      `manifest exceeds ${MAX_MANIFEST_BYTES} bytes`,
    );
  }
  if (!isJsonContentType(contentType)) {
    throw addonFetchError(
      AddonFetchErrorCode.BAD_CONTENT_TYPE,
      `content type is not JSON: ${contentType}`,
    );
  }
  let parsed: unknown;
  try {
    parsed = JSON.parse(new TextDecoder('utf-8', { fatal: true }).decode(bytes));
  } catch {
    throw addonFetchError(AddonFetchErrorCode.INVALID_MANIFEST, 'manifest is not valid JSON');
  }
  return parseManifestObject(parsed);
}
