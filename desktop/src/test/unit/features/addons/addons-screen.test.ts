/**
 * Addons screen content (slice 7, unit 7b): the read-only first-party section
 * lists exactly the two built-ins with no
 * install/uninstall affordance and no registry row; the installed list
 * reflects the shared registry (install-by-URL creates exactly one entry,
 * enable/disable persist, uninstall removes the entry and its sources stop
 * contributing); an inline error or offline state leaves both lists rendered
 * and usable; the deep-link confirmation stays reachable with
 * route === 'addons' and runs the same registry install flow.
 *
 * The shared registry here is an in-memory fake behind the REAL factory
 * (`createSettingsAddons`) and the REAL rebuilding provider — no Tauri
 * invoke is touched, so this file cannot flake on the host transport.
 */
import { fileURLToPath } from 'node:url';
import { dirname, resolve } from 'node:path';
import { readFileSync } from 'node:fs';
import { describe, expect, it, vi } from 'vitest';
import { render, screen } from '@testing-library/svelte';
import userEvent from '@testing-library/user-event';

import AddonsInstalledList from '$lib/features/addons/components/AddonsInstalledList.svelte';
import AddonsFirstPartySection from '$lib/features/addons/components/AddonsFirstPartySection.svelte';
import { FIRST_PARTY_BUILTINS } from '$lib/features/addons/firstPartySources';
import { createSettingsAddons } from '$lib/features/settings/useSettingsAddons.svelte';
import { createInstallDeepLink } from '$lib/features/settings/useInstallDeepLink.svelte';
import { createRebuildingCatalogProvider } from '$lib/shared/services/catalog/CompositeCatalogProvider';
import { addonIdFromUrl as registryAddonIdFromUrl } from '$lib/shared/services/addons/addonId';
import type { AddonManifest } from '@nexo/manifest-validator';
import type { AddonTransport } from '$lib/shared/services/addons/AddonRegistry';
import type { MessageKey } from '$lib/shared/i18n';

const here = dirname(fileURLToPath(import.meta.url));
const readSource = (rel: string): string => readFileSync(resolve(here, rel), 'utf8');

const t = (key: MessageKey): string => key;

const SPACE_MANIFEST: AddonManifest = {
  id: 'space-books',
  name: 'Space Books',
  version: '1.0.0',
  catalogs: [{ type: 'book', id: 'main', name: 'Main catalog' }],
  resources: ['catalog'],
  searchUrl: 'https://space.example/search?q={query}&page={page}',
  detailsUrl: 'https://space.example/book/{bookId}',
};

const DEEP_MANIFEST: AddonManifest = {
  id: 'deep-books',
  name: 'Deep Books',
  version: '1.0.0',
  catalogs: [{ type: 'book', id: 'main', name: 'Main catalog' }],
  resources: ['catalog'],
};

const INSTALL_URL = 'https://space.example/manifest.json';

interface FakeAddonRow {
  id: string;
  url: string;
  manifest: AddonManifest;
  enabled: boolean;
  addedAt: number;
}

/** In-memory registry behind the real factory: both surfaces share one. */
function sharedAddonRegistry(): {
  rows: FakeAddonRow[];
  listInstalled(): Promise<FakeAddonRow[]>;
  install(url: string): Promise<AddonManifest>;
  installManifest(url: string, manifest: AddonManifest): Promise<AddonManifest>;
  setEnabled(id: string, enabled: boolean): Promise<void>;
  uninstall(id: string): Promise<void>;
  onChanged(listener: (version: number) => void): () => void;
} {
  const rows: FakeAddonRow[] = [];
  const listeners = new Set<(version: number) => void>();
  let version = 0;
  let clock = 0;
  const notify = (): void => {
    version += 1;
    for (const listener of [...listeners]) listener(version);
  };
  const registry = {
    rows,
    async listInstalled(): Promise<FakeAddonRow[]> {
      return rows.map((r) => ({ ...r }));
    },
    async install(url: string): Promise<AddonManifest> {
      return registry.installManifest(url, { ...SPACE_MANIFEST });
    },
    async installManifest(url: string, manifest: AddonManifest): Promise<AddonManifest> {
      const id = await registryAddonIdFromUrl(url);
      const existing = rows.find((r) => r.id === id);
      if (existing) existing.manifest = manifest;
      else rows.push({ id, url, manifest, enabled: true, addedAt: (clock += 1) });
      notify();
      return manifest;
    },
    async setEnabled(id: string, enabled: boolean): Promise<void> {
      const row = rows.find((r) => r.id === id);
      if (row) row.enabled = enabled;
      notify();
    },
    async uninstall(id: string): Promise<void> {
      const i = rows.findIndex((r) => r.id === id);
      if (i >= 0) rows.splice(i, 1);
      notify();
    },
    onChanged(listener: (version: number) => void): () => void {
      listeners.add(listener);
      return () => {
        listeners.delete(listener);
      };
    },
  };
  return registry;
}

function payloadTransport(): AddonTransport & { calls: string[] } {
  const calls: string[] = [];
  const transport = (async (url: string) => {
    calls.push(url);
    return {
      status: 200,
      contentType: 'application/json',
      body: new TextEncoder().encode(JSON.stringify({ id: 'book-1', title: 'Space Book' })),
    };
  }) as AddonTransport;
  return Object.assign(transport, { calls });
}

function manifestTransport(manifest: unknown): AddonTransport {
  return (async () => ({
    status: 200,
    contentType: 'application/json',
    body: new TextEncoder().encode(
      typeof manifest === 'string' ? manifest : JSON.stringify(manifest),
    ),
  })) as AddonTransport;
}

describe('addons first-party section (read-only)', () => {
  it('lists exactly the two built-ins', () => {
    expect(FIRST_PARTY_BUILTINS.map((s) => s.sourceId)).toEqual([
      'builtin:gutendex',
      'builtin:openlibrary',
    ]);
    expect(FIRST_PARTY_BUILTINS.map((s) => s.name)).toEqual(['Gutendex', 'Open Library']);
  });

  it('renders every first-party name with no install/uninstall affordance', () => {
    const { container } = render(AddonsFirstPartySection, { t });
    for (const name of ['Gutendex', 'Open Library']) {
      expect(container.textContent).toContain(name);
    }
    // No install/uninstall/enable affordance anywhere in the section.
    expect(container.querySelector('button')).toBeNull();
    expect(container.querySelector('input')).toBeNull();
  });

  it('never touches the registry: no registry, install, or uninstall surface', () => {
    const model = readSource('../../../../lib/features/addons/firstPartySources.ts');
    expect(model).not.toContain('getAddonRegistry');
    expect(model).not.toContain('new AddonRegistry');
    expect(model).not.toContain('.install(');
    expect(model).not.toContain('.uninstall(');
    const section = readSource(
      '../../../../lib/features/addons/components/AddonsFirstPartySection.svelte',
    );
    expect(section).toContain('FIRST_PARTY_BUILTINS');
    expect(section).not.toContain('FIRST_PARTY_CURATED');
    expect(section).not.toContain('onInstall');
    expect(section).not.toContain('onUninstall');
    expect(section).not.toContain('onToggle');
    expect(section).not.toContain('AddonRegistry');
    expect(section).not.toContain('install(');
    expect(section).not.toContain('uninstall(');
  });
});

describe('addons installed list (presentational)', () => {
  function listProps(overrides = {}): Record<string, unknown> {
    return {
      t,
      url: '',
      installed: [],
      isBusy: false,
      installOutcome: { kind: 'idle' as const },
      onUrlChange: vi.fn(),
      onInstall: vi.fn(),
      onToggle: vi.fn(),
      onUninstall: vi.fn(),
      ...overrides,
    };
  }

  it('renders the install form and the empty copy', () => {
    const { container } = render(AddonsInstalledList, listProps());
    expect(container.querySelector('input[type="url"]')).not.toBeNull();
    expect(container.textContent).toContain('settings.addons.empty');
    expect(container.querySelector('[role="alert"]')).toBeNull();
  });

  it('renders rows with enable/disable + uninstall and calls back with the row id', async () => {
    const user = userEvent.setup();
    const onToggle = vi.fn();
    const onUninstall = vi.fn();
    const onInstall = vi.fn();
    const { container } = render(
      AddonsInstalledList,
      listProps({
        installed: [
          {
            id: 'addon-1',
            url: INSTALL_URL,
            manifest: SPACE_MANIFEST,
            enabled: true,
            addedAt: 1,
          },
        ],
        onToggle,
        onUninstall,
        onInstall,
      }),
    );
    expect(container.textContent).toContain('Space Books');
    expect(container.textContent).toContain(INSTALL_URL);
    await user.click(screen.getByRole('button', { name: 'settings.addons.disable' }));
    expect(onToggle).toHaveBeenCalledWith('addon-1', false);
    // ADD-01 B: uninstall is destructive and now requires an explicit
    // confirmation that names the addon; the first click only arms it.
    await user.click(screen.getByRole('button', { name: 'settings.addons.uninstall' }));
    expect(onUninstall).not.toHaveBeenCalled();
    expect(container.textContent).toContain('addons.uninstall.confirm');
    await user.click(screen.getByRole('button', { name: 'addons.uninstall.confirmAction' }));
    expect(onUninstall).toHaveBeenCalledWith('addon-1');
    await user.click(screen.getByRole('button', { name: 'settings.addons.install' }));
    expect(onInstall).toHaveBeenCalledTimes(1);
  });

  it('keeps both lists rendered and usable with an inline error', async () => {
    const user = userEvent.setup();
    const onInstall = vi.fn();
    const onToggle = vi.fn();
    const { container } = render(
      AddonsInstalledList,
      listProps({
        installed: [
          {
            id: 'addon-1',
            url: INSTALL_URL,
            manifest: SPACE_MANIFEST,
            enabled: true,
            addedAt: 1,
          },
        ],
        installOutcome: { kind: 'error' as const, code: 'ADDON_FETCH_INVALID_MANIFEST' },
        onInstall,
        onToggle,
      }),
    );
    // The inline error is present…
    const alert = container.querySelector('[role="alert"]');
    expect(alert?.textContent).toContain('addons.install.errorInline');
    // …and the installed list stays mounted and usable.
    expect(container.textContent).toContain('Space Books');
    await user.click(screen.getByRole('button', { name: 'settings.addons.disable' }));
    expect(onToggle).toHaveBeenCalledWith('addon-1', false);
    await user.click(screen.getByRole('button', { name: 'addons.install.retry' }));
    expect(onInstall).toHaveBeenCalledTimes(1);
  });

  it('keeps both lists rendered with the offline state', () => {
    const { container } = render(
      AddonsInstalledList,
      listProps({
        installed: [
          {
            id: 'addon-1',
            url: INSTALL_URL,
            manifest: SPACE_MANIFEST,
            enabled: false,
            addedAt: 1,
          },
        ],
        installOutcome: { kind: 'offline' as const },
      }),
    );
    expect(container.querySelector('[role="alert"]')?.textContent).toContain(
      'addons.install.offline',
    );
    expect(container.textContent).toContain('Space Books');
    expect(container.textContent).toContain(INSTALL_URL);
  });
});

describe('addons screen state (shared registry)', () => {
  it('install-by-URL creates exactly one entry; enable/disable persist; uninstall removes it', async () => {
    const registry = sharedAddonRegistry();
    const state = createSettingsAddons({ registry, pushToast: vi.fn(), t });
    state.url = INSTALL_URL;
    await state.handleInstall();
    expect(registry.rows).toHaveLength(1);
    expect(state.installed).toHaveLength(1);
    const id = state.installed[0].id;

    await state.handleToggle(id, false);
    await state.refresh();
    expect(state.installed[0].enabled).toBe(false);

    await state.handleToggle(id, true);
    await state.refresh();
    expect(state.installed[0].enabled).toBe(true);

    await state.handleUninstall(id);
    expect(registry.rows).toHaveLength(0);
    expect(state.installed).toHaveLength(0);
  });

  it('uninstall stops the addon sources from contributing with no extra I/O', async () => {
    const registry = sharedAddonRegistry();
    const payload = payloadTransport();
    const supplier = createRebuildingCatalogProvider(() => registry.listInstalled(), payload);
    registry.onChanged(() => supplier.invalidate());
    const state = createSettingsAddons({ registry, pushToast: vi.fn(), t });

    state.url = INSTALL_URL;
    await state.handleInstall();
    const id = state.installed[0].id;
    const addonId = await registryAddonIdFromUrl(INSTALL_URL);

    const built = await supplier.current();
    expect(
      built
        .listSources()
        .filter((s) => s.kind === 'addon')
        .map((s) => s.sourceId),
    ).toEqual([`addon:${addonId}`]);
    const callsAfterBuild = payload.calls.length;

    await state.handleUninstall(id);
    const gone = await supplier.current();
    expect(gone.listSources().filter((s) => s.kind === 'addon')).toEqual([]);
    await expect(gone.getDetails(`addon:${addonId}:book-1`)).rejects.toMatchObject({
      code: 'NOT_FOUND',
    });
    expect(payload.calls).toHaveLength(callsAfterBuild);
  });

  it('deep-link confirmation runs the same registry install flow and is visible to the screen state', async () => {
    const registry = sharedAddonRegistry();
    const state = createSettingsAddons({ registry, pushToast: vi.fn(), t });
    const flow = createInstallDeepLink({
      registry,
      transport: manifestTransport(DEEP_MANIFEST),
    });

    await flow.handleInstallUrl('https://deep.example/manifest.json');
    expect(flow.state).toBe('confirming');
    expect(flow.dialogOpen).toBe(true);
    await flow.confirm();
    expect(registry.rows).toHaveLength(1);

    await state.refresh();
    expect(state.installed).toHaveLength(1);
    expect(state.installed[0].manifest.id).toBe('deep-books');
  });

  it('keeps the deep-link confirmation shell-global so it stays reachable with route === addons', () => {
    const modals = readSource('../../../../lib/shared/ui/layout/AppModals.svelte');
    expect(modals).toContain('AddonInstallConfirmDialog');
    expect(modals).toContain('installDeepLink.dialogOpen');
  });
});

describe('addons screen composition', () => {
  it('composes the shared list, the first-party section, and the singleton wiring', () => {
    const screenSource = readSource('../../../../lib/features/addons/AddonsScreen.svelte');
    expect(screenSource).toContain('<AddonsInstalledList');
    expect(screenSource).toContain('<AddonsFirstPartySection');
    expect(screenSource).toContain('addonsState');
    expect(screenSource).toContain('bindAddonsNotifier');
    expect(screenSource).toContain("t('addons.title')");
    expect(screenSource).toContain("t('addons.subtitle')");

    const store = readSource('../../../../lib/features/addons/addonsStore.svelte.ts');
    expect(store).toContain('getAddonRegistry()');
    expect(store).toContain('bindAddonsNotifier');
    expect(store).not.toContain('new AddonRegistry');

    const panel = readSource('../../../../lib/features/settings/components/SettingsPanel.svelte');
    expect(panel).toContain('addonsState');
    expect(panel).toContain('bindAddonsNotifier');
    expect(panel).not.toContain('createSettingsAddons(');

    const section = readSource(
      '../../../../lib/features/settings/components/SettingsAddonsSection.svelte',
    );
    expect(section).toContain('<AddonsInstalledList');
    expect(section).toContain('installOutcome');
  });
});
