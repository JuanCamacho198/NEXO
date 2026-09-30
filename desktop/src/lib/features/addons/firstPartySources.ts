/**
 * firstPartySources — read-only first-party model for the Addons screen
 * (slice 7). Exactly the 2 built-ins (`builtin:gutendex`,
 * `builtin:openlibrary`) plus the 6 curated entries from `curated.json`.
 *
 * No install/uninstall affordance and never a registry row: building this
 * model performs zero registry writes and zero I/O (the curated bundle is
 * validated in memory, failing fast like `CuratedCatalogProvider`).
 */
import {
  BUILTIN_GUTENDEX,
  BUILTIN_OPENLIBRARY,
} from '$lib/shared/services/catalog/CatalogProvider';
import { validateManifest } from '@nexo/manifest-validator';
import curatedJson from '$lib/shared/services/addons/curated.json';

export interface FirstPartySource {
  sourceId: string;
  name: string;
  kind: 'builtin' | 'curated';
}

/** The 2 built-in sources (names mirror BuiltInCatalogProviders listSources). */
export const FIRST_PARTY_BUILTINS: readonly FirstPartySource[] = [
  { sourceId: BUILTIN_GUTENDEX, name: 'Gutendex', kind: 'builtin' },
  { sourceId: BUILTIN_OPENLIBRARY, name: 'Open Library', kind: 'builtin' },
];

function loadCurated(): FirstPartySource[] {
  const bundle = curatedJson as { addons?: unknown[] };
  if (!Array.isArray(bundle.addons)) {
    throw new Error('curated bundle must be {addons: [...]}');
  }
  const encoder = new TextEncoder();
  return bundle.addons.map((entry) => {
    const manifest = validateManifest(encoder.encode(JSON.stringify(entry)), 'application/json');
    return {
      sourceId: `builtin:${manifest.id}`,
      name: manifest.name,
      kind: 'curated' as const,
    };
  });
}

/** The 6 curated entries from `curated.json` (validated at load, fail fast). */
export const FIRST_PARTY_CURATED: readonly FirstPartySource[] = loadCurated();
