import { SyncService } from '$lib/shared/services/SyncService';
import type { SyncHealth, SyncScope, SyncConflict } from '$lib/shared/types/book';
import { invoke } from '$lib/shared/api/invokeWrapper';
import { dictionaryState } from '$lib/shared/stores/DictionaryState.svelte';

const SCOPES_KEY = 'sync.scopes';
const DEFAULT_SCOPES: Record<SyncScope, boolean> = {
  progress: true,
  bookmarks: true,
  highlights: true,
  sessions: true,
  catalog: true,
  dictionary: false,
  library: true,
};

function loadScopes(): Record<string, boolean> {
  try {
    const raw = localStorage.getItem(SCOPES_KEY);
    if (raw) return { ...DEFAULT_SCOPES, ...(JSON.parse(raw) as Record<string, boolean>) };
  } catch {}
  return { ...DEFAULT_SCOPES };
}

function persistScopes(map: Record<string, boolean>): void {
  try {
    localStorage.setItem(SCOPES_KEY, JSON.stringify(map));
  } catch {}
}

export function createSyncHealthState(): {
  readonly health: SyncHealth | null;
  readonly scopes: Record<string, boolean>;
  readonly conflicts: SyncConflict[];
  readonly isLoading: boolean;
  readonly error: string | null;
  refresh(): Promise<void>;
  setScopeEnabled(scope: SyncScope, enabled: boolean): void;
  isScopeEnabled(scope: SyncScope): boolean;
  resolveConflict(conflictId: string, resolution: 'keep_local' | 'keep_remote'): Promise<void>;
  pushConflict(c: SyncConflict): void;
  startPoll(intervalMs?: number): void;
  stopPoll(): void;
} {
  let health = $state<SyncHealth | null>(null);
  let scopes = $state<Record<string, boolean>>(loadScopes());
  let conflicts = $state<SyncConflict[]>([]);
  let isLoading = $state(false);
  let error = $state<string | null>(null);
  let pollTimer: ReturnType<typeof setInterval> | null = null;

  async function refresh(): Promise<void> {
    isLoading = true;
    error = null;
    try {
      health = await SyncService.getSyncHealth();
    } catch (e) {
      error = e instanceof Error ? e.message : String(e);
    } finally {
      isLoading = false;
    }
  }

  function setScopeEnabled(scope: SyncScope, enabled: boolean): void {
    scopes = { ...scopes, [scope]: enabled };
    persistScopes(scopes);
    // On enable, trigger flush; on disable pull is skipped via Reader checks
    if (enabled) void SyncService.syncMetadata().catch(() => {});
  }

  function isScopeEnabled(scope: SyncScope): boolean {
    return scopes[scope] !== false;
  }

  async function resolveConflict(
    conflictId: string,
    resolution: 'keep_local' | 'keep_remote',
  ): Promise<void> {
    const conflict = conflicts.find((c) => c.id === conflictId);
    if (!conflict) throw new Error('conflict.not_found');
    if (resolution === 'keep_local') {
      // LWW bump: set updated_at = now() + enqueue UPSERT
      const now = new Date(Date.now() + 1).toISOString();
      try {
        await invoke('updateDictionaryWord', {
          payload: {
            id: conflict.id,
            word: conflict.localWord ?? conflict.word ?? '',
            updatedAt: now,
          },
        });
        // Enqueue outbox with bumped clock
        await invoke('addCoalescedSyncOutboxItem', {
          entityType: 'DICTIONARY_WORD',
          entityId: conflict.id,
          operation: 'UPSERT',
          payloadJson: JSON.stringify({
            word: conflict.localWord ?? conflict.word,
            updatedAt: now,
          }),
        });
      } catch (e) {
        // Fallback: update local state directly and queue via SyncService path
        // Use dictionaryState update if invoke fails
        const found = dictionaryState.words.find((w) => w.id === conflictId);
        if (found)
          await dictionaryState.update(conflictId, {
            word: found.word ?? conflict.localWord ?? '',
          });
      }
    }
    // remove conflict from list
    conflicts = conflicts.filter((c) => c.id !== conflictId);
  }

  function pushConflict(c: SyncConflict): void {
    if (conflicts.some((x) => x.id === c.id)) return;
    conflicts = [...conflicts, c];
  }

  function startPoll(intervalMs = 15000): void {
    if (pollTimer) return;
    void refresh();
    pollTimer = setInterval(() => void refresh(), intervalMs);
  }

  function stopPoll(): void {
    if (pollTimer) {
      clearInterval(pollTimer);
      pollTimer = null;
    }
  }

  return {
    get health() {
      return health;
    },
    get scopes() {
      return scopes;
    },
    get conflicts() {
      return conflicts;
    },
    get isLoading() {
      return isLoading;
    },
    get error() {
      return error;
    },
    refresh,
    setScopeEnabled,
    isScopeEnabled,
    resolveConflict,
    pushConflict,
    startPoll,
    stopPoll,
  };
}

export const syncHealthState = createSyncHealthState();
