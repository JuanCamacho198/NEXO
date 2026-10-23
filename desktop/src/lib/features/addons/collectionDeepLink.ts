/**
 * Collection deep-link routing (task 15). Pure module: parse + route, no store
 * or Tauri imports — mirrors `installDeepLink.ts` next to it.
 *
 * `nexo://collection?url=<https-url>` (legacy `nextpage*://` still resolves) is
 * a different host from the single-addon `install` link, so the two routers
 * never claim each other's URLs and the existing install parser is untouched.
 */
import { assertHttpsInstallUrl } from '@nexo/manifest-validator';

import { logger } from '$lib/shared/logger/Logger';

const COLLECTION_SCHEMES = new Set(['nexo', 'nextpage', 'nextpage-desktop']);
const COLLECTION_HOST = 'collection';

export interface CollectionDeepLinkDeps {
  /** Receives the https collection-JSON URL to preview + confirm. */
  onCollectionUrl: (collectionUrl: string) => void;
}

let onCollectionUrl: CollectionDeepLinkDeps['onCollectionUrl'] | null = null;

/** Register the collection-flow handler (production: useCollectionDeepLink store). */
export function setCollectionDeepLinkHandler(
  next: CollectionDeepLinkDeps['onCollectionUrl'],
): void {
  onCollectionUrl = next;
}

/** Test seam: clear the handler between tests. */
export function resetCollectionDeepLinkHandler(): void {
  onCollectionUrl = null;
}

/**
 * Parse a raw deep-link URL into the collection JSON URL. Returns null for
 * anything else, including a non-https target: a collection decides where a
 * whole set of addons comes from, so its scheme is checked at the boundary
 * (the store checks again before fetching). Never throws.
 */
export function parseCollectionDeepLink(raw: string): { collectionUrl: string } | null {
  let parsed: URL;
  try {
    parsed = new URL(raw);
  } catch {
    return null;
  }
  if (!COLLECTION_SCHEMES.has(parsed.protocol.replace(':', ''))) return null;
  if (parsed.host !== COLLECTION_HOST) return null;
  const collectionUrl = parsed.searchParams.get('url');
  if (!collectionUrl) return null;
  try {
    assertHttpsInstallUrl(collectionUrl);
  } catch {
    return null;
  }
  return { collectionUrl };
}

/**
 * Route a batch of deep-link URLs (cold start + warm single-instance) to the
 * registered collection handler. Runs alongside `handleDeepLinkUrls`, which
 * treats collection URLs as non-install and ignores them.
 */
export async function handleCollectionDeepLinkUrls(urls: string[]): Promise<void> {
  for (const raw of urls) {
    const parsed = parseCollectionDeepLink(raw);
    if (!parsed) {
      logger.debug('deep link ignored (not a collection):', { raw }, 'app_shell');
      continue;
    }
    if (!onCollectionUrl) {
      logger.warn(
        'collection deep link received before handler registration',
        undefined,
        'app_shell',
      );
      continue;
    }
    onCollectionUrl(parsed.collectionUrl);
  }
}
