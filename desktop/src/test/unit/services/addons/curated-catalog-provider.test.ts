import { describe, expect, it } from 'vitest';
import { validateManifest } from '@nexo/manifest-validator';
import { CuratedCatalogProvider } from '$lib/shared/services/addons/CuratedCatalogProvider';
import curatedJson from '$lib/shared/services/addons/curated.json';
import { CompositeCatalogProvider } from '$lib/shared/services/catalog/CompositeCatalogProvider';
import {
  BUILTIN_GUTENDEX,
  BUILTIN_OPENLIBRARY,
} from '$lib/shared/services/catalog/CatalogProvider';
import type { CatalogProvider, PagedResult } from '$lib/shared/services/catalog/CatalogProvider';
import { DETAIL_TTL_S, PAGE_TTL_S } from '$lib/shared/services/catalog/DiscoverCache';
import {
  DEBOUNCE_MS,
  DEFAULT_PAGE_SIZE,
  MAX_PAGE_SIZE,
  MIN_PAGE_SIZE,
  OL_MIN_GAP_MS,
} from '$lib/shared/services/catalog/policy';

const CURATED_IDS = [
  'gutendex',
  'openlibrary',
  'standard-ebooks',
  'librivox',
  'wikisource',
  'faded-page',
];

const CURATED_NAMES = [
  'Gutendex',
  'Open Library',
  'Standard Ebooks',
  'LibriVox',
  'Wikisource',
  'Faded Page',
];

function entryBytes(entry: unknown): Uint8Array {
  return new TextEncoder().encode(JSON.stringify(entry));
}

describe('curated bundle', () => {
  it('contains exactly the six first-party manifests in order', () => {
    const entries = (curatedJson as { addons: unknown[] }).addons;
    expect(entries).toHaveLength(6);
    expect(entries.map((e) => (e as { id: string }).id)).toEqual(CURATED_IDS);
    expect(entries.map((e) => (e as { name: string }).name)).toEqual(CURATED_NAMES);
  });

  it('every entry passes the shared manifest validation', () => {
    const entries = (curatedJson as { addons: unknown[] }).addons;
    for (const entry of entries) {
      expect(() => validateManifest(entryBytes(entry), 'application/json')).not.toThrow();
    }
  });
});

describe('CuratedCatalogProvider', () => {
  it('listSources() returns six curated sources derived from manifest ids', () => {
    const provider = new CuratedCatalogProvider();
    expect(provider.listSources()).toEqual(
      CURATED_IDS.map((id, i) => ({
        sourceId: `builtin:${id}`,
        name: CURATED_NAMES[i],
        kind: 'curated' as const,
      })),
    );
  });

  it('search is browse-only: empty page, no I/O, no throw', async () => {
    const provider = new CuratedCatalogProvider();
    const page = await provider.search('pride', 1);
    expect(page).toEqual({ results: [], nexoPage: null, totalCount: 0 });
  });

  it('getDetails fails stable not-routable (NOT_FOUND, no crash)', async () => {
    const provider = new CuratedCatalogProvider();
    const err = await provider.getDetails('standardebooks:some-book').catch((e: unknown) => e);
    expect((err as { code: string }).code).toBe('NOT_FOUND');
  });
});

describe('composite integration', () => {
  class EmptyProvider implements CatalogProvider {
    async search(): Promise<PagedResult> {
      throw new Error('not expected');
    }
    async getDetails(id: string): Promise<never> {
      throw new Error(id);
    }
    resolveDownloadUrl(): string {
      return '';
    }
    listSources() {
      return [
        { sourceId: BUILTIN_GUTENDEX, name: 'Gutendex', kind: 'builtin' as const },
        { sourceId: BUILTIN_OPENLIBRARY, name: 'Open Library', kind: 'builtin' as const },
      ];
    }

    /** Test fake: no featured capability (fail-closed). */
    async featured(): Promise<PagedResult> {
      return { results: [], nexoPage: null, totalCount: 0 };
    }

    supportsFeatured(): boolean {
      return false;
    }

    async searchSource(): Promise<PagedResult> {
      return { results: [], nexoPage: null, totalCount: 0 };
    }
  }

  it('curated sources never shadow the built-ins (dedupe keeps first/builtin kind)', () => {
    const provider = new CompositeCatalogProvider([
      new EmptyProvider(),
      new CuratedCatalogProvider(),
    ]);
    const sources = provider.listSources();
    expect(sources[0]).toEqual({
      sourceId: 'builtin:gutendex',
      name: 'Gutendex',
      kind: 'builtin',
    });
    expect(sources.filter((s) => s.kind === 'curated')).toHaveLength(4);
  });

  it('courtesy policy constants are untouched by the curated layer', () => {
    expect(DEBOUNCE_MS).toBe(350);
    expect(OL_MIN_GAP_MS).toBe(1_000);
    expect(DEFAULT_PAGE_SIZE).toBe(24);
    expect(MIN_PAGE_SIZE).toBe(20);
    expect(MAX_PAGE_SIZE).toBe(32);
    expect(PAGE_TTL_S).toBe(86_400);
    expect(DETAIL_TTL_S).toBe(604_800);
  });
});
