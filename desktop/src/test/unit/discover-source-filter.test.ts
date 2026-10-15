import { describe, expect, it } from 'vitest';
import { filterBooksByChip } from '$lib/features/discover/discoverChips';
import {
  ALL_SOURCES,
  filterBooksBySource,
  matchesSource,
  reconcileSourceFilter,
} from '$lib/features/discover/discoverSourceFilter';
import type { CatalogBook, CatalogSourceInfo } from '$lib/shared/services/catalog';

function book(id: string, provider: string, subjects: string[] = ['Fiction']): CatalogBook {
  return {
    id,
    provider,
    title: `Title ${id}`,
    authors: ['Author'],
    coverUrl: null,
    languages: ['en'],
    subjects,
    downloadUrl: null,
  };
}

const GUTENDEX: CatalogSourceInfo = {
  sourceId: 'builtin:gutendex' as CatalogSourceInfo['sourceId'],
  name: 'Gutenberg',
  kind: 'builtin',
};
const OPENLIBRARY: CatalogSourceInfo = {
  sourceId: 'builtin:openlibrary' as CatalogSourceInfo['sourceId'],
  name: 'Open Library',
  kind: 'builtin',
};

describe('discover source filter — predicate', () => {
  it('matches every book when the selection is "all"', () => {
    expect(matchesSource(book('a', 'builtin:gutendex'), ALL_SOURCES)).toBe(true);
    expect(matchesSource(book('b', 'addon:0123456789abcdef'), ALL_SOURCES)).toBe(true);
  });

  it('matches only books of the selected source', () => {
    const book1 = book('a', 'builtin:gutendex');
    const book2 = book('b', 'builtin:openlibrary');
    expect(matchesSource(book1, 'builtin:gutendex')).toBe(true);
    expect(matchesSource(book2, 'builtin:gutendex')).toBe(false);
  });

  it('returns the slice untouched for "all" and narrows otherwise', () => {
    const books = [book('a', 'builtin:gutendex'), book('b', 'builtin:openlibrary')];
    expect(filterBooksBySource(books, ALL_SOURCES)).toBe(books);
    expect(filterBooksBySource(books, 'builtin:openlibrary').map((b) => b.id)).toEqual(['b']);
    expect(filterBooksBySource(books, 'builtin:gutendex').map((b) => b.id)).toEqual(['a']);
  });

  it('returns an empty slice for a source no loaded book belongs to', () => {
    expect(filterBooksBySource([book('a', 'builtin:gutendex')], 'addon:0123456789abcdef')).toEqual(
      [],
    );
  });
});

describe('discover source filter — rail narrowing', () => {
  const narrow = (
    railKind: 'Loaded' | 'Hidden' | 'Loading',
    books: CatalogBook[],
    selected: string | null,
  ): { kind: string; books: CatalogBook[] } => {
    if (railKind !== 'Loaded') return { kind: railKind, books: [] };
    const narrowed = filterBooksBySource(books, selected);
    if (narrowed.length === 0) return { kind: 'Hidden', books: [] };
    return { kind: 'Loaded', books: narrowed };
  };

  it('hides a rail whose loaded books all belong to other sources', () => {
    const rail = [book('a', 'builtin:openlibrary')];
    expect(narrow('Loaded', rail, 'builtin:gutendex')).toEqual({ kind: 'Hidden', books: [] });
  });

  it('keeps a rail that still has a book of the selected source', () => {
    const rail = [book('a', 'builtin:gutendex'), book('b', 'builtin:openlibrary')];
    expect(narrow('Loaded', rail, 'builtin:gutendex')).toEqual({
      kind: 'Loaded',
      books: [book('a', 'builtin:gutendex')],
    });
  });

  it('leaves Hidden and Loading rails untouched', () => {
    expect(narrow('Hidden', [], 'builtin:gutendex')).toEqual({ kind: 'Hidden', books: [] });
    expect(narrow('Loading', [], 'builtin:gutendex')).toEqual({ kind: 'Loading', books: [] });
  });

  it('"all" leaves a loaded rail exactly as published', () => {
    const rail = [book('a', 'builtin:gutendex')];
    const result = narrow('Loaded', rail, ALL_SOURCES);
    expect(result.books).toBe(rail);
  });

  it('composes with the chip filter: source narrows the chip-filtered set', () => {
    const rail = [
      book('a', 'builtin:gutendex', ['Mystery']),
      book('b', 'builtin:openlibrary', ['Mystery']),
      book('c', 'builtin:gutendex', ['Romance']),
    ];
    const chipFiltered = filterBooksByChip(rail, 'fiction');
    // 'fiction' matches nothing here, so the chip filter alone would already
    // yield zero and hide the rail — the source filter is a second dimension.
    expect(chipFiltered).toEqual([]);
    expect(filterBooksBySource(chipFiltered, 'builtin:gutendex')).toEqual([]);
  });
});

describe('discover source filter — degrade honestly', () => {
  it('keeps "all" as "all"', () => {
    expect(reconcileSourceFilter(ALL_SOURCES, [GUTENDEX])).toBe(ALL_SOURCES);
    expect(reconcileSourceFilter(ALL_SOURCES, [])).toBe(ALL_SOURCES);
  });

  it('keeps a selection still present in the live source list', () => {
    expect(reconcileSourceFilter('builtin:gutendex', [GUTENDEX, OPENLIBRARY])).toBe(
      'builtin:gutendex',
    );
  });

  it('falls back to "all" when the selected source disappeared from the build', () => {
    expect(reconcileSourceFilter('builtin:googlebooks', [GUTENDEX, OPENLIBRARY])).toBe(ALL_SOURCES);
    expect(reconcileSourceFilter('addon:0123456789abcdef', [GUTENDEX])).toBe(ALL_SOURCES);
  });
});
