/**
 * Request-URL construction for Tier A (routed) addons. Pure, no I/O.
 *
 * `installUrl` is only ever a string: provenance lives in the install record,
 * so this module cannot leak credentials or ambient state into a URL.
 */

import { AddonFetchError, AddonFetchErrorCode } from '@nexo/manifest-validator';

export type AddonResourceName = 'catalog' | 'meta' | 'stream';

export interface ResourceRequest {
  resource: AddonResourceName;
  type: string;
  id: string;
  extra?: Record<string, string | number | null | undefined>;
}

const MANIFEST_SUFFIX = /\/manifest\.json$/;
const TRAILING_SLASHES = /\/+$/;

function invalid(detail: string): never {
  throw new AddonFetchError(AddonFetchErrorCode.INVALID_MANIFEST, detail);
}

/** `https://a.test/addons/x/manifest.json/` → `https://a.test/addons/x`. */
export function resolveAddonBase(installUrl: string): string {
  return installUrl
    .trim()
    .replace(TRAILING_SLASHES, '')
    .replace(MANIFEST_SUFFIX, '')
    .replace(TRAILING_SLASHES, '');
}

/**
 * `encodeURIComponent` with `%3A` restored to `:`, so `isbn:978…` stays
 * readable in a path segment and in an extra value.
 */
export function encodeSegment(value: string): string {
  return encodeURIComponent(value).replace(/%3A/g, ':');
}

/**
 * Serializes extra arguments in insertion order. Nullish and empty-string
 * values are skipped: an absent filter must not become `genre=`.
 */
export function encodeExtra(extra: ResourceRequest['extra']): string {
  if (extra === undefined) return '';
  const parts: string[] = [];
  for (const [key, value] of Object.entries(extra)) {
    if (value === null || value === undefined || value === '') continue;
    parts.push(`${encodeSegment(key)}=${encodeSegment(String(value))}`);
  }
  return parts.join('&');
}

function decodePart(raw: string): string | null {
  try {
    return decodeURIComponent(raw);
  } catch {
    return null;
  }
}

/** Inverse of `encodeExtra` for a client that receives a routed path. */
export function decodeExtra(raw: string): Record<string, string> {
  const decoded: Record<string, string> = {};
  for (const segment of raw.split('&')) {
    const separator = segment.indexOf('=');
    if (separator <= 0) continue;
    const key = decodePart(segment.slice(0, separator));
    const value = decodePart(segment.slice(separator + 1));
    if (key === null || value === null) continue;
    decoded[key] = value;
  }
  return decoded;
}

/**
 * A `/` in a type or id would forge an extra path segment, so it is a manifest
 * defect rather than something to percent-encode into an unreadable URL.
 */
function assertRoutableSegment(value: string, label: string): void {
  if (value.length === 0) invalid(`request ${label} must be non-empty`);
  if (value.includes('/')) invalid(`request ${label} must not contain "/": ${value}`);
}

/** `{base}/{resource}/{type}/{id}[/{extra}].json` */
export function buildRoutedUrl(installUrl: string, req: ResourceRequest): string {
  const base = resolveAddonBase(installUrl);
  if (base.length === 0) invalid(`install URL has no base: ${installUrl}`);
  assertRoutableSegment(req.type, 'type');
  assertRoutableSegment(req.id, 'id');

  const extra = encodeExtra(req.extra);
  const tail = extra.length > 0 ? `/${extra}` : '';
  return `${base}/${req.resource}/${encodeSegment(req.type)}/${encodeSegment(req.id)}${tail}.json`;
}
