/**
 * Collection deep-link routing + store (task 15). One `nexo://collection?url=…`
 * link previews a whole set, lists it with the addons that will ask for
 * `stream` consent, and installs only after confirmation.
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import {
  handleCollectionDeepLinkUrls,
  parseCollectionDeepLink,
  resetCollectionDeepLinkHandler,
  setCollectionDeepLinkHandler,
} from '$lib/features/addons/collectionDeepLink';
import { createCollectionDeepLink } from '$lib/features/addons/useCollectionDeepLink.svelte';
import { AddonRegistry, type AddonTransport } from '$lib/shared/services/addons/AddonRegistry';
import { addonConsent } from '$lib/shared/services/addons/AddonConsent';
import { logger } from '$lib/shared/logger/Logger';

const COLLECTION_URL = 'https://collections.example/books.json';
const CATALOG_URL = 'https://catalog.example/manifest.json';
const STREAM_URL = 'https://streams.example/manifest.json';

function manifestJson(id: string): Record<string, unknown> {
  return {
    id,
    version: '3.0.0',
    name: id,
    types: ['book'],
    catalogs: [{ type: 'book', id: 'popular', name: 'Popular' }],
    resources: ['catalog', 'meta'],
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

function collectionTransport(addons: unknown[]): AddonTransport {
  const body = new TextEncoder().encode(JSON.stringify({ addons }));
  return async () => ({ status: 200, contentType: 'application/json', body });
}

interface FakeRow {
  id: string;
  url: string;
  manifestJson: string;
  enabled: boolean;
  addedAt: number;
}

function fakeStore() {
  const rows: FakeRow[] = [];
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

function registryWith(store = fakeStore()): AddonRegistry {
  return new AddonRegistry({
    store,
    transport: async () => ({
      status: 200,
      contentType: 'application/json',
      body: new Uint8Array(),
    }),
    now: () => 3_000,
  });
}

const MIXED_COLLECTION = [
  entry(CATALOG_URL, manifestJson('org.example.catalog')),
  entry(STREAM_URL, streamManifestJson('org.example.streams')),
  entry('http://plain.example/manifest.json', manifestJson('org.example.plain')),
  entry('https://bad.example/manifest.json', { id: 'org.example.bad' }),
];

describe('parseCollectionDeepLink', () => {
  it('parses the canonical nexo://collection link', () => {
    expect(parseCollectionDeepLink(`nexo://collection?url=${COLLECTION_URL}`)).toEqual({
      collectionUrl: COLLECTION_URL,
    });
  });

  it('keeps the legacy schemes working', () => {
    for (const scheme of ['nextpage', 'nextpage-desktop']) {
      expect(parseCollectionDeepLink(`${scheme}://collection?url=${COLLECTION_URL}`)).toEqual({
        collectionUrl: COLLECTION_URL,
      });
    }
  });

  it('never claims the single-addon install host or an auth host', () => {
    for (const raw of [
      'nexo://install?url=https://example.com/manifest.json',
      'nextpage://auth/callback?access_token=t',
      'nextpage://auth/reset-password',
      'nexo://addons?url=https://example.com/collection.json',
      '',
      'not a url',
      'https://example.com/collection.json',
    ]) {
      expect(parseCollectionDeepLink(raw)).toBeNull();
    }
  });

  it('rejects a missing, empty or non-https url param', () => {
    expect(parseCollectionDeepLink('nexo://collection')).toBeNull();
    expect(parseCollectionDeepLink('nexo://collection?other=1')).toBeNull();
    expect(parseCollectionDeepLink('nexo://collection?url=')).toBeNull();
    expect(parseCollectionDeepLink('nexo://collection?url=http://example.com/c.json')).toBeNull();
    expect(parseCollectionDeepLink('nexo://collection?url=file:///etc/passwd')).toBeNull();
  });
});

describe('handleCollectionDeepLinkUrls', () => {
  beforeEach(() => resetCollectionDeepLinkHandler());
  afterEach(() => {
    resetCollectionDeepLinkHandler();
    vi.restoreAllMocks();
  });

  it('routes collection urls to the registered handler', async () => {
    const handler = vi.fn();
    setCollectionDeepLinkHandler(handler);
    await handleCollectionDeepLinkUrls([`nexo://collection?url=${COLLECTION_URL}`]);
    expect(handler).toHaveBeenCalledWith(COLLECTION_URL);
  });

  it('ignores install and auth urls', async () => {
    const handler = vi.fn();
    setCollectionDeepLinkHandler(handler);
    await handleCollectionDeepLinkUrls([
      'nexo://install?url=https://example.com/manifest.json',
      'nexo://auth/callback?access_token=t',
    ]);
    expect(handler).not.toHaveBeenCalled();
  });

  it('is a no-op without a handler', async () => {
    vi.spyOn(logger, 'debug').mockImplementation(() => {});
    await expect(
      handleCollectionDeepLinkUrls([`nexo://collection?url=${COLLECTION_URL}`]),
    ).resolves.toBeUndefined();
  });
});

describe('createCollectionDeepLink', () => {
  it('lists every importable addon and marks the ones requesting stream', async () => {
    const flow = createCollectionDeepLink({
      registry: registryWith(),
      transport: collectionTransport(MIXED_COLLECTION),
    });
    await flow.handleCollectionUrl(COLLECTION_URL);

    expect(flow.state).toBe('confirming');
    expect(flow.dialogOpen).toBe(true);
    expect(flow.entries.map((item) => item.manifest.id)).toEqual([
      'org.example.catalog',
      'org.example.streams',
    ]);
    expect(flow.entries.map((item) => item.requestsStream)).toEqual([false, true]);
    expect(flow.skipped).toBe(2);
  });

  it('installs nothing until confirmed', async () => {
    const registry = registryWith();
    const flow = createCollectionDeepLink({
      registry,
      transport: collectionTransport(MIXED_COLLECTION),
    });
    await flow.handleCollectionUrl(COLLECTION_URL);
    expect(await registry.listInstalled()).toHaveLength(0);
  });

  it('confirm installs the whole set and grants no stream consent', async () => {
    const grant = vi.spyOn(addonConsent, 'grant');
    const registry = registryWith();
    const flow = createCollectionDeepLink({
      registry,
      transport: collectionTransport(MIXED_COLLECTION),
    });
    await flow.handleCollectionUrl(COLLECTION_URL);
    await flow.confirm();

    expect(flow.state).toBe('done');
    expect(flow.dialogOpen).toBe(false);
    const rows = await registry.listInstalled();
    expect(rows.map((row) => row.url)).toEqual([CATALOG_URL, STREAM_URL]);
    expect(grant).not.toHaveBeenCalled();
    for (const row of rows) expect(addonConsent.hasConsent(row.id)).toBe(false);
    grant.mockRestore();
  });

  it('marks addons that are already installed so the dialog can say they update', async () => {
    const registry = registryWith();
    const flow = createCollectionDeepLink({
      registry,
      transport: collectionTransport(MIXED_COLLECTION),
    });
    await flow.handleCollectionUrl(COLLECTION_URL);
    await flow.confirm();

    const again = createCollectionDeepLink({
      registry,
      transport: collectionTransport(MIXED_COLLECTION),
    });
    await again.handleCollectionUrl(COLLECTION_URL);
    expect(again.alreadyInstalled).toEqual([CATALOG_URL, STREAM_URL]);
  });

  it('cancel leaves the reader with nothing installed', async () => {
    const registry = registryWith();
    const flow = createCollectionDeepLink({
      registry,
      transport: collectionTransport(MIXED_COLLECTION),
    });
    await flow.handleCollectionUrl(COLLECTION_URL);
    flow.cancel();
    expect(flow.state).toBe('idle');
    expect(flow.entries).toEqual([]);
    expect(await registry.listInstalled()).toHaveLength(0);
  });

  it('reports a non-https collection link without fetching', async () => {
    const transport = vi.fn(async () => ({
      status: 200,
      contentType: 'application/json',
      body: new Uint8Array(),
    }));
    const flow = createCollectionDeepLink({ registry: registryWith(), transport });
    await flow.handleCollectionUrl('http://collections.example/books.json');
    expect(flow.state).toBe('error');
    expect(flow.errorCode).toBe('ADDON_FETCH_HTTPS_REQUIRED');
    expect(transport).not.toHaveBeenCalled();
  });

  it('reports a collection with nothing importable as an invalid manifest', async () => {
    const flow = createCollectionDeepLink({
      registry: registryWith(),
      transport: collectionTransport([]),
    });
    await flow.handleCollectionUrl(COLLECTION_URL);
    expect(flow.state).toBe('error');
    expect(flow.errorCode).toBe('ADDON_FETCH_INVALID_MANIFEST');
  });
});
