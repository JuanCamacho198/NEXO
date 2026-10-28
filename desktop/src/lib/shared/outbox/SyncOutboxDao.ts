/**
 * SyncOutboxDao — SQLite-backed outbox for offline-first Supabase sync.
 *
 * Mutations are INSERTed here first, then a timer-based processor
 * (SyncOutboxService) flushes them to Supabase. On 2xx, the row is
 * deleted. On failure, retry_count is incremented with exponential
 * backoff (2^retry_count seconds, capped at 60s, max 50 retries).
 *
 * Backed by specific Tauri commands (no generic SQL bridge).
 */
import { invoke } from '$lib/shared/api/invokeWrapper';

export interface SyncOutboxRow {
  id: string;
  entityType: string;
  entityId: string | null;
  operation: 'UPSERT' | 'DELETE';
  payloadJson: string;
  retryCount: number;
  lastError: string | null;
  createdAt: string;
  nextRetryAt: string;
}

export class SyncOutboxDao {
  /**
   * Insert a new outbox row. Returns the generated UUID.
   */
  async add(
    entityType: string,
    entityId: string | null,
    operation: 'UPSERT' | 'DELETE',
    payloadJson: string,
  ): Promise<string> {
    ensureValidJson(payloadJson);
    return invoke<string>('addSyncOutboxItem', {
      entityType,
      entityId,
      operation,
      payloadJson,
    });
  }

  /**
   * Coalesced enqueue (D5, SR-4.1): transactionally UPDATE-else-INSERT the row
   * keyed by (entityType, entityId) + payload `userId`, latest client
   * `updatedAt` wins (D6). One IPC per location change — the flood of progress
   * events collapses to a single row per (user_id, book_id) in SQLite, so the
   * flush never re-sends a stale event. Never drops rows. Returns the id of the
   * row that now holds the event (existing row updated in place, or the new id).
   */
  async addCoalesced(
    entityType: string,
    entityId: string,
    operation: 'UPSERT',
    payloadJson: string,
  ): Promise<string> {
    ensureValidJson(payloadJson);
    return invoke<string>('addCoalescedSyncOutboxItem', {
      entityType,
      entityId,
      operation,
      payloadJson,
    });
  }

  /** READING_PROGRESS coalesced by bookId — keep latest per (user, book). */
  async enqueueProgress(bookId: string, payload: Record<string, unknown>): Promise<string> {
    const json = JSON.stringify(payload);
    ensureValidJson(json);
    return this.addCoalesced('READING_PROGRESS', bookId, 'UPSERT', json);
  }

  /** HIGHLIGHT per id — atomic enqueue, never coalesced across ids. */
  async enqueueHighlight(
    id: string,
    payload: Record<string, unknown>,
    operation: 'UPSERT' | 'DELETE' = 'UPSERT',
  ): Promise<string> {
    const json = JSON.stringify(payload);
    ensureValidJson(json);
    return this.add('HIGHLIGHT', id, operation, json);
  }

  /** BOOKMARK per id — atomic enqueue, never coalesced across ids. */
  async enqueueBookmark(
    id: string,
    payload: Record<string, unknown>,
    operation: 'UPSERT' | 'DELETE' = 'UPSERT',
  ): Promise<string> {
    const json = JSON.stringify(payload);
    ensureValidJson(json);
    return this.add('BOOKMARK', id, operation, json);
  }

  /** READING_SESSION per id — never coalesced, one row per session. */
  async enqueueSession(id: string, payload: Record<string, unknown>): Promise<string> {
    const json = JSON.stringify(payload);
    ensureValidJson(json);
    return this.add('READING_SESSION', id, 'UPSERT', json);
  }

  /**
   * Return all rows owned by `userId` that are ready for retry
   * (next_retry_at <= now), ordered by created_at ASC (FIFO). Excludes items
   * with retry_count >= 50. Scoping the read to one owner is what keeps
   * account A's queued writes from being flushed under account B's session.
   */
  async listReady(userId: string): Promise<SyncOutboxRow[]> {
    const rows = await invoke<SyncOutboxRow[]>('listSyncOutboxReady', { userId });
    return rows.map((r) => ({
      ...r,
      operation: r.operation as 'UPSERT' | 'DELETE',
    }));
  }

  /**
   * Mark an item as failed: increments retry_count, sets last_error,
   * and calculates next_retry_at with exponential backoff.
   */
  async markFailed(id: string, error: string): Promise<void> {
    await invoke('markSyncOutboxFailed', { id, error });
  }

  /**
   * Delete a row after successful sync.
   */
  async delete(id: string): Promise<void> {
    await invoke('deleteSyncOutboxItem', { id });
  }

  /**
   * Prune rows older than 7 days with retry_count >= 10.
   * Returns the number of rows deleted.
   */
  async prune(): Promise<number> {
    return invoke<number>('pruneSyncOutbox');
  }
}

function ensureValidJson(json: string): void {
  if (typeof json !== 'string' || json.length === 0)
    throw new Error('payloadJson must be non-empty valid JSON');
  try {
    const v = JSON.parse(json);
    if (v === null || typeof v !== 'object') throw new Error('payloadJson must be JSON object');
  } catch (e) {
    throw new Error(
      `payloadJson must be valid JSON: ${e instanceof Error ? e.message : String(e)}`,
    );
  }
}

/**
 * Auth-class failure detection (D4, R2): the outbox circuit breaker pauses on
 * these and only these failures. A PostgrestError with HTTP status 400 (RLS
 * denial) or 401 (invalid/expired JWT), or a typed SyncError with code
 * AUTH_REQUIRED / AUTH_EXPIRED. Anything else is a normal failure that keeps
 * the existing per-row exponential backoff — never the breaker.
 */
export function isAuthClassError(error: unknown): boolean {
  const status = (error as { status?: unknown } | null)?.status;
  if (typeof status === 'number' && (status === 400 || status === 401)) return true;
  const code = (error as { code?: unknown } | null)?.code;
  return code === 'AUTH_REQUIRED' || code === 'AUTH_EXPIRED';
}
