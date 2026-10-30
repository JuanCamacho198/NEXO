import { describe, expect, it, vi, beforeEach } from 'vitest';
import { isAuthClassError, SyncOutboxDao } from '$lib/shared/outbox/SyncOutboxDao';

// ─── Hoisted mock ───
const mockInvoke = vi.hoisted(() => vi.fn());

vi.mock('$lib/shared/api/invokeWrapper', () => ({
  invoke: mockInvoke,
}));

describe('isAuthClassError (D4, R2 narrow classification)', () => {
  it('classifies PostgrestError status 400 as auth-class (RLS denial)', () => {
    const err = Object.assign(new Error('RLS denied'), { status: 400, code: '42501' });
    expect(isAuthClassError(err)).toBe(true);
  });

  it('classifies PostgrestError status 401 as auth-class (invalid/expired JWT)', () => {
    const err = Object.assign(new Error('Invalid JWT'), { status: 401, code: 'JWT' });
    expect(isAuthClassError(err)).toBe(true);
  });

  it('classifies SyncError AUTH_REQUIRED as auth-class', () => {
    const err = Object.assign(new Error('Drive access expired'), {
      code: 'AUTH_REQUIRED',
      retryable: false,
    });
    expect(isAuthClassError(err)).toBe(true);
  });

  it('classifies SyncError AUTH_EXPIRED as auth-class', () => {
    const err = Object.assign(new Error('token expired'), { code: 'AUTH_EXPIRED' });
    expect(isAuthClassError(err)).toBe(true);
  });

  it('does NOT classify PostgrestError 500 as auth-class', () => {
    const err = Object.assign(new Error('server error'), { status: 500, code: 'XX' });
    expect(isAuthClassError(err)).toBe(false);
  });

  it('does NOT classify plain errors or unrelated codes as auth-class', () => {
    expect(isAuthClassError(new Error('network drop'))).toBe(false);
    const permission = Object.assign(new Error('denied'), { code: 'PERMISSION_DENIED' });
    expect(isAuthClassError(permission)).toBe(false);
    expect(isAuthClassError(null)).toBe(false);
    expect(isAuthClassError(undefined)).toBe(false);
    expect(isAuthClassError('string error')).toBe(false);
  });
});

describe('SyncOutboxDao.addCoalesced (IPC wiring)', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('invokes addCoalescedSyncOutboxItem with camelCase args and returns the id', async () => {
    mockInvoke.mockResolvedValue('row-42');
    const dao = new SyncOutboxDao();

    const id = await dao.addCoalesced(
      'READING_PROGRESS',
      'book-1',
      'UPSERT',
      '{"userId":"u1","bookId":"book-1","updatedAt":"2026-08-07T10:00:00Z"}',
    );

    expect(id).toBe('row-42');
    expect(mockInvoke).toHaveBeenCalledWith('addCoalescedSyncOutboxItem', {
      entityType: 'READING_PROGRESS',
      entityId: 'book-1',
      operation: 'UPSERT',
      payloadJson: '{"userId":"u1","bookId":"book-1","updatedAt":"2026-08-07T10:00:00Z"}',
    });
  });
});

describe('SyncOutboxDao.listReady (owner scoping)', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('asks the backend for one owner only, as camelCase userId', async () => {
    mockInvoke.mockResolvedValue([]);
    const dao = new SyncOutboxDao();

    await dao.listReady('user-b');

    expect(mockInvoke).toHaveBeenCalledWith('listSyncOutboxReady', { userId: 'user-b' });
  });
});
