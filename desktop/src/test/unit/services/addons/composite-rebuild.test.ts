import { describe, expect, it, vi } from 'vitest';

import { AddonRegistry, type AddonRegistryStore } from '$lib/shared/services/addons/AddonRegistry';
import { createRebuildingCatalogProvider } from '$lib/shared/services/catalog/CompositeCatalogProvider';
import { addonIdFromUrl } from '$lib/shared/services/addons/addonId';
import type { AddonManifest } from '@nexo/manifest-validator';
import type { AddonTransport } from '$lib/shared/services/addons/AddonRegistry';
import { createSettingsAddons } from '$lib/features/settings/useSettingsAddons.svelte';

const ADDON_MANIFEST: AddonManifest = {
  id: 'space-books',
  name: 'Space Books',
  version: '1.0.0',
  catalogs: [{ type: 'book', id: 'main', name: 'Main catalog' }],
  resources: ['catalog'],
  searchUrl: 'https://space.example/search?q={query}&page={page}',
  detailsUrl: 'https://space.example/book/{bookId}',
};

const INSTALL_URL = 'https://space.example/manifest.json';

interface FakeRow {
  id: string;
  url: string;
  manifestJson: string;
  enabled: boolean;
  addedAt: number;
}

function fakeStore(seed: FakeRow[] = []): AddonRegistryStore & { rows: FakeRow[] } {
  const rows = [...seed];
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

/** Manifest transport for installs; payload transport for addon catalog fetches. */
function transportFor(
  results: Array<{ status?: number; body: unknown }>,
): AddonTransport & { calls: string[] } {
  let call = 0;
  const calls: string[] = [];
  return Object.assign(
    async (url: string) => {
      calls.push(url);
      const r = results[Math.min(call, results.length - 1)];
      call += 1;
      return {
        status: r.status ?? 200,
        contentType: 'application/json',
        body: new TextEncoder().encode(
          typeof r.body === 'string' ? r.body : JSON.stringify(r.body),
        ),
      };
    },
    { calls },
  );
}

const SPACE_PAYLOAD = { results: [{ id: 'book-1', title: 'Space Book' }], totalCount: 1 };

describe('AddonRegistry change notifications', () => {
  it('fires onChanged with a monotonically increasing version on every mutation', async () => {
    const store = fakeStore();
    const registry = new AddonRegistry({
      store,
      transport: transportFor([{ body: ADDON_MANIFEST }]),
    });
    const versions: number[] = [];
    registry.onChanged((v) => versions.push(v));

    await registry.install(INSTALL_URL);
    const id = store.rows[0].id;
    await registry.setEnabled(id, false);
    await registry.setEnabled(id, true);
    await registry.uninstall(id);

    expect(versions).toEqual([1, 2, 3, 4]);
  });

  it('unsubscribe stops notifications', async () => {
    const store = fakeStore();
    const registry = new AddonRegistry({
      store,
      transport: transportFor([{ body: ADDON_MANIFEST }]),
    });
    const versions: number[] = [];
    const off = registry.onChanged((v) => versions.push(v));
    await registry.install(INSTALL_URL);
    off();
    await registry.setEnabled(store.rows[0].id, false);
    expect(versions).toEqual([1]);
  });

  it('registry rows survive a fresh registry instance (restart parity)', async () => {
    const store = fakeStore();
    const first = new AddonRegistry({ store, transport: transportFor([{ body: ADDON_MANIFEST }]) });
    await first.install(INSTALL_URL);

    const restarted = new AddonRegistry({
      store,
      transport: transportFor([{ body: SPACE_PAYLOAD }]),
    });
    const supplier = createRebuildingCatalogProvider(() => restarted.listInstalled());
    const provider = await supplier.current();
    const addonId = await addonIdFromUrl(INSTALL_URL);
    const sources = provider.listSources().filter((s) => s.kind === 'addon');
    expect(sources.map((s) => s.sourceId)).toEqual([`addon:${addonId}`]);
  });
});

describe('RebuildingCatalogProvider (live composite)', () => {
  it('rebuilds after registry mutations: installed addon serves details, uninstalled is unroutable', async () => {
    const store = fakeStore();
    const manifestTransport = transportFor([{ body: ADDON_MANIFEST }]);
    const payloadTransport = transportFor([{ body: { id: 'book-1', title: 'Space Book' } }]);
    const registry = new AddonRegistry({ store, transport: manifestTransport });
    const supplier = createRebuildingCatalogProvider(
      () => registry.listInstalled(),
      payloadTransport,
    );
    registry.onChanged(() => supplier.invalidate());

    // Startup: no addons yet — rebuilt on first use.
    expect((await supplier.current()).listSources().filter((s) => s.kind === 'addon')).toEqual([]);

    await registry.install(INSTALL_URL);
    const addonId = store.rows[0].id;
    const built = await supplier.current();
    expect(
      built
        .listSources()
        .filter((s) => s.kind === 'addon')
        .map((s) => s.sourceId),
    ).toEqual([`addon:${addonId}`]);
    const book = await built.getDetails(`addon:${addonId}:book-1`);
    expect(book.title).toBe('Space Book');
    expect(book.provider).toBe(`addon:${addonId}`);
    expect(payloadTransport.calls).toEqual(['https://space.example/book/book-1']);

    // Disable: source stops contributing, details unroutable with no I/O.
    await registry.setEnabled(addonId, false);
    const disabled = await supplier.current();
    expect(disabled.listSources().filter((s) => s.kind === 'addon')).toEqual([]);
    await expect(disabled.getDetails(`addon:${addonId}:book-1`)).rejects.toMatchObject({
      code: 'NOT_FOUND',
    });
    expect(payloadTransport.calls).toHaveLength(1);

    // Uninstall after re-enable: same unroutable guarantee.
    await registry.setEnabled(addonId, true);
    await registry.uninstall(addonId);
    const gone = await supplier.current();
    expect(gone.listSources().filter((s) => s.kind === 'addon')).toEqual([]);
    await expect(gone.getDetails(`addon:${addonId}:book-1`)).rejects.toMatchObject({
      code: 'NOT_FOUND',
    });
    expect(payloadTransport.calls).toHaveLength(1);
  });
});

describe('settings seam drives the live composite', () => {
  it('createSettingsAddons subscribes to registry change notifications', async () => {
    const store = fakeStore();
    const registry = new AddonRegistry({
      store,
      transport: transportFor([{ body: ADDON_MANIFEST }]),
    });
    const invalidations = vi.fn();
    const d = createSettingsAddons({ registry, onAddonsChanged: invalidations });
    d.url = INSTALL_URL;
    await d.handleInstall();
    expect(invalidations).toHaveBeenCalled();
    expect(d.installed).toHaveLength(1);
  });
});
