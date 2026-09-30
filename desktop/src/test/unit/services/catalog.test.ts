import { describe, expect, it, vi } from 'vitest';
import { CatalogError } from '$lib/shared/services/catalog/errors';
import {
  computeNextPage,
  firstIsbn10,
  firstIsbn13,
  gutenbergCoverUrl,
  isGutendexPublicDomain,
  isOpenLibraryPublic,
  mapGutendexBook,
  mapOpenLibraryDoc,
  mergeResults,
  resolveDownloadUrl,
  resolveTotalCount,
} from '$lib/shared/services/catalog/mappers';
import {
  clampPageSize,
  createSearchDebouncer,
  fetchWithRetry,
  shouldRetryStatus,
} from '$lib/shared/services/catalog/policy';
import { GutendexDataSource } from '$lib/shared/services/catalog/GutendexDataSource';
import { OpenLibraryDataSource } from '$lib/shared/services/catalog/OpenLibraryDataSource';
import { CompositeCatalogProvider } from '$lib/shared/services/catalog/CompositeCatalogProvider';
import {
  GutendexCatalogProvider,
  OpenLibraryCatalogProvider,
} from '$lib/shared/services/catalog/BuiltInCatalogProviders';
import gutendexFixture from '$lib/shared/services/catalog/fixtures/gutendex-search.json';
import openLibraryFixture from '$lib/shared/services/catalog/fixtures/openlibrary-search.json';
import type { GutendexRecord, OpenLibraryDoc } from '$lib/shared/services/catalog/mappers';

const gutendexRecords = gutendexFixture.results as unknown as GutendexRecord[];
const olDocs = openLibraryFixture.docs as unknown as OpenLibraryDoc[];

function stubFetch(handler: (url: string) => { status: number; body: unknown }): typeof fetch {
  return (async (input: unknown) => {
    const { status, body } = handler(String(input));
    return new Response(JSON.stringify(body), { status });
  }) as typeof fetch;
}

describe('resolveDownloadUrl', () => {
  const formats = {
    'application/epub+zip': 'https://example.com/book.epub',
    'text/plain': 'https://example.com/book.txt',
  };

  it('prefers EPUB when preferEpub is true', () => {
    expect(resolveDownloadUrl(formats, true)).toBe('https://example.com/book.epub');
  });

  it('falls back to TXT first when preferEpub is false', () => {
    expect(resolveDownloadUrl(formats, false)).toBe('https://example.com/book.txt');
  });

  it('throws UNAVAILABLE_DOWNLOAD for empty formats', () => {
    try {
      resolveDownloadUrl({}, true);
      expect.unreachable();
    } catch (err) {
      expect(err).toBeInstanceOf(CatalogError);
      expect((err as CatalogError).code).toBe('UNAVAILABLE_DOWNLOAD');
    }
  });

  it('rejects non-https URLs', () => {
    try {
      resolveDownloadUrl({ 'text/plain': 'http://example.com/book.txt' }, false);
      expect.unreachable();
    } catch (err) {
      expect((err as CatalogError).code).toBe('UNAVAILABLE_DOWNLOAD');
    }
  });
});

describe('PD predicates and mappers', () => {
  it('excludes copyright=true Gutendex records', () => {
    expect(isGutendexPublicDomain(gutendexRecords[1])).toBe(false);
    expect(mapGutendexBook(gutendexRecords[1])).toBeNull();
  });

  it('maps a public-domain Gutendex record', () => {
    const book = mapGutendexBook(gutendexRecords[0]);
    expect(book?.id).toBe('gutendex:1342');
    expect(book?.title).toBe('Pride and Prejudice');
    expect(book?.authors).toEqual(['Austen, Jane']);
  });

  it('excludes borrowable Open Library docs', () => {
    expect(isOpenLibraryPublic(olDocs[1])).toBe(false);
    expect(mapOpenLibraryDoc(olDocs[1])).toBeNull();
  });

  it('maps a public OL doc with a cover URL', () => {
    const book = mapOpenLibraryDoc(olDocs[0]);
    expect(book?.coverUrl).toBe('https://covers.openlibrary.org/b/id/6794977-M.jpg');
  });

  it('maps a public OL doc without cover to null coverUrl', () => {
    expect(mapOpenLibraryDoc(olDocs[2])?.coverUrl).toBeNull();
  });
});

describe('WU1 plumbing: description, cover, formats', () => {
  it('joins Gutendex summaries with blank-line separators', () => {
    const book = mapGutendexBook(gutendexRecords[0]);
    expect(book?.description).toBe(
      'It is a truth universally acknowledged, that a single man in possession of a good fortune, must be in want of a wife.\n\nElizabeth Bennet spars with the proud Mr. Darcy.',
    );
  });

  it('leaves description undefined when summaries are missing or blank', () => {
    const book = mapGutendexBook(gutendexRecords[2]);
    expect(book?.description).toBeUndefined();
    expect(
      mapGutendexBook({
        id: 1,
        title: 'Blank',
        copyright: false,
        summaries: ['   '],
      })?.description,
    ).toBeUndefined();
  });

  it('derives the Gutenberg cover and leaves non-Gutenberg books unaffected', () => {
    expect(gutenbergCoverUrl(1342)).toBe(
      'https://www.gutenberg.org/cache/epub/1342/pg1342.cover.medium.jpg',
    );
    expect(gutenbergCoverUrl(0)).toBeNull();
    expect(mapGutendexBook(gutendexRecords[0])?.coverUrl).toBe(gutenbergCoverUrl(1342));
    expect(mapOpenLibraryDoc(olDocs[0])?.coverUrl).toBe(
      'https://covers.openlibrary.org/b/id/6794977-M.jpg',
    );
  });

  it('threads formats verbatim and resolves downloadUrl from them', () => {
    const book = mapGutendexBook(gutendexRecords[0]);
    expect(book?.formats?.['text/plain']).toBe('https://www.gutenberg.org/ebooks/1342.txt.utf8');
    expect(book?.downloadUrl).toBe('https://www.gutenberg.org/ebooks/1342.epub3.images');
    // Empty formats map → no usable URL → null (never throws out of the mapper).
    expect(mapGutendexBook(gutendexRecords[2])?.downloadUrl).toBeNull();
  });

  it('splits OL ISBNs by digit length and carries work/IA identity', () => {
    expect(firstIsbn13(['978-0141439518', '0141439513'])).toBe('9780141439518');
    expect(firstIsbn10(['978-0141439518', '0141439513'])).toBe('0141439513');
    const book = mapOpenLibraryDoc(olDocs[0]);
    expect(book?.isbn13).toBe('9780141439518');
    expect(book?.isbn10).toBe('0141439513');
    expect(book?.openLibraryWorkId).toBe('/works/OL66554W');
    expect(book?.internetArchiveId).toBe('prideandprejudice0000aust');
  });
});

describe('merge and pagination', () => {
  it('lets Gutendex win (Gutenberg cover) and fills identity from OL', () => {
    const gBooks = gutendexRecords.map(mapGutendexBook).filter((b) => b !== null);
    const oBooks = olDocs.map(mapOpenLibraryDoc).filter((b) => b !== null);
    const merged = mergeResults(gBooks, oBooks);
    const pride = merged.find((b) => b.id === 'gutendex:1342');
    expect(pride?.provider).toBe('builtin:gutendex');
    // Gutendex derives its own cover from the record id, so the Gutenberg
    // URL wins and OL no longer fills the gap for this book (Android parity).
    expect(pride?.coverUrl).toBe(
      'https://www.gutenberg.org/cache/epub/1342/pg1342.cover.medium.jpg',
    );
    expect(pride?.isbn13).toBe('9780141439518');
    expect(pride?.openLibraryWorkId).toBe('/works/OL66554W');
    // Borrowable OL doc dropped by the mapper, so it never reaches the merge.
    expect(merged.some((b) => b.title === 'Borrow Restricted Title')).toBe(false);
  });

  it('computes nextPage and resolves totalCount with Gutendex authority', () => {
    expect(resolveTotalCount(1200, 42)).toBe(1200);
    expect(resolveTotalCount(null, 42)).toBe(42);
    expect(computeNextPage(1, 24, 1200)).toBe(2);
    expect(computeNextPage(50, 24, 1200)).toBeNull();
  });
});

describe('policy', () => {
  it('clamps page size into 20–32 with default 24', () => {
    expect(clampPageSize(24)).toBe(24);
    expect(clampPageSize(4)).toBe(20);
    expect(clampPageSize(200)).toBe(32);
    expect(clampPageSize(Number.NaN)).toBe(24);
  });

  it('retries only 429/5xx', () => {
    expect(shouldRetryStatus(429)).toBe(true);
    expect(shouldRetryStatus(503)).toBe(true);
    expect(shouldRetryStatus(404)).toBe(false);
    expect(shouldRetryStatus(400)).toBe(false);
  });

  it('retries once after 429 then succeeds', async () => {
    let calls = 0;
    const fetchFn = stubFetch(() =>
      ++calls === 1 ? { status: 429, body: {} } : { status: 200, body: { ok: true } },
    );
    const res = await fetchWithRetry('https://example.com', {}, fetchFn);
    expect(res.ok).toBe(true);
    expect(calls).toBe(2);
  });

  it('debounces bursts so only the latest query hits I/O', async () => {
    vi.useFakeTimers();
    try {
      const executed: string[] = [];
      const { search } = createSearchDebouncer(async (q: string) => {
        executed.push(q);
        return q;
      }, 350);
      const p1 = search('first', 1);
      const p2 = search('second', 1);
      const p3 = search('third', 1);
      await vi.advanceTimersByTimeAsync(400);
      await expect(p1).resolves.toBe('third');
      await expect(p2).resolves.toBe('third');
      await expect(p3).resolves.toBe('third');
      expect(executed).toEqual(['third']);
    } finally {
      vi.useRealTimers();
    }
  });
});

describe('datasources (offline stubs)', () => {
  it('Gutendex search sends UA and drops in-copyright records', async () => {
    let userAgent: string | null = null;
    const fetchFn = stubFetch(() => ({ status: 200, body: gutendexFixture }));
    const recording: typeof fetch = (async (...args: Parameters<typeof fetch>) => {
      userAgent = new Headers(args[1]?.headers).get('User-Agent');
      return fetchFn(...args);
    }) as typeof fetch;
    const ds = new GutendexDataSource(recording);
    const { books, totalCount } = await ds.search('pride', 1);
    expect(userAgent).toContain('Nexo/Desktop');
    expect(totalCount).toBe(3);
    expect(books.map((b) => b.id)).toEqual(['gutendex:1342', 'gutendex:11']);
  });

  it('Open Library search drops borrowable docs', async () => {
    const fetchFn = stubFetch(() => ({ status: 200, body: openLibraryFixture }));
    const ds = new OpenLibraryDataSource(fetchFn);
    const { books, totalCount } = await ds.search('pride', 1);
    expect(totalCount).toBe(3);
    expect(books.map((b) => b.id)).toEqual([
      'openlibrary:/works/OL66554W',
      'openlibrary:/works/OL11W',
    ]);
  });
});

describe('CompositeCatalogProvider', () => {
  function stubbedComposite(): {
    provider: CompositeCatalogProvider;
    calls: { g: number; o: number };
  } {
    const calls = { g: 0, o: 0 };
    const g = new GutendexDataSource(
      stubFetch(() => {
        calls.g += 1;
        return { status: 200, body: gutendexFixture };
      }),
    );
    const o = new OpenLibraryDataSource(
      stubFetch(() => {
        calls.o += 1;
        return { status: 200, body: openLibraryFixture };
      }),
    );
    return {
      provider: new CompositeCatalogProvider(
        [new GutendexCatalogProvider(g), new OpenLibraryCatalogProvider(o)],
        { debounceMs: 0 },
      ),
      calls,
    };
  }

  it('rejects page < 1 before any I/O', async () => {
    const { provider, calls } = stubbedComposite();
    const err = await provider.search('x', 0).catch((e: unknown) => e);
    expect(err).toBeInstanceOf(CatalogError);
    expect((err as CatalogError).code).toBe('INVALID_PAGE');
    expect(calls).toEqual({ g: 0, o: 0 });
  });

  it('merges with Gutendex authority (Gutenberg cover wins)', async () => {
    const { provider } = stubbedComposite();
    const page = await provider.search('pride', 1);
    expect(page.totalCount).toBe(3);
    const pride = page.results.find((b) => b.id === 'gutendex:1342');
    expect(pride?.coverUrl).toBe(
      'https://www.gutenberg.org/cache/epub/1342/pg1342.cover.medium.jpg',
    );
    expect(page.results.some((b) => b.title === 'Borrow Restricted Title')).toBe(false);
  });

  it('resolves getDetails for Gutendex ids and 404s unknown prefixes', async () => {
    const fetchFn = stubFetch((url) =>
      url.endsWith('/books/1342/')
        ? { status: 200, body: gutendexFixture.results[0] }
        : { status: 404, body: {} },
    );
    const provider = new CompositeCatalogProvider([
      new GutendexCatalogProvider(new GutendexDataSource(fetchFn)),
    ]);
    await expect(provider.getDetails('gutendex:1342')).resolves.toMatchObject({
      id: 'gutendex:1342',
    });
    for (const id of ['openlibrary:/works/OL11W', 'gutendex:99991']) {
      const err = await provider.getDetails(id).catch((e: unknown) => e);
      expect(err).toBeInstanceOf(CatalogError);
      expect((err as CatalogError).code).toBe('NOT_FOUND');
    }
  });

  it('delegates resolveDownloadUrl to the pure priority function', () => {
    const { provider } = stubbedComposite();
    expect(provider.resolveDownloadUrl({ 'text/plain': 'https://example.com/b.txt' }, true)).toBe(
      'https://example.com/b.txt',
    );
  });
});
