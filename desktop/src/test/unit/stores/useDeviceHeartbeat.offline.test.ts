import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';

const mockUpdateHeartbeat = vi.fn();
const mockGetSessionClient = vi.fn();

vi.mock('$lib/services/devices', async () => {
  const actual =
    await vi.importActual<typeof import('$lib/services/devices')>('$lib/services/devices');
  return { ...actual, updateHeartbeat: (...args: unknown[]) => mockUpdateHeartbeat(...args) };
});
vi.mock('$lib/services/supabase', () => ({
  getSessionClient: () => mockGetSessionClient(),
}));

/**
 * Offline guard: the heartbeat is a presence ping, so an offline tick must be
 * skipped BEFORE any client or network work — not attempted and swallowed.
 * Swallowing still pays the request timeout every 120s and hides the reason.
 */
describe('useDeviceHeartbeat — offline tick is skipped', () => {
  beforeEach(() => {
    vi.useFakeTimers();
    mockUpdateHeartbeat.mockResolvedValue(undefined);
    mockGetSessionClient.mockReturnValue({} as never);
  });
  afterEach(() => {
    vi.useRealTimers();
    vi.clearAllMocks();
    vi.unstubAllGlobals();
  });

  it('does not call updateHeartbeat nor read the client while offline', async () => {
    vi.stubGlobal('navigator', { onLine: false });
    const { createDeviceHeartbeat } = await import('$lib/shared/stores/useDeviceHeartbeat.svelte');
    const hb = createDeviceHeartbeat();
    hb.start('dev-1');
    await vi.advanceTimersByTimeAsync(120_000);
    expect(mockUpdateHeartbeat).not.toHaveBeenCalled();
    expect(mockGetSessionClient).not.toHaveBeenCalled();
    hb.destroy();
  });

  it('resumes on the next tick once the network is back', async () => {
    const nav = { onLine: false };
    vi.stubGlobal('navigator', nav);
    const { createDeviceHeartbeat } = await import('$lib/shared/stores/useDeviceHeartbeat.svelte');
    const hb = createDeviceHeartbeat();
    hb.start('dev-1');
    await vi.advanceTimersByTimeAsync(120_000);
    expect(mockUpdateHeartbeat).not.toHaveBeenCalled();
    nav.onLine = true;
    await vi.advanceTimersByTimeAsync(120_000);
    expect(mockUpdateHeartbeat).toHaveBeenCalledTimes(1);
    hb.destroy();
  });
});
