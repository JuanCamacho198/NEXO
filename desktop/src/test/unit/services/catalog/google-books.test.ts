import { describe, expect, it, vi } from 'vitest';
import {
  BUILTIN_GOOGLEBOOKS,
  BUILTIN_GUTENDEX,
  BUILTIN_OPENLIBRARY,
  parseCatalogSource,
} from '$lib/shared/services/catalog/CatalogProvider';
import type { CatalogSource } from '$lib/shared/services/catalog/CatalogProvider';
import { CompositeCatalogProvider } from '$lib/shared/services/catalog/CompositeCatalogProvider';
import { defaultCatalogProviders } from '$lib/shared/services/catalog/CompositeCatalogProvider';
import {
  GoogleBooksCatalogProvider,
  googleBooksKeyFromEnv,
  googleBooksProviderOrNull,
} from '$lib/shared/services/catalog/BuiltInCatalogProviders';
import {
  GOOGLE_BOOKS_BASE_URL,
  GOOGLE_BOOKS_VOLUME_FIELDS,
  GoogleBooksDataSource,
  buildGoogleBooksQuery,
} from '$lib/shared/services/catalog/GoogleBooksDataSource';
import { CatalogError } from '$lib/shared/services/catalog/errors';
import { mapGoogleBooksVolume } from '$lib/shared/services/catalog/mappers';
import type {
  GoogleBooksVolumeItem,
  GoogleBooksSearchResponse,
} from '$lib/shared/services/catalog/mappers';

const KEY = 'test-key-123';

function stubFetch(handler: (url: string) => { status: number; body: unknown }): typeof fetch {
  return (async (input: unknown) => {
    const { status, body } = handler(String(input));
    return new Response(JSON.stringify(body), { status });
  }) as typeof fetch;
}

function recordingFetch(payload: unknown): { fetchFn: typeof fetch; urls: string[] } {
  const urls: string[] = [];
  const fetchFn = stubFetch((url) => {
    urls.push(url);
    return { status: 200, body: payload };
  });
  return { fetchFn, urls };
}

function noIoFetch(): typeof fetch {
  return (async () => {
    throw new Error('unexpected network I/O');
  }) as typeof fetch;
}

function volumePayload(): GoogleBooksSearchResponse {
  return {
    totalItems: 2,
    items: [
      {
        id: 'abc123',
        volumeInfo: {
          title: 'Pride and Prejudice',
          authors: ['Jane Austen'],
          description: 'A classic.',
          language: 'en',
          categories: ['Fiction', 'Classics'],
          imageLinks: {
            thumbnail:
              'http://books.google.com/books/content?id=abc123&printsec=frontcover&img=1&zoom=1',
          },
          industryIdentifiers: [
            { type: 'ISBN_10', identifier: '0141439513' },
            { type: 'ISBN_13', identifier: '978-0-14-143951-8' },
          ],
        },
      },
      {
        id: 'no-cover-1',
        volumeInfo: { title: 'Coverless Essay', authors: ['Anon'], language: 'es' },
      },
    ],
  };
}

describe('buildGoogleBooksQuery', () => {
  it('compacts a bare ISBN-10/13 to plain digits (dashes, spaces, X check digit)', () => {
    expect(buildGoogleBooksQuery('9780140449136')).toBe('9780140449136');
    expect(buildGoogleBooksQuery('978-0-14-044913-6')).toBe('9780140449136');
    expect(buildGoogleBooksQuery(' 0141439513 ')).toBe('0141439513');
    expect(buildGoogleBooksQuery('043942089X')).toBe('043942089X');
  });

  it('never emits a colon operator, which the live API answers with zero results', () => {
    const queries = ['9780140449136', '978-0-14-044913-6', '043942089X', 'Jane Austen Pride'];
    for (const query of queries) {
      expect(buildGoogleBooksQuery(query)).not.toContain(':');
    }
  });

  it('passes title/author queries through trimmed', () => {
    expect(buildGoogleBooksQuery('  Jane Austen Pride  ')).toBe('Jane Austen Pride');
    expect(buildGoogleBooksQuery('978014044913')).toBe('978014044913');
  });
});

describe('googleBooksKeyFromEnv (fail-closed key gate)', () => {
  it('blank, absent, and non-string keys resolve to an empty key', () => {
    expect(googleBooksKeyFromEnv({})).toBe('');
    expect(googleBooksKeyFromEnv({ VITE_GOOGLE_BOOKS_KEY: '   ' })).toBe('');
    expect(googleBooksKeyFromEnv({ VITE_GOOGLE_BOOKS_KEY: undefined })).toBe('');
    expect(googleBooksKeyFromEnv({ VITE_GOOGLE_BOOKS_KEY: 42 })).toBe('');
  });

  it('trims a configured key (copy-pasted trailing newline still enables the source)', () => {
    expect(googleBooksKeyFromEnv({ VITE_GOOGLE_BOOKS_KEY: '  test-key-123\n' })).toBe(KEY);
  });
});

describe('googleBooksProviderOrNull', () => {
  it('omits the provider entirely on an absent or blank key', () => {
    expect(googleBooksProviderOrNull('')).toBeNull();
    expect(googleBooksProviderOrNull('   ')).toBeNull();
  });

  it('registers a googlebooks source when the key is present', () => {
    const provider = googleBooksProviderOrNull(KEY, noIoFetch());
    expect(provider).not.toBeNull();
    expect(provider?.listSources()).toEqual([
      { sourceId: BUILTIN_GOOGLEBOOKS, name: 'Google Books', kind: 'builtin' },
    ]);
    expect(parseCatalogSource('builtin:googlebooks')).toBe(BUILTIN_GOOGLEBOOKS);
  });

  it('defaults to a metadata-only provider: no featured rail, fail-closed unknown source', async () => {
    const provider = googleBooksProviderOrNull(KEY, noIoFetch())!;
    expect(provider.supportsFeatured('POPULAR')).toBe(false);
    expect(await provider.featured('POPULAR', 6)).toEqual({
      results: [],
      nexoPage: null,
      totalCount: 0,
    });
    expect(await provider.searchSource('builtin:gutendex' as CatalogSource, 'x', 1)).toEqual({
      results: [],
      nexoPage: null,
      totalCount: 0,
    });
  });
});

describe('GoogleBooksDataSource', () => {
  it('maps volumes through an injected transport, normalizing covers to https', async () => {
    const { fetchFn, urls } = recordingFetch(volumePayload());
    const ds = new GoogleBooksDataSource(fetchFn, KEY);
    const { books, totalCount } = await ds.search('pride prejudice', 1);

    expect(totalCount).toBe(2);
    expect(books).toHaveLength(2);
    expect(urls[0]?.startsWith(`${GOOGLE_BOOKS_BASE_URL}/volumes?q=`)).toBe(true);
    expect(urls[0]).toContain('startIndex=0');
    expect(urls[0]).toContain('maxResults=24');
    expect(urls[0]).toContain(`key=${KEY}`);

    const first = books.find((b) => b.id === 'googlebooks:abc123')!;
    expect(first.provider).toBe(BUILTIN_GOOGLEBOOKS);
    expect(first.title).toBe('Pride and Prejudice');
    expect(first.authors).toEqual(['Jane Austen']);
    expect(first.description).toBe('A classic.');
    expect(first.languages).toEqual(['en']);
    expect(first.subjects).toEqual(['Fiction', 'Classics']);
    expect(first.coverUrl?.startsWith('https://')).toBe(true);
    expect(first.coverUrl).not.toContain('http://');
    expect(first.isbn13).toBe('9780141439518');
    expect(first.isbn10).toBe('0141439513');
    expect(first.googleBooksId).toBe('abc123');
    // Metadata/enrichment only: Google Books never becomes a download source.
    expect(first.downloadUrl).toBeNull();
    expect(first.isPublicDomain).toBeNull();

    const coverless = books.find((b) => b.id === 'googlebooks:no-cover-1')!;
    expect(coverless.coverUrl).toBeNull();
    expect(coverless.isbn13).toBeNull();
    expect(coverless.isbn10).toBeNull();
    expect(coverless.downloadUrl).toBeNull();
  });

  it('appends the key only when non-blank (blank key => no key param, no I/O failure)', async () => {
    const blank = recordingFetch(volumePayload());
    await new GoogleBooksDataSource(blank.fetchFn, '').search('pride', 1);
    expect(blank.urls[0]).not.toContain('key=');

    const whitespace = recordingFetch(volumePayload());
    await new GoogleBooksDataSource(whitespace.fetchFn, '   ').search('pride', 1);
    expect(whitespace.urls[0]).not.toContain('key=');
  });

  it('sends a bare ISBN as plain digits, never as a colon operator', async () => {
    const { fetchFn, urls } = recordingFetch(volumePayload());
    await new GoogleBooksDataSource(fetchFn, KEY).search('9780140449136', 1);
    // Regression pin. The previous shape was `q=isbn%3A9780140449136`, which the
    // live API answers with `totalItems: 0`; plain digits answer with volumes.
    expect(urls[0]).toContain('q=9780140449136');
    expect(urls[0]).not.toContain('isbn');
    expect(urls[0]).not.toContain('%3A');
  });

  it('detail request carries the fields whitelist and the key when configured', async () => {
    const { fetchFn, urls } = recordingFetch({
      id: 'abc123',
      volumeInfo: { title: 'Pride and Prejudice' },
    });
    const book = await new GoogleBooksDataSource(fetchFn, KEY).getById('abc123');
    expect(urls[0]).toContain(`/volumes/abc123?fields=${GOOGLE_BOOKS_VOLUME_FIELDS}`);
    expect(urls[0]).toContain(`key=${KEY}`);
    expect(GOOGLE_BOOKS_VOLUME_FIELDS).toContain('industryIdentifiers');
    expect(book.id).toBe('googlebooks:abc123');
  });

  it('rejects NOT_FOUND for an unusable volume payload (no title/id)', async () => {
    const ds = new GoogleBooksDataSource(
      stubFetch(() => ({ status: 200, body: {} })),
      KEY,
    );
    const err = await ds.getById('missing').catch((e: unknown) => e);
    expect(err).toBeInstanceOf(CatalogError);
    expect((err as CatalogError).code).toBe('NOT_FOUND');
  });

  it('never echoes the key into a surfaced error message', async () => {
    const failing = (async () => {
      throw new Error(`network down while calling key=${KEY}`);
    }) as typeof fetch;
    const ds = new GoogleBooksDataSource(failing, KEY);
    const err = await ds.search('pride', 1).catch((e: unknown) => e);
    expect(err).toBeInstanceOf(CatalogError);
    expect((err as Error).message).not.toContain(KEY);
  });

  it('maps upstream rate limits to RATE_LIMITED', async () => {
    const ds = new GoogleBooksDataSource(
      stubFetch(() => ({ status: 429, body: { error: 'quota' } })),
      KEY,
    );
    const err = await ds.search('pride', 1).catch((e: unknown) => e);
    expect((err as CatalogError).code).toBe('RATE_LIMITED');
  });
});

describe('mapGoogleBooksVolume (pure mapper)', () => {
  it('returns null without a usable id or title', () => {
    expect(mapGoogleBooksVolume({})).toBeNull();
    expect(mapGoogleBooksVolume({ id: 'x', volumeInfo: { title: '   ' } })).toBeNull();
    expect(mapGoogleBooksVolume({ id: '  ', volumeInfo: { title: 'T' } })).toBeNull();
  });

  it('caps subjects at 8 and drops blank description/language', () => {
    const item: GoogleBooksVolumeItem = {
      id: 'v1',
      volumeInfo: {
        title: 'Title',
        description: '   ',
        language: '  ',
        categories: Array.from({ length: 10 }, (_, i) => `c${i}`),
      },
    };
    const book = mapGoogleBooksVolume(item)!;
    expect(book.subjects).toHaveLength(8);
    expect(book.description).toBeUndefined();
    expect(book.languages).toEqual([]);
  });

  it('drops non-https thumbnails and mismatched ISBN lengths', () => {
    const book = mapGoogleBooksVolume({
      id: 'v2',
      volumeInfo: {
        title: 'Title',
        imageLinks: { thumbnail: 'ftp://example.com/x.jpg' },
        industryIdentifiers: [
          { type: 'ISBN_13', identifier: '978014143951' },
          { type: 'ISBN_10', identifier: '01414395130' },
        ],
      },
    })!;
    expect(book.coverUrl).toBeNull();
    expect(book.isbn13).toBeNull();
    expect(book.isbn10).toBeNull();
  });
});

describe('GoogleBooksCatalogProvider (detail routing)', () => {
  it('rejects malformed or foreign ids with NOT_FOUND and zero I/O', async () => {
    const fetchFn = vi.fn(noIoFetch());
    const provider = new GoogleBooksCatalogProvider(new GoogleBooksDataSource(fetchFn, KEY));
    for (const id of ['gutendex:11', 'googlebooks:', 'googlebooks:a/b', 'googlebooks:a b']) {
      const err = await provider.getDetails(id).catch((e: unknown) => e);
      expect(err).toBeInstanceOf(CatalogError);
      expect((err as CatalogError).code).toBe('NOT_FOUND');
    }
    expect(fetchFn).not.toHaveBeenCalled();
  });
});

describe('composite fan-out with the key-gated Google Books provider', () => {
  it('absent key: no googlebooks source is registered (fail-closed)', () => {
    const sources = defaultCatalogProviders([], undefined, '').flatMap((p) => p.listSources());
    expect(sources.map((s) => s.sourceId)).not.toContain(BUILTIN_GOOGLEBOOKS);
    expect(sources.filter((s) => s.kind === 'builtin').map((s) => s.sourceId)).toEqual([
      BUILTIN_GUTENDEX,
      BUILTIN_OPENLIBRARY,
    ]);
    expect(sources.filter((s) => s.kind === 'curated')).toHaveLength(6);
  });

  it('present key: Google Books sits after Open Library and before curated', () => {
    const sources = defaultCatalogProviders([], undefined, KEY).flatMap((p) => p.listSources());
    expect(sources.filter((s) => s.kind === 'builtin').map((s) => s.sourceId)).toEqual([
      BUILTIN_GUTENDEX,
      BUILTIN_OPENLIBRARY,
      BUILTIN_GOOGLEBOOKS,
    ]);
    const googleIndex = sources.findIndex((s) => s.sourceId === BUILTIN_GOOGLEBOOKS);
    const curatedIndex = sources.findIndex((s) => s.kind === 'curated');
    expect(googleIndex).toBeLessThan(curatedIndex);
  });

  it('routes googlebooks:<id> details through the composite; malformed ids fail closed', async () => {
    const { fetchFn, urls } = recordingFetch({
      id: 'abc123',
      volumeInfo: { title: 'Pride and Prejudice' },
    });
    const provider = new CompositeCatalogProvider([googleBooksProviderOrNull(KEY, fetchFn)!], {
      debounceMs: 0,
    });
    expect(provider.listSources().map((s) => s.sourceId)).toEqual([BUILTIN_GOOGLEBOOKS]);

    const detail = await provider.getDetails('googlebooks:abc123');
    expect(detail.title).toBe('Pride and Prejudice');
    expect(detail.downloadUrl).toBeNull();
    expect(urls[0]).toContain('/volumes/abc123');

    for (const id of ['googlebooks:', 'googlebooks:a/b', 'unknown:1']) {
      const err = await provider.getDetails(id).catch((e: unknown) => e);
      expect(err).toBeInstanceOf(CatalogError);
      expect((err as CatalogError).code).toBe('NOT_FOUND');
    }
    // Routing failures never trigger I/O: only the one successful detail call.
    expect(urls).toHaveLength(1);
  });
});
