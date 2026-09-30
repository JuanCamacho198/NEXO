/**
 * useInstallDeepLink store tests (sdd/addon-deeplink-v1 Work Unit A).
 * Fakes mirror src/test/unit/services/addons fakes; validateManifest is the
 * real pure validator.
 */
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { createInstallDeepLink } from '$lib/features/settings/useInstallDeepLink.svelte';
import { AddonFetchErrorCode, type AddonManifest } from '@nexo/manifest-validator';
import type { AddonFetchResult } from '$lib/shared/services/addons/AddonRegistry';

const MANIFEST: AddonManifest = {
  id: 'my-addon',
  name: 'My Addon',
  version: '1.0.0',
  catalogs: [{ type: 'book', id: 'main', name: 'Main catalog' }],
  resources: ['catalog'],
};

const URL = 'https://example.com/manifest.json';

function okTransport(manifest: unknown = MANIFEST) {
  return vi.fn(async (_url: string): Promise<AddonFetchResult> => ({
    status: 200,
    contentType: 'application/json',
    body: new TextEncoder().encode(JSON.stringify(manifest)),
  }));
}

function fakeRegistry() {
  return {
    installManifest: vi.fn(async (_url: string, _manifest: AddonManifest) => MANIFEST),
    listInstalled: vi.fn(async () => [] as { id: string; url: string }[]),
  };
}

function jsonError(manifest: unknown): Uint8Array {
  return new TextEncoder().encode(JSON.stringify(manifest));
}

describe('createInstallDeepLink', () => {
  let registry: ReturnType<typeof fakeRegistry>;
  let transport: ReturnType<typeof okTransport>;

  beforeEach(() => {
    registry = fakeRegistry();
    transport = okTransport();
  });

  it('happy path: fetch → confirming with manifest preview', async () => {
    const store = createInstallDeepLink({ registry, transport });
    await store.handleInstallUrl(URL);
    expect(transport).toHaveBeenCalledTimes(1);
    expect(store.state).toBe('confirming');
    expect(store.manifest?.id).toBe('my-addon');
    expect(store.dialogOpen).toBe(true);
  });

  it('fetch failure (non-2xx) → error state, no install', async () => {
    transport.mockImplementation(async (): Promise<AddonFetchResult> => ({
      status: 500,
      contentType: null,
      body: new Uint8Array(),
    }));
    const store = createInstallDeepLink({ registry, transport });
    await store.handleInstallUrl(URL);
    expect(store.state).toBe('error');
    expect(store.errorCode).toBe(AddonFetchErrorCode.NETWORK);
    expect(registry.installManifest).not.toHaveBeenCalled();
    expect(store.dialogOpen).toBe(true);
  });

  it('invalid manifest → error state with INVALID_MANIFEST code', async () => {
    transport.mockImplementation(async (): Promise<AddonFetchResult> => ({
      status: 200,
      contentType: 'application/json',
      body: jsonError({ id: 'x' }),
    }));
    const store = createInstallDeepLink({ registry, transport });
    await store.handleInstallUrl(URL);
    expect(store.state).toBe('error');
    expect(store.errorCode).toBe(AddonFetchErrorCode.INVALID_MANIFEST);
  });

  it('https rejection happens before any fetch', async () => {
    const store = createInstallDeepLink({ registry, transport });
    await store.handleInstallUrl('http://example.com/manifest.json');
    expect(transport).not.toHaveBeenCalled();
    expect(store.state).toBe('error');
    expect(store.errorCode).toBe(AddonFetchErrorCode.HTTPS_REQUIRED);
    expect(store.dialogOpen).toBe(true);
  });

  it('confirm → installManifest called once, single fetch total', async () => {
    const store = createInstallDeepLink({ registry, transport });
    await store.handleInstallUrl(URL);
    await store.confirm();
    expect(registry.installManifest).toHaveBeenCalledTimes(1);
    expect(registry.installManifest).toHaveBeenCalledWith(URL, MANIFEST);
    expect(transport).toHaveBeenCalledTimes(1);
    expect(store.state).toBe('done');
    expect(store.dialogOpen).toBe(false);
  });

  it('install failure after confirm → error state', async () => {
    registry.installManifest.mockRejectedValue(new Error('boom'));
    const store = createInstallDeepLink({ registry, transport });
    await store.handleInstallUrl(URL);
    await store.confirm();
    expect(store.state).toBe('error');
    expect(store.errorCode).toBe(AddonFetchErrorCode.INVALID_MANIFEST);
    expect(store.dialogOpen).toBe(true);
  });

  it('cancel → nothing installed, store resets to idle', async () => {
    const store = createInstallDeepLink({ registry, transport });
    await store.handleInstallUrl(URL);
    store.cancel();
    expect(store.state).toBe('idle');
    expect(store.manifest).toBeNull();
    expect(store.dialogOpen).toBe(false);
    expect(registry.installManifest).not.toHaveBeenCalled();
  });

  it('ignores new urls while busy (fetching/installing guard)', async () => {
    let release: () => void = () => {};
    const gate = new Promise<void>((resolve) => {
      release = resolve;
    });
    transport.mockImplementation(async (): Promise<AddonFetchResult> => {
      await gate;
      return { status: 200, contentType: 'application/json', body: jsonError(MANIFEST) };
    });
    const store = createInstallDeepLink({ registry, transport });
    const first = store.handleInstallUrl(URL);
    const second = store.handleInstallUrl('https://other.example/manifest.json');
    release();
    await Promise.all([first, second]);
    expect(transport).toHaveBeenCalledTimes(1);
    expect(store.manifest?.id).toBe('my-addon');
  });

  it('marks already-installed when a registry row shares the url', async () => {
    registry.listInstalled.mockResolvedValue([{ id: 'row-1', url: URL }]);
    const store = createInstallDeepLink({ registry, transport });
    await store.handleInstallUrl(URL);
    expect(store.alreadyInstalled).toBe(true);
  });
});
