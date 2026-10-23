/**
 * Desktop collection service: import/export a set through the protocol package,
 * fetching the collection JSON through the platform transport and installing the
 * entries through the real registry.
 *
 * Rules pinned here (task 15): HTTPS-only transport urls, per-entry validation,
 * `flags` dropped, duplicates collapsed, re-import preserving enabled/addedAt,
 * and — the one that matters most — a collection never pre-grants `stream`
 * consent.
 */
import { describe, expect, it, vi } from 'vitest';

import { AddonFetchError, AddonFetchErrorCode } from '$lib/shared/services/addons/AddonRegistry';
import { AddonRegistry } from '$lib/shared/services/addons/AddonRegistry';
import { addonConsent } from '$lib/shared/services/addons/AddonConsent';
import { addonIdFromUrl } from '$lib/shared/services/addons/addonId';
import {
  MAX_COLLECTION_ENTRIES,
  fetchCollection,
  installCollection,
  parseCollection,
  requestsStream,
  serializeCollection,
} from '$lib/shared/services/addons/collection';

const CATALOG_URL = 'https://catalog.example/manifest.json';
const STREAM_URL = 'https://streams.example/manifest.json';

function manifestJson(
  id: string,
  overrides: Record<string, unknown> = {},
): Record<string, unknown> {
  return {
    id,
    version: '3.0.0',
    name: id,
    types: ['book'],
    catalogs: [{ type: 'book', id: 'popular', name: 'Popular' }],
    resources: ['catalog', 'meta'],
    ...overrides,
  };
}

function streamManifestJson(id: string): Record<string, unknown> {
  return {
    id,
    version: '3.0.0',
    name: id,
    types: ['book'],
    catalogs: [],
    resources: [{ name: 'stream', types: ['book'] }],
  };
}

function entry(transportUrl: string, manifest: unknown): Record<string, unknown> {
  return { transportUrl, transportName: 'http', manifest, flags: {} };
}

function collectionBody(addons: unknown[]): Uint8Array {
  return new TextEncoder().encode(JSON.stringify({ addons }));
}

function collectionTransport(body: Uint8Array, status = 200) {
  return vi.fn(async () => ({ status, contentType: 'application/json', body }));
}

interface FakeRow {
  id: string;
  url: string;
  manifestJson: string;
  enabled: boolean;
  addedAt: number;
}

/** Mirrors the Rust UPSERT: every column is written, so preservation must be real. */
function fakeStore(seed: FakeRow[] = []) {
  const rows: FakeRow[] = seed.map((row) => ({ ...row }));
  return {
    rows,
    async listInstalled(): Promise<FakeRow[]> {
      return rows.map((row) => ({ ...row }));
    },
    async upsert(row: FakeRow): Promise<void> {
      const index = rows.findIndex((existing) => existing.id === row.id);
      if (index >= 0) rows[index] = { ...row };
      else rows.push({ ...row });
    },
    async setEnabled(id: string, enabled: boolean): Promise<void> {
      const row = rows.find((existing) => existing.id === id);
      if (row) row.enabled = enabled;
    },
    async uninstall(id: string): Promise<void> {
      const index = rows.findIndex((existing) => existing.id === id);
      if (index >= 0) rows.splice(index, 1);
    },
  };
}

describe('fetchCollection', () => {
  it('parses the Stremio collection shape through the platform transport', async () => {
    const transport = collectionTransport(
      collectionBody([
        entry(CATALOG_URL, manifestJson('org.example.catalog')),
        entry(STREAM_URL, streamManifestJson('org.example.streams')),
      ]),
    );
    const parsed = await fetchCollection(transport, 'https://collections.example/books.json');
    expect(transport).toHaveBeenCalledWith('https://collections.example/books.json');
    expect(parsed.skipped).toBe(0);
    expect(parsed.entries.map((item) => item.transportUrl)).toEqual([CATALOG_URL, STREAM_URL]);
    expect(parsed.entries.map((item) => requestsStream(item.manifest))).toEqual([false, true]);
  });

  it('refuses a non-https collection link before any fetch', async () => {
    const transport = collectionTransport(collectionBody([]));
    await expect(
      fetchCollection(transport, 'http://collections.example/books.json'),
    ).rejects.toMatchObject({ code: AddonFetchErrorCode.HTTPS_REQUIRED });
    expect(transport).not.toHaveBeenCalled();
  });

  it('reports a non-2xx response as a network failure', async () => {
    const transport = collectionTransport(collectionBody([]), 503);
    await expect(
      fetchCollection(transport, 'https://collections.example/books.json'),
    ).rejects.toBeInstanceOf(AddonFetchError);
  });

  it('skips an http: entry, a non-object entry and a malformed manifest', async () => {
    const parsed = await fetchCollection(
      collectionTransport(
        collectionBody([
          entry('http://plain.example/manifest.json', manifestJson('org.example.plain')),
          'not an entry',
          entry('https://bad.example/manifest.json', { id: 'org.example.bad' }),
          entry(CATALOG_URL, manifestJson('org.example.catalog')),
        ]),
      ),
      'https://collections.example/books.json',
    );
    expect(parsed.skipped).toBe(3);
    expect(parsed.entries.map((item) => item.transportUrl)).toEqual([CATALOG_URL]);
  });

  it(`caps at ${MAX_COLLECTION_ENTRIES} entries and counts the overflow as skipped`, async () => {
    const addons = Array.from({ length: MAX_COLLECTION_ENTRIES + 1 }, (_unused, index) =>
      entry(`https://addon-${index}.example/m.json`, manifestJson(`org.example.a${index}`)),
    );
    const parsed = await fetchCollection(
      collectionTransport(collectionBody(addons)),
      'https://collections.example/books.json',
    );
    expect(parsed.entries).toHaveLength(MAX_COLLECTION_ENTRIES);
    expect(parsed.skipped).toBe(1);
  });

  it('ignores flags: a collection cannot promote an addon or record consent', async () => {
    const parsed = await fetchCollection(
      collectionTransport(
        collectionBody([
          {
            transportUrl: STREAM_URL,
            manifest: streamManifestJson('org.example.streams'),
            flags: { official: true, trusted: true, consent: true, streamConsent: true },
          },
        ]),
      ),
      'https://collections.example/books.json',
    );
    expect(parsed.entries).toHaveLength(1);
    expect(Object.keys(parsed.entries[0]).sort()).toEqual(['manifest', 'transportUrl']);
  });

  it('treats a body that is not a collection as an empty set', async () => {
    const parsed = await fetchCollection(
      collectionTransport(new TextEncoder().encode('not json')),
      'https://collections.example/books.json',
    );
    expect(parsed).toEqual({ entries: [], skipped: 0 });
  });
});

describe('installCollection', () => {
  function registryWith(store = fakeStore()) {
    return new AddonRegistry({
      store,
      transport: async () => ({
        status: 200,
        contentType: 'application/json',
        body: new Uint8Array(),
      }),
      now: () => 2_000,
    });
  }

  it('installs every entry through the registry', async () => {
    const registry = registryWith();
    const parsed = parseCollection({
      addons: [
        entry(CATALOG_URL, manifestJson('org.example.catalog')),
        entry(STREAM_URL, streamManifestJson('org.example.streams')),
      ],
    });
    const result = await installCollection(registry, parsed.entries);
    expect(result).toEqual({ installed: 2, failed: 0 });

    const rows = await registry.listInstalled();
    expect(rows.map((row) => row.url)).toEqual([CATALOG_URL, STREAM_URL]);
  });

  it('never grants stream consent on the reader behalf', async () => {
    const grant = vi.spyOn(addonConsent, 'grant');
    const registry = registryWith();
    const parsed = parseCollection({
      addons: [entry(STREAM_URL, streamManifestJson('org.example.streams'))],
    });
    await installCollection(registry, parsed.entries);

    const id = await addonIdFromUrl(STREAM_URL);
    expect(grant).not.toHaveBeenCalled();
    expect(addonConsent.hasConsent(id)).toBe(false);
    grant.mockRestore();
  });

  it('keeps going when one install fails and reports it', async () => {
    const store = fakeStore();
    const registry = new AddonRegistry({
      store,
      transport: async () => ({
        status: 200,
        contentType: 'application/json',
        body: new Uint8Array(),
      }),
      now: () => 2_000,
    });
    const parsed = parseCollection({
      addons: [
        // A reserved built-in id is refused by the registry install path.
        entry('https://builtin.example/m.json', manifestJson('gutendex')),
        entry(CATALOG_URL, manifestJson('org.example.catalog')),
      ],
    });
    const result = await installCollection(registry, parsed.entries);
    expect(result).toEqual({ installed: 1, failed: 1 });
    expect((await registry.listInstalled()).map((row) => row.url)).toEqual([CATALOG_URL]);
  });

  it('updates an already installed addon and preserves enabled and addedAt', async () => {
    const store = fakeStore([
      {
        id: await addonIdFromUrl(CATALOG_URL),
        url: CATALOG_URL,
        manifestJson: JSON.stringify(manifestJson('org.example.catalog')),
        enabled: false,
        addedAt: 111,
      },
    ]);
    const registry = registryWith(store);
    const parsed = parseCollection({
      addons: [entry(CATALOG_URL, manifestJson('org.example.catalog', { name: 'Renamed' }))],
    });
    await installCollection(registry, parsed.entries);

    const row = store.rows.find((item) => item.url === CATALOG_URL);
    expect(row?.enabled).toBe(false);
    expect(row?.addedAt).toBe(111);
    expect(JSON.parse(row?.manifestJson ?? '{}').name).toBe('Renamed');
  });
});

describe('serializeCollection round trip', () => {
  it('re-imports what it exported', async () => {
    const parsed = parseCollection({
      addons: [
        entry(CATALOG_URL, manifestJson('org.example.catalog')),
        entry(STREAM_URL, streamManifestJson('org.example.streams')),
      ],
    });
    const wire = serializeCollection(
      parsed.entries.map((item) => ({ manifest: item.manifest, installUrl: item.transportUrl })),
    );
    const again = await fetchCollection(
      collectionTransport(new TextEncoder().encode(wire)),
      'https://collections.example/books.json',
    );
    expect(again.entries).toEqual(parsed.entries);
  });
});
