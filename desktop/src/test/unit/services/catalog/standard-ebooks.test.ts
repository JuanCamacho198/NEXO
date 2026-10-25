import { describe, expect, it } from 'vitest';
import {
  BUILTIN_STANDARD_EBOOKS,
  STANDARD_EBOOKS_ATOM_FEED_URL,
  StandardEbooksCatalogProvider,
  StandardEbooksDataSource,
  mapStandardEbooksAtomFeed,
  standardEbooksSlugOf,
} from '$lib/shared/services/catalog';
import { STANDARD_EBOOKS_ATOM_FEED as FEED } from '../../../fixtures-recorded/catalogPayloads';

/**
 * The recorded payload is the live `https://standardebooks.org/feeds/atom/new-releases`
 * response (HTTP 200, `application/atom+xml`, 15 entries, captured 2026-10-23).
 * The OPDS JSON feed the original plan named answers HTTP 401 with a Basic
 * `WWW-Authenticate` for the Patrons Circle, so it is not a public catalog and
 * no JSON shape could be recorded from it.
 */

const parser = (): DOMParser => new DOMParser();

describe('mapStandardEbooksAtomFeed', () => {
  it('maps every recorded entry to a namespaced, downloadable catalog book', () => {
    const books = mapStandardEbooksAtomFeed(FEED, parser());
    expect(books).toHaveLength(15);
    for (const book of books) {
      expect(book.id.startsWith('standard-ebooks:')).toBe(true);
      expect(book.provider).toBe(BUILTIN_STANDARD_EBOOKS);
      expect(book.title.trim()).not.toBe('');
      expect(book.authors.length).toBeGreaterThan(0);
      // The acquisition link must be the EPUB and must be https.
      expect(book.downloadUrl).toMatch(/^https:\/\/standardebooks\.org\/.*\.epub(\?|$)/);
      expect(book.coverUrl).toMatch(/^https:\/\/standardebooks\.org\/.*\.jpg$/);
      expect(book.isPublicDomain).toBe(true);
    }
  });

  it('pins the first recorded entry field by field', () => {
    const [first] = mapStandardEbooksAtomFeed(FEED, parser());
    expect(first).toEqual({
      id: 'standard-ebooks:henry-seton-merriman/suspense',
      provider: BUILTIN_STANDARD_EBOOKS,
      title: 'Suspense',
      authors: ['Henry Seton Merriman'],
      coverUrl:
        'https://standardebooks.org/ebooks/henry-seton-merriman/suspense/downloads/cover-thumbnail.jpg',
      languages: [],
      subjects: [
        'Man-woman relationships--Fiction',
        'War correspondents--Fiction',
        'Russo-Turkish War, 1877-1878--Fiction',
        'Fiction',
      ],
      downloadUrl:
        'https://standardebooks.org/ebooks/henry-seton-merriman/suspense/downloads/henry-seton-merriman_suspense.epub?source=feed',
      description: expect.stringContaining('Two childhood friends'),
      isPublicDomain: true,
    });
  });

  it('keeps languages empty rather than guessing, since the feed states none', () => {
    const books = mapStandardEbooksAtomFeed(FEED, parser());
    for (const book of books) {
      expect(book.languages).toEqual([]);
    }
    // Guard the reason: the payload genuinely carries no language element.
    expect(FEED).not.toContain('dc:language');
    expect(FEED).not.toContain('xml:lang');
  });

  it('namespaces ids per work and collapses the translator segment', () => {
    expect(standardEbooksSlugOf('https://standardebooks.org/ebooks/jules-verne/robur')).toBe(
      'jules-verne/robur',
    );
    // A third segment is the translator: dropped so a retranslation keeps the id.
    expect(
      standardEbooksSlugOf(
        'https://standardebooks.org/ebooks/seneca/moral-letters/richard-m-gummere',
      ),
    ).toBe('seneca/moral-letters');
    expect(standardEbooksSlugOf('https://standardebooks.org/collections/all')).toBeNull();
    expect(standardEbooksSlugOf('https://standardebooks.org/ebooks/only-author')).toBeNull();
  });

  it('produces no duplicate ids across the whole recorded feed', () => {
    const ids = mapStandardEbooksAtomFeed(FEED, parser()).map((book) => book.id);
    expect(new Set(ids).size).toBe(ids.length);
  });

  it('returns no books for a malformed or wrong-root document', () => {
    expect(mapStandardEbooksAtomFeed('<feed><entry>', parser())).toEqual([]);
    expect(mapStandardEbooksAtomFeed('<html><body>nope</body></html>', parser())).toEqual([]);
    expect(mapStandardEbooksAtomFeed('', parser())).toEqual([]);
  });

  it('drops an entry with no title, author or epub link instead of half-rendering it', () => {
    const entry = (inner: string): string =>
      `<feed xmlns="http://www.w3.org/2005/Atom"><entry>${inner}</entry></feed>`;
    // No author.
    expect(
      mapStandardEbooksAtomFeed(
        entry(
          '<id>https://standardebooks.org/ebooks/a/b</id><title>T</title>' +
            '<link rel="enclosure" type="application/epub+zip" title="Recommended compatible epub" href="https://standardebooks.org/a.epub"/>',
        ),
        parser(),
      ),
    ).toEqual([]);
    // No download.
    expect(
      mapStandardEbooksAtomFeed(
        entry(
          '<id>https://standardebooks.org/ebooks/a/b</id><title>T</title>' +
            '<author><name>A</name></author>',
        ),
        parser(),
      ),
    ).toEqual([]);
    // http download: dropped, exactly like every other provider's https-only rule.
    expect(
      mapStandardEbooksAtomFeed(
        entry(
          '<id>https://standardebooks.org/ebooks/a/b</id><title>T</title>' +
            '<author><name>A</name></author>' +
            '<link rel="enclosure" type="application/epub+zip" title="Recommended compatible epub" href="http://standardebooks.org/a.epub"/>',
        ),
        parser(),
      ),
    ).toEqual([]);
  });

  it('drops an http cover instead of degrading to a mixed-content image', () => {
    const [book] = mapStandardEbooksAtomFeed(
      '<feed xmlns="http://www.w3.org/2005/Atom" xmlns:media="http://search.yahoo.com/mrss/"><entry>' +
        '<id>https://standardebooks.org/ebooks/a/b</id><title>T</title>' +
        '<author><name>A</name></author>' +
        '<media:thumbnail url="http://standardebooks.org/cover.jpg"/>' +
        '<link rel="enclosure" type="application/epub+zip" title="Recommended compatible epub" href="https://standardebooks.org/a.epub"/>' +
        '</entry></feed>',
      parser(),
    );
    expect(book?.coverUrl).toBeNull();
  });
});

describe('StandardEbooksDataSource', () => {
  function stubFetch(body: string, status = 200): typeof fetch {
    return (async () =>
      ({
        ok: status >= 200 && status < 300,
        status,
        text: async () => body,
      }) as unknown as Response) as unknown as typeof fetch;
  }

  it('requests the measured Atom feed with the Atom media type', async () => {
    const seen: { url: string; accept: string }[] = [];
    const fetchFn = (async (url: string, init: RequestInit) => {
      seen.push({
        url,
        accept: String((init.headers as Record<string, string>).Accept),
      });
      return {
        ok: true,
        status: 200,
        text: async () => FEED,
      } as unknown as Response;
    }) as unknown as typeof fetch;

    const books = await new StandardEbooksDataSource(fetchFn).newest(1).then((r) => r.books);
    expect(books).toHaveLength(15);
    expect(seen).toEqual([{ url: STANDARD_EBOOKS_ATOM_FEED_URL, accept: 'application/atom+xml' }]);
    expect(STANDARD_EBOOKS_ATOM_FEED_URL).toBe(
      'https://standardebooks.org/feeds/atom/new-releases',
    );
  });

  it('returns an empty page past page 1 without any I/O', async () => {
    let calls = 0;
    const fetchFn = (async () => {
      calls += 1;
      return { ok: true, status: 200, text: async () => FEED } as unknown as Response;
    }) as unknown as typeof fetch;
    const page = await new StandardEbooksDataSource(fetchFn).newest(2);
    expect(page).toEqual({ books: [], totalCount: 0 });
    expect(calls).toBe(0);
  });

  it('surfaces a 401 as an upstream error, never as an empty catalog', async () => {
    await expect(new StandardEbooksDataSource(stubFetch('', 401)).newest(1)).rejects.toMatchObject({
      code: 'UPSTREAM_ERROR',
    });
  });

  it('getById resolves a feed book and rejects an unknown id with NOT_FOUND', async () => {
    const ds = new StandardEbooksDataSource(stubFetch(FEED));
    await expect(
      ds.getById('standard-ebooks:henry-seton-merriman/suspense'),
    ).resolves.toMatchObject({ title: 'Suspense' });
    await expect(ds.getById('standard-ebooks:nobody/nothing')).rejects.toMatchObject({
      code: 'NOT_FOUND',
    });
  });
});

describe('StandardEbooksCatalogProvider', () => {
  function providerWith(body: string): StandardEbooksCatalogProvider {
    const fetchFn = (async () =>
      ({
        ok: true,
        status: 200,
        text: async () => body,
      }) as unknown as Response) as unknown as typeof fetch;
    return new StandardEbooksCatalogProvider(new StandardEbooksDataSource(fetchFn));
  }

  it('serves one built-in source and slices the shelf to the requested row', async () => {
    const provider = providerWith(FEED);
    expect(provider.listSources()).toEqual([
      { sourceId: BUILTIN_STANDARD_EBOOKS, name: 'Standard Ebooks', kind: 'builtin' },
    ]);
    const page = await provider.newest(6);
    expect(page.results).toHaveLength(6);
    expect(page.totalCount).toBe(15);
  });

  it('resolves searchSource for its own id and fails closed for any other', async () => {
    const provider = providerWith(FEED);
    const own = await provider.searchSource(BUILTIN_STANDARD_EBOOKS, '', 1);
    expect(own.results).toHaveLength(15);
    const other = await provider.searchSource('builtin:gutendex' as never, '', 1);
    expect(other).toEqual({ results: [], nexoPage: null, totalCount: 0 });
  });

  it('never fakes a keyword search over an unsearchable feed', async () => {
    const provider = providerWith(FEED);
    await expect(provider.search('verne', 1)).resolves.toEqual({
      results: [],
      nexoPage: null,
      totalCount: 0,
    });
  });

  it('rejects an unknown or malformed detail id with NOT_FOUND, no I/O', async () => {
    const provider = providerWith(FEED);
    await expect(provider.getDetails('gutendex:1342')).rejects.toMatchObject({
      code: 'NOT_FOUND',
    });
  });

  it('is fail-closed for featured and rejects an invalid limit', async () => {
    const provider = providerWith(FEED);
    expect(provider.supportsFeatured('POPULAR')).toBe(false);
    await expect(provider.featured('POPULAR', 0)).rejects.toMatchObject({ code: 'INVALID_PAGE' });
    await expect(provider.featured('POPULAR', 6)).resolves.toEqual({
      results: [],
      nexoPage: null,
      totalCount: 0,
    });
  });
});
