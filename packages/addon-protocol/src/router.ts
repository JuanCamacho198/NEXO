/**
 * Candidate routing: which installed addons can answer a request. Pure, no I/O.
 *
 * Two tiers answer here. A routed addon declares the resource in `resources`;
 * a templated addon declares `nexo.templates[resource]` and is selectable even
 * when its `resources` list does not mention it, because the template is the
 * only thing needed to build the URL.
 */

import type { AddonManifestV3, AddonResourceV3 } from './manifest';

export interface AddonDescriptor {
  id: string;
  installUrl: string;
  addedAt: number;
  enabled: boolean;
  manifest: AddonManifestV3;
}

export interface AddonRouteRequest {
  resource: string;
  type: string;
  id?: string;
}

/**
 * Only `meta` and `stream` address a single item, so only they honor
 * `idPrefixes`. `catalog` ids are catalog identifiers, never item ids.
 */
const PREFIX_FILTERED_RESOURCES: ReadonlySet<string> = new Set(['meta', 'stream']);

function hasTemplate(manifest: AddonManifestV3, resource: string): boolean {
  return manifest.nexo?.templates?.[resource] !== undefined;
}

/** Per-resource list first, then the manifest-wide list, then no filter. */
function effectivePrefixes(
  resource: AddonResourceV3,
  manifest: AddonManifestV3,
): readonly string[] {
  if (resource.idPrefixes !== undefined && resource.idPrefixes.length > 0) {
    return resource.idPrefixes;
  }
  if (manifest.idPrefixes !== undefined && manifest.idPrefixes.length > 0) {
    return manifest.idPrefixes;
  }
  return [];
}

/** A request without an id has nothing to filter on, so it is not filtered. */
function matchesIdPrefixes(prefixes: readonly string[], id: string | undefined): boolean {
  if (prefixes.length === 0) return true;
  if (id === undefined) return true;
  return prefixes.some((prefix) => id.startsWith(prefix));
}

function matchesDeclaration(
  resource: AddonResourceV3,
  requested: string,
  opts: { type: string; id?: string },
  manifest: AddonManifestV3,
): boolean {
  if (resource.types !== undefined && !resource.types.includes(opts.type)) return false;
  if (!PREFIX_FILTERED_RESOURCES.has(requested)) return true;
  return matchesIdPrefixes(effectivePrefixes(resource, manifest), opts.id);
}

export function declaresResource(
  manifest: AddonManifestV3,
  resource: string,
  opts: { type: string; id?: string },
): boolean {
  const declared = manifest.resources.some(
    (entry) => entry.name === resource && matchesDeclaration(entry, resource, opts, manifest),
  );
  return declared || hasTemplate(manifest, resource);
}

/**
 * Ascending `addedAt`, then ascending `id`. Plain code-unit comparison on
 * purpose: `localeCompare` would make the order depend on the host locale.
 */
function compareAddons(left: AddonDescriptor, right: AddonDescriptor): number {
  if (left.addedAt !== right.addedAt) return left.addedAt - right.addedAt;
  if (left.id === right.id) return 0;
  return left.id < right.id ? -1 : 1;
}

export function selectAddons(
  addons: AddonDescriptor[],
  req: AddonRouteRequest,
): AddonDescriptor[] {
  return addons
    .filter(
      (addon) =>
        addon.enabled &&
        declaresResource(addon.manifest, req.resource, { type: req.type, id: req.id }),
    )
    .sort(compareAddons);
}

/** The catalog's own allow-list, in declaration order. Unknown ids yield `[]`. */
export function declaredExtras(manifest: AddonManifestV3, catalogId: string): string[] {
  const catalog = manifest.catalogs.find((entry) => entry.id === catalogId);
  if (catalog?.extra === undefined) return [];
  return catalog.extra.map((extra) => extra.name);
}
