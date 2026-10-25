/**
 * End-to-end download proof: resolve a known public-domain book, take its EPUB
 * URL, fetch it, and assert the bytes are a real EPUB rather than an HTML error
 * page or a login redirect.
 *
 * Two variants, deliberately split:
 *
 * - The OFFLINE variant runs in CI on every push. It builds a minimal but real
 *   ZIP container in memory and runs the SAME byte-level assertions the live
 *   download runs, so the header/EOF checks are exercised deterministically
 *   instead of only on a machine that opted in.
 * - The NETWORK variant is opt-in via `NEXTPAGE_LIVE_DOWNLOAD=1`, so CI stays
 *   deterministic and never depends on gutenberg.org being up, fast, or willing
 *   to serve a 24 MB file.
 */
import { describe, expect, it } from 'vitest';
import {
  BUILTIN_GUTENDEX,
  GutendexCatalogProvider,
  GutendexDataSource,
} from '$lib/shared/services/catalog';
import { GUTENDEX_BOOK_1342 } from '../../../fixtures-recorded/catalogPayloads';

/** `PK\x03\x04`: the ZIP local-file header every EPUB starts with. */
const ZIP_LOCAL_FILE_HEADER = [0x50, 0x4b, 0x03, 0x04];
/** End-of-central-directory record: `PK\x05\x06`, what makes a ZIP complete. */
const ZIP_EOCD = [0x50, 0x4b, 0x05, 0x06];
/** A real book is never a few hundred bytes; this rejects an error page. */
const MIN_EPUB_BYTES = 10_000;

const liveFlag = process.env.NEXTPAGE_LIVE_DOWNLOAD === '1';
/** Opt-in only: set `NEXTPAGE_LIVE_DOWNLOAD=1` to run these. */
const liveIt = liveFlag ? it : it.skip;

/** Assert the buffer is a structurally plausible EPUB (ZIP container). */
function expectRealEpub(buffer: Uint8Array, label: string): void {
  expect(buffer.length, `${label}: EPUB size`).toBeGreaterThan(MIN_EPUB_BYTES);
  expect(Array.from(buffer.subarray(0, 4)), `${label}: ZIP local-file header`).toEqual(
    ZIP_LOCAL_FILE_HEADER,
  );
  // A ZIP ends with its central directory; the record sits in the last 64 KiB.
  const tail = buffer.subarray(Math.max(0, buffer.length - 65_536));
  const hasEocd = tail.some(
    (_, i) =>
      tail[i] === ZIP_EOCD[0] &&
      tail[i + 1] === ZIP_EOCD[1] &&
      tail[i + 2] === ZIP_EOCD[2] &&
      tail[i + 3] === ZIP_EOCD[3],
  );
  expect(hasEocd, `${label}: ZIP end-of-central-directory record`).toBe(true);
}

/**
 * A minimal stored-entry ZIP: one local file header, the payload, then the
 * end-of-central-directory record. Enough structure for the EPUB checks above
 * without pulling a zip library into the assertion it is supposed to prove.
 */
function buildZip(payload: Uint8Array): Uint8Array {
  const name = new TextEncoder().encode('mimetype');
  const local = new Uint8Array(30 + name.length);
  const localView = new DataView(local.buffer);
  local.set([0x50, 0x4b, 0x03, 0x04], 0);
  localView.setUint16(4, 20, true); // version needed
  localView.setUint16(6, 0, true); // flags
  localView.setUint16(8, 0, true); // method: stored
  localView.setUint32(18, payload.length, true); // compressed size
  localView.setUint32(22, payload.length, true); // uncompressed size
  localView.setUint16(26, name.length, true);
  local.set(name, 30);

  const eocd = new Uint8Array(22);
  const eocdView = new DataView(eocd.buffer);
  eocd.set(ZIP_EOCD, 0);
  eocdView.setUint16(10, 1, true); // total entries
  eocdView.setUint16(8, 1, true); // entries on this disk

  const out = new Uint8Array(local.length + payload.length + eocd.length);
  out.set(local, 0);
  out.set(payload, local.length);
  out.set(eocd, local.length + payload.length);
  return out;
}

describe('discover download — recorded fixture (runs offline in CI)', () => {
  it('resolves book 1342 from the recorded Gutendex payload to an https EPUB url', async () => {
    const fetchFn = (async () =>
      ({
        ok: true,
        status: 200,
        json: async () => JSON.parse(GUTENDEX_BOOK_1342),
        text: async () => GUTENDEX_BOOK_1342,
      }) as unknown as Response) as unknown as typeof fetch;

    const provider = new GutendexCatalogProvider(new GutendexDataSource(fetchFn));
    const book = await provider.getDetails('gutendex:1342');
    expect(book.title).toContain('Pride and Prejudice');
    expect(book.downloadUrl).toBe('https://www.gutenberg.org/ebooks/1342.epub3.images');
    expect(book.downloadUrl?.startsWith('https://')).toBe(true);
  });

  it('accepts the downloaded bytes when they are a real EPUB', () => {
    // A ~12 KB payload keeps the fixture inside the "non-trivial size" window.
    const payload = new TextEncoder().encode('x'.repeat(12_000));
    expectRealEpub(buildZip(payload), 'offline synthetic');
  });

  it('rejects an HTML error body of the same size, because the header decides', () => {
    const html = new TextEncoder().encode(
      `<!doctype html><html><body>${'<p>Book not found</p>'.repeat(1_000)}</body></html>`,
    );
    expect(html.length).toBeGreaterThan(MIN_EPUB_BYTES);
    expect([...html.subarray(0, 4)]).not.toEqual(ZIP_LOCAL_FILE_HEADER);
  });

  it('rejects a truncated ZIP that lacks the end-of-central-directory record', () => {
    const headerOnly = buildZip(new TextEncoder().encode('x'.repeat(12_000))).subarray(0, 2_000);
    expect([...headerOnly.subarray(0, 4)]).toEqual(ZIP_LOCAL_FILE_HEADER);
    const tail = headerOnly;
    const hasEocd = tail.some(
      (_, i) =>
        tail[i] === ZIP_EOCD[0] &&
        tail[i + 1] === ZIP_EOCD[1] &&
        tail[i + 2] === ZIP_EOCD[2] &&
        tail[i + 3] === ZIP_EOCD[3],
    );
    expect(hasEocd).toBe(false);
  });
});

describe('discover download — live network (opt-in)', () => {
  liveIt(
    'live-download: Gutendex 1342 EPUB fetch returns real ZIP bytes',
    async () => {
      const provider = new GutendexCatalogProvider();
      const book = await provider.getDetails('gutendex:1342');
      const url = book.downloadUrl;
      expect(url).toMatch(/^https:\/\//);

      // Follow the gutenberg.org redirect chain the same way the app does.
      const res = await fetch(url!, { redirect: 'follow' });
      expect(res.status).toBe(200);
      expectRealEpub(new Uint8Array(await res.arrayBuffer()), 'live gutenberg');
    },
    // A 24 MB download on a slow link is not a test failure.
    120_000,
  );

  liveIt(
    'live-download: the Gutendex popular ordering carries cover-bearing books',
    async () => {
      // Through the featured rail path, which is what the DISCOVER popular rail
      // actually calls (`?sort=popular`); `searchSource(…, '')` would issue an
      // unfiltered `?search=` full scan that takes ~64s.
      const page = await new GutendexCatalogProvider().featured('POPULAR', 6);
      expect(page.results.length).toBeGreaterThan(0);
      expect(page.results.every((book) => book.coverUrl !== null)).toBe(true);
      expect(page.results.every((book) => book.downloadUrl !== null)).toBe(true);
    },
    60_000,
  );

  liveIt(
    'live-download: Standard Ebooks Atom feed parses into downloadable books',
    async () => {
      const { BUILTIN_STANDARD_EBOOKS, StandardEbooksCatalogProvider, StandardEbooksDataSource } =
        await import('$lib/shared/services/catalog');
      const page = await new StandardEbooksCatalogProvider(
        new StandardEbooksDataSource(fetch),
      ).newest(6);
      expect(page.results.length).toBeGreaterThan(0);
      for (const book of page.results) {
        expect(book.id).toMatch(/^standard-ebooks:/);
        expect(book.downloadUrl).toMatch(/^https:\/\/standardebooks\.org\/.*\.epub/);
        expect(book.coverUrl).toMatch(/^https:\/\//);
      }
      // The first EPUB really is an EPUB, not an HTML landing page.
      const res = await fetch(page.results[0]!.downloadUrl!, { redirect: 'follow' });
      expect(res.status).toBe(200);
      const bytes = new Uint8Array(await res.arrayBuffer());
      expect(bytes.length).toBeGreaterThan(MIN_EPUB_BYTES);
      expect([...bytes.subarray(0, 4)]).toEqual(ZIP_LOCAL_FILE_HEADER);
      // The source id is what the rail routes through.
      expect(page.results[0]!.provider).toBe(BUILTIN_STANDARD_EBOOKS);
    },
    120_000,
  );
});

/** Keep the Gutendex fixture honest: it really is the 1342 record. */
describe('discover download — fixture provenance', () => {
  it('records the id and the epub format for book 1342', () => {
    const record = JSON.parse(GUTENDEX_BOOK_1342) as {
      id: number;
      formats: Record<string, string>;
    };
    expect(record.id).toBe(1342);
    expect(record.formats['application/epub+zip']).toBe(
      'https://www.gutenberg.org/ebooks/1342.epub3.images',
    );
  });
});
