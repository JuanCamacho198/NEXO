/**
 * CuratedCatalogProvider — read-only, manifest-shaped first-party bundle.
 * The bundled curated.json lists exactly six public-domain sources; every
 * entry passes the shared manifest validation at construction (a bad bundle
 * fails fast, guarded by tests). Browse-only: it never serves search results
 * (search returns an empty page without I/O, keeping the composite's
 * zero-addon parity byte-for-byte) and getDetails fails stable NOT_FOUND
 * ("not routable"). Never a registry row.
 */
import { catalogError } from '../catalog/errors';
import type {
  CatalogBook,
  CatalogFeaturedSort,
  CatalogProvider,
  CatalogSource,
  CatalogSourceInfo,
  PagedResult,
} from '../catalog/CatalogProvider';
import { parseCatalogSource } from '../catalog/CatalogProvider';
import { resolveDownloadUrl } from '../catalog/mappers';
import { validateManifest, type AddonManifest } from '@nexo/manifest-validator';
import curatedJson from './curated.json';

interface CuratedBundle {
  addons: unknown[];
}

function loadCuratedManifests(): AddonManifest[] {
  const bundle = curatedJson as CuratedBundle;
  if (!Array.isArray(bundle.addons)) {
    throw new Error('curated bundle must be {addons: [...]}');
  }
  const encoder = new TextEncoder();
  return bundle.addons.map((entry) =>
    validateManifest(encoder.encode(JSON.stringify(entry)), 'application/json'),
  );
}

const EMPTY_PAGE: PagedResult = { results: [], nexoPage: null, totalCount: 0 };

export class CuratedCatalogProvider implements CatalogProvider {
  private readonly sources: CatalogSourceInfo[];

  constructor() {
    this.sources = loadCuratedManifests().map((manifest) => ({
      sourceId: parseCatalogSource(`builtin:${manifest.id}`),
      name: manifest.name,
      kind: 'curated' as const,
    }));
  }

  listSources(): CatalogSourceInfo[] {
    return this.sources;
  }

  /** Browse-only: contributes nothing to composite search, never I/O. */
  async search(_query: string, _page: number): Promise<PagedResult> {
    return EMPTY_PAGE;
  }

  /** Curated sources are not routable; stable NOT_FOUND, no I/O. */
  async getDetails(id: string): Promise<CatalogBook> {
    throw catalogError('NOT_FOUND', `curated sources are not routable: ${id}`);
  }

  /** Browse-only bundle: no featured rails, the rail auto-hides (fail-closed). */
  async featured(_sort: CatalogFeaturedSort, limit: number): Promise<PagedResult> {
    if (!Number.isInteger(limit) || limit < 1) {
      throw catalogError('INVALID_PAGE', `limit must be >= 1, got ${limit}`);
    }
    return EMPTY_PAGE;
  }

  supportsFeatured(_sort: CatalogFeaturedSort): boolean {
    return false;
  }

  /** Curated sources serve no per-source search; fail closed, no I/O. */
  async searchSource(
    _sourceId: CatalogSource,
    _query: string,
    _page: number,
  ): Promise<PagedResult> {
    return EMPTY_PAGE;
  }

  resolveDownloadUrl(formats: Record<string, string>, preferEpub: boolean): string {
    return resolveDownloadUrl(formats, preferEpub);
  }
}
