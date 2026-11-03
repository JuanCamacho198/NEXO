/**
 * Active-user port adapters.
 *
 * The Rust `setActiveUser` command names its IPC argument `userId` (the
 * command uses `rename_all = "camelCase"`), and `clearActiveUser` takes no
 * argument at all. The Tauri adapter must forward exactly that shape, or the
 * backend sees a missing field and rejects.
 *
 * The mock keeps the last id in memory so wiring can be asserted without a
 * running app.
 */
import { beforeEach, describe, expect, it, vi } from 'vitest';

vi.mock('$lib/shared/api/tauriClient', () => ({
  setActiveUser: vi.fn(),
  clearActiveUser: vi.fn(),
}));

const { setActiveUser, clearActiveUser } = await import('$lib/shared/api/tauriClient');
const { TauriActiveUserAdapter } =
  await import('$lib/shared/ports/adapters/tauri/TauriActiveUserAdapter');
const { MockActiveUserAdapter } =
  await import('$lib/shared/ports/adapters/mock/MockActiveUserAdapter');

describe('TauriActiveUserAdapter', () => {
  beforeEach(() => {
    vi.mocked(setActiveUser).mockReset();
    vi.mocked(clearActiveUser).mockReset();
  });

  it('forwards the userId to the setActiveUser command', async () => {
    vi.mocked(setActiveUser).mockResolvedValue(undefined);
    const adapter = new TauriActiveUserAdapter();

    await adapter.setActiveUser('user-42');

    expect(setActiveUser).toHaveBeenCalledWith('user-42');
  });

  it('forwards null to the setActiveUser command', async () => {
    vi.mocked(setActiveUser).mockResolvedValue(undefined);
    const adapter = new TauriActiveUserAdapter();

    await adapter.setActiveUser(null);

    expect(setActiveUser).toHaveBeenCalledWith(null);
  });

  it('calls clearActiveUser and propagates a rejection', async () => {
    vi.mocked(clearActiveUser).mockRejectedValue(new Error('ACTIVE_USER_CLEAR_FAILED'));
    const adapter = new TauriActiveUserAdapter();

    await expect(adapter.clearActiveUser()).rejects.toThrow('ACTIVE_USER_CLEAR_FAILED');
    expect(clearActiveUser).toHaveBeenCalledTimes(1);
  });
});

describe('MockActiveUserAdapter', () => {
  it('stores the userId after setActiveUser', async () => {
    const adapter = new MockActiveUserAdapter();

    await adapter.setActiveUser('abc');

    expect(adapter.activeUserId).toBe('abc');
  });

  it('clears the userId after clearActiveUser', async () => {
    const adapter = new MockActiveUserAdapter();
    await adapter.setActiveUser('abc');

    await adapter.clearActiveUser();

    expect(adapter.activeUserId).toBeNull();
  });

  it('accepts null without throwing', async () => {
    const adapter = new MockActiveUserAdapter();

    await expect(adapter.setActiveUser(null)).resolves.toBeUndefined();
    expect(adapter.activeUserId).toBeNull();
  });

  it('records every call, oldest first — for assertions on wiring', async () => {
    const adapter = new MockActiveUserAdapter();

    await adapter.setActiveUser('abc');
    await adapter.clearActiveUser();
    await adapter.setActiveUser('def');

    expect(adapter.calls).toEqual(['set:abc', 'clear', 'set:def']);
  });
});
