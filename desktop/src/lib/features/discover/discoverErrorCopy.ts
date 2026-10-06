/**
 * Catalog failure → copy key, shared by the inline rail error and the
 * screen-level search/scope error paths so the states never drift: offline
 * (connectivity), slow (the source timed out), rate-limited (catalog
 * throttling) and upstream.
 */
import type { CatalogErrorCode } from '$lib/shared/services/catalog';
import type { MessageKey } from '$lib/shared/i18n/messages.en';
import { isOfflineCatalogCode } from './DiscoverRailsDomainState.svelte';

/** Connectivity → offline, timeout → slow, 429 → rate-limited, else upstream. */
export function discoverErrorKey(code: CatalogErrorCode | null | undefined): MessageKey {
  if (code != null && isOfflineCatalogCode(code)) return 'discover.offline';
  if (code === 'UPSTREAM_TIMEOUT') return 'discover.errorSlow';
  if (code === 'RATE_LIMITED') return 'discover.rateLimited';
  return 'discover.errorUpstream';
}
