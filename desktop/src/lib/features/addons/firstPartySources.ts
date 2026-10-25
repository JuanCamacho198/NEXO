/**
 * firstPartySources — read-only first-party model for the Addons screen
 * (slice 7). Exactly the 3 built-ins that have a real provider:
 * `builtin:gutendex`, `builtin:openlibrary`, `builtin:standard-ebooks`.
 * Google Books is deliberately absent: it is a key-gated enrichment source,
 * not a catalog the user can browse here.
 *
 * No install/uninstall affordance and never a registry row: building this
 * model performs zero registry writes and zero I/O.
 */
import {
  BUILTIN_GUTENDEX,
  BUILTIN_OPENLIBRARY,
  BUILTIN_STANDARD_EBOOKS,
} from '$lib/shared/services/catalog/CatalogProvider';

export interface FirstPartySource {
  sourceId: string;
  name: string;
  kind: 'builtin';
}

/** The 3 built-in sources (names mirror BuiltInCatalogProviders listSources). */
export const FIRST_PARTY_BUILTINS: readonly FirstPartySource[] = [
  { sourceId: BUILTIN_GUTENDEX, name: 'Gutendex', kind: 'builtin' },
  { sourceId: BUILTIN_OPENLIBRARY, name: 'Open Library', kind: 'builtin' },
  { sourceId: BUILTIN_STANDARD_EBOOKS, name: 'Standard Ebooks', kind: 'builtin' },
];
