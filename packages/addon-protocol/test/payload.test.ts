import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { describe, expect, it } from 'vitest';
import { SHAPES } from '../src/constants';
import type { AddonShape } from '../src/manifest';
import {
  type BookStream,
  assertDeliverableStream,
  orderStreams,
  parseCatalogPayload,
  parseMetaPayload,
  parseStreamPayload,
} from '../src/payload';
import { shapeGutendexCatalog, shapeGutendexMeta } from '../src/shapes/gutendex';
import { shapeOpenlibraryCatalog, shapeOpenlibraryMeta } from '../src/shapes/openlibrary';

const fixturesDir = join(__dirname, 'fixtures', 'payloads');

function loadFixture(name: string): unknown {
  return JSON.parse(readFileSync(join(fixturesDir, `${name}.json`), 'utf8'));
}

const MALFORMED_BODIES: unknown[] = [
  null,
  undefined,
  'nope',
  42,
  true,
  [],
  {},
  { metas: {} },
  { metas: 'nope' },
  { streams: {} },
  { streams: 'nope' },
];

function stream(overrides: Partial<BookStream> = {}): BookStream {
  return {
    source: 'download',
    url: 'https://example.org/book.epub',
    accessType: 'unknown',
    format: 'epub',
    ...overrides,
  };
}

describe('parseCatalogPayload', () => {
  it('parses a metas envelope and coerces missing author arrays', () => {
    const { metas, skipped } = parseCatalogPayload(loadFixture('catalog-raw'), 'raw');

    expect(skipped).toBe(2);
    expect(metas).toHaveLength(3);
    expect(metas[0]).toEqual({
      id: 'gutenberg:1342',
      type: 'book',
      name: 'Pride and Prejudice',
      poster: 'https://www.gutenberg.org/cache/epub/1342/pg1342.cover.medium.jpg',
      description: "Austen's most famous novel.",
      releaseInfo: '1813',
      authors: ['Jane Austen'],
      languages: ['en'],
      subjects: ['Courtship -- Fiction'],
    });
    expect(metas[1]).toEqual({
      id: 'isbn:9780141439518',
      type: 'book',
      name: 'Pride and Prejudice (Penguin Classics)',
      authors: ['Jane Austen'],
      languages: ['en'],
      subjects: [],
      isbn13: '9780141439518',
    });
    expect(metas[2]).toEqual({
      id: 'gutenberg:84',
      type: 'book',
      name: 'Frankenstein; Or, The Modern Prometheus',
      authors: [],
      languages: [],
      subjects: [],
    });
  });

  it('caps metas at 200 and counts the overflow as skipped', () => {
    const body = {
      metas: Array.from({ length: 250 }, (_, index) => ({
        id: `gutenberg:${index}`,
        type: 'book',
        name: `Book ${index}`,
      })),
    };

    const { metas, skipped } = parseCatalogPayload(body, 'raw');

    expect(metas).toHaveLength(200);
    expect(skipped).toBe(50);
    expect(metas[199]?.id).toBe('gutenberg:199');
  });

  it('truncates a long string at 2048 chars instead of dropping the meta', () => {
    const { metas, skipped } = parseCatalogPayload(
      { metas: [{ id: 'gutenberg:1', name: 'Long', description: 'x'.repeat(3000) }] },
      'raw',
    );

    expect(skipped).toBe(0);
    expect(metas[0]?.description).toHaveLength(2048);
    expect(metas[0]?.name).toBe('Long');
  });

  it('skips a meta whose isbn id is not a 13 digit isbn', () => {
    const { metas } = parseCatalogPayload({ metas: [{ id: 'isbn:0141439513', name: 'Ten digits' }] }, 'raw');

    expect(metas[0]?.isbn13).toBeUndefined();
  });

  it('returns an empty result for a malformed envelope instead of throwing', () => {
    for (const body of MALFORMED_BODIES) {
      expect(() => parseCatalogPayload(body, 'raw')).not.toThrow();
      expect(parseCatalogPayload(body, 'raw')).toEqual({ metas: [], skipped: 1 });
    }
  });

  it('reads a gutendex native payload through its shape', () => {
    const { metas, skipped } = parseCatalogPayload(loadFixture('gutendex-books'), 'gutendex');

    expect(skipped).toBe(1);
    expect(metas).toHaveLength(2);
    expect(metas[0]).toEqual({
      id: 'gutenberg:1342',
      type: 'book',
      name: 'Pride and Prejudice',
      poster: 'https://www.gutenberg.org/cache/epub/1342/pg1342.cover.medium.jpg',
      authors: ['Austen, Jane'],
      languages: ['en'],
      subjects: ['Courtship -- Fiction', 'England -- Fiction'],
    });
  });

  it('reads an openlibrary search payload through its shape', () => {
    const { metas, skipped } = parseCatalogPayload(loadFixture('openlibrary-search'), 'openlibrary');

    expect(skipped).toBe(1);
    expect(metas).toHaveLength(1);
    expect(metas[0]).toEqual({
      id: 'openlibrary:OL66554W',
      type: 'book',
      name: 'Pride and prejudice',
      poster: 'https://covers.openlibrary.org/b/id/8732464-M.jpg',
      releaseInfo: '1813',
      authors: ['Jane Austen'],
      languages: ['eng'],
      subjects: ['Courtship', 'Fiction'],
      isbn13: '9780141439518',
    });
  });

  it('contributes nothing for a shape this build does not implement', () => {
    for (const shape of ['standard-ebooks', 'librivox'] as const) {
      expect(parseCatalogPayload(loadFixture('catalog-raw'), shape)).toEqual({ metas: [], skipped: 0 });
    }
  });

  it('reads a legacy-v2 catalog payload through its shape', () => {
    const { metas, skipped } = parseCatalogPayload(loadFixture('legacy-v2-catalog'), 'legacy-v2');

    expect(skipped).toBe(1);
    expect(metas).toHaveLength(2);
    expect(metas[0]).toEqual({
      id: '1342',
      type: 'book',
      name: 'Pride and Prejudice',
      poster: 'https://www.gutenberg.org/cache/epub/1342/pg1342.cover.medium.jpg',
      authors: ['Jane Austen'],
      languages: ['en'],
      subjects: ['Courtship -- Fiction', 'England -- Fiction'],
    });
    // A non-https cover is dropped; the book still installs and lists.
    expect(metas[1]).toEqual({
      id: '84',
      type: 'book',
      name: 'Frankenstein; Or, The Modern Prometheus',
      authors: ['Shelley, Mary Wollstonecraft'],
      languages: ['en'],
      subjects: [],
    });
  });

  it('returns an empty result when a legacy-v2 body is not a results envelope', () => {
    for (const body of MALFORMED_BODIES) {
      expect(parseCatalogPayload(body, 'legacy-v2')).toEqual({ metas: [], skipped: 1 });
    }
    expect(parseCatalogPayload({ results: [] }, 'legacy-v2')).toEqual({ metas: [], skipped: 0 });
  });

  it('counts an unknown shape as one unreadable payload, never a throw', () => {
    const unknown = 'mystery' as AddonShape;
    expect(() => parseCatalogPayload(loadFixture('catalog-raw'), unknown)).not.toThrow();
    expect(parseCatalogPayload(loadFixture('catalog-raw'), unknown)).toEqual({
      metas: [],
      skipped: 1,
    });
    expect(() => parseMetaPayload(loadFixture('meta-raw'), unknown)).not.toThrow();
    expect(parseMetaPayload(loadFixture('meta-raw'), unknown)).toBeNull();
  });

  it('never throws for any body and shape combination', () => {
    for (const shape of SHAPES) {
      for (const body of MALFORMED_BODIES) {
        expect(() => parseCatalogPayload(body, shape)).not.toThrow();
      }
    }
  });
});

describe('parseMetaPayload', () => {
  it('reads a single meta from the envelope', () => {
    expect(parseMetaPayload(loadFixture('meta-raw'), 'raw')).toEqual({
      id: 'openlibrary:OL66554W',
      type: 'book',
      name: 'Pride and Prejudice',
      poster: 'https://covers.openlibrary.org/b/id/8732464-M.jpg',
      description: 'A novel of manners.',
      releaseInfo: '1813',
      authors: ['Jane Austen'],
      languages: ['en'],
      subjects: ['Courtship -- Fiction'],
      isbn13: '9780141439518',
    });
  });

  it('returns null for a malformed meta envelope', () => {
    for (const body of MALFORMED_BODIES) {
      expect(parseMetaPayload(body, 'raw')).toBeNull();
    }
  });

  it('returns null when the single entry is missing id or name', () => {
    expect(parseMetaPayload({ meta: { type: 'book', name: 'No id' } }, 'raw')).toBeNull();
    expect(parseMetaPayload({ meta: { id: 'gutenberg:1', type: 'book' } }, 'raw')).toBeNull();
    expect(parseMetaPayload({ meta: null }, 'raw')).toBeNull();
  });

  it('reads a gutendex book and an openlibrary work through their shapes', () => {
    const books = loadFixture('gutendex-books') as { results: unknown[] };
    expect(parseMetaPayload(books.results[1], 'gutendex')?.id).toBe('gutenberg:84');
    expect(parseMetaPayload(books.results[2], 'gutendex')).toBeNull();

    const work = parseMetaPayload(loadFixture('openlibrary-work'), 'openlibrary');
    expect(work?.id).toBe('openlibrary:OL66554W');
    expect(work?.description).toBe('A novel of manners.');
    expect(work?.releaseInfo).toBe('1813');
    expect(work?.poster).toBe('https://covers.openlibrary.org/b/id/8732464-M.jpg');
  });

  it('reads a legacy-v2 book object through its shape', () => {
    expect(parseMetaPayload(loadFixture('legacy-v2-meta'), 'legacy-v2')).toEqual({
      id: '1342',
      type: 'book',
      name: 'Pride and Prejudice',
      poster: 'https://www.gutenberg.org/cache/epub/1342/pg1342.cover.medium.jpg',
      authors: ['Jane Austen'],
      languages: ['en'],
      subjects: ['Courtship -- Fiction'],
    });
  });

  it('returns null for a legacy-v2 book without an id or a title', () => {
    expect(parseMetaPayload({ id: '1342' }, 'legacy-v2')).toBeNull();
    expect(parseMetaPayload({ title: 'Pride and Prejudice' }, 'legacy-v2')).toBeNull();
    expect(parseMetaPayload({ meta: loadFixture('legacy-v2-meta') }, 'legacy-v2')).toBeNull();
  });
});

describe('parseStreamPayload', () => {
  it('maps url streams to download and externalUrl streams to external', () => {
    const { streams, skipped } = parseStreamPayload(loadFixture('streams-raw'));

    expect(skipped).toBe(1);
    expect(streams).toHaveLength(3);
    expect(streams[0]).toEqual({
      source: 'download',
      url: 'https://standardebooks.org/ebooks/jane-austen/pride-and-prejudice/downloads/jane-austen_pride-and-prejudice.epub',
      name: 'Standard Ebooks',
      title: 'EPUB · Public domain',
      accessType: 'public-domain',
      format: 'epub',
    });
    expect(streams[1]).toEqual({
      source: 'external',
      url: 'https://openlibrary.org/borrow/ia/prideandprejudice00aust',
      name: 'Open Library',
      title: 'Borrow for 14 days',
      accessType: 'borrow',
      format: 'epub',
    });
    expect(streams[2]).toEqual({
      source: 'download',
      url: 'https://archive.org/download/prideandprejudice/prideandprejudice.pdf?token=abc',
      name: 'Archive scan',
      accessType: 'unknown',
      format: 'pdf',
    });
  });

  it('drops every infoHash stream and counts it as skipped', () => {
    const body = {
      streams: [
        { url: 'https://example.org/book.epub' },
        { url: 'https://example.org/a.epub', infoHash: 'aaa' },
        { url: 'https://example.org/b.epub', infoHash: 'bbb', sources: ['tracker:udp://x'] },
        { url: 'https://example.org/c.epub', infoHash: null },
      ],
    };

    const { streams, skipped } = parseStreamPayload(body);

    expect(streams).toHaveLength(1);
    expect(streams[0]?.url).toBe('https://example.org/book.epub');
    expect(skipped).toBe(3);
  });

  it('drops a stream whose behaviorHints declare bittorrent', () => {
    const { streams, skipped } = parseStreamPayload({
      streams: [
        { url: 'https://example.org/a.epub', behaviorHints: { bittorrent: { infoHash: 'aaa' } } },
        { url: 'https://example.org/b.epub', behaviorHints: { accessType: 'borrow' } },
      ],
    });

    expect(streams).toHaveLength(1);
    expect(skipped).toBe(1);
  });

  it('drops a stream with sources of type torrent', () => {
    const { streams, skipped } = parseStreamPayload({
      streams: [
        { url: 'https://example.org/a.epub', sources: [{ type: 'torrent' }] },
        { url: 'https://example.org/b.epub', sources: [{ type: 'TORRENT' }] },
        { url: 'https://example.org/c.epub', sources: [{ type: 'web' }] },
      ],
    });

    expect(streams.map((entry) => entry.url)).toEqual(['https://example.org/c.epub']);
    expect(skipped).toBe(2);
  });

  it('drops a non http link even when it is the only link present', () => {
    const { streams, skipped } = parseStreamPayload({
      streams: [
        { url: 'magnet:?xt=urn:btih:8f2a1c4b' },
        { externalUrl: 'magnet:?xt=urn:btih:8f2a1c4b' },
        { url: 'ftp://example.org/book.epub' },
      ],
    });

    expect(streams).toEqual([]);
    expect(skipped).toBe(3);
  });

  it('infers format from the url extension, defaulting to epub', () => {
    const { streams } = parseStreamPayload({
      streams: [
        { url: 'https://example.org/book.pdf' },
        { url: 'https://example.org/book.PDF?download=1#page=2' },
        { url: 'https://example.org/book.epub' },
        { externalUrl: 'https://openlibrary.org/borrow/ia/somebook' },
        { url: 'https://example.org/book.mobi', behaviorHints: { format: 'pdf' } },
      ],
    });

    expect(streams.map((entry) => entry.format)).toEqual(['pdf', 'pdf', 'epub', 'epub', 'pdf']);
  });

  it('reads accessType from behaviorHints and defaults to unknown', () => {
    const { streams } = parseStreamPayload({
      streams: [
        { url: 'https://example.org/a.epub', behaviorHints: { accessType: 'public-domain' } },
        { url: 'https://example.org/b.epub', behaviorHints: { accessType: 'borrow' } },
        { url: 'https://example.org/c.epub', behaviorHints: { accessType: 'purchase' } },
        { url: 'https://example.org/d.epub', behaviorHints: { accessType: 'rented' } },
        { url: 'https://example.org/e.epub' },
      ],
    });

    expect(streams.map((entry) => entry.accessType)).toEqual([
      'public-domain',
      'borrow',
      'purchase',
      'unknown',
      'unknown',
    ]);
  });

  it('caps streams at 100 and counts the overflow as skipped', () => {
    const body = {
      streams: Array.from({ length: 150 }, (_, index) => ({
        url: `https://example.org/${index}.epub`,
      })),
    };

    const { streams, skipped } = parseStreamPayload(body);

    expect(streams).toHaveLength(100);
    expect(skipped).toBe(50);
  });

  it('returns an empty result for a malformed stream envelope instead of throwing', () => {
    for (const body of MALFORMED_BODIES) {
      expect(() => parseStreamPayload(body)).not.toThrow();
      expect(parseStreamPayload(body)).toEqual({ streams: [], skipped: 1 });
    }
  });

  it('a TPB-shaped addon contributes zero options and zero errors', () => {
    const body = loadFixture('tpb-torrents') as { streams: unknown[] };
    expect(body.streams).toHaveLength(3);

    const result = parseStreamPayload(body);

    expect(result.streams).toHaveLength(0);
    expect(result.skipped).toBe(3);
  });
});

describe('assertDeliverableStream', () => {
  it('accepts a plain download or external link', () => {
    expect(assertDeliverableStream({ url: 'https://example.org/book.epub' })).toEqual({
      source: 'download',
      url: 'https://example.org/book.epub',
      accessType: 'unknown',
      format: 'epub',
    });
    expect(assertDeliverableStream({ externalUrl: 'https://example.org/borrow/1' })).toEqual({
      source: 'external',
      url: 'https://example.org/borrow/1',
      accessType: 'unknown',
      format: 'epub',
    });
  });

  it('rejects every torrent identity and every entry without a usable link', () => {
    const rejected: unknown[] = [
      null,
      undefined,
      'https://example.org/book.epub',
      [],
      {},
      { name: 'No link' },
      { url: '' },
      { url: 42 },
      { infoHash: 'aaa' },
      { infoHash: 'aaa', url: 'https://example.org/book.epub' },
      { infoHash: null, url: 'https://example.org/book.epub' },
      { url: 'https://example.org/book.epub', behaviorHints: { bittorrent: {} } },
      { url: 'https://example.org/book.epub', behaviorHints: { bittorrent: null } },
      { url: 'https://example.org/book.epub', sources: [{ type: 'torrent' }] },
      { url: 'https://example.org/book.epub', sources: [{ type: ' Torrent ' }] },
      { url: 'magnet:?xt=urn:btih:aaa' },
      { externalUrl: 'magnet:?xt=urn:btih:aaa' },
      { url: 'data:text/plain,hello' },
    ];

    for (const raw of rejected) {
      expect(assertDeliverableStream(raw)).toBeNull();
    }
  });

  it('lets a valid stream through when behaviorHints carry unrelated keys', () => {
    expect(
      assertDeliverableStream({
        url: 'https://example.org/book.epub',
        behaviorHints: { bingeGroup: 'x', proxyHeaders: {} },
      }),
    ).not.toBeNull();
  });
});

describe('orderStreams', () => {
  it('orders options public-domain first, then epub, then addon order', () => {
    const publicEpubLate = stream({ accessType: 'public-domain', addonId: 'zz', addedAt: 200 });
    const publicEpubEarly = stream({ accessType: 'public-domain', addonId: 'mm', addedAt: 100 });
    const publicPdf = stream({ accessType: 'public-domain', format: 'pdf', addonId: 'cc', addedAt: 3 });
    const borrow = stream({ accessType: 'borrow', addonId: 'bb', addedAt: 2 });
    const purchase = stream({ accessType: 'purchase', addonId: 'aa', addedAt: 1 });
    const unknown = stream({ accessType: 'unknown', addonId: 'dd', addedAt: 4 });

    const ordered = orderStreams([unknown, purchase, publicPdf, borrow, publicEpubLate, publicEpubEarly]);

    expect(ordered.map((entry) => `${entry.accessType}/${entry.format}/${entry.addonId}`)).toEqual([
      'public-domain/epub/mm',
      'public-domain/epub/zz',
      'public-domain/pdf/cc',
      'borrow/epub/bb',
      'purchase/epub/aa',
      'unknown/epub/dd',
    ]);
  });

  it('breaks the addon order tie with the addon id', () => {
    const ordered = orderStreams([
      stream({ accessType: 'public-domain', addonId: 'b-addon', addedAt: 10 }),
      stream({ accessType: 'public-domain', addonId: 'a-addon', addedAt: 10 }),
    ]);

    expect(ordered.map((entry) => entry.addonId)).toEqual(['a-addon', 'b-addon']);
  });

  it('is stable and leaves the input untouched', () => {
    const first = stream({ accessType: 'borrow', addonId: 'same' });
    const second = stream({ accessType: 'borrow', addonId: 'same' });
    const input = [first, second];

    const ordered = orderStreams(input);

    expect(ordered).not.toBe(input);
    expect(ordered[0]).toBe(first);
    expect(ordered[1]).toBe(second);
    expect(orderStreams(input).map((entry) => entry.url)).toEqual(['https://example.org/book.epub', 'https://example.org/book.epub']);
  });
});

describe('shape modules', () => {
  it('counts native entries they cannot read', () => {
    expect(shapeGutendexCatalog(null)).toEqual({ metas: [], skipped: 1 });
    expect(shapeOpenlibraryCatalog({ docs: 'nope' })).toEqual({ metas: [], skipped: 1 });
  });

  it('return null for a single unreadable native entry', () => {
    expect(shapeGutendexMeta({})).toBeNull();
    expect(shapeOpenlibraryMeta({ key: '/works/OL1W' })).toBeNull();
  });
});
