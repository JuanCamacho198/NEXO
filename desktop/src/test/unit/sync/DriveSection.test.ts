/**
 * PR4 — Drive section three honest states (FR-DD1).
 *
 * Covers `DriveSection.svelte` with a mocked DriveConnectService (existing
 * DriveConnectService.test.ts pattern): not-connected offers Connect,
 * connected shows the linked indicator plus Disconnect, error shows the
 * reason plus Retry, retry success recovers to connected, and Disconnect
 * delegates to `disconnectDrive` (which clears only the Drive grant — the
 * service-level guarantee pinned by DriveConnectService.test.ts).
 */

import { fireEvent, render, screen } from '@testing-library/svelte';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import DriveSection from '$lib/features/sync/components/DriveSection.svelte';
import { driveState } from '$lib/shared/stores/driveState.svelte';
import type { MessageKey } from '$lib/shared/i18n/messages.en';

const mockBeginDriveConnect = vi.fn();
const mockDisconnectDrive = vi.fn();
const mockIsDriveAuthorized = vi.fn();

vi.mock('$lib/shared/services/DriveConnectService', () => ({
  beginDriveConnect: (...args: unknown[]) => mockBeginDriveConnect(...args),
  disconnectDrive: (...args: unknown[]) => mockDisconnectDrive(...args),
  isDriveAuthorized: (...args: unknown[]) => mockIsDriveAuthorized(...args),
}));

vi.mock('$lib/shared/stores/ToastQueue.svelte', () => ({
  pushToast: vi.fn(),
}));

const t = (key: MessageKey): string => key;

beforeEach(() => {
  vi.clearAllMocks();
  driveState.resetDriveState();
  mockIsDriveAuthorized.mockResolvedValue(false);
  mockBeginDriveConnect.mockResolvedValue({ kind: 'canceled' });
  mockDisconnectDrive.mockImplementation(() => {
    driveState.setDriveAuthorized(false);
    return Promise.resolve();
  });
});

describe('DriveSection three states (FR-DD1)', () => {
  it('not connected offers Connect and no Disconnect', async () => {
    render(DriveSection, { props: { t } });
    expect(await screen.findByText('settings.sync.drive.notConnected')).toBeInTheDocument();
    expect(await screen.findByText('settings.sync.drive.connect')).toBeInTheDocument();
    expect(screen.queryByText('settings.sync.drive.disconnect')).toBeNull();
  });

  it('connecting shows a spinner state with no action', async () => {
    driveState.setDriveConnecting(true);
    render(DriveSection, { props: { t } });
    expect(await screen.findByText('settings.sync.drive.connecting')).toBeInTheDocument();
    expect(screen.queryByText('settings.sync.drive.connect')).toBeNull();
    expect(screen.queryByText('settings.sync.drive.disconnect')).toBeNull();
    expect(screen.queryByText('error.retry')).toBeNull();
  });

  it('connected shows the linked indicator and Disconnect', async () => {
    driveState.setDriveAuthorized(true);
    render(DriveSection, { props: { t } });
    expect(await screen.findByText('settings.sync.drive.connected')).toBeInTheDocument();
    expect(await screen.findByText('settings.sync.drive.disconnect')).toBeInTheDocument();
    expect(screen.queryByText('settings.sync.drive.connect')).toBeNull();
  });

  it('error shows the reason with Retry and recovers to connected', async () => {
    driveState.setDriveError('LOOPBACK_UNAVAILABLE');
    mockBeginDriveConnect.mockImplementation(() => {
      driveState.setDriveAuthorized(true);
      return Promise.resolve({ kind: 'success', accessToken: 'token' });
    });
    render(DriveSection, { props: { t } });

    expect(await screen.findByText('LOOPBACK_UNAVAILABLE')).toBeInTheDocument();
    const retry = await screen.findByText('error.retry');
    await fireEvent.click(retry.closest('button') ?? retry);

    expect(mockBeginDriveConnect).toHaveBeenCalledOnce();
    expect(await screen.findByText('settings.sync.drive.connected')).toBeInTheDocument();
    expect(await screen.findByText('settings.sync.drive.disconnect')).toBeInTheDocument();
  });

  it('Disconnect delegates to disconnectDrive, clearing the grant only', async () => {
    driveState.setDriveAuthorized(true);
    render(DriveSection, { props: { t } });

    const disconnect = await screen.findByText('settings.sync.drive.disconnect');
    await fireEvent.click(disconnect.closest('button') ?? disconnect);

    expect(mockDisconnectDrive).toHaveBeenCalledOnce();
    expect(await screen.findByText('settings.sync.drive.notConnected')).toBeInTheDocument();
  });
});

describe('DriveSection atom migration (UI-04)', () => {
  it('renders Connect through the Button atom with its testid', async () => {
    render(DriveSection, { props: { t } });

    const connect = await screen.findByTestId('drive-connect');
    expect(connect.tagName).toBe('BUTTON');
    expect(connect).toHaveClass('text-sm', 'font-medium', 'rounded-lg', 'inline-flex');
  });

  it('renders Disconnect through the Button atom with its testid', async () => {
    driveState.setDriveAuthorized(true);
    render(DriveSection, { props: { t } });

    const disconnect = await screen.findByTestId('drive-disconnect');
    expect(disconnect.tagName).toBe('BUTTON');
    expect(disconnect).toHaveClass('text-sm', 'font-medium', 'rounded-lg', 'inline-flex');
  });

  it('renders Retry through the Button atom with its testid', async () => {
    driveState.setDriveError('LOOPBACK_UNAVAILABLE');
    render(DriveSection, { props: { t } });

    const retry = await screen.findByTestId('drive-retry');
    expect(retry.tagName).toBe('BUTTON');
    expect(retry).toHaveClass('text-sm', 'font-medium', 'rounded-lg', 'inline-flex');
  });

  it('renders no raw buttons in the connected state', async () => {
    driveState.setDriveAuthorized(true);
    const { container } = render(DriveSection, { props: { t } });
    await screen.findByTestId('drive-disconnect');

    for (const button of container.querySelectorAll('button')) {
      expect(button.classList.contains('font-medium')).toBe(true);
    }
  });
});
