import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';

const mockPluginStart = vi.fn();
const mockOpenUrl = vi.fn();

vi.mock('@fabianlars/tauri-plugin-oauth', () => ({
  start: (...args: unknown[]) => mockPluginStart(...args),
  cancel: () => Promise.resolve(),
  onUrl: () => Promise.resolve(() => {}),
}));
vi.mock('@tauri-apps/plugin-opener', () => ({
  openUrl: (...args: unknown[]) => mockOpenUrl(...args),
}));
vi.mock('$lib/shared/stores/drivePersistence', () => ({
  loadDriveGrant: () => Promise.resolve(null),
  saveDriveGrant: () => Promise.resolve(),
  clearDriveGrant: () => Promise.resolve(),
}));
vi.mock('$lib/shared/logger/Logger', () => ({
  logger: { warn: vi.fn(), error: vi.fn(), info: vi.fn(), debug: vi.fn() },
}));

import {
  beginDriveConnect,
  __resetDriveConnectForTests,
} from '$lib/shared/services/DriveConnectService';
import { driveState } from '$lib/shared/stores/driveState.svelte';

/**
 * Offline guard: the connect flow starts a loopback server, launches the
 * browser and then awaits a callback that arrives over the network boundary.
 * Offline, that wait never settles and `driveState.isConnecting` stays true —
 * `DriveSection.svelte` renders the "Connecting…" spinner forever and the
 * Disconnect/Connect buttons stay disabled. The guard must reject the attempt
 * up front with an explicit offline failure instead of hanging.
 */
describe('beginDriveConnect — offline is an explicit failure, not a hang', () => {
  beforeEach(() => {
    __resetDriveConnectForTests();
    driveState.resetDriveState();
    mockPluginStart.mockReset().mockResolvedValue(42813);
    mockOpenUrl.mockReset().mockResolvedValue(undefined);
    vi.stubEnv('VITE_GOOGLE_OAUTH_CLIENT_ID', 'test-client-id');
  });

  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it('returns a failure carrying DRIVE_OFFLINE and leaves isConnecting false', async () => {
    vi.stubGlobal('navigator', { onLine: false });

    const result = await beginDriveConnect();

    expect(result.kind).toBe('failure');
    if (result.kind === 'failure') {
      expect(result.code).toBe('DRIVE_OFFLINE');
    }
    expect(driveState.isConnecting).toBe(false);
  });

  it('does no loopback or browser work while offline', async () => {
    vi.stubGlobal('navigator', { onLine: false });

    await beginDriveConnect();

    expect(mockPluginStart).not.toHaveBeenCalled();
    expect(mockOpenUrl).not.toHaveBeenCalled();
  });
});
