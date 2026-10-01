import { describe, expect, it, vi, beforeEach, afterEach } from 'vitest';
import { SyncOutboxService, type OutboxHandler } from '$lib/shared/outbox/SyncOutboxService';
import type { SyncOutboxDao, SyncOutboxRow } from '$lib/shared/outbox/SyncOutboxDao';
import {
  catalogSyncStatus,
  clearCatalogSyncStatus,
} from '$lib/shared/stores/catalogSyncStatus.svelte';

// ---- Mock control variables ----
let mockRecheckLiveSession = vi.fn<() => Promise<boolean>>();
let mockReportAuthError = vi.fn<(error: unknown) => boolean>();

vi.mock('$lib/services/supabase', () => ({
  recheckLiveSession: () => mockRecheckLiveSession(),
}));

vi.mock('$lib/shared/stores/syncAlert.svelte', () => ({
  reportAuthError: (error: unknown) => mockReportAuthError(error),
}));

const row = (entityType: string): SyncOutboxRow => ({
  id: 'row-1',
  entityType,
  entityId: 'book-1',
  operation: 'UPSERT',
  payloadJson: '{}',
  retryCount: 0,
  lastError: null,
  createdAt: '2026-01-01T00:00:00.000Z',
  nextRetryAt: '2026-01-01T00:00:00.000Z',
});

function makeDao(items: SyncOutboxRow[]): SyncOutboxDao {
  return {
    listReady: vi.fn().mockResolvedValue(items),
    delete: vi.fn().mockResolvedValue(undefined),
    markFailed: vi.fn().mockResolvedValue(undefined),
    prune: vi.fn().mockResolvedValue(0),
    add: vi.fn(),
    addCoalesced: vi.fn().mockResolvedValue('row-1'),
  } as unknown as SyncOutboxDao;
}

/** Gate always open — the default per-test stance so existing flush behavior is unchanged. */
const gateOpen = async () => true;

beforeEach(() => {
  vi.clearAllMocks();
  mockRecheckLiveSession.mockResolvedValue(true);
  mockReportAuthError.mockReturnValue(false);
});

describe('SyncOutboxService', () => {
  it('keeps unsupported entities retryable with evidence instead of deleting them', async () => {
    const dao = makeDao([row('UNSUPPORTED')]);
    const handler: OutboxHandler = vi
      .fn()
      .mockRejectedValue(new Error('Unsupported outbox entity: UNSUPPORTED'));
    const service = new SyncOutboxService(dao, gateOpen);
    service.setHandler(handler);

    await service.flush();

    expect(handler).toHaveBeenCalledWith('UNSUPPORTED', 'book-1', 'UPSERT', '{}');
    expect(dao.delete).not.toHaveBeenCalled();
    expect(dao.markFailed).toHaveBeenCalledWith('row-1', 'Unsupported outbox entity: UNSUPPORTED');
  });

  it('deletes an item only after its handler succeeds', async () => {
    const dao = makeDao([row('BOOK')]);
    const handler: OutboxHandler = vi.fn().mockResolvedValue(undefined);
    const service = new SyncOutboxService(dao, gateOpen);
    service.setHandler(handler);

    await service.flush();

    expect(dao.delete).toHaveBeenCalledWith('row-1');
    expect(dao.markFailed).not.toHaveBeenCalled();
  });
});

describe('SyncOutboxService — live-session flush gate (SR-1.1)', () => {
  it('skips the ENTIRE flush when gated: rows keep retry state, no request, no markFailed, no prune', async () => {
    const dao = makeDao([row('READING_PROGRESS')]);
    const handler: OutboxHandler = vi.fn();
    const service = new SyncOutboxService(dao, async () => false);
    service.setHandler(handler);

    await service.flush();

    expect(handler).not.toHaveBeenCalled();
    expect(dao.listReady).not.toHaveBeenCalled();
    expect(dao.delete).not.toHaveBeenCalled();
    expect(dao.markFailed).not.toHaveBeenCalled();
    expect(dao.prune).not.toHaveBeenCalled();
  });

  it('skips the flush when the re-check finds no live session (stale without event, DA-1.2)', async () => {
    mockRecheckLiveSession.mockResolvedValue(false);
    const dao = makeDao([row('READING_PROGRESS')]);
    const service = new SyncOutboxService(dao); // default gate → recheckLiveSession
    service.setHandler(vi.fn());

    await service.flush();

    expect(mockRecheckLiveSession).toHaveBeenCalledTimes(1);
    expect(dao.listReady).not.toHaveBeenCalled();
  });

  it('is not rescheduled or retried by the gate itself — a later flush with a live session proceeds', async () => {
    let sessionAlive = false;
    const dao = makeDao([row('BOOK')]);
    const handler: OutboxHandler = vi.fn().mockResolvedValue(undefined);
    const service = new SyncOutboxService(dao, async () => sessionAlive);
    service.setHandler(handler);

    await service.flush(); // gated → skip
    expect(handler).not.toHaveBeenCalled();

    sessionAlive = true;
    await service.flush(); // live again → rows process normally

    expect(handler).toHaveBeenCalledTimes(1);
    expect(dao.delete).toHaveBeenCalledWith('row-1');
  });

  it('surfaces typed AUTH_REQUIRED handler failures to the banner before markFailed (SR-3)', async () => {
    mockReportAuthError.mockReturnValue(true);
    const dao = makeDao([row('BOOK')]);
    const authError = Object.assign(
      new Error('Google Drive access expired. Please sign in with Google again.'),
      {
        code: 'AUTH_REQUIRED',
        retryable: false,
      },
    );
    const service = new SyncOutboxService(dao, gateOpen);
    service.setHandler(vi.fn().mockRejectedValue(authError));

    await service.flush();

    expect(mockReportAuthError).toHaveBeenCalledWith(authError);
    expect(dao.delete).not.toHaveBeenCalled();
    expect(dao.markFailed).toHaveBeenCalledWith('row-1', authError.message);
  });

  it('leaves non-auth failures to the existing backoff path without touching the banner', async () => {
    const dao = makeDao([row('BOOK')]);
    const service = new SyncOutboxService(dao, gateOpen);
    service.setHandler(vi.fn().mockRejectedValue(new Error('network drop')));

    await service.flush();

    expect(mockReportAuthError).toHaveBeenCalledWith(expect.any(Error));
    expect(mockReportAuthError).toHaveReturnedWith(false);
    expect(dao.markFailed).toHaveBeenCalledWith('row-1', 'network drop');
  });
});

describe('SyncOutboxService — auth circuit breaker (D4, SR-2.1/4.2)', () => {
  const authError400 = () => Object.assign(new Error('RLS denied'), { status: 400, code: '42501' });
  const authError401 = () => Object.assign(new Error('invalid JWT'), { status: 401, code: 'JWT' });
  const authExpired = () =>
    Object.assign(new Error('Drive access expired'), { code: 'AUTH_EXPIRED', retryable: false });

  beforeEach(() => {
    vi.useFakeTimers();
    vi.setSystemTime(new Date('2026-08-07T12:00:00.000Z'));
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  it('arms the breaker on a PostgrestError 400 and pauses the next flush entirely (SR-2.1)', async () => {
    const dao = makeDao([row('READING_PROGRESS')]);
    const service = new SyncOutboxService(dao, gateOpen);
    service.setHandler(vi.fn().mockRejectedValue(authError400()));

    await service.flush();

    // Row kept (never dropped) + retry state preserved via markFailed.
    expect(dao.delete).not.toHaveBeenCalled();
    expect(dao.markFailed).toHaveBeenCalledWith('row-1', 'RLS denied');
    expect(service.isBreakerPaused()).toBe(true);

    // Next flush while armed: ENTIRE flush skipped — no listReady, no handler.
    const handler = vi.fn();
    service.setHandler(handler);
    await service.flush();
    expect(dao.listReady).toHaveBeenCalledTimes(1); // only the first flush listed
    expect(handler).not.toHaveBeenCalled();
    expect(dao.markFailed).toHaveBeenCalledTimes(1); // no new marks while paused
  });

  it('backs off 60 → 120 → 240 → 300s cap (D4 pause formula)', async () => {
    const dao = makeDao([row('READING_PROGRESS')]);
    const service = new SyncOutboxService(dao, gateOpen);
    const failingHandler = vi.fn().mockRejectedValue(authError401());
    service.setHandler(failingHandler);

    // Streak 1 → 60s
    await service.flush();
    expect(service.isBreakerPaused()).toBe(true);
    vi.advanceTimersByTime(59_000);
    expect(service.isBreakerPaused()).toBe(true);
    vi.advanceTimersByTime(1_001);
    expect(service.isBreakerPaused()).toBe(false);

    // Streak 2 → 120s
    await service.flush();
    expect(service.isBreakerPaused()).toBe(true);
    vi.advanceTimersByTime(119_000);
    expect(service.isBreakerPaused()).toBe(true);
    vi.advanceTimersByTime(1_001);
    expect(service.isBreakerPaused()).toBe(false);

    // Streak 3 → 240s
    await service.flush();
    expect(service.isBreakerPaused()).toBe(true);
    vi.advanceTimersByTime(239_000);
    expect(service.isBreakerPaused()).toBe(true);
    vi.advanceTimersByTime(1_001);
    expect(service.isBreakerPaused()).toBe(false);

    // Streak 4 → capped at 300s (not 480s)
    await service.flush();
    expect(service.isBreakerPaused()).toBe(true);
    vi.advanceTimersByTime(299_000);
    expect(service.isBreakerPaused()).toBe(true);
    vi.advanceTimersByTime(1_001);
    expect(service.isBreakerPaused()).toBe(false);
  });

  it('resets on a fully-successful flush (D4)', async () => {
    const dao = makeDao([row('READING_PROGRESS')]);
    const service = new SyncOutboxService(dao, gateOpen);
    service.setHandler(vi.fn().mockRejectedValue(authExpired()));

    await service.flush();
    expect(service.isBreakerPaused()).toBe(true);

    // Pause window (60s) elapses, then auth recovers; the next flush fully
    // succeeds → breaker resets.
    vi.advanceTimersByTime(60_001);
    service.setHandler(vi.fn().mockResolvedValue(undefined));
    await service.flush();

    expect(dao.delete).toHaveBeenCalledWith('row-1');
    expect(service.isBreakerPaused()).toBe(false);
  });

  it('resets via resetAuthBreaker() — wired to SIGNED_IN/TOKEN_REFRESHED (D4)', async () => {
    const dao = makeDao([row('READING_PROGRESS')]);
    const service = new SyncOutboxService(dao, gateOpen);
    service.setHandler(vi.fn().mockRejectedValue(authError400()));

    await service.flush();
    expect(service.isBreakerPaused()).toBe(true);

    service.resetAuthBreaker();

    expect(service.isBreakerPaused()).toBe(false);
    // And the next flush proceeds normally (fresh tokens).
    const handler = vi.fn().mockResolvedValue(undefined);
    service.setHandler(handler);
    await service.flush();
    expect(handler).toHaveBeenCalledTimes(1);
  });

  it('never drops rows while the breaker pauses (SR-4.2)', async () => {
    const items = [row('READING_PROGRESS'), row('BOOK')];
    const dao = makeDao(items);
    const service = new SyncOutboxService(dao, gateOpen);
    // First row auth-fails; second row would succeed if reached.
    let calls = 0;
    service.setHandler(async () => {
      calls += 1;
      throw authError400();
    });

    await service.flush();
    expect(dao.delete).not.toHaveBeenCalled();
    expect(service.isBreakerPaused()).toBe(true);

    // While paused, a flush lists nothing new and marks nothing — rows intact.
    await service.flush();
    expect(dao.listReady).toHaveBeenCalledTimes(1);
    expect(dao.markFailed).toHaveBeenCalledTimes(1);
    expect(calls).toBe(1);
  });

  it('does NOT arm the breaker on non-auth failures (R2 narrow classification)', async () => {
    const dao = makeDao([row('BOOK')]);
    const service = new SyncOutboxService(dao, gateOpen);
    service.setHandler(vi.fn().mockRejectedValue(new Error('network drop')));

    await service.flush();

    expect(service.isBreakerPaused()).toBe(false);
    expect(dao.markFailed).toHaveBeenCalledWith('row-1', 'network drop');
  });

  it('does NOT arm on PostgrestError 500 (only 400/401 are auth-class)', async () => {
    const dao = makeDao([row('BOOK')]);
    const service = new SyncOutboxService(dao, gateOpen);
    service.setHandler(
      vi.fn().mockRejectedValue(Object.assign(new Error('server error'), { status: 500 })),
    );

    await service.flush();

    expect(service.isBreakerPaused()).toBe(false);
  });
});

describe('SyncOutboxService — BOOK catalog failure status (aggregate, retry unchanged)', () => {
  beforeEach(() => {
    clearCatalogSyncStatus();
  });

  it('records a BOOK handler failure with its typed code and clears it on a later success', async () => {
    const dao = makeDao([row('BOOK')]);
    const service = new SyncOutboxService(dao, gateOpen);
    service.setHandler(vi.fn().mockRejectedValue(new Error('network drop')));

    await service.flush();

    // Retry/backoff resilience is untouched: the row still goes through markFailed.
    expect(dao.markFailed).toHaveBeenCalledWith('row-1', 'network drop');
    expect(dao.delete).not.toHaveBeenCalled();
    // Typed aggregate for the status line.
    expect(catalogSyncStatus.report).toEqual({ failedCount: 1, codes: ['UNAVAILABLE'] });

    service.setHandler(vi.fn().mockResolvedValue(undefined));
    await service.flush();

    expect(catalogSyncStatus.report).toBeNull();
  });

  it('leaves the catalog status alone for non-BOOK failures', async () => {
    const dao = makeDao([row('READING_PROGRESS')]);
    const service = new SyncOutboxService(dao, gateOpen);
    service.setHandler(vi.fn().mockRejectedValue(new Error('network drop')));

    await service.flush();

    expect(dao.markFailed).toHaveBeenCalledWith('row-1', 'network drop');
    expect(catalogSyncStatus.report).toBeNull();
  });
});
