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
 *
 * Classification (NOTIF-01): this is a domain status store with its own UI, not
 * a notification store — the per-book map is kept as-is. It ALSO emits one
 * deduped `notify` per failing book so the event reaches the unified tray.
 */
import type { SyncErrorCode } from '$lib/shared/protocol/DriveCatalogContract';
import { notify } from './notificationCenter.svelte';

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
  // Deduped per book+code so a background retry cycle cannot flood the tray.
  // Silent by contract (NOTIF-03): the Data/Storage status line is the
  // surface for background cycles, never a toast — the tray still records.
  notify({
    source: 'sync',
    severity: 'error',
    interruption: 'silent',
    i18nKey: 'notifications.catalog.failed',
    i18nParams: { code },
    dedupKey: `catalog:${bookId}:${code}`,
    target: { kind: 'book', bookId },
  });
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
