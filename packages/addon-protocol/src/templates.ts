/**
 * Tier B: `nexo.templates[resource]` wins over the routed form. Pure, no I/O.
 *
 * Templates are a client-side courtesy to addons that cannot host Stremio
 * routes. They substitute a closed placeholder set and nothing else.
 */

import { AddonFetchError, AddonFetchErrorCode } from '@nexo/manifest-validator';
import type { AddonManifestV3 } from './manifest';
import { buildRoutedUrl, encodeSegment, type ResourceRequest } from './resourceUrl';

/** Any brace-delimited token, so `{publisher}` is caught, not passed through. */
const TEMPLATE_TOKEN = /\{([^{}]*)\}/g;

type PlaceholderValue = string | number | null | undefined;

function invalid(detail: string): never {
  throw new AddonFetchError(AddonFetchErrorCode.INVALID_MANIFEST, detail);
}

/**
 * `{query}` and `{page}` are legacy aliases of `{search}` and `{skip}`, and
 * `{bookId}` of `{id}`. A request that omits an argument yields an empty
 * substitution, never a literal token sent upstream.
 */
function placeholderValue(token: string, req: ResourceRequest): PlaceholderValue {
  const extra = req.extra ?? {};
  switch (token) {
    case 'search':
    case 'query':
      return extra.search ?? extra.query;
    case 'skip':
    case 'page':
      return extra.skip ?? extra.page;
    case 'genre':
      return extra.genre;
    case 'lang':
      return extra.lang;
    case 'id':
    case 'bookId':
      return req.id;
    default:
      invalid(`template placeholder "{${token}}" is not allowed`);
  }
}

export function fillTemplate(template: string, req: ResourceRequest): string {
  return template.replace(TEMPLATE_TOKEN, (_match, token: string) => {
    const value = placeholderValue(token, req);
    return encodeSegment(value === null || value === undefined ? '' : String(value));
  });
}

/**
 * Structural on purpose: the install record (`AddonDescriptor`, with install
 * metadata) satisfies it, and this module never needs the metadata.
 */
export function buildRequestUrl(
  addon: { installUrl: string; manifest: AddonManifestV3 },
  req: ResourceRequest,
): string {
  const template = addon.manifest.nexo?.templates?.[req.resource];
  if (template !== undefined) return fillTemplate(template, req);
  return buildRoutedUrl(addon.installUrl, req);
}
