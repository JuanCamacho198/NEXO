/**
 * DISC-04c — Gutendex and Open Library as lazy, per-book authorities.
 *
 * Search is Google Books-only now, so a result carries no `openLibraryWorkId`
 * / `internetArchiveId` and no Gutendex download. These tests pin the lazy
 * resolve that buys them back: the pure merge rules, the composite's routing
 * and detail-key cache, the fields projection, the no-ISBN skip, and the fact
 * that a no-match never grows a download affordance.
 */
import { describe, expect, it, vi } from 'vitest';
import {
  authorityQuery,
  hasResolvedAuthorities,
  isbnOf,
  resolveBookAuthorities,
  type BookAuthorityLookups,
} from '$lib/shared/services/catalog/bookAuthorityResolver';
import { CompositeCatalogProvider } from '$lib/shared/services/catalog/CompositeCatalogProvider';
import {
  GutendexCatalogProvider,
  OpenLibraryCatalogProvider,
  googleBooksProviderOrNull,
} from '$lib/shared/services/catalog/BuiltInCatalogProviders';
import {
  OPEN_LIBRARY_ISBN_FIELDS,
  OpenLibraryDataSource,
} from '$lib/shared/services/catalog/OpenLibraryDataSource';
import { GutendexDataSource } from '$lib/shared/services/catalog/GutendexDataSource';
import {
  DETAIL_TTL_S,
  InMemoryDiscoverCache,
  detailCacheKey,
} from '$lib/shared/services/catalog/DiscoverCache';
import { resolveAccess } from '$lib/shared/services/catalog/accessResolver';
import type { CatalogBook } from '$lib/shared/services/catalog/CatalogProvider';

const KEY = 'test-key-123';

function stubFetch(handler: (url: string) => { status: number; body: unknown }): typeof fetch {
  return (async (input: unknown) => {
    const { status, body } = handler(String(input));
    return new Response(JSON.stringify(body), { status });
  }) as typeof fetch;
}

function noIoFetch(): typeof fetch {
  return (async () => {
    throw new Error('unexpected network I/O');
  }) as typeof fetch;
}

/** A Google Books result: metadata only, no authorities, one ISBN. */
function googleBooksBook(overrides: Partial<CatalogBook> = {}): CatalogBook {
  return {
    id: 'googlebooks:abc123',
    provider: 'builtin:googlebooks',
    title: 'Pride and Prejudice',
    authors: ['Jane Austen'],
    coverUrl: null,
    languages: ['en'],
    subjects: ['Fiction'],
    downloadUrl: null,
    isbn13: '9780141439518',
    isbn10: null,
    isPublicDomain: null,
    googleBooksId: 'abc123',
    ...overrides,
  };
}

function gutendexEdition(): CatalogBook {
  return {
    id: 'gutendex:1342',
    provider: 'builtin:gutendex',
    title: 'Pride and Prejudice',
    authors: ['Austen, Jane'],
    coverUrl: null,
    languages: ['en'],
    subjects: ['Fiction'],
    downloadUrl: 'https://www.gutenberg.org/ebooks/1342.epub3.images',
    isPublicDomain: true,
  };
}

function lookups(overrides: Partial<BookAuthorityLookups> = {}): BookAuthorityLookups {
  return {
    gutendex: vi.fn(async () => [gutendexEdition()]),
    openLibraryByIsbn: vi.fn(async () => ({
      workId: '/works/OL66554W',
      archiveId: 'prideandprejudice0000aust',
    })),
    ...overrides,
  };
}

function gutendexPayload(): unknown {
  return {
    count: 1,
    results: [
      {
        id: 1342,
        title: 'Pride and Prejudice',
        authors: [{ name: 'Austen, Jane' }],
        copyright: false,
        languages: ['en'],
        subjects: ['Fiction'],
        formats: {
          'application/epub+zip': 'https://www.gutenberg.org/ebooks/1342.epub3.images',
        },
      },
    ],
  };
}

function openLibraryPayload(): unknown {
  return {
    numFound: 1,
    docs: [
      {
        key: '/works/OL66554W',
        ia: ['prideandprejudice0000aust'],
        ebook_access: 'public',
        title: 'Pride and Prejudice',
      },
    ],
  };
}

describe('bookAuthorityResolver pure rules', () => {
  it('builds a plain title-author Gutendex query with no colon operator', () => {
    expect(authorityQuery(googleBooksBook())).toBe('Pride and Prejudice Jane Austen');
    expect(authorityQuery(googleBooksBook())).not.toContain(':');
    expect(authorityQuery(googleBooksBook({ authors: [] }))).toBe('Pride and Prejudice');
  });

  it('reads the first present non-blank ISBN, preferring ISBN-13', () => {
    expect(isbnOf(googleBooksBook())).toBe('9780141439518');
    expect(isbnOf(googleBooksBook({ isbn13: null, isbn10: '0141439513' }))).toBe('0141439513');
    expect(isbnOf(googleBooksBook({ isbn13: null, isbn10: null }))).toBeNull();
    expect(isbnOf(googleBooksBook({ isbn13: '   ', isbn10: null }))).toBeNull();
  });

  it('short-circuits once any authority is present', () => {
    expect(hasResolvedAuthorities(googleBooksBook())).toBe(false);
    expect(hasResolvedAuthorities(googleBooksBook({ downloadUrl: 'https://x/1.epub' }))).toBe(true);
    expect(hasResolvedAuthorities(googleBooksBook({ isPublicDomain: true }))).toBe(true);
    expect(hasResolvedAuthorities(googleBooksBook({ openLibraryWorkId: '/works/OL1W' }))).toBe(
      true,
    );
    expect(hasResolvedAuthorities(googleBooksBook({ internetArchiveId: 'ia-1' }))).toBe(true);
  });

  it('resolves both authorities for a Google Books book with an ISBN', async () => {
    const book = googleBooksBook();
    const ports = lookups();

    const resolved = await resolveBookAuthorities(book, ports);

    expect(resolved.downloadUrl).toBe('https://www.gutenberg.org/ebooks/1342.epub3.images');
    expect(resolved.isPublicDomain).toBe(true);
    expect(resolved.openLibraryWorkId).toBe('/works/OL66554W');
    expect(resolved.internetArchiveId).toBe('prideandprejudice0000aust');
    expect(ports.gutendex).toHaveBeenCalledWith('Pride and Prejudice Jane Austen');
    expect(ports.openLibraryByIsbn).toHaveBeenCalledWith('9780141439518');
  });

  it('skips the Open Library call entirely when the book has no ISBN', async () => {
    const book = googleBooksBook({ isbn13: null, isbn10: null });
    const ports = lookups();

    const resolved = await resolveBookAuthorities(book, ports);

    // Open Library is never asked (a skip, not a failure); Gutendex still is.
    expect(ports.openLibraryByIsbn).not.toHaveBeenCalled();
    expect(ports.gutendex).toHaveBeenCalledOnce();
    expect(resolved.openLibraryWorkId ?? null).toBeNull();
    expect(resolved.internetArchiveId ?? null).toBeNull();
    expect(resolved.downloadUrl).toBe('https://www.gutenberg.org/ebooks/1342.epub3.images');
  });

  it('attaches no download when no Gutendex edition matches', async () => {
    // A PD Gutendex record for a different title must never be paired.
    const other = { ...gutendexEdition(), title: 'Pride and Prejudice and Zombies' };
    const ports = lookups({ gutendex: vi.fn(async () => [other]) });

    const resolved = await resolveBookAuthorities(googleBooksBook(), ports);

    expect(resolved.downloadUrl ?? null).toBeNull();
    expect(resolved.isPublicDomain ?? null).toBeNull();
    const access = resolveAccess(resolved);
    expect(access.canDownloadInApp).toBe(false);
    expect(access.options.some((option) => option.titleKey === 'discover.accessDownload')).toBe(
      false,
    );
  });

  it('leaves the seed untouched when both lookups fail', async () => {
    const book = googleBooksBook();
    const ports = lookups({
      gutendex: vi.fn(async () => {
        throw new Error('gutendex down');
      }),
      openLibraryByIsbn: vi.fn(async () => {
        throw new Error('openlibrary down');
      }),
    });

    const resolved = await resolveBookAuthorities(book, ports);

    expect(resolved).toBe(book);
  });
});

describe('CompositeCatalogProvider.resolveBookAuthorities (DISC-04c)', () => {
  function buildComposite(cache: InMemoryDiscoverCache): {
    composite: CompositeCatalogProvider;
    urls: { gutendex: string[]; ol: string[]; gb: string[] };
  } {
    const urls = { gutendex: [] as string[], ol: [] as string[], gb: [] as string[] };
    const composite = new CompositeCatalogProvider(
      [
        new GutendexCatalogProvider(
          new GutendexDataSource(
            stubFetch((url) => {
              urls.gutendex.push(url);
              return { status: 200, body: gutendexPayload() };
            }),
          ),
        ),
        new OpenLibraryCatalogProvider(
          new OpenLibraryDataSource(
            stubFetch((url) => {
              urls.ol.push(url);
              return { status: 200, body: openLibraryPayload() };
            }),
          ),
        ),
        googleBooksProviderOrNull(
          KEY,
          stubFetch((url) => {
            urls.gb.push(url);
            return { status: 200, body: {} };
          }),
        )!,
      ],
      { debounceMs: 0, cache, nowEpochSecs: () => 1_000 },
    );
    return { composite, urls };
  }

  it('resolves through Gutendex searchSource and a fields-projected OL ISBN call', async () => {
    const cache = new InMemoryDiscoverCache();
    const { composite, urls } = buildComposite(cache);

    const resolved = await composite.resolveBookAuthorities(googleBooksBook());

    expect(resolved.downloadUrl).toBe('https://www.gutenberg.org/ebooks/1342.epub3.images');
    expect(resolved.isPublicDomain).toBe(true);
    expect(resolved.openLibraryWorkId).toBe('/works/OL66554W');
    expect(resolved.internetArchiveId).toBe('prideandprejudice0000aust');

    // Gutendex was reached through searchSource (a plain query, no colon).
    expect(urls.gutendex).toHaveLength(1);
    expect(urls.gutendex[0]).toContain('search=Pride');
    expect(urls.gutendex[0]).not.toContain('%3A');

    // Open Library carries the `fields` projection and the measured-good colon.
    expect(urls.ol).toHaveLength(1);
    expect(urls.ol[0]).toContain(`fields=${encodeURIComponent(OPEN_LIBRARY_ISBN_FIELDS)}`);
    expect(OPEN_LIBRARY_ISBN_FIELDS).toContain('key');
    expect(OPEN_LIBRARY_ISBN_FIELDS).toContain('ia');
    expect(OPEN_LIBRARY_ISBN_FIELDS).toContain('ebook_access');
    expect(urls.ol[0]).toContain('q=isbn%3A9780141439518');
    expect(urls.ol[0]).toContain('limit=1');

    // The resolver never calls Google Books, and no GB request ever has a colon.
    expect(urls.gb).toHaveLength(0);
    for (const url of urls.gb) expect(url).not.toContain('%3A');
  });

  it('caches under the existing detail key so a second resolve issues no request', async () => {
    const cache = new InMemoryDiscoverCache();
    const { composite, urls } = buildComposite(cache);

    const first = await composite.resolveBookAuthorities(googleBooksBook());
    expect(urls.gutendex).toHaveLength(1);
    expect(urls.ol).toHaveLength(1);

    const entry = cache.read(detailCacheKey('builtin:googlebooks', 'googlebooks:abc123'), 1_000);
    expect(entry?.ttlS).toBe(DETAIL_TTL_S);
    expect(entry?.stale).toBe(false);

    const second = await composite.resolveBookAuthorities(googleBooksBook());
    expect(second).toEqual(first);
    expect(urls.gutendex).toHaveLength(1);
    expect(urls.ol).toHaveLength(1);
  });

  it('issues no Open Library request when the book has no ISBN', async () => {
    const cache = new InMemoryDiscoverCache();
    const { composite, urls } = buildComposite(cache);

    const book = googleBooksBook({ isbn13: null, isbn10: null });
    const resolved = await composite.resolveBookAuthorities(book);

    expect(urls.ol).toHaveLength(0);
    expect(urls.gutendex).toHaveLength(1);
    expect(resolved.openLibraryWorkId ?? null).toBeNull();
  });

  it('returns a book with an unroutable id unchanged (no I/O)', async () => {
    const cache = new InMemoryDiscoverCache();
    const { composite, urls } = buildComposite(cache);
    const book = googleBooksBook({ id: 'unknown:1' });

    const resolved = await composite.resolveBookAuthorities(book);

    expect(resolved).toBe(book);
    expect(urls.gutendex).toHaveLength(0);
    expect(urls.ol).toHaveLength(0);
  });

  it('does not re-resolve a book that already carries both authorities', async () => {
    const cache = new InMemoryDiscoverCache();
    const { composite, urls } = buildComposite(cache);
    const book = googleBooksBook({
      downloadUrl: 'https://example.com/direct.epub',
      isPublicDomain: true,
      openLibraryWorkId: '/works/OL1W',
      internetArchiveId: 'ia-1',
    });

    const resolved = await composite.resolveBookAuthorities(book);

    expect(resolved).toBe(book);
    expect(urls.gutendex).toHaveLength(0);
    expect(urls.ol).toHaveLength(0);
  });
});
