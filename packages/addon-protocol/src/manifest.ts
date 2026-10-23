/**
 * Manifest v3 parsing: pure, no I/O.
 *
 * The caller fetches bytes through the platform network layer and hands them
 * here. Size cap, HTTPS rule and error class come from `@nexo/manifest-validator`
 * so v2 and v3 cannot drift on transport-level policy.
 */

import {
  AddonFetchError,
  AddonFetchErrorCode,
  MAX_MANIFEST_BYTES,
  assertHttpsInstallUrl,
  isJsonContentType,
} from '@nexo/manifest-validator';
import {
  EXTRA_NAMES,
  MAX_NEXO_CHARS,
  RESERVED_TYPES,
  SHAPES,
  TYPE_BOOK,
} from './constants';

export interface AddonExtraArg {
  name: string;
  isRequired?: boolean;
}

export interface AddonCatalogEntryV3 {
  type: string;
  id: string;
  name: string;
  extra?: AddonExtraArg[];
}

/** A resource declaration. String entries normalize to `{ name }`. */
export interface AddonResourceV3 {
  name: string;
  types?: string[];
  idPrefixes?: string[];
}

export type AddonShape = (typeof SHAPES)[number];

/** NEXO-only extension. Ignored by Stremio, so it can never collide. */
export interface AddonNexo {
  homepage?: string;
  license?: string;
  templates?: Record<string, string>;
  shape?: AddonShape;
}

export interface AddonManifestV3 {
  id: string;
  version: string;
  name: string;
  description?: string;
  types: string[];
  catalogs: AddonCatalogEntryV3[];
  resources: AddonResourceV3[];
  idPrefixes?: string[];
  logo?: string;
  background?: string;
  behaviorHints?: { adult?: boolean };
  nexo?: AddonNexo;
}

const MAX_TYPES = 16;
const MAX_TYPE_CHARS = 64;
const MAX_RESOURCES = 16;
const MAX_CATALOGS = 16;
const MAX_CATALOG_ENTRY_CHARS = 512;
const MAX_EXTRA_ARGS = 8;
const MAX_ID_PREFIXES = 16;
const MAX_TEMPLATES = 16;
const MAX_STRING_CHARS = 2048;

function invalid(detail: string): never {
  throw new AddonFetchError(AddonFetchErrorCode.INVALID_MANIFEST, detail);
}

function isPlainObject(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

function isNonEmptyString(value: unknown): value is string {
  return typeof value === 'string' && value.length > 0;
}

/** Mirrors the validator's rule: `application/json` or any `+json` suffix. */
/**
 * Bounded HTTPS string. Delegates the scheme decision to the shared install-URL
 * rule, then reports a shape error because a bad template is a manifest defect.
 */
function parseHttpsUrl(value: unknown, label: string, maxChars = MAX_STRING_CHARS): string {
  if (!isNonEmptyString(value) || value.length > maxChars) {
    invalid(`${label} must be a non-empty https string`);
  }
  try {
    assertHttpsInstallUrl(value);
  } catch {
    invalid(`${label} must be https: ${value}`);
  }
  return value;
}

function parseBoundedString(value: unknown, label: string, maxChars = MAX_STRING_CHARS): string {
  if (!isNonEmptyString(value) || value.length > maxChars) {
    invalid(`${label} must be a non-empty string of at most ${maxChars} chars`);
  }
  return value;
}

function assertSupportedType(entry: string): void {
  if ((RESERVED_TYPES as readonly string[]).includes(entry)) {
    throw new AddonFetchError(
      AddonFetchErrorCode.UNSUPPORTED_TYPE,
      `type "${entry}" is reserved and not implemented`,
    );
  }
}

function parseTypes(value: unknown, label: string): string[] {
  if (!Array.isArray(value) || value.length === 0) {
    invalid(`${label} must be a non-empty array`);
  }
  if (value.length > MAX_TYPES) {
    invalid(`too many ${label} entries`);
  }
  return value.map((entry) => {
    const parsed = parseBoundedString(entry, `${label} entry`, MAX_TYPE_CHARS);
    assertSupportedType(parsed);
    return parsed;
  });
}

function parseIdPrefixes(value: unknown, label: string): string[] {
  if (!Array.isArray(value) || value.length > MAX_ID_PREFIXES) {
    invalid(`${label} must be an array of at most ${MAX_ID_PREFIXES} entries`);
  }
  return value.map((entry) => parseBoundedString(entry, `${label} entry`, MAX_TYPE_CHARS));
}

function parseResource(value: unknown): AddonResourceV3 {
  if (typeof value === 'string') {
    return { name: parseBoundedString(value, 'resource entry', MAX_TYPE_CHARS) };
  }
  if (!isPlainObject(value)) {
    invalid('resource entry must be a string or an object');
  }
  const resource: AddonResourceV3 = { name: parseBoundedString(value.name, 'resource name') };
  if (value.types !== undefined) {
    resource.types = parseTypes(value.types, 'resource types');
  }
  if (value.idPrefixes !== undefined) {
    resource.idPrefixes = parseIdPrefixes(value.idPrefixes, 'resource idPrefixes');
  }
  return resource;
}

function parseResources(value: unknown): AddonResourceV3[] {
  if (!Array.isArray(value) || value.length === 0) {
    invalid('resources must be a non-empty array');
  }
  if (value.length > MAX_RESOURCES) {
    invalid('too many resources');
  }
  return value.map(parseResource);
}

function parseExtraArgs(value: unknown): AddonExtraArg[] {
  if (value === undefined) return [];
  if (!Array.isArray(value) || value.length > MAX_EXTRA_ARGS) {
    invalid(`catalog extra must be an array of at most ${MAX_EXTRA_ARGS} entries`);
  }
  const args: AddonExtraArg[] = [];
  for (const entry of value) {
    if (!isPlainObject(entry)) {
      invalid('catalog extra entry must be an object');
    }
    const name = parseBoundedString(entry.name, 'catalog extra name', MAX_TYPE_CHARS);
    // Unknown extra names are dropped: `catalog[].extra` is the allow-list.
    if (!(EXTRA_NAMES as readonly string[]).includes(name)) continue;
    if (entry.isRequired !== undefined && typeof entry.isRequired !== 'boolean') {
      invalid(`catalog extra "${name}" has a non-boolean isRequired`);
    }
    args.push(
      entry.isRequired === undefined ? { name } : { name, isRequired: entry.isRequired },
    );
  }
  return args;
}

function parseCatalogEntry(value: unknown): AddonCatalogEntryV3 | null {
  if (!isPlainObject(value)) {
    invalid('catalog entry must be an object');
  }
  const type = parseBoundedString(value.type, 'catalog type', MAX_CATALOG_ENTRY_CHARS);
  // Other declared types install successfully and contribute nothing.
  if (type !== TYPE_BOOK) return null;

  const entry: AddonCatalogEntryV3 = {
    type,
    id: parseBoundedString(value.id, 'catalog id', MAX_CATALOG_ENTRY_CHARS),
    name: parseBoundedString(value.name, 'catalog name', MAX_CATALOG_ENTRY_CHARS),
  };
  const extra = parseExtraArgs(value.extra);
  if (extra.length > 0) entry.extra = extra;
  return entry;
}

/** Catalogs MAY be empty: a stream-only addon has no catalog. */
function parseCatalogs(value: unknown): AddonCatalogEntryV3[] {
  if (value === undefined) return [];
  if (!Array.isArray(value)) {
    invalid('catalogs must be an array');
  }
  if (value.length > MAX_CATALOGS) {
    invalid('too many catalogs');
  }
  const catalogs: AddonCatalogEntryV3[] = [];
  for (const entry of value) {
    const parsed = parseCatalogEntry(entry);
    if (parsed !== null) catalogs.push(parsed);
  }
  return catalogs;
}

function parseTemplates(value: unknown): Record<string, string> {
  if (!isPlainObject(value)) {
    invalid('nexo.templates must be an object');
  }
  const entries = Object.entries(value);
  if (entries.length > MAX_TEMPLATES) {
    invalid('too many nexo.templates entries');
  }
  const templates: Record<string, string> = {};
  for (const [resource, template] of entries) {
    parseBoundedString(resource, 'nexo.templates key', MAX_TYPE_CHARS);
    templates[resource] = parseHttpsUrl(template, `nexo.templates.${resource}`);
  }
  return templates;
}

/**
 * `nexo` is preserved verbatim but bounded, and carries only the four keys
 * NEXO interprets. Unknown keys inside it are dropped, never surfaced.
 */
function parseNexo(value: unknown): AddonNexo | undefined {
  if (value === undefined) return undefined;
  if (!isPlainObject(value)) {
    invalid('nexo must be an object');
  }
  if (JSON.stringify(value).length > MAX_NEXO_CHARS) {
    invalid(`nexo exceeds ${MAX_NEXO_CHARS} chars`);
  }
  const nexo: AddonNexo = {};
  if (value.homepage !== undefined) {
    nexo.homepage = parseHttpsUrl(value.homepage, 'nexo.homepage');
  }
  if (value.license !== undefined) {
    nexo.license = parseBoundedString(value.license, 'nexo.license');
  }
  if (value.templates !== undefined) {
    nexo.templates = parseTemplates(value.templates);
  }
  if (value.shape !== undefined) {
    const shape = parseBoundedString(value.shape, 'nexo.shape', MAX_TYPE_CHARS);
    if (!(SHAPES as readonly string[]).includes(shape)) {
      invalid(`nexo.shape "${shape}" is not a known shape`);
    }
    nexo.shape = shape as AddonShape;
  }
  return nexo;
}

function parseBehaviorHints(value: unknown): { adult?: boolean } | undefined {
  if (value === undefined) return undefined;
  if (!isPlainObject(value)) {
    invalid('behaviorHints must be an object');
  }
  if (value.adult === undefined) return {};
  if (typeof value.adult !== 'boolean') {
    invalid('behaviorHints.adult must be a boolean');
  }
  return { adult: value.adult };
}

function parseManifestObject(value: unknown): AddonManifestV3 {
  if (!isPlainObject(value)) {
    invalid('manifest must be a JSON object');
  }
  const manifest: AddonManifestV3 = {
    id: parseBoundedString(value.id, 'id'),
    version: parseBoundedString(value.version, 'version'),
    name: parseBoundedString(value.name, 'name'),
    types: parseTypes(value.types, 'types'),
    catalogs: parseCatalogs(value.catalogs),
    resources: parseResources(value.resources),
  };
  if (value.description !== undefined) {
    manifest.description = parseBoundedString(value.description, 'description');
  }
  if (value.idPrefixes !== undefined) {
    manifest.idPrefixes = parseIdPrefixes(value.idPrefixes, 'idPrefixes');
  }
  if (value.logo !== undefined) {
    manifest.logo = parseHttpsUrl(value.logo, 'logo');
  }
  if (value.background !== undefined) {
    manifest.background = parseHttpsUrl(value.background, 'background');
  }
  const behaviorHints = parseBehaviorHints(value.behaviorHints);
  if (behaviorHints !== undefined) manifest.behaviorHints = behaviorHints;
  const nexo = parseNexo(value.nexo);
  if (nexo !== undefined) manifest.nexo = nexo;
  return manifest;
}

/**
 * Cheap structural probe used to choose the v3 parser. It selects, it never
 * validates: `types` is an array and every `resources` entry is a string or an
 * object carrying a string `name`.
 */
export function isManifestV3(value: unknown): boolean {
  if (!isPlainObject(value)) return false;
  if (!Array.isArray(value.types)) return false;
  if (!Array.isArray(value.resources)) return false;
  return value.resources.every(
    (entry) =>
      typeof entry === 'string' ||
      (isPlainObject(entry) && typeof entry.name === 'string'),
  );
}

/**
 * Validate manifest bytes + upstream content type.
 * Order: size cap (pre-parse) → content type → JSON parse → shape.
 * Unknown top-level fields are ignored.
 */
export function parseManifestV3(
  bytes: Uint8Array,
  contentType: string | null,
): AddonManifestV3 {
  if (bytes.byteLength > MAX_MANIFEST_BYTES) {
    throw new AddonFetchError(
      AddonFetchErrorCode.TOO_LARGE,
      `manifest exceeds ${MAX_MANIFEST_BYTES} bytes`,
    );
  }
  if (!isJsonContentType(contentType)) {
    throw new AddonFetchError(
      AddonFetchErrorCode.BAD_CONTENT_TYPE,
      `content type is not JSON: ${contentType}`,
    );
  }
  let parsed: unknown;
  try {
    parsed = JSON.parse(new TextDecoder('utf-8', { fatal: true }).decode(bytes));
  } catch {
    invalid('manifest is not valid JSON');
  }
  return parseManifestObject(parsed);
}
