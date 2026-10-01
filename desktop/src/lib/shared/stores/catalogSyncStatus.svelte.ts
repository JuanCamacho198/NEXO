/**
 * catalogSyncStatus — aggregate status of the BACKGROUND book-catalog upsert.
 *
 * The catalog sync is an outbox/timer operation, not a user-initiated action:
 * a toast per cycle would be unacceptable (spam). Instead every per-book
 * failure is recorded here with its stable `SyncErrorCode`, and a later
 * successful upsert of the SAME book removes it again. The Data/Storage
 * settings panel renders this as a single status line; an empty map renders
 * nothing.
 *
 * Only the typed code is retained — never the raw message — so nothing that
 * could carry a token, JWT, or credential ever reaches the UI.
 */
import type { SyncErrorCode } from '$lib/shared/protocol/DriveCatalogContract';

export interface CatalogSyncFailureReport {
  /** Number of distinct books currently failing to reach the catalog. */
  failedCount: number;
  /** Distinct stable error codes across those books. */
  codes: SyncErrorCode[];
}

let failures = $state<Record<string, SyncErrorCode>>({});

/**
 * Record a per-book catalog upsert/remove failure. Repeated failures for the
 * same book overwrite the code without inflating the count.
 */
export function recordCatalogSyncFailure(bookId: string, code: SyncErrorCode): void {
  failures = { ...failures, [bookId]: code };
}

/** Remove a book from the failure set once its catalog row later succeeds. */
export function recordCatalogSyncSuccess(bookId: string): void {
  if (!(bookId in failures)) return;
  const next: Record<string, SyncErrorCode> = { ...failures };
  delete next[bookId];
  failures = next;
}

/** Reset the status (sign-out, tests). */
export function clearCatalogSyncStatus(): void {
  failures = {};
}

export const catalogSyncStatus = {
  get report(): CatalogSyncFailureReport | null {
    const entries = Object.entries(failures);
    if (entries.length === 0) return null;
    return {
      failedCount: entries.length,
      codes: [...new Set(entries.map(([, code]) => code))],
    };
  },
};
