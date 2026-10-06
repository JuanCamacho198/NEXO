import { authState } from '$lib/shared/stores/AuthState.svelte';
import { SupabaseProgressSync } from '$lib/shared/sync/SupabaseProgressSync';
import { SyncOutboxDao } from '$lib/shared/outbox/SyncOutboxDao';
import type { HighlightDto } from '$lib/shared/types';
import type { HighlightsViewDeps } from './highlightsViewDeps';

export function sortByUpdatedAtDesc(list: HighlightDto[]): HighlightDto[] {
  return [...list].sort(
    (a, b) => new Date(b.updatedAt).getTime() - new Date(a.updatedAt).getTime(),
  );
}

export function chunkRows<T>(rows: T[], chunkSize = 500): T[][] {
  const out: T[][] = [];
  for (let i = 0; i < rows.length; i += chunkSize) out.push(rows.slice(i, i + chunkSize));
  return out;
}

export function isSameHighlights(a: HighlightDto[], b: HighlightDto[]): boolean {
  return a.length === b.length && a.every((h, idx) => h.id === b[idx]?.id);
}

export function createHighlightsSync(opts: {
  deps: HighlightsViewDeps;
  getHighlights: () => HighlightDto[];
  setHighlights: (v: HighlightDto[]) => void;
  getSupabaseSync?: (userId: string) => SupabaseProgressSync;
  outbox?: SyncOutboxDao;
}): {
  readonly syncState: 'idle' | 'syncing' | 'synced';
  syncHighlightsInBackground(force?: boolean): Promise<void>;
  handleDelete(
    highlight: HighlightDto,
    currentHighlights: HighlightDto[],
    setHl: (v: HighlightDto[]) => void,
  ): Promise<void>;
  cleanup(): void;
  sortByUpdatedAtDesc(list: HighlightDto[]): HighlightDto[];
  chunkRows<T>(rows: T[], chunkSize?: number): T[][];
  isSameHighlights(a: HighlightDto[], b: HighlightDto[]): boolean;
} {
  const outboxDao = opts.outbox ?? new SyncOutboxDao();
  let syncState = $state<'idle' | 'syncing' | 'synced'>('idle');
  let syncTimeout: ReturnType<typeof setTimeout> | null = null;

  async function syncHighlightsInBackground(force = false): Promise<void> {
    if (syncState === 'syncing' && !force) return;
    if (!authState.userId) return;
    syncState = 'syncing';
    try {
      const sync = opts.getSupabaseSync
        ? opts.getSupabaseSync(authState.userId)
        : new SupabaseProgressSync(authState.userId);
      const rows = await sync.fetchAllHighlightsForPull();
      if (rows.length > 0) {
        const chunkSize = 500;
        for (let i = 0; i < rows.length; i += chunkSize) {
          const chunk = rows.slice(i, i + chunkSize);
          try {
            await opts.deps.upsertRemoteHighlights(chunk);
          } catch {
            // chunk failure non-fatal
          }
        }
        const fresh = await opts.deps.listHighlights();
        const sorted = sortByUpdatedAtDesc(fresh);
        const current = opts.getHighlights();
        const same =
          sorted.length === current.length && sorted.every((h, idx) => h.id === current[idx]?.id);
        if (!same) opts.setHighlights(sorted);
      }
      syncState = 'synced';
      if (syncTimeout) clearTimeout(syncTimeout);
      syncTimeout = setTimeout(() => {
        syncState = 'idle';
      }, 3000);
    } catch {
      syncState = 'idle';
    }
  }

  async function handleDelete(
    highlight: HighlightDto,
    currentHighlights: HighlightDto[],
    setHl: (v: HighlightDto[]) => void,
  ): Promise<void> {
    try {
      await opts.deps.deleteHighlight(highlight.id);
      setHl(currentHighlights.filter((h) => h.id !== highlight.id));
      if (authState.userId) {
        const updatedAt = new Date().toISOString();
        void outboxDao.add(
          'HIGHLIGHT',
          highlight.id,
          'DELETE',
          JSON.stringify({
            userId: authState.userId,
            bookId: highlight.bookId,
            cfiRange: highlight.cfi ?? '',
            textContent: highlight.text,
            color: highlight.color,
            page: highlight.pageNumber,
            deletedAt: updatedAt,
            updatedAt,
          }),
        );
      }
    } catch {
      /* silent */
    }
  }

  function cleanup(): void {
    if (syncTimeout) clearTimeout(syncTimeout);
  }

  return {
    get syncState() {
      return syncState;
    },
    syncHighlightsInBackground,
    handleDelete,
    cleanup,
    sortByUpdatedAtDesc,
    chunkRows,
    isSameHighlights,
  };
}

export type HighlightsSyncState = ReturnType<typeof createHighlightsSync>;
