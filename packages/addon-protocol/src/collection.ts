/**
 * Addon collections: one link installs a whole set. Pure, no I/O.
 *
 * The wire shape is Stremio's, the one `stremio-addon-client` ships:
 *
 *     { "addons": [ { "manifest": {...}, "transportUrl": "https://...",
 *                     "transportName": "http", "flags": {} } ] }
 *
 * A bare array of those entries is accepted too, and a collection may arrive as
 * the JSON string a fetched link returns.
 *
 * A collection is untrusted input written by a stranger, so importing one is
 * held to stricter terms than installing a single addon:
 *
 * - `transportUrl` must be HTTPS. An `http:` entry is skipped, never upgraded
 *   or downgraded, because the collection would otherwise choose the scheme.
 * - `flags` is DROPPED, never read. Stremio collections carry a `flags` object
 *   describing transport quirks; here it would be a field a stranger controls.
 *   Do NOT "fix" this by honouring a flag that grants an addon trust, marks it
 *   official, or records stream consent: a collection cannot vouch for its own
 *   entries, and it cannot consent on the reader's behalf. `stream` consent is
 *   per addon, asked individually at install time, and lives on the device.
 * - One bad entry never fails the set: a malformed manifest, a non-object entry
 *   or an `http:` URL is counted in `skipped` and the rest still import.
 * - At most {@link MAX_COLLECTION_ENTRIES} entries install; the overflow is
 *   reported as skipped rather than silently truncated.
 */

import { assertHttpsInstallUrl } from '@nexo/manifest-validator';
import { loadManifest } from './legacy';
import type { AddonManifestV3 } from './manifest';

/** Hard ceiling on one import. Overflow counts toward `skipped`. */
export const MAX_COLLECTION_ENTRIES = 40;

/**
 * Embedded manifests are re-validated through the install path, which needs a
 * content type; the collection already told us this is JSON.
 */
const EMBEDDED_MANIFEST_CONTENT_TYPE = 'application/json';

export interface CollectionEntry {
  manifest: AddonManifestV3;
  transportUrl: string;
}

export interface ParsedCollection {
  /** The importable addons, in collection order, deduplicated by transportUrl. */
  entries: CollectionEntry[];
  /** Entries this import refused: non-objects, non-HTTPS, invalid manifests, overflow. */
  skipped: number;
}

function isPlainObject(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

/** The entry list, or `null` when the input is not a collection at all. */
function readAddonList(input: unknown): unknown[] | null {
  if (typeof input === 'string') {
    let decoded: unknown;
    try {
      decoded = JSON.parse(input);
    } catch {
      return null;
    }
    return readAddonList(decoded);
  }
  if (Array.isArray(input)) return input;
  if (isPlainObject(input) && Array.isArray(input.addons)) return input.addons;
  return null;
}

/** HTTPS enforcement is the shared install-URL rule, so v2 and v3 cannot drift. */
function isHttpsUrl(value: unknown): value is string {
  if (typeof value !== 'string' || value.length === 0) return false;
  try {
    assertHttpsInstallUrl(value);
    return true;
  } catch {
    return false;
  }
}

/**
 * Validate the embedded manifest exactly as an install would (`loadManifest`:
 * size cap, JSON, v3 shape, or the lossless v2 adapter). A failure here is the
 * entry's problem alone, so it is reported as `null`, never thrown.
 */
function readManifest(value: unknown): AddonManifestV3 | null {
  if (!isPlainObject(value)) return null;
  try {
    return loadManifest(
      new TextEncoder().encode(JSON.stringify(value)),
      EMBEDDED_MANIFEST_CONTENT_TYPE,
    );
  } catch {
    // Any AddonFetchError — too large, invalid JSON, reserved type — is a skip.
    return null;
  }
}

/**
 * Parse a collection into the addons it is allowed to install.
 *
 * Never throws: input that is not a collection yields no entries and no skips,
 * so the caller can say "this link holds no addons" instead of failing a dialog.
 */
export function parseCollection(input: unknown): ParsedCollection {
  const addons = readAddonList(input);
  if (addons === null) return { entries: [], skipped: 0 };

  const entries: CollectionEntry[] = [];
  const seen = new Set<string>();
  let skipped = 0;

  for (const raw of addons) {
    if (!isPlainObject(raw)) {
      skipped += 1;
      continue;
    }
    // `raw.flags` is deliberately never read: see the module comment. Every
    // field except `manifest` and `transportUrl` is untrusted decoration.
    const transportUrl = raw.transportUrl;
    if (!isHttpsUrl(transportUrl)) {
      skipped += 1;
      continue;
    }
    // Duplicates collapse to one entry: the same addon listed twice is not an
    // error, and the first occurrence is the one the author wrote first.
    if (seen.has(transportUrl)) continue;
    if (entries.length >= MAX_COLLECTION_ENTRIES) {
      skipped += 1;
      continue;
    }
    const manifest = readManifest(raw.manifest);
    if (manifest === null) {
      skipped += 1;
      continue;
    }
    seen.add(transportUrl);
    entries.push({ manifest, transportUrl });
  }

  return { entries, skipped };
}

/**
 * Export a set as the Stremio collection shape, so a NEXO export imports
 * anywhere and re-importing it here is a no-op.
 *
 * `transportName` is set to `"http"` for interop; `flags` is omitted on purpose
 * (nothing in a NEXO export grants trust or consent). Non-HTTPS install URLs and
 * duplicate URLs are dropped, and the list is capped like an import, so
 * round-tripping a parsed set always yields the same entries.
 */
export function serializeCollection(
  descriptors: Array<{ manifest: AddonManifestV3; installUrl: string }>,
): string {
  const addons: unknown[] = [];
  const seen = new Set<string>();
  for (const descriptor of descriptors) {
    const installUrl = descriptor?.installUrl;
    if (!isHttpsUrl(installUrl)) continue;
    if (seen.has(installUrl)) continue;
    if (addons.length >= MAX_COLLECTION_ENTRIES) continue;
    seen.add(installUrl);
    addons.push({
      transportUrl: installUrl,
      transportName: 'http',
      manifest: descriptor.manifest,
    });
  }
  return JSON.stringify({ addons });
}
