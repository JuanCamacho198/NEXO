/**
 * Addons installed list — ADD-01 / ADD-02 state coverage.
 *
 * Loading (`isLoading`) means the initial registry read is in flight: the list
 * renders in-shape skeletons and never the empty copy. The empty copy appears
 * only once loading has finished. The install-by-URL form is the secondary
 * affordance the Addons screen moves to the end (`showInstallForm={false}`)
 * while Settings keeps it in-list. Uninstall now requires an explicit
 * confirmation that names the addon before `onUninstall` can fire.
 */
import { fileURLToPath } from 'node:url';
import { dirname, resolve } from 'node:path';
import { readFileSync } from 'node:fs';
import { describe, expect, it, vi } from 'vitest';
import { render, screen } from '@testing-library/svelte';
import userEvent from '@testing-library/user-event';

import AddonsInstalledList from '$lib/features/addons/components/AddonsInstalledList.svelte';
import type { InstalledAddonRow } from '$lib/shared/services/addons/AddonRegistry';
import type { MessageKey } from '$lib/shared/i18n';

const here = dirname(fileURLToPath(import.meta.url));
const readSource = (rel: string): string => readFileSync(resolve(here, rel), 'utf8');

const t = (key: MessageKey): string => key;

const INSTALL_URL = 'https://space.example/manifest.json';

const ROW: InstalledAddonRow = {
  id: 'addon-1',
  url: INSTALL_URL,
  manifest: {
    id: 'space-books',
    name: 'Space Books',
    version: '1.0.0',
    catalogs: [{ type: 'book', id: 'main', name: 'Main catalog' }],
    resources: ['catalog'],
  },
  enabled: true,
  addedAt: 1,
};

function listProps(overrides: Record<string, unknown> = {}): Record<string, unknown> {
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

describe('AddonsInstalledList loading vs empty (ADD-02 G)', () => {
  it('shows skeletons and no false empty copy while the initial refresh is in flight', () => {
    const { container } = render(AddonsInstalledList, listProps({ isLoading: true }));
    expect(container.querySelector('[aria-busy="true"]')).not.toBeNull();
    expect(container.textContent).not.toContain('settings.addons.empty');
  });

  it('shows the empty copy only once loading has finished', () => {
    const { container } = render(AddonsInstalledList, listProps({ isLoading: false }));
    expect(container.querySelector('[aria-busy="true"]')).toBeNull();
    expect(container.textContent).toContain('settings.addons.empty');
  });

  it('never hides existing rows behind the loading state', () => {
    const { container } = render(
      AddonsInstalledList,
      listProps({ installed: [ROW], isLoading: true }),
    );
    expect(container.textContent).toContain('Space Books');
    expect(container.querySelector('[aria-busy="true"]')).not.toBeNull();
  });
});

describe('AddonsInstalledList form placement (ADD-01 A)', () => {
  it('renders the install-by-URL form by default (Settings contract)', () => {
    const { container } = render(AddonsInstalledList, listProps());
    expect(container.querySelector('input[type="url"]')).not.toBeNull();
  });

  it('defers the form when showInstallForm is false (Addons screen contract)', () => {
    const { container } = render(
      AddonsInstalledList,
      listProps({ installed: [ROW], showInstallForm: false }),
    );
    expect(container.textContent).toContain('Space Books');
    expect(container.querySelector('input[type="url"]')).toBeNull();
  });
});

describe('AddonsInstalledList uninstall confirmation (ADD-01 B / C)', () => {
  it('does not uninstall on the first click and names the addon in the prompt', async () => {
    const user = userEvent.setup();
    const onUninstall = vi.fn();
    const { container } = render(AddonsInstalledList, listProps({ installed: [ROW], onUninstall }));

    await user.click(screen.getByRole('button', { name: 'settings.addons.uninstall' }));
    expect(onUninstall).not.toHaveBeenCalled();
    expect(container.textContent).toContain('addons.uninstall.confirm');
    expect(container.textContent).toContain('Space Books');

    await user.click(screen.getByRole('button', { name: 'addons.uninstall.confirmAction' }));
    expect(onUninstall).toHaveBeenCalledWith('addon-1');
  });

  it('cancelling closes the confirmation without uninstalling', async () => {
    const user = userEvent.setup();
    const onUninstall = vi.fn();
    render(AddonsInstalledList, listProps({ installed: [ROW], onUninstall }));

    await user.click(screen.getByRole('button', { name: 'settings.addons.uninstall' }));
    await user.click(screen.getByRole('button', { name: 'addons.uninstall.cancel' }));

    expect(onUninstall).not.toHaveBeenCalled();
    expect(screen.queryByRole('button', { name: 'addons.uninstall.confirmAction' })).toBeNull();
  });

  it('keeps enable/disable and uninstall as named buttons in every row', () => {
    render(AddonsInstalledList, listProps({ installed: [ROW] }));
    expect(screen.getByRole('button', { name: 'settings.addons.disable' })).toBeTruthy();
    expect(screen.getByRole('button', { name: 'settings.addons.uninstall' })).toBeTruthy();
  });
});

describe('AddonsScreen structure (ADD-01 D / ADD-02 H)', () => {
  it('gives the page a real h1 and binds the toast host', () => {
    const source = readSource('../../../../lib/features/addons/AddonsScreen.svelte');
    expect(source).toContain('<h1');
    expect(source).toContain('id="addons-heading"');
    expect(source).not.toContain('<h2 id="addons-heading"');
    expect(source).toContain('pushToast');
    expect(source).toContain('bindAddonsNotifier({ t, pushToast');
  });
});
