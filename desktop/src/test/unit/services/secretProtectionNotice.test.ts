/**
 * Secret protection startup notice (desktop-security-checklist-audit #08).
 *
 * On macOS/Linux the Rust side cannot seal secrets, so the Supabase session is
 * never written to disk. That used to be an `eprintln!` in a shipped GUI app —
 * invisible to the user. `surfaceSecretProtectionUnavailable` turns it into one
 * tray entry, exactly once per launch, and does nothing when protection works.
 */
import { describe, it, expect, beforeEach, vi } from 'vitest';

const notifySpy = vi.fn();
vi.mock('$lib/shared/stores/notificationCenter.svelte', () => ({
  notify: (...args: unknown[]) => notifySpy(...args),
}));

import {
  SECRET_PROTECTION_DEDUP_KEY,
  checkSecretProtectionAtStartup,
  secretProtectionNoticeKey,
  surfaceSecretProtectionUnavailable,
} from '$lib/shared/services/secretProtectionNotice';

beforeEach(() => {
  notifySpy.mockClear();
});

describe('surfaceSecretProtectionUnavailable', () => {
  it('does nothing when protection is available (Windows)', () => {
    surfaceSecretProtectionUnavailable(true, vi.fn());
    expect(notifySpy).not.toHaveBeenCalled();
  });

  it('requests the banner exactly once when protection is unavailable', async () => {
    const warn = vi.fn();
    surfaceSecretProtectionUnavailable(false, warn);
    expect(warn).toHaveBeenCalledWith(expect.stringContaining(SECRET_PROTECTION_DEDUP_KEY));
    await Promise.resolve();
    expect(notifySpy).toHaveBeenCalledTimes(1);
    expect(notifySpy.mock.calls[0]?.[0]).toMatchObject({
      severity: 'warning',
      i18nKey: 'notifications.secrets.unavailable',
      dedupKey: SECRET_PROTECTION_DEDUP_KEY,
    });
  });

  it('still emits the notice when a re-read of the diagnostic fails', async () => {
    const warn = vi.fn();
    surfaceSecretProtectionUnavailable(false, warn, {
      readDiagnostic: () => Promise.reject(new Error('ipc down')),
    });
    await Promise.resolve();
    expect(notifySpy).toHaveBeenCalledTimes(1);
  });
});

describe('secretProtectionNoticeKey', () => {
  it('uses the new i18n key, not the generic auth one', () => {
    expect(secretProtectionNoticeKey()).toBe('notifications.secrets.unavailable');
  });
});

describe('checkSecretProtectionAtStartup', () => {
  it('never notifies when the backend reports protection available (Windows)', async () => {
    await checkSecretProtectionAtStartup(vi.fn(), () => Promise.resolve(true));
    expect(notifySpy).not.toHaveBeenCalled();
  });

  it('notifies when the backend reports protection unavailable', async () => {
    await checkSecretProtectionAtStartup(vi.fn(), () => Promise.resolve(false));
    await Promise.resolve();
    expect(notifySpy).toHaveBeenCalledTimes(1);
  });

  it('stays silent when the IPC is missing (web host)', async () => {
    await checkSecretProtectionAtStartup(vi.fn(), () => Promise.reject(new Error('no ipc')));
    expect(notifySpy).not.toHaveBeenCalled();
  });
});
