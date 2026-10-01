/**
 * Unit tests for DriveUsageService — the three honest Drive-usage states
 * (not_connected / failed / measured) and the lazy measurement cache.
 */
import { describe, it, expect, vi, beforeEach } from 'vitest';

vi.mock('$lib/shared/services/DriveConnectService', () => ({
  isDriveAuthorized: vi.fn(),
}));

import {
  getDriveUsage,
  __resetDriveUsageCache,
  DRIVE_USAGE_CACHE_TTL_MS,
  type DriveUsageReader,
} from '$lib/shared/services/storage/DriveUsageService';

function provider(bytes: number, fileCount = 1): DriveUsageReader {
  return { getUsage: vi.fn().mockResolvedValue({ bytes, fileCount }) };
}

describe('DriveUsageService', () => {
  beforeEach(() => {
    __resetDriveUsageCache();
  });

  it('reports not_connected without touching Drive when there is no grant', async () => {
    const p = provider(100);
    const res = await getDriveUsage({ provider: p, isAuthorized: async () => false });

    expect(res).toEqual({ state: 'not_connected' });
    expect(p.getUsage).not.toHaveBeenCalled();
  });

  it('reports measured bytes with a timestamp when the grant is live', async () => {
    const p = provider(2048, 3);
    const res = await getDriveUsage({
      provider: p,
      isAuthorized: async () => true,
      now: () => 1_000,
    });

    expect(res).toEqual({ state: 'measured', bytes: 2048, fileCount: 3, measuredAt: 1_000 });
  });

  it('reports failed (never a stale number) when the measurement call throws', async () => {
    const p: DriveUsageReader = { getUsage: vi.fn().mockRejectedValue(new Error('boom')) };
    const res = await getDriveUsage({ provider: p, isAuthorized: async () => true });

    expect(res).toMatchObject({ state: 'failed', message: 'boom' });
  });

  it('reports failed when the authorization check itself throws', async () => {
    const res = await getDriveUsage({
      provider: provider(1),
      isAuthorized: async () => {
        throw new Error('grant read failed');
      },
    });

    expect(res).toMatchObject({ state: 'failed', message: 'grant read failed' });
  });

  it('caches the measurement so repeated reads do not call Drive again', async () => {
    const p = provider(500);
    await getDriveUsage({ provider: p, isAuthorized: async () => true });
    await getDriveUsage({ provider: p, isAuthorized: async () => true });

    expect(p.getUsage).toHaveBeenCalledTimes(1);
  });

  it('force bypasses a fresh cache entry', async () => {
    const p = provider(500);
    await getDriveUsage({ provider: p, isAuthorized: async () => true });
    await getDriveUsage({ provider: p, isAuthorized: async () => true, force: true });

    expect(p.getUsage).toHaveBeenCalledTimes(2);
  });

  it('re-measures once the cached entry is older than the TTL', async () => {
    const p = provider(500);
    let clock = 10_000;
    await getDriveUsage({ provider: p, isAuthorized: async () => true, now: () => clock });

    clock += DRIVE_USAGE_CACHE_TTL_MS + 1;
    await getDriveUsage({ provider: p, isAuthorized: async () => true, now: () => clock });

    expect(p.getUsage).toHaveBeenCalledTimes(2);
  });

  it('a cached measurement is not served when the grant is gone (not_connected wins)', async () => {
    const p = provider(500);
    await getDriveUsage({ provider: p, isAuthorized: async () => true });

    const res = await getDriveUsage({ provider: p, isAuthorized: async () => false });

    expect(res).toEqual({ state: 'not_connected' });
    expect(p.getUsage).toHaveBeenCalledTimes(1);
  });
});
