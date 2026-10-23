/**
 * Registry v3 read path + the platform resource transport.
 *
 * Two guarantees the desktop registry owes the protocol package:
 * v2 rows adapt to `AddonManifestV3` on READ (the `manifest_json` bytes are
 * never rewritten), and every addon resource request goes through the Tauri
 * `fetchAddonResource` command bounded by the protocol deadline and by the
 * "max 3 concurrent requests per host" rule.
 */
import { describe, expect, it, vi } from 'vitest';
import { invoke } from '@tauri-apps/api/core';

import {
  AddonRegistry,
  MAX_CONCURRENT_PER_HOST,
  createAddonResourceTransport,
  type AddonFetchResult,
  type AddonRegistryStore,
  type AddonTransport,
} from '$lib/shared/services/addons/AddonRegistry';
import type { AddonManifestV3 } from '@nexo/addon-protocol';
import { AddonFetchErrorCode } from '@nexo/manifest-validator';

const INSTALL_URL = 'https://addons.example/manifest.json';

/** Routed v3 addon: catalog + meta + stream, one book catalog with search. */
const ROUTED_V3: AddonManifestV3 = {
  id: 'org.example.gutenberg',
  version: '3.0.0',
  name: 'Gutenberg v3',
  types: ['book'],
  catalogs: [{ type: 'book', id: 'popular', name: 'Popular', extra: [{ name: 'search' }] }],
  resources: [
    { name: 'catalog' },
    { name: 'meta', types: ['book'] },
    { name: 'stream', types: ['book'], idPrefixes: ['gutenberg:'] },
  ],
};

/** The Pirate Bay shape: no catalogs at all, streams only. */
const STREAM_ONLY_V3 = {
  id: 'org.example.tpb',
  version: '3.0.0',
  name: 'TPB Books',
  types: ['book'],
  catalogs: [],
  resources: [{ name: 'stream', types: ['book'], idPrefixes: ['gutenberg:', 'archive:'] }],
};

/** Stored v2 row: flat endpoint templates, no `types` / `resources` objects. */
const STORED_V2 = {
  id: 'org.example.legacy',
  name: 'Legacy Books',
  version: '2.0.0',
  catalogs: [{ type: 'book', id: 'main', name: 'Main' }],
  resources: ['catalog', 'resolve'],
  searchUrl: 'https://api.example/books?q={query}&page={page}',
  resolveUrl: 'https://api.example/resolve?isbn={isbn}',
  capabilities: ['resolve'],
};

interface FakeRow {
  id: string;
  url: string;
  manifestJson: string;
  enabled: boolean;
  addedAt: number;
}

function fakeStore(initial: FakeRow[] = []): AddonRegistryStore & { rows: FakeRow[] } {
  const rows: FakeRow[] = initial.map((row) => ({ ...row }));
  return {
    rows,
    async listInstalled(): Promise<FakeRow[]> {
      return rows.map((row) => ({ ...row }));
    },
    async upsert(row: FakeRow): Promise<void> {
      const existing = rows.find((candidate) => candidate.id === row.id);
      if (existing) existing.manifestJson = row.manifestJson;
      else rows.push({ ...row });
    },
    async setEnabled(id: string, enabled: boolean): Promise<void> {
      const row = rows.find((candidate) => candidate.id === id);
      if (row) row.enabled = enabled;
    },
    async uninstall(id: string): Promise<void> {
      const index = rows.findIndex((row) => row.id === id);
      if (index >= 0) rows.splice(index, 1);
    },
  };
}

function bytesOf(value: unknown): Uint8Array {
  return new TextEncoder().encode(JSON.stringify(value));
}

function manifestTransport(body: Uint8Array): AddonTransport {
  return async (): Promise<AddonFetchResult> => ({
    status: 200,
    contentType: 'application/json',
    body,
  });
}

describe('registry v3 read path', () => {
  it('installs a routed v3 manifest and exposes it as AddonManifestV3', async () => {
    const store = fakeStore();
    const registry = new AddonRegistry({ store, transport: manifestTransport(bytesOf(ROUTED_V3)) });

    const manifest = await registry.install(INSTALL_URL);

    expect(manifest.types).toEqual(['book']);
    expect(manifest.catalogs).toHaveLength(1);
    expect(manifest.resources.map((resource) => resource.name)).toEqual([
      'catalog',
      'meta',
      'stream',
    ]);
    expect(manifest.nexo).toBeUndefined();
    const [row] = await registry.listInstalled();
    expect(row?.manifest).toMatchObject({ types: ['book'] });
    expect(store.rows).toHaveLength(1);
  });

  it('installs an empty-catalog, stream-only manifest (the TPB shape)', async () => {
    const store = fakeStore();
    const registry = new AddonRegistry({
      store,
      transport: manifestTransport(bytesOf(STREAM_ONLY_V3)),
    });

    const manifest = await registry.install(INSTALL_URL);

    expect(manifest.catalogs).toEqual([]);
    expect(manifest.resources).toEqual([
      { name: 'stream', types: ['book'], idPrefixes: ['gutenberg:', 'archive:'] },
    ]);
    expect(store.rows).toHaveLength(1);

    const [row] = await registry.listInstalled();
    expect(row?.manifest).toMatchObject({
      catalogs: [],
      resources: [{ name: 'stream', types: ['book'], idPrefixes: ['gutenberg:', 'archive:'] }],
    });
  });

  it('reads a stored v2 row as v3 without touching the stored JSON', async () => {
    const storedJson = JSON.stringify(STORED_V2);
    const store = fakeStore([
      {
        id: 'aaaaaaaabbbbbbbb',
        url: INSTALL_URL,
        manifestJson: storedJson,
        enabled: true,
        addedAt: 7,
      },
    ]);
    const registry = new AddonRegistry({ store });

    const [row] = await registry.listInstalled();

    expect(row?.manifest).toMatchObject({
      types: ['book'],
      catalogs: [
        { type: 'book', id: 'main', name: 'Main', extra: [{ name: 'search' }, { name: 'skip' }] },
      ],
      nexo: {
        shape: 'legacy-v2',
        templates: {
          catalog: 'https://api.example/books?q={search}&page={skip}',
          stream: 'https://api.example/resolve?isbn={isbn}',
        },
      },
    });
    // The column keeps the raw v2 bytes: no rewrite, no migration.
    expect(store.rows[0]?.manifestJson).toBe(storedJson);
  });

  it('drops a row whose stored JSON cannot be parsed as a manifest', async () => {
    const store = fakeStore([
      {
        id: 'ccccccccdddddddd',
        url: INSTALL_URL,
        manifestJson: '{"nope":true}',
        enabled: true,
        addedAt: 1,
      },
    ]);
    const registry = new AddonRegistry({ store });

    expect(await registry.listInstalled()).toEqual([]);
  });

  it('rejects an oversized manifest before persisting anything', async () => {
    const store = fakeStore();
    const registry = new AddonRegistry({
      store,
      transport: manifestTransport(new Uint8Array(65 * 1024)),
    });

    await expect(registry.install(INSTALL_URL)).rejects.toMatchObject({
      code: AddonFetchErrorCode.TOO_LARGE,
    });
    expect(store.rows).toHaveLength(0);
  });

  /**
   * `standard-ebooks` and `librivox` are not in the closed shape set: no reader
   * exists for either, so installing one would be silent emptiness. They are
   * rejected at install, the same way an invented shape name is.
   */
  it('rejects a manifest that selects a shape no reader can serve', async () => {
    for (const shape of ['standard-ebooks', 'librivox']) {
      const store = fakeStore();
      const registry = new AddonRegistry({
        store,
        transport: manifestTransport(bytesOf({ ...ROUTED_V3, nexo: { shape } })),
      });

      await expect(registry.install(INSTALL_URL)).rejects.toMatchObject({
        code: AddonFetchErrorCode.INVALID_MANIFEST,
      });
      expect(store.rows).toHaveLength(0);
    }
  });

  it('exposes protocol descriptors so the router can address every addon', async () => {
    const store = fakeStore();
    const registry = new AddonRegistry({ store, transport: manifestTransport(bytesOf(ROUTED_V3)) });
    await registry.install(INSTALL_URL);

    const descriptors = await registry.listDescriptors();
    const [descriptor] = descriptors;

    expect(descriptors).toHaveLength(1);
    expect(descriptor?.installUrl).toBe(INSTALL_URL);
    expect(descriptor?.enabled).toBe(true);
    expect(descriptor?.manifest.types).toEqual(['book']);
    expect(typeof descriptor?.addedAt).toBe('number');
  });
});

describe('registry addon clients', () => {
  it('memoizes one protocol client per addon', async () => {
    const registry = new AddonRegistry({ store: fakeStore() });

    expect(registry.addonClient('aaaaaaaaaaaaaaaa')).toBe(registry.addonClient('aaaaaaaaaaaaaaaa'));
    expect(registry.addonClient('aaaaaaaaaaaaaaaa')).not.toBe(
      registry.addonClient('bbbbbbbbbbbbbbbb'),
    );
  });
});

describe('createAddonResourceTransport', () => {
  it('fetches through the Tauri command and returns the parsed body', async () => {
    const payload = { metas: [{ id: 'gutenberg:1342', name: 'Moby Dick' }] };
    vi.mocked(invoke).mockResolvedValue({
      status: 200,
      contentType: 'application/json',
      body: Array.from(bytesOf(payload)),
    });

    const transport = createAddonResourceTransport();
    const response = await transport('https://addons.example/catalog/book/popular.json', {
      timeoutMs: 1_000,
    });

    expect(response).toEqual({ status: 200, body: payload });
    expect(invoke).toHaveBeenCalledWith('fetchAddonResource', {
      url: 'https://addons.example/catalog/book/popular.json',
    });
  });

  it('returns a non-2xx status without parsing the body', async () => {
    const transport = createAddonResourceTransport({
      fetch: async () => ({
        status: 404,
        contentType: 'text/html',
        body: new TextEncoder().encode('<html>gone</html>'),
      }),
    });

    await expect(
      transport('https://addons.example/catalog/book/popular.json', { timeoutMs: 1_000 }),
    ).resolves.toEqual({ status: 404, body: undefined });
  });

  it('rejects with a TimeoutError when the deadline elapses', async () => {
    const transport = createAddonResourceTransport({
      fetch: () => new Promise<AddonFetchResult>(() => undefined),
      timeoutMs: 10,
    });

    const error = await transport('https://addons.example/catalog/book/popular.json', {
      timeoutMs: 10,
    }).catch((err: unknown) => err);

    expect((error as Error).name).toBe('TimeoutError');
  });

  it(`bounds concurrent requests to ${MAX_CONCURRENT_PER_HOST} per host`, async () => {
    let inFlight = 0;
    let peak = 0;
    const transport = createAddonResourceTransport({
      fetch: async () => {
        inFlight += 1;
        peak = Math.max(peak, inFlight);
        await new Promise((resolve) => setTimeout(resolve, 5));
        inFlight -= 1;
        return { status: 200, contentType: 'application/json', body: bytesOf({ metas: [] }) };
      },
    });

    const urls = Array.from(
      { length: 6 },
      (_, index) => `https://one.example/catalog/book/id-${index}.json`,
    );
    await Promise.all(urls.map((url) => transport(url, { timeoutMs: 1_000 })));

    expect(peak).toBe(MAX_CONCURRENT_PER_HOST);
  });

  it('keeps distinct hosts independent', async () => {
    let inFlight = 0;
    let peak = 0;
    const transport = createAddonResourceTransport({
      fetch: async () => {
        inFlight += 1;
        peak = Math.max(peak, inFlight);
        await new Promise((resolve) => setTimeout(resolve, 5));
        inFlight -= 1;
        return { status: 200, contentType: 'application/json', body: bytesOf({ metas: [] }) };
      },
    });

    await Promise.all(
      ['https://one.example/a.json', 'https://one.example/b.json'].map((url) =>
        transport(url, { timeoutMs: 1_000 }),
      ),
    );
    await Promise.all(
      ['https://two.example/a.json', 'https://two.example/b.json'].map((url) =>
        transport(url, { timeoutMs: 1_000 }),
      ),
    );
    expect(peak).toBe(2);

    peak = 0;
    await Promise.all(
      [
        'https://three.example/a.json',
        'https://three.example/b.json',
        'https://four.example/a.json',
        'https://four.example/b.json',
      ].map((url) => transport(url, { timeoutMs: 1_000 })),
    );
    expect(peak).toBe(4);
  });
});
