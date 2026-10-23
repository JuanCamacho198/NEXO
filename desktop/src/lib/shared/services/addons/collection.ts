/**
 * Desktop collection service: one link installs a whole set.
 *
 * The wire format and every import rule live in `@nexo/addon-protocol`, shared
 * with the Android implementation — this module only adds the desktop edges:
 * fetching the collection JSON through the platform transport and installing the
 * entries through the registry.
 *
 * Rule 4, stated where the install happens: a collection can NEVER grant
 * `stream` consent. A collection is untrusted input from a stranger, so it
 * cannot consent on the reader's behalf; every addon that requests `stream`
 * asks for permission individually, through `AddonConsentService.grant`, at the
 * moment the reader allows that addon. Do not "simplify" the loop below by
 * granting consent for the whole set.
 */
import {
  AddonFetchError,
  AddonFetchErrorCode,
  assertHttpsInstallUrl,
} from '@nexo/manifest-validator';
import {
  MAX_COLLECTION_ENTRIES,
  parseCollection,
  serializeCollection,
  type AddonManifestV3,
  type CollectionEntry,
  type ParsedCollection,
} from '@nexo/addon-protocol';

import type { AddonTransport } from './AddonRegistry';

export { MAX_COLLECTION_ENTRIES, parseCollection, serializeCollection };
export type { CollectionEntry, ParsedCollection };

/** The registry surface a collection install needs (satisfied by `AddonRegistry`). */
export interface CollectionInstallRegistry {
  installManifest(url: string, manifest: AddonManifestV3): Promise<unknown>;
  listInstalled(): Promise<{ id: string; url: string }[]>;
}

export interface CollectionInstallResult {
  installed: number;
  failed: number;
}

/** `stream` is the one resource that needs per-addon consent. */
export function requestsStream(manifest: AddonManifestV3): boolean {
  return manifest.resources.some((resource) => resource.name === 'stream');
}

function decodeBody(body: Uint8Array): string {
  try {
    return new TextDecoder('utf-8', { fatal: true }).decode(body);
  } catch {
    throw new AddonFetchError(
      AddonFetchErrorCode.INVALID_MANIFEST,
      'collection is not valid UTF-8',
    );
  }
}

/**
 * Fetch + parse a collection. HTTPS is enforced before any I/O, so a link can
 * never make the app fetch over a scheme the collection chose.
 */
export async function fetchCollection(
  transport: AddonTransport,
  url: string,
): Promise<ParsedCollection> {
  assertHttpsInstallUrl(url);
  const fetched = await transport(url);
  if (fetched.status < 200 || fetched.status >= 300) {
    throw new AddonFetchError(
      AddonFetchErrorCode.NETWORK,
      `collection fetch status ${fetched.status}`,
    );
  }
  return parseCollection(decodeBody(fetched.body));
}

/**
 * Install the set, one addon at a time: a single failure is counted and the rest
 * still install. No consent is recorded — see the module comment.
 */
export async function installCollection(
  registry: CollectionInstallRegistry,
  entries: readonly CollectionEntry[],
): Promise<CollectionInstallResult> {
  let installed = 0;
  let failed = 0;
  for (const entry of entries) {
    try {
      await registry.installManifest(entry.transportUrl, entry.manifest);
      installed += 1;
    } catch {
      failed += 1;
    }
  }
  return { installed, failed };
}
