import { describe, expect, it } from 'vitest';

import {
  AddonFetchErrorCode,
  AddonRegistry,
  type AddonManifest,
} from '$lib/shared/services/addons/AddonRegistry';
import { AddonCatalogProvider } from '$lib/shared/services/addons/AddonCatalogProvider';
import {
  CompositeCatalogProvider,
  defaultCatalogProviders,
} from '$lib/shared/services/catalog/CompositeCatalogProvider';
import { addonIdFromUrl } from '$lib/shared/services/addons/addonId';

const VALID_MANIFEST: AddonManifest = {
  id: 'my-addon',
  name: 'My Addon',
  version: '1.0.0',
  catalogs: [{ type: 'book', id: 'main', name: 'Main catalog' }],
  resources: ['catalog'],
};

interface FakeRow {
  id: string;
  url: string;
  manifestJson: string;
  enabled: boolean;
  addedAt: number;
}

/** Mirrors the Rust/Room UPSERT semantics: reinstall preserves enabled + addedAt. */
function fakeStore() {
  const rows: FakeRow[] = [];
  return {
    rows,
    async listInstalled(): Promise<FakeRow[]> {
      return rows.map((r) => ({ ...r }));
    },
    async upsert(row: FakeRow): Promise<void> {
      const existing = rows.find((r) => r.id === row.id);
      if (existing) {
        existing.manifestJson = row.manifestJson;
      } else {
        rows.push({ ...row });
      }
    },
    async setEnabled(id: string, enabled: boolean): Promise<void> {
      const row = rows.find((r) => r.id === id);
      if (row) row.enabled = enabled;
    },
    async uninstall(id: string): Promise<void> {
      const i = rows.findIndex((r) => r.id === id);
      if (i >= 0) rows.splice(i, 1);
    },
  };
}

type TransportResult = { status: number; contentType: string | null; body: Uint8Array };

/** Transport returning a different manifest per install call, in order. */
function scriptedTransport(...manifests: unknown[]) {
  let call = 0;
  const transport = async (_url: string): Promise<TransportResult> => {
    const manifest = manifests[Math.min(call, manifests.length - 1)];
    call += 1;
    return { status: 200, contentType: 'application/json', body: jsonBytes(manifest) };
  };
  return transport;
}

function okTransport(manifest: unknown = VALID_MANIFEST, status = 200) {
  const transport = async (_url: string): Promise<TransportResult> => ({
    status,
    contentType: 'application/json',
    body: new TextEncoder().encode(JSON.stringify(manifest)),
  });
  return Object.assign(transport, { calls: 0 }) as typeof transport & { calls: number };
}

function jsonBytes(value: unknown): Uint8Array {
  return new TextEncoder().encode(JSON.stringify(value));
}

describe('AddonRegistry.install', () => {
  it('happy path stores validated manifest keyed by sha256 addonId', async () => {
    const store = fakeStore();
    const transport = okTransport();
    const registry = new AddonRegistry({ store, transport });
    const url = 'https://example.com/manifest.json';
    const manifest = await registry.install(url);
    expect(manifest.id).toBe('my-addon');
    expect(store.rows).toHaveLength(1);
    expect(store.rows[0].id).toBe(await addonIdFromUrl(url));
    expect(store.rows[0].url).toBe(url);
    expect(store.rows[0].enabled).toBe(true);
    expect(JSON.parse(store.rows[0].manifestJson).name).toBe('My Addon');
  });

  it('rejects non-HTTPS URLs before any network I/O', async () => {
    const store = fakeStore();
    const transport = okTransport();
    const registry = new AddonRegistry({ store, transport });
    await expect(registry.install('http://example.com/manifest.json')).rejects.toMatchObject({
      code: AddonFetchErrorCode.HTTPS_REQUIRED,
    });
    await expect(registry.install('ftp://example.com/manifest.json')).rejects.toMatchObject({
      code: AddonFetchErrorCode.HTTPS_REQUIRED,
    });
    expect((transport as typeof transport & { calls: number }).calls).toBe(0);
    expect(store.rows).toHaveLength(0);
  });

  it('is idempotent: reinstall updates manifest and preserves enabled', async () => {
    const store = fakeStore();
    const transport = scriptedTransport(VALID_MANIFEST, { ...VALID_MANIFEST, version: '2.0.0' });
    const registry = new AddonRegistry({ store, transport });
    const url = 'https://example.com/manifest.json';
    await registry.install(url);
    const id = store.rows[0].id;
    await registry.setEnabled(id, false);
    await registry.install(url);
    expect(store.rows).toHaveLength(1);
    expect(store.rows[0].id).toBe(id);
    expect(JSON.parse(store.rows[0].manifestJson).version).toBe('2.0.0');
    expect(store.rows[0].enabled).toBe(false);
  });

  it('rejects non-2xx fetch status as network error', async () => {
    const registry = new AddonRegistry({
      store: fakeStore(),
      transport: okTransport(VALID_MANIFEST, 404),
    });
    await expect(registry.install('https://example.com/manifest.json')).rejects.toMatchObject({
      code: AddonFetchErrorCode.NETWORK,
    });
  });

  it('rejects HTML content type as bad content type', async () => {
    const transport = async (): Promise<TransportResult> => ({
      status: 200,
      contentType: 'text/html',
      body: jsonBytes('<html>not a manifest</html>'),
    });
    const registry = new AddonRegistry({ store: fakeStore(), transport });
    await expect(registry.install('https://example.com/manifest.json')).rejects.toMatchObject({
      code: AddonFetchErrorCode.BAD_CONTENT_TYPE,
    });
  });

  it('rejects oversized manifests before parse', async () => {
    const transport = async (): Promise<TransportResult> => ({
      status: 200,
      contentType: 'application/json',
      body: new Uint8Array(65 * 1024),
    });
    const registry = new AddonRegistry({ store: fakeStore(), transport });
    await expect(registry.install('https://example.com/manifest.json')).rejects.toMatchObject({
      code: AddonFetchErrorCode.TOO_LARGE,
    });
  });

  it('rejects manifests whose id collides with a built-in source name', async () => {
    const store = fakeStore();
    const registry = new AddonRegistry({
      store,
      transport: okTransport({ ...VALID_MANIFEST, id: 'gutendex' }),
    });
    await expect(registry.install('https://example.com/manifest.json')).rejects.toMatchObject({
      code: AddonFetchErrorCode.INVALID_MANIFEST,
    });
    expect(store.rows).toHaveLength(0);
  });
});

describe('AddonRegistry.manage', () => {
  it('uninstall removes the row; other rows unaffected', async () => {
    const store = fakeStore();
    const registry = new AddonRegistry({ store, transport: okTransport() });
    await registry.install('https://one.example/m.json');
    await registry.install('https://two.example/m.json');
    const before = await registry.listInstalled();
    expect(before).toHaveLength(2);
    await registry.uninstall(before[0].id);
    const after = await registry.listInstalled();
    expect(after).toHaveLength(1);
    expect(after[0].id).not.toBe(before[0].id);
  });

  it('setEnabled persists the toggle', async () => {
    const store = fakeStore();
    const registry = new AddonRegistry({ store, transport: okTransport() });
    await registry.install('https://example.com/manifest.json');
    const id = store.rows[0].id;
    await registry.setEnabled(id, false);
    expect((await registry.listInstalled())[0].enabled).toBe(false);
    await registry.setEnabled(id, true);
    expect((await registry.listInstalled())[0].enabled).toBe(true);
  });

  it('listInstalled surfaces rows in install order with parsed manifests', async () => {
    const store = fakeStore();
    const registry = new AddonRegistry({ store, transport: okTransport() });
    await registry.install('https://one.example/m.json');
    await registry.install('https://two.example/m.json');
    const rows = await registry.listInstalled();
    expect(rows[0].addedAt).toBeLessThanOrEqual(rows[1].addedAt);
    expect(rows[0].manifest.name).toBe('My Addon');
  });
});

describe('AddonCatalogProvider + dynamic composite', () => {
  it('exposes one addon-kind source and is browse-only', async () => {
    const addonId = await addonIdFromUrl('https://example.com/manifest.json');
    const provider = new AddonCatalogProvider(VALID_MANIFEST, addonId);
    const sources = provider.listSources();
    expect(sources).toHaveLength(1);
    expect(sources[0].sourceId).toBe(`addon:${addonId}`);
    expect(sources[0].kind).toBe('addon');
    expect(sources[0].name).toBe('My Addon');
    await expect(provider.search('anything', 1)).resolves.toEqual({
      results: [],
      nexoPage: null,
      totalCount: 0,
    });
    await expect(provider.getDetails(`addon:${addonId}:some-book`)).rejects.toMatchObject({
      code: 'NOT_FOUND',
    });
  });

  it('defaultCatalogProviders appends enabled addons in install order and excludes disabled', async () => {
    const store = fakeStore();
    const registry = new AddonRegistry({ store, transport: okTransport() });
    await registry.install('https://one.example/m.json');
    await registry.install('https://two.example/m.json');
    await registry.setEnabled(store.rows[0].id, false);

    const rows = await registry.listInstalled();
    const providers = defaultCatalogProviders(rows);
    // These tests read the RAW provider list, not the composite's deduped one,
    // so the curated bundle still lists its `standard-ebooks` copy here.
    const sources = providers.flatMap((p) => p.listSources());
    const addons = sources.filter((s) => s.kind === 'addon');
    expect(addons).toHaveLength(1);
    expect(addons[0].sourceId).toBe(`addon:${store.rows[1].id}`);
    expect(sources.map((s) => s.kind)).toEqual([
      // Gutendex, Open Library, Standard Ebooks (first-party providers).
      'builtin',
      'builtin',
      'builtin',
      // Curated bundle: gutendex, openlibrary, standard-ebooks.
      'curated',
      'curated',
      'curated',
      // The single enabled addon.
      'addon',
    ]);
    // The last entry is the enabled addon (install order, disabled excluded).
    expect(sources[sources.length - 1].sourceId).toBe(`addon:${store.rows[1].id}`);
  });

  it('uninstall isolates: remaining composite keeps built-ins and other addons', async () => {
    const store = fakeStore();
    const registry = new AddonRegistry({ store, transport: okTransport() });
    await registry.install('https://one.example/m.json');
    await registry.install('https://two.example/m.json');
    await registry.uninstall((await registry.listInstalled())[0].id);

    const rows = await registry.listInstalled();
    const sources = defaultCatalogProviders(rows).flatMap((p) => p.listSources());
    expect(sources.filter((s) => s.kind === 'builtin')).toHaveLength(3);
    expect(sources.filter((s) => s.kind === 'curated')).toHaveLength(3);
    expect(sources.filter((s) => s.kind === 'addon')).toHaveLength(1);
    expect(sources.some((s) => s.sourceId === 'builtin:gutendex')).toBe(true);
  });

  it('defaultCatalogProviders with zero addons keeps three real built-in sources', () => {
    const providers = defaultCatalogProviders([]);
    const kinds = providers.flatMap((p) => p.listSources()).map((s) => s.kind);
    expect(kinds).toEqual([
      // Gutendex, Open Library, Standard Ebooks.
      'builtin',
      'builtin',
      'builtin',
      // Curated bundle.
      'curated',
      'curated',
      'curated',
    ]);
    // The composite dedupes the shared `standard-ebooks` id down to one source,
    // and the first-party provider wins that dedupe.
    const deduped = new CompositeCatalogProvider(providers).listSources();
    expect(deduped.map((s) => s.kind)).toEqual(['builtin', 'builtin', 'builtin']);
    expect(deduped.map((s) => s.sourceId)).toEqual([
      'builtin:gutendex',
      'builtin:openlibrary',
      'builtin:standard-ebooks',
    ]);
  });
});
