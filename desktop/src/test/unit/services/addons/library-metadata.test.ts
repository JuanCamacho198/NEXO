/**
 * Library metadata enrichment on the desktop side.
 *
 * A metadata addon (`resources: ["catalog", "meta"]`, `idPrefixes: ["isbn:"]`)
 * can only describe a book the reader already owns if this client asks about
 * it. These tests pin the four things that decide whether that happens: the
 * identifiers the book carries, the addon's declared prefixes, the separate
 * `library_meta` consent scope, and the fill-only merge that must never
 * overwrite what the reader already has.
 */
import { describe, expect, it, vi } from 'vitest';

import { TtlCache, type AddonClient, type AddonDescriptor } from '@nexo/addon-protocol';
import {
  LibraryMetadataService,
  libraryMetaCacheKey,
  type LibraryMetadataPersistence,
} from '$lib/shared/services/addons/libraryMetadata';

const ISBN = '9780141439518';
const CANDIDATE = `isbn:${ISBN}`;

function manifest(
  overrides: Partial<AddonDescriptor['manifest']> = {},
): AddonDescriptor['manifest'] {
  return {
    id: 'org.example.metadata',
    version: '3.0.0',
    name: 'Metadata authority',
    types: ['book'],
    catalogs: [],
    resources: [{ name: 'catalog' }, { name: 'meta', types: ['book'], idPrefixes: ['isbn:'] }],
    ...overrides,
  };
}

function addon(
  id = 'org.example.metadata',
  overrides: Partial<AddonDescriptor> = {},
): AddonDescriptor {
  return {
    id,
    installUrl: `https://${id}.example/manifest.json`,
    addedAt: 1,
    enabled: true,
    manifest: manifest({ id }),
    ...overrides,
  };
}

interface FakeClient {
  client: AddonClient;
  getMeta: ReturnType<typeof vi.fn>;
}

function fakeClient(
  meta: Record<string, unknown> | null = {
    id: CANDIDATE,
    type: 'book',
    name: 'Pride and Prejudice',
    description: 'A novel of manners.',
    releaseInfo: '1813',
    languages: ['en'],
    subjects: ['Fiction'],
    poster: 'https://covers.example.org/pride.jpg',
  },
): FakeClient {
  const getMeta = vi.fn(async () => ({
    items: meta === null ? [] : [meta],
    failures: [],
    skipped: 0,
  }));
  return {
    getMeta,
    client: {
      getMeta,
      searchCatalog: vi.fn(),
      getStreams: vi.fn(),
    } as unknown as AddonClient,
  };
}

function book(overrides: Record<string, unknown> = {}): {
  id: string;
  title: string;
  description?: string | null;
  publisher?: string | null;
  publishedDate?: string | null;
  language?: string | null;
  genre?: string | null;
  tags?: readonly string[] | null;
  coverPath?: string | null;
  sourceItemId?: string | null;
  isbn13?: string | null;
} {
  return {
    id: 'book-1',
    title: 'Pride and Prejudice',
    description: null,
    publisher: null,
    publishedDate: null,
    language: null,
    genre: null,
    tags: null,
    coverPath: null,
    sourceItemId: null,
    isbn13: ISBN,
    ...overrides,
  };
}

function persistence(): Required<LibraryMetadataPersistence> & {
  updateFields: ReturnType<typeof vi.fn>;
  updateCover: ReturnType<typeof vi.fn>;
} {
  return {
    updateFields: vi.fn(async () => {}),
    updateCover: vi.fn(async () => {}),
  };
}

function service(
  overrides: {
    addons?: AddonDescriptor[];
    client?: FakeClient;
    consent?: { ensureLoaded: () => Promise<void>; hasLibraryMetaConsent: (id: string) => boolean };
    cache?: TtlCache;
    now?: () => number;
    persist?: LibraryMetadataPersistence;
    fetchBytes?: (url: string) => Promise<{
      status: number;
      contentType: string | null;
      body: Uint8Array;
    }>;
  } = {},
): {
  enrich: LibraryMetadataService['enrich'];
  fake: FakeClient;
  persist: ReturnType<typeof persistence>;
} {
  const fake = overrides.client ?? fakeClient();
  const persist = persistence();
  const instance = new LibraryMetadataService({
    loadAddons: async () => overrides.addons ?? [addon()],
    clientFor: () => fake.client,
    consent: overrides.consent ?? {
      ensureLoaded: async () => {},
      hasLibraryMetaConsent: () => true,
    },
    cache: overrides.cache,
    now: overrides.now,
    persist: overrides.persist ?? persist,
    fetchBytes:
      overrides.fetchBytes ??
      (async () => ({
        status: 200,
        contentType: 'image/jpeg',
        body: new Uint8Array([1, 2, 3]),
      })),
  });
  return { enrich: instance.enrich.bind(instance), fake, persist };
}

describe('LibraryMetadataService', () => {
  it('a book with no namespaced id and no isbn is skipped without a request', async () => {
    const { enrich, fake, persist } = service();

    const result = await enrich(book({ isbn13: null, sourceItemId: null }));

    expect(result.candidates).toEqual([]);
    expect(result.changed).toEqual({});
    expect(fake.getMeta).not.toHaveBeenCalled();
    expect(persist.updateFields).not.toHaveBeenCalled();
  });

  it('an addon declaring meta with no prefixes is not consulted', async () => {
    const { enrich, fake } = service({
      addons: [
        addon('org.example.noprefix', {
          manifest: manifest({
            id: 'org.example.noprefix',
            resources: [{ name: 'catalog' }, { name: 'meta', types: ['book'] }],
          }),
        }),
      ],
    });

    const result = await enrich(book());

    expect(result.changed).toEqual({});
    expect(fake.getMeta).not.toHaveBeenCalled();
  });

  it('no library_meta consent means zero requests', async () => {
    const { enrich, fake } = service({
      consent: {
        ensureLoaded: async () => {},
        hasLibraryMetaConsent: () => false,
      },
    });

    const result = await enrich(book());

    expect(result.changed).toEqual({});
    expect(fake.getMeta).not.toHaveBeenCalled();
  });

  it('fills the blank fields and persists exactly those', async () => {
    const { enrich, persist } = service();

    const result = await enrich(book());

    expect(result.changed).toEqual({
      description: 'A novel of manners.',
      publishedDate: '1813',
      language: 'en',
      genre: 'Fiction',
      tags: ['Fiction'],
      coverUrl: 'https://covers.example.org/pride.jpg',
    });
    expect(persist.updateFields).toHaveBeenCalledWith(
      'book-1',
      expect.objectContaining({
        description: 'A novel of manners.',
        language: 'en',
        genre: 'Fiction',
      }),
    );
  });

  it('a book that already has a description and a cover is not overwritten', async () => {
    const { enrich, persist } = service();

    const result = await enrich(
      book({ description: 'What the reader already has.', coverPath: 'C:/covers/book-1.png' }),
    );

    expect(result.changed.description).toBeUndefined();
    expect(result.changed.coverUrl).toBeUndefined();
    expect(persist.updateFields).toHaveBeenCalledWith(
      'book-1',
      expect.not.objectContaining({ description: expect.anything() }),
    );
    expect(persist.updateCover).not.toHaveBeenCalled();
  });

  it('writes a missing cover through the import cover path', async () => {
    const { enrich, persist } = service();

    await enrich(book({ description: 'Set.' }));

    expect(persist.updateCover).toHaveBeenCalledWith('book-1', {
      bytes: new Uint8Array([1, 2, 3]),
      contentType: 'image/jpeg',
    });
  });

  it('a cover download that fails leaves the fields written', async () => {
    const { enrich, persist } = service({
      fetchBytes: async () => ({ status: 404, contentType: null, body: new Uint8Array() }),
    });

    const result = await enrich(book());

    expect(result.changed.language).toBe('en');
    expect(persist.updateCover).not.toHaveBeenCalled();
  });

  it('a failing addon is silent: no throw, nothing written', async () => {
    const failing = fakeClient();
    failing.getMeta.mockRejectedValue(new Error('addon is down'));

    const { enrich, persist } = service({ client: failing });

    await expect(enrich(book())).resolves.toMatchObject({ changed: {} });
    expect(persist.updateFields).not.toHaveBeenCalled();
  });

  it('caches one response per (addonId, candidateId) for 24 hours', async () => {
    let now = 1_000;
    const fake = fakeClient();
    const cache = new TtlCache({ now: () => now });
    const { enrich } = service({ client: fake, cache, now: () => now });

    await enrich(book());
    await enrich(book());
    expect(fake.getMeta).toHaveBeenCalledTimes(1);

    now += 24 * 60 * 60 * 1000 - 1;
    await enrich(book());
    expect(fake.getMeta).toHaveBeenCalledTimes(1);

    now += 2;
    await enrich(book());
    expect(fake.getMeta).toHaveBeenCalledTimes(2);
  });

  it('a manual refresh bypasses the cached response and warms the stable key', async () => {
    const fake = fakeClient();
    const cache = new TtlCache({ now: () => 1_000 });
    const { enrich } = service({ client: fake, cache, now: () => 1_000 });

    await enrich(book());
    expect(fake.getMeta).toHaveBeenCalledTimes(1);

    await enrich(book(), { refresh: true });
    expect(fake.getMeta).toHaveBeenCalledTimes(2);

    // The refresh wrote the stable key too, so the next read costs no request.
    await enrich(book());
    expect(fake.getMeta).toHaveBeenCalledTimes(2);
    expect(cache.get(libraryMetaCacheKey('org.example.metadata', CANDIDATE))).toBeDefined();
  });

  it('merges addons in install order, first non-blank value winning', async () => {
    const primary = fakeClient({
      id: CANDIDATE,
      type: 'book',
      name: 'Pride and Prejudice',
      description: 'First authority.',
    });
    const secondary = fakeClient({
      id: CANDIDATE,
      type: 'book',
      name: 'Pride and Prejudice',
      description: 'Second authority.',
      languages: ['es'],
    });
    const clients = new Map([
      ['org.example.first', primary.client],
      ['org.example.second', secondary.client],
    ]);
    const persist = persistence();
    const instance = new LibraryMetadataService({
      loadAddons: async () => [
        addon('org.example.first', { addedAt: 1 }),
        addon('org.example.second', { addedAt: 2 }),
      ],
      clientFor: (id) => clients.get(id) as AddonClient,
      consent: { ensureLoaded: async () => {}, hasLibraryMetaConsent: () => true },
      persist,
    });

    const result = await instance.enrich(book());

    expect(result.changed.description).toBe('First authority.');
    expect(result.changed.language).toBe('es');
    expect(persist.updateFields).toHaveBeenCalledTimes(1);
  });

  /**
   * Desktop stores an addon import under the namespaced catalog id as its LOCAL
   * book id, so a row whose `source_item_id` never landed still holds the very
   * identity the addon minted. Without this fallback every library book reported
   * zero candidates and no metadata addon was ever asked.
   */
  it('falls back to a namespaced local id when source_item_id is blank', async () => {
    const fake = fakeClient({
      id: 'gutenberg:1342',
      type: 'book',
      name: 'Pride and Prejudice',
      description: 'A novel of manners.',
    });
    const { enrich } = service({
      client: fake,
      addons: [
        addon('org.example.gutendex', {
          manifest: manifest({
            id: 'org.example.gutendex',
            resources: [{ name: 'meta', types: ['book'], idPrefixes: ['gutenberg:'] }],
          }),
        }),
      ],
    });

    const result = await enrich(book({ id: 'gutenberg:1342', isbn13: null, sourceItemId: null }));

    expect(result.candidates).toEqual(['gutenberg:1342']);
    expect(fake.getMeta).toHaveBeenCalledWith([expect.anything()], { id: 'gutenberg:1342' });
    expect(result.changed.description).toBe('A novel of manners.');
  });

  it('never guesses an identity from an unprefixed or blank local id', async () => {
    const { enrich, fake } = service();

    const unprefixed = await enrich(book({ id: 'book-1', isbn13: null, sourceItemId: null }));
    const blank = await enrich(book({ id: '   ', isbn13: null, sourceItemId: '   ' }));

    expect(unprefixed.candidates).toEqual([]);
    expect(blank.candidates).toEqual([]);
    expect(fake.getMeta).not.toHaveBeenCalled();
  });

  it('a recorded source_item_id still wins over the local id', async () => {
    const { enrich, fake } = service();

    const result = await enrich(book({ id: 'gutenberg:1342', sourceItemId: `isbn:${ISBN}` }));

    expect(result.candidates).toEqual([CANDIDATE]);
    expect(fake.getMeta).toHaveBeenCalledWith([expect.anything()], { id: CANDIDATE });
  });
});
