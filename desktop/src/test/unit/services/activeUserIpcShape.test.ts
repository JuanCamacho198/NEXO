/**
 * IPC shape of the active-user commands.
 *
 * The Rust `setActiveUser` command declares `user_id: Option<String>` under
 * `#[tauri::command(rename_all = "camelCase")]`, so the IPC argument name is
 * `userId`. Two failure modes are silent and therefore need pinning:
 *
 * 1. Wrong key casing (`user_id`). Tauri deserializes a *missing* key as
 *    `None` for `Option<String>`, so a mis-cased payload does not error — it
 *    just clears the slot and the owner is never stored.
 * 2. Wrong command name casing (`set_active_user`). Tauri resolves commands by
 *    exact external name, so this one rejects, but the rejection is easy to
 *    swallow on a best-effort path and then never noticed.
 *
 * The adapter tests in `activeUserAdapters.test.ts` mock `tauriClient`, so they
 * cannot see either. This suite mocks `invokeWrapper` — one layer lower — and
 * asserts the exact command name and argument key that cross the IPC boundary.
 */
import { beforeEach, describe, expect, it, vi } from 'vitest';

const invoke = vi.hoisted(() => vi.fn());

vi.mock('$lib/shared/api/invokeWrapper', () => ({ invoke }));

vi.mock('$lib/services/supabase', () => ({ getSessionClient: vi.fn(() => ({})) }));

vi.mock('$lib/services/devices', () => ({
  renameDevice: vi.fn(),
  listDevices: vi.fn(),
  isDeviceStale: vi.fn(),
  removeDevice: vi.fn(),
}));

const { setActiveUser, clearActiveUser } = await import('$lib/shared/api/tauriClient');

describe('active-user IPC shape', () => {
  beforeEach(() => {
    invoke.mockReset();
    invoke.mockResolvedValue(undefined);
  });

  it('invokes setActiveUser with the camelCase userId key', async () => {
    await setActiveUser('user-42');

    expect(invoke).toHaveBeenCalledTimes(1);
    const [command, payload] = invoke.mock.calls[0] as [string, Record<string, unknown>];

    expect(command).toBe('setActiveUser');
    // The exact key, not `user_id`. Tauri accepts the wrong key in silence.
    expect(Object.keys(payload)).toEqual(['userId']);
    expect(payload.userId).toBe('user-42');
  });

  it('forwards null under the same userId key', async () => {
    await setActiveUser(null);

    const [command, payload] = invoke.mock.calls[0] as [string, Record<string, unknown>];

    expect(command).toBe('setActiveUser');
    expect(Object.keys(payload)).toEqual(['userId']);
    expect(payload.userId).toBeNull();
  });

  it('invokes clearActiveUser with no payload', async () => {
    await clearActiveUser();

    expect(invoke).toHaveBeenCalledWith('clearActiveUser');
  });
});
