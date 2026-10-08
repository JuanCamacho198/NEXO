/**
 * AddonInstallConfirmDialog tests (sdd/addon-deeplink-v1 Work Unit A).
 * Mirrors FeedbackDialog.test.ts render patterns: role=dialog, manifest
 * fields render, confirm/cancel buttons, i18n keys, busy + error states.
 */
import { describe, expect, it, vi } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/svelte';

import AddonInstallConfirmDialog from '$lib/shared/ui/addons/AddonInstallConfirmDialog.svelte';
import {
  ADDON_INSTALL_CONFIRM_TITLE,
  ADDON_INSTALL_CONFIRM_DESCRIPTION,
  ADDON_INSTALL_CONFIRM_INSTALL_LABEL,
  ADDON_INSTALL_CONFIRM_INSTALLING_LABEL,
  ADDON_INSTALL_CONFIRM_CANCEL_LABEL,
  ADDON_INSTALL_CONFIRM_VERSION_LABEL,
  ADDON_INSTALL_CONFIRM_PUBLISHER_LABEL,
  ADDON_INSTALL_CONFIRM_ALREADY_INSTALLED,
  addonInstallConfirmErrorText,
} from '$lib/shared/ui/addons/addonInstallDialog';
import { AddonFetchErrorCode, type AddonManifest } from '@nexo/manifest-validator';

const MANIFEST: AddonManifest = {
  id: 'my-addon',
  name: 'My Addon',
  version: '1.0.0',
  catalogs: [{ type: 'book', id: 'main', name: 'Main catalog' }],
  resources: ['catalog'],
};

function renderDialog(
  overrides: {
    open?: boolean;
    manifest?: AddonManifest | null;
    busy?: boolean;
    error?: string | null;
    alreadyInstalled?: boolean;
    onconfirm?: () => void;
    oncancel?: () => void;
  } = {},
) {
  return render(AddonInstallConfirmDialog, {
    open: overrides.open ?? true,
    manifest: overrides.manifest !== undefined ? overrides.manifest : MANIFEST,
    busy: overrides.busy ?? false,
    error: overrides.error ?? null,
    alreadyInstalled: overrides.alreadyInstalled ?? false,
    onconfirm: overrides.onconfirm ?? vi.fn(),
    oncancel: overrides.oncancel ?? vi.fn(),
  });
}

describe('AddonInstallConfirmDialog', () => {
  it('renders the dialog with title and manifest fields', () => {
    renderDialog();
    expect(screen.getByRole('dialog')).toBeInTheDocument();
    expect(screen.getByText(ADDON_INSTALL_CONFIRM_TITLE)).toBeInTheDocument();
    expect(screen.getByText('My Addon')).toBeInTheDocument();
    expect(screen.getByText('1.0.0')).toBeInTheDocument();
    expect(screen.getByText('my-addon')).toBeInTheDocument();
  });

  it('explains the install consequence through the linked description', () => {
    renderDialog();
    const content = document.querySelector('[data-dialog-content]') as HTMLElement;
    const describedBy = content.getAttribute('aria-describedby');
    expect(describedBy).toBeTruthy();
    expect(document.getElementById(describedBy as string)?.textContent).toContain(
      ADDON_INSTALL_CONFIRM_DESCRIPTION,
    );
  });

  it('labels version and publisher with i18n keys', () => {
    renderDialog();
    expect(screen.getByText(ADDON_INSTALL_CONFIRM_VERSION_LABEL)).toBeInTheDocument();
    expect(screen.getByText(ADDON_INSTALL_CONFIRM_PUBLISHER_LABEL)).toBeInTheDocument();
  });

  it('renders confirm (primary Install) and Cancel buttons', () => {
    renderDialog();
    expect(
      screen.getByRole('button', { name: ADDON_INSTALL_CONFIRM_INSTALL_LABEL }),
    ).toBeInTheDocument();
    expect(
      screen.getByRole('button', { name: ADDON_INSTALL_CONFIRM_CANCEL_LABEL }),
    ).toBeInTheDocument();
  });

  it('confirm click invokes onconfirm once', async () => {
    const onconfirm = vi.fn();
    renderDialog({ onconfirm });
    await fireEvent.click(
      screen.getByRole('button', { name: ADDON_INSTALL_CONFIRM_INSTALL_LABEL }),
    );
    expect(onconfirm).toHaveBeenCalledTimes(1);
  });

  it('cancel click invokes oncancel once', async () => {
    const oncancel = vi.fn();
    renderDialog({ oncancel });
    await fireEvent.click(screen.getByRole('button', { name: ADDON_INSTALL_CONFIRM_CANCEL_LABEL }));
    expect(oncancel).toHaveBeenCalledTimes(1);
  });

  it('does not render when open is false', () => {
    renderDialog({ open: false });
    expect(screen.queryByRole('dialog')).toBeNull();
  });

  it('busy state disables buttons and shows installing label', () => {
    renderDialog({ busy: true });
    const install = screen.getByRole('button', {
      name: ADDON_INSTALL_CONFIRM_INSTALLING_LABEL,
    });
    expect(install).toBeDisabled();
    expect(screen.getByRole('button', { name: ADDON_INSTALL_CONFIRM_CANCEL_LABEL })).toBeDisabled();
  });

  it('error text renders from mapped code', () => {
    renderDialog({ error: addonInstallConfirmErrorText(AddonFetchErrorCode.NETWORK) });
    expect(screen.getByText(/ADDON_FETCH_NETWORK/)).toBeInTheDocument();
  });

  it('shows already-installed note when flagged', () => {
    renderDialog({ alreadyInstalled: true });
    expect(screen.getByText(ADDON_INSTALL_CONFIRM_ALREADY_INSTALLED)).toBeInTheDocument();
  });
});
