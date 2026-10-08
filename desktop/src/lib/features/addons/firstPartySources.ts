/**
 * firstPartySources — read-only first-party model for the Addons screen
 * (slice 7). Exactly the 2 built-ins (`builtin:gutendex`,
 * `builtin:openlibrary`).
 *
 * No install/uninstall affordance and never a registry row: building this
 * model performs zero registry writes and zero I/O.
 */
import {
  BUILTIN_GUTENDEX,
  BUILTIN_OPENLIBRARY,
} from '$lib/shared/services/catalog/CatalogProvider';

export interface FirstPartySource {
  sourceId: string;
  name: string;
  kind: 'builtin';
}

/** The 2 built-in sources (names mirror BuiltInCatalogProviders listSources). */
export const FIRST_PARTY_BUILTINS: readonly FirstPartySource[] = [
  { sourceId: BUILTIN_GUTENDEX, name: 'Gutendex', kind: 'builtin' },
  { sourceId: BUILTIN_OPENLIBRARY, name: 'Open Library', kind: 'builtin' },
];
