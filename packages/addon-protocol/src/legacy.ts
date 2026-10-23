/**
 * Lossless v2 → v3 adapter. Pure, no I/O.
 *
 * A v2 manifest describes the same addon with flat endpoint URLs instead of
 * resources. Adapting rather than re-fetching keeps every installed addon
 * working through one code path, and `nexo.shape: "legacy-v2"` keeps today's
 * payload parsers in charge of the response.
 */

import { type AddonManifest, validateManifest } from '@nexo/manifest-validator';
import { TYPE_BOOK } from './constants';
import { isManifestV3, parseManifestV3, type AddonManifestV3, type AddonResourceV3 } from './manifest';

/** v2 template placeholders and their v3 names. */
const LEGACY_PLACEHOLDERS: ReadonlyArray<readonly [RegExp, string]> = [
  [/\{query\}/g, '{search}'],
  [/\{page\}/g, '{skip}'],
  [/\{bookId\}/g, '{id}'],
];

function rewriteTemplate(template: string): string {
  return LEGACY_PLACEHOLDERS.reduce(
    (rewritten, [pattern, token]) => rewritten.replace(pattern, token),
    template,
  );
}

/** v2 declared `book-catalog`; v3 has a single book type. */
function adaptCatalog(manifest: AddonManifest) {
  const extra = manifest.searchUrl === undefined ? undefined : [{ name: 'search' }, { name: 'skip' }];
  return manifest.catalogs.map((entry) =>
    extra === undefined
      ? { type: TYPE_BOOK, id: entry.id, name: entry.name }
      : { type: TYPE_BOOK, id: entry.id, name: entry.name, extra },
  );
}

/**
 * v2 resources are endpoint names (`search`, `book-details`, `resolve`); v3
 * resources are `catalog` / `meta` / `stream`. Unmapped v2 names contribute
 * nothing rather than an unknown v3 resource.
 */
function adaptResources(manifest: AddonManifest): AddonResourceV3[] {
  const declared = new Set(manifest.resources);
  const hasStream =
    manifest.resolveUrl !== undefined ||
    declared.has('resolve') ||
    (manifest.capabilities ?? []).includes('resolve');

  const resources: AddonResourceV3[] = [];
  // The v2 validator guarantees at least one catalog.
  resources.push({ name: 'catalog' });
  if (manifest.detailsUrl !== undefined || declared.has('book-details') || declared.has('details')) {
    resources.push({ name: 'meta', types: [TYPE_BOOK] });
  }
  if (hasStream) resources.push({ name: 'stream', types: [TYPE_BOOK] });
  return resources;
}

export function adaptLegacyManifest(manifest: AddonManifest): AddonManifestV3 {
  const templates: Record<string, string> = {};
  if (manifest.searchUrl !== undefined) templates.catalog = rewriteTemplate(manifest.searchUrl);
  if (manifest.detailsUrl !== undefined) templates.meta = rewriteTemplate(manifest.detailsUrl);
  if (manifest.resolveUrl !== undefined) templates.stream = rewriteTemplate(manifest.resolveUrl);

  const adapted: AddonManifestV3 = {
    id: manifest.id,
    version: manifest.version,
    name: manifest.name,
    types: [TYPE_BOOK],
    catalogs: adaptCatalog(manifest),
    resources: adaptResources(manifest),
    nexo: { shape: 'legacy-v2' },
  };
  if (Object.keys(templates).length > 0) adapted.nexo!.templates = templates;
  return adapted;
}

const UNDECODABLE = Symbol('undecodable-json');

function decodeJson(bytes: Uint8Array): unknown {
  try {
    return JSON.parse(new TextDecoder('utf-8', { fatal: true }).decode(bytes));
  } catch {
    return UNDECODABLE;
  }
}

/**
 * Dispatches on manifest shape, then lets the owning validator report every
 * transport and shape error: v3 has `types` + `resources`, v2 does not.
 * A body that is not JSON falls through to the v2 validator so the caller
 * sees the canonical `ADDON_FETCH_INVALID_MANIFEST`.
 */
export function loadManifest(bytes: Uint8Array, contentType: string | null): AddonManifestV3 {
  const candidate = decodeJson(bytes);
  if (candidate !== UNDECODABLE && isManifestV3(candidate)) {
    return parseManifestV3(bytes, contentType);
  }
  return adaptLegacyManifest(validateManifest(bytes, contentType));
}
