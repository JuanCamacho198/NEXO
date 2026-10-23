import { describe, expect, it } from 'vitest';
import {
  candidateIds,
  mergeMeta,
  shouldConsultLibraryMeta,
  type LibraryMetaBook,
  type LibraryMetaSource,
} from '../src/library';
import type { AddonManifestV3 } from '../src/manifest';

function manifest(overrides: Partial<AddonManifestV3> = {}): AddonManifestV3 {
  return {
    id: 'org.example.tmdb',
    version: '3.0.0',
    name: 'Metadata authority',
    types: ['book'],
    catalogs: [],
    resources: [{ name: 'catalog' }, { name: 'meta', types: ['book'] }],
    ...overrides,
  };
}

describe('candidateIds', () => {
  it('a namespaced source item id is the only candidate', () => {
    expect(candidateIds({ sourceItemId: 'gutenberg:1342', isbn13: '9780141439518' })).toEqual([
      'gutenberg:1342',
    ]);
    expect(candidateIds({ sourceItemId: 'isbn:9780141439518' })).toEqual(['isbn:9780141439518']);
    expect(candidateIds({ sourceItemId: 'standardebooks:pride-and-prejudice' })).toEqual([
      'standardebooks:pride-and-prejudice',
    ]);
  });

  it('an id under an unknown namespace falls back to the isbn', () => {
    expect(candidateIds({ sourceItemId: 'tmdb:603', isbn13: '9780141439518' })).toEqual([
      'isbn:9780141439518',
    ]);
  });

  it('an isbn alone becomes an isbn: candidate, 13 or 10 digits', () => {
    expect(candidateIds({ isbn13: '9780141439518' })).toEqual(['isbn:9780141439518']);
    expect(candidateIds({ isbn13: '0141439519' })).toEqual(['isbn:0141439519']);
  });

  it('blank and non-digit identities yield no candidate', () => {
    expect(candidateIds({})).toEqual([]);
    expect(candidateIds({ sourceItemId: '   ', isbn13: '  ' })).toEqual([]);
    expect(candidateIds({ sourceItemId: null, isbn13: null })).toEqual([]);
    expect(candidateIds({ isbn13: '978-0-14-143951-8' })).toEqual([]);
    expect(candidateIds({ isbn13: '12345' })).toEqual([]);
    expect(candidateIds({ isbn13: '97801414395189' })).toEqual([]);
  });
});

describe('shouldConsultLibraryMeta', () => {
  it('a meta resource whose prefixes match a candidate is consulted', () => {
    const declared = manifest({
      resources: [{ name: 'meta', types: ['book'], idPrefixes: ['isbn:', 'tmdb:'] }],
    });
    expect(shouldConsultLibraryMeta(declared, ['isbn:9780141439518'])).toBe(true);
  });

  it('the top-level prefixes apply when the resource declares none', () => {
    const declared = manifest({
      resources: [{ name: 'catalog' }, { name: 'meta' }],
      idPrefixes: ['gutenberg:'],
    });
    expect(shouldConsultLibraryMeta(declared, ['gutenberg:1342'])).toBe(true);
    expect(shouldConsultLibraryMeta(declared, ['isbn:9780141439518'])).toBe(false);
  });

  it('an addon declaring meta with no prefixes is never consulted', () => {
    expect(shouldConsultLibraryMeta(manifest(), ['isbn:9780141439518'])).toBe(false);
    expect(
      shouldConsultLibraryMeta(
        manifest({ resources: [{ name: 'meta', types: ['book'], idPrefixes: [] }] }),
        ['isbn:9780141439518'],
      ),
    ).toBe(false);
    expect(
      shouldConsultLibraryMeta(
        manifest({ resources: [{ name: 'catalog' }, { name: 'meta' }], idPrefixes: [] }),
        ['gutenberg:1342'],
      ),
    ).toBe(false);
  });

  it('the per-resource prefixes win over the top-level list', () => {
    const declared = manifest({
      resources: [{ name: 'meta', idPrefixes: ['tmdb:'] }],
      idPrefixes: ['gutenberg:'],
    });
    expect(shouldConsultLibraryMeta(declared, ['gutenberg:1342'])).toBe(false);
    expect(shouldConsultLibraryMeta(declared, ['tmdb:603'])).toBe(true);
  });

  it('a meta resource that does not serve books is not consulted', () => {
    const declared = manifest({
      resources: [{ name: 'meta', types: ['movie'], idPrefixes: ['isbn:'] }],
    });
    expect(shouldConsultLibraryMeta(declared, ['isbn:9780141439518'])).toBe(false);
  });

  it('no meta resource, no candidates, or a disabled addon is not consulted', () => {
    const prefixes = { idPrefixes: ['isbn:'] };
    expect(
      shouldConsultLibraryMeta(manifest({ resources: [{ name: 'catalog' }], ...prefixes }), [
        'isbn:1',
      ]),
    ).toBe(false);
    expect(
      shouldConsultLibraryMeta(
        manifest({ resources: [{ name: 'meta', idPrefixes: ['isbn:'] }] }),
        [],
      ),
    ).toBe(false);
    expect(
      shouldConsultLibraryMeta(
        manifest({ resources: [{ name: 'meta', idPrefixes: ['isbn:'] }] }),
        ['isbn:9780141439518'],
        { enabled: false },
      ),
    ).toBe(false);
  });
});

function book(overrides: Partial<LibraryMetaBook & Record<string, unknown>> = {}) {
  return {
    id: 'book-1',
    title: 'Pride and Prejudice',
    author: 'Jane Austen',
    filePath: 'C:/books/pride.epub',
    format: 'epub',
    totalPages: 432,
    currentPage: 12,
    progressPercentage: 2.8,
    readingStatus: 'reading' as const,
    userRating: 4,
    sourceAddonId: 'org.example.gutendex',
    sourceItemId: 'gutenberg:1342',
    isbn13: '9780141439518',
    coverPath: null as string | null,
    description: null as string | null,
    publisher: null as string | null,
    publishedDate: null as string | null,
    language: null as string | null,
    genre: null as string | null,
    tags: null as string[] | null,
    ...overrides,
  };
}

const RICH_META: LibraryMetaSource = {
  description: 'A novel of manners.',
  publisher: 'Penguin Classics',
  releaseInfo: '1813',
  languages: ['en'],
  subjects: ['Fiction', 'Romance'],
  poster: 'https://covers.example.org/pride.jpg',
};

describe('mergeMeta', () => {
  it('fills the blank descriptive fields from the meta', () => {
    const { book: merged, changed } = mergeMeta(book(), RICH_META);

    expect(merged.description).toBe('A novel of manners.');
    expect(merged.publisher).toBe('Penguin Classics');
    expect(merged.publishedDate).toBe('1813');
    expect(merged.language).toBe('en');
    expect(merged.genre).toBe('Fiction');
    expect(merged.tags).toEqual(['Fiction', 'Romance']);
    expect(changed).toEqual({
      description: 'A novel of manners.',
      publisher: 'Penguin Classics',
      publishedDate: '1813',
      language: 'en',
      genre: 'Fiction',
      tags: ['Fiction', 'Romance'],
      coverUrl: 'https://covers.example.org/pride.jpg',
    });
  });

  it('a book that already has a description and a cover is not overwritten', () => {
    const existing = book({
      description: 'A description the reader already has.',
      coverPath: 'C:/covers/book-1.png',
    });

    const { book: merged, changed } = mergeMeta(existing, RICH_META);

    expect(merged.description).toBe('A description the reader already has.');
    expect(merged.coverPath).toBe('C:/covers/book-1.png');
    expect(changed.description).toBeUndefined();
    expect(changed.coverUrl).toBeUndefined();
    // Everything else was blank, so the rest still fills.
    expect(merged.language).toBe('en');
  });

  it('never touches identity, reading state or provenance', () => {
    const before = book({ description: null, coverPath: null });
    const { book: merged } = mergeMeta(before, RICH_META);

    expect(merged.id).toBe(before.id);
    expect(merged.title).toBe(before.title);
    expect(merged.author).toBe(before.author);
    expect(merged.filePath).toBe(before.filePath);
    expect(merged.format).toBe(before.format);
    expect(merged.totalPages).toBe(before.totalPages);
    expect(merged.currentPage).toBe(before.currentPage);
    expect(merged.progressPercentage).toBe(before.progressPercentage);
    expect(merged.readingStatus).toBe(before.readingStatus);
    expect(merged.userRating).toBe(before.userRating);
    expect(merged.sourceAddonId).toBe(before.sourceAddonId);
    expect(merged.sourceItemId).toBe(before.sourceItemId);
    expect(merged.isbn13).toBe(before.isbn13);
  });

  it('whitespace-only values count as blank', () => {
    const { book: merged } = mergeMeta(
      book({ description: '   ', language: '', genre: '\t', tags: ['', ' '] }),
      RICH_META,
    );

    expect(merged.description).toBe('A novel of manners.');
    expect(merged.language).toBe('en');
    expect(merged.genre).toBe('Fiction');
    expect(merged.tags).toEqual(['Fiction', 'Romance']);
  });

  it('an existing tag list and genre survive', () => {
    const { book: merged, changed } = mergeMeta(
      book({ genre: 'Classics', tags: ['owned'] }),
      RICH_META,
    );

    expect(merged.genre).toBe('Classics');
    expect(merged.tags).toEqual(['owned']);
    expect(changed.genre).toBeUndefined();
    expect(changed.tags).toBeUndefined();
  });

  it('a blank meta never erases an existing value and never fills a blank one', () => {
    const { book: merged, changed } = mergeMeta(book({ description: null }), {
      description: '   ',
      languages: [],
      subjects: [],
      poster: '   ',
    });

    expect(merged.description).toBeNull();
    expect(merged.language).toBeNull();
    expect(merged.tags).toBeNull();
    expect(changed).toEqual({});
  });

  it('the cover is signalled only for an https poster the caller can persist', () => {
    const { changed } = mergeMeta(book(), { poster: 'http://covers.example.org/p.jpg' });
    expect(changed.coverUrl).toBeUndefined();

    const insecureScheme = mergeMeta(book(), { poster: 'magnet:?xt=urn:btih:abc' });
    expect(insecureScheme.changed.coverUrl).toBeUndefined();

    // A pure function cannot download: the local path stays whatever it was.
    const { book: merged } = mergeMeta(book(), RICH_META);
    expect(merged.coverPath).toBeNull();
  });

  it('returns a copy, leaving the caller row untouched', () => {
    const before = book();
    mergeMeta(before, RICH_META);
    expect(before.description).toBeNull();
    expect(before.language).toBeNull();
  });
});
