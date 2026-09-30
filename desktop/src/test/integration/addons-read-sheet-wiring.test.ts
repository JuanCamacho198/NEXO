/**
 * Integration (design/testing `Integration` row): the Addons screen's read-sheet
 * wiring.
 *
 * W2 recorded that this wiring was guarded only by a `toContain` source-read.
 * This suite mounts the REAL `AddonsScreen` and drives the screen-owned
 * `createAddonReadSheet` machine into a real state, then clicks the REAL
 * `AddonReadSheet` buttons so the screen's own `onClose` / `onRetry` / `onAllow`
 * / `onDeny` / `onCancel` bindings execute.
 *
 * The screen owns its machine privately (and the spec deliberately ships no
 * user-reachable trigger — verify S1), so the hook factory is wrapped with
 * `importOriginal`: the REAL machine is created and every port stays real —
 * production `liveCatalogProvider` → rebuilding composite → consent-gated
 * `AddonCatalogProvider` → mocked Tauri transport seam (`fetchAddonResource`),
 * production `addonConsent` singleton, production `AddonReadSheet`. The
 * machine is exposed only so the test can call `open`; each wrapper method
 * delegates to the real one after counting the call.
 *
 * Offline: the built-in datasources are served by an injected `fetch`, and the
 * Tauri IPC seam is a local mock (no Rust, no network).
 */
import { afterAll, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';
import { invoke } from '@tauri-apps/api/core';
import { render, screen } from '@testing-library/svelte';
import userEvent from '@testing-library/user-event';

import AddonsScreen from '$lib/features/addons/AddonsScreen.svelte';
import { addonsState } from '$lib/features/addons/addonsStore.svelte';
import { addonConsent } from '$lib/shared/services/addons/AddonConsent';
import type { CatalogBook, CatalogSource } from '$lib/shared/services/catalog/CatalogProvider';
import type { AddonManifest } from '@nexo/manifest-validator';
import type { MessageKey } from '$lib/shared/i18n';
import type { AddonReadState } from '$lib/features/addons/useAddonReadSheet.svelte';

const sheetHarness = vi.hoisted(() => ({
  machine: null as unknown,
  deps: null as unknown,
  calls: { close: 0, retry: 0, allow: 0, deny: 0, cancelDownload: 0 },
}));

vi.mock('$lib/features/addons/useAddonReadSheet.svelte', async (importOriginal) => {
  const actual =
    await importOriginal<typeof import('$lib/features/addons/useAddonReadSheet.svelte')>();
  return {
    ...actual,
    createAddonReadSheet: (deps: Parameters<typeof actual.createAddonReadSheet>[0]) => {
      // The REAL machine over the REAL ports the screen passes.
      const machine = actual.createAddonReadSheet(deps);
      sheetHarness.machine = machine;
      sheetHarness.deps = deps;
      return {
        get state() {
          return machine.state;
        },
        open: machine.open,
        close: () => {
          sheetHarness.calls.close += 1;
          machine.close();
        },
        retry: () => {
          sheetHarness.calls.retry += 1;
          return machine.retry();
        },
        allow: () => {
          sheetHarness.calls.allow += 1;
          return machine.allow();
        },
        deny: () => {
          sheetHarness.calls.deny += 1;
          return machine.deny();
        },
        cancelDownload: () => {
          sheetHarness.calls.cancelDownload += 1;
          machine.cancelDownload();
        },
      };
    },
  };
});

interface SheetMachine {
  readonly state: AddonReadState;
  open: (book: CatalogBook, addonId: string) => Promise<void>;
}

const ADDON_ID = 'a1b2c3d4e5f60718';
const INSTALL_URL = 'https://space.example/manifest.json';
const READ_URL = 'https://shop.example/buy/1';

const RESOLVE_MANIFEST: AddonManifest = {
  id: 'space-books',
  name: 'Space Books',
  version: '2.0.0',
  catalogs: [{ type: 'book', id: 'main', name: 'Main catalog' }],
  resources: ['catalog'],
  searchUrl: 'https://space.example/search?q={query}&page={page}',
  resolveUrl: 'https://space.example/resolve?isbn={isbn}',
  capabilities: ['resolve'],
};

/** The resolve payload the mocked Rust transport seam answers. */
const RESOLVE_PAYLOAD = {
  results: [{ accessType: 'buy', readUrl: READ_URL }],
};

const BOOK: CatalogBook = {
  id: `addon:${ADDON_ID}:book-1`,
  provider: `addon:${ADDON_ID}` as CatalogSource,
  title: 'Dune',
  authors: ['Herbert'],
  coverUrl: null,
  languages: ['en'],
  subjects: ['sci-fi'],
  downloadUrl: null,
};

const t = (key: MessageKey): string => key;

const builtInFetchCalls: string[] = [];

function offlineBuiltInFetch(url: string): Promise<Response> {
  builtInFetchCalls.push(url);
  return Promise.resolve({
    ok: true,
    status: 200,
    json: () => Promise.resolve({ results: [], count: 0, numFound: 0, docs: [] }),
  } as unknown as Response);
}

const ambientFetch = globalThis.fetch;

const invokeLog: { cmd: string; args?: Record<string, unknown> }[] = [];

beforeAll(() => {
  globalThis.fetch = offlineBuiltInFetch as unknown as typeof fetch;
});

afterAll(() => {
  globalThis.fetch = ambientFetch;
});

function registeredAddonRow(): Record<string, unknown> {
  return {
    id: ADDON_ID,
    url: INSTALL_URL,
    manifestJson: JSON.stringify(RESOLVE_MANIFEST),
    enabled: true,
    addedAt: 1,
  };
}

beforeEach(async () => {
  sheetHarness.calls = { close: 0, retry: 0, allow: 0, deny: 0, cancelDownload: 0 };
  invokeLog.length = 0;
  vi.mocked(invoke).mockImplementation(async (cmd: string, args?: unknown) => {
    invokeLog.push({ cmd, args: args as Record<string, unknown> | undefined });
    switch (cmd) {
      case 'listInstalledAddons':
        return [registeredAddonRow()];
      case 'listAddonConsents':
        return [];
      case 'fetchAddonResource':
        return {
          status: 200,
          contentType: 'application/json',
          body: Array.from(new TextEncoder().encode(JSON.stringify(RESOLVE_PAYLOAD))),
        };
      default:
        return null;
    }
  });
  // Fail-closed start for the production consent singleton (per-addon).
  await addonConsent.revoke(ADDON_ID);
});

function readSheetMachine(): SheetMachine {
  return sheetHarness.machine as SheetMachine;
}

function transportCalls(): number {
  return invokeLog.filter((entry) => entry.cmd === 'fetchAddonResource').length;
}

function consentWrites(): { id: unknown; granted: unknown }[] {
  return invokeLog
    .filter((entry) => entry.cmd === 'setAddonConsent')
    .map((entry) => ({ id: entry.args?.id, granted: entry.args?.granted }));
}

describe('addons screen read-sheet wiring (real handlers)', () => {
  it('mounts the real screen with the sheet Hidden and performs no catalog I/O', async () => {
    const { container } = render(AddonsScreen, { t });

    await vi.waitFor(() => expect(screen.getByText('addons.title')).toBeTruthy());
    // The screen-owned real machine starts Hidden and the real sheet composes
    // nothing (the screen mounted it; nothing has opened it).
    expect(readSheetMachine().state).toEqual({ kind: 'Hidden' });
    expect(container.querySelector('[role="dialog"]')).toBeNull();
    expect(builtInFetchCalls).toEqual([]);
  });

  it('drives the addon-book consent flow end to end through the screen handlers', async () => {
    const user = userEvent.setup();
    const { container } = render(AddonsScreen, { t });

    // The screen's real `addonNameOf` port reads the live installed rows.
    await vi.waitFor(() => expect(addonsState.installed).toHaveLength(1));
    const deps = sheetHarness.deps as { addonNameOf: (id: string) => string };
    expect(deps.addonNameOf(ADDON_ID)).toBe('Space Books');

    // Open the addon book: the production resolve port reaches the
    // consent-gated addon provider, which fails closed BEFORE any transport.
    await readSheetMachine().open(BOOK, ADDON_ID);
    await vi.waitFor(() => expect(screen.getByText('addons.consent.title')).toBeTruthy());
    expect(screen.getByText('Space Books')).toBeTruthy();
    expect(transportCalls()).toBe(0);

    // Click the real Allow button: the screen's `onAllow` binding grants
    // durably and re-resolves, this time through the mocked transport seam.
    await user.click(screen.getByRole('button', { name: 'addons.consent.allow' }));
    expect(sheetHarness.calls.allow).toBe(1);
    await vi.waitFor(() => expect(screen.getByText('addons.readSheet.legalNotice')).toBeTruthy());
    expect(consentWrites().at(-1)).toEqual({ id: ADDON_ID, granted: true });
    expect(transportCalls()).toBe(1);
    const link = container.querySelector(`a[href="${READ_URL}"]`);
    expect(link?.textContent).toContain('discover.accessOpen');

    // Click the real Dismiss button: the screen's `onClose` binding hides the
    // sheet again.
    await user.click(screen.getByRole('button', { name: 'discover.dismiss' }));
    expect(sheetHarness.calls.close).toBe(1);
    await vi.waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
  });

  it('drives the deny binding to a durable withdrawal with zero resolve I/O', async () => {
    const user = userEvent.setup();
    render(AddonsScreen, { t });

    await vi.waitFor(() => expect(addonsState.installed).toHaveLength(1));
    await readSheetMachine().open(BOOK, ADDON_ID);
    await vi.waitFor(() => expect(screen.getByText('addons.consent.title')).toBeTruthy());

    await user.click(screen.getByRole('button', { name: 'addons.consent.deny' }));
    expect(sheetHarness.calls.deny).toBe(1);
    await vi.waitFor(() =>
      expect(consentWrites().at(-1)).toEqual({ id: ADDON_ID, granted: false }),
    );
    await vi.waitFor(() => expect(readSheetMachine().state.kind).toBe('ConsentRequired'));
    expect(transportCalls()).toBe(0);
  });
});
