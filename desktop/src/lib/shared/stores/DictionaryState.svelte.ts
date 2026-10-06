import { invoke } from '$lib/shared/api/invokeWrapper';
import type { DictionaryWordDto } from '$lib/shared/types';
import type { DictionaryEvidence } from '$lib/shared/dictionary/captureFromSelection';
import { authState } from '$lib/shared/stores/AuthState.svelte';
import { hasLiveSession } from '$lib/services/supabase';
import {
  SupabaseDictionarySync,
  type SupabaseDictionaryRow,
} from '$lib/shared/sync/SupabaseDictionarySync';
import { normalizeDictionaryKey } from '$lib/shared/dictionary/dictionaryKey';

// REQ-DSI-004: the store has exactly one normalization implementation, the
// shared `normalizeDictionaryKey` contract. The previous local variant was
// applied in the other order (lowercase before NFD) and duplicated the rule.
function normalizedForSearch(word: string): string {
  return normalizeDictionaryKey(word);
}

/** The ten rich-entry fields, camelCase, applied whenever a remote row wins. */
function evidenceFields(row: SupabaseDictionaryRow): Partial<DictionaryWordDto> {
  return {
    definition: row.definition ?? null,
    partOfSpeech: row.partOfSpeech ?? null,
    phonetic: row.phonetic ?? null,
    example: row.example ?? null,
    quote: row.quote ?? null,
    sourceBookId: row.sourceBookId ?? null,
    sourceBookTitle: row.sourceBookTitle ?? null,
    sourceBookAuthor: row.sourceBookAuthor ?? null,
    sourceChapter: row.sourceChapter ?? null,
    sourceLocator: row.sourceLocator ?? null,
  };
}

/**
 * REQ-DSI-003 / Decision 6: one outbox payload shape for every dictionary
 * UPSERT — the full 17-column row snapshot. A partial payload would omit keys
 * that `SupabaseDictionarySync.upsert` serialises, and an absent key can only
 * be read back as "erase this column" once the mapper applies a null fallback.
 * `userId` is added by `queueOutbox`; the natural key is computed from the word
 * so the client never depends on the server echoing a value it owns.
 */
export function dictionaryOutboxPayload(dto: DictionaryWordDto): Record<string, unknown> {
  return {
    word: dto.word,
    normalizedWord: normalizeDictionaryKey(dto.word ?? ''),
    tags: dto.tags ?? [],
    srsStage: dto.srsStage ?? 0,
    updatedAt: dto.updatedAt ?? dto.createdAt,
    createdAt: dto.createdAt,
    deletedAt: dto.deletedAt ?? null,
    definition: dto.definition ?? null,
    partOfSpeech: dto.partOfSpeech ?? null,
    phonetic: dto.phonetic ?? null,
    example: dto.example ?? null,
    quote: dto.quote ?? null,
    sourceBookId: dto.sourceBookId ?? null,
    sourceBookTitle: dto.sourceBookTitle ?? null,
    sourceBookAuthor: dto.sourceBookAuthor ?? null,
    sourceChapter: dto.sourceChapter ?? null,
    sourceLocator: dto.sourceLocator ?? null,
  };
}

function levenshtein(a: string, b: string): number {
  if (a.length === 0) return b.length;
  if (b.length === 0) return a.length;
  const prev: number[] = Array.from({ length: b.length + 1 }, (_, i) => i);
  const cur: number[] = new Array(b.length + 1);
  for (let i = 0; i < a.length; i++) {
    cur[0] = i + 1;
    for (let j = 0; j < b.length; j++) {
      const cost = a[i] === b[j] ? 0 : 1;
      cur[j + 1] = Math.min(prev[j + 1] + 1, cur[j] + 1, prev[j] + cost);
    }
    for (let k = 0; k < prev.length; k++) prev[k] = cur[k];
  }
  return prev[b.length];
}

export function createDictionaryState(): {
  readonly words: DictionaryWordDto[];
  readonly isLoading: boolean;
  readonly error: string | null;
  syncEnabled: boolean;
  load(): Promise<void>;
  add(
    word: string,
    opts?: {
      tags?: string[];
      srsStage?: number;
      evidence?: DictionaryEvidence | null;
    },
  ): Promise<DictionaryWordDto>;
  capture(entryId: string, evidence: DictionaryEvidence): Promise<DictionaryWordDto>;
  update(
    id: string,
    patch: {
      word?: string;
      tags?: string[];
      srsStage?: number;
      definition?: string | null;
      partOfSpeech?: string | null;
      phonetic?: string | null;
      example?: string | null;
    },
  ): Promise<DictionaryWordDto>;
  remove(id: string): Promise<void>;
  search(query: string, limit?: number): DictionaryWordDto[];
  exportData(format: 'json' | 'csv'): Promise<string>;
  importData(
    payload: string,
    format: 'json' | 'csv',
  ): Promise<{ imported: number; errors: { row: number; reason: string }[] }>;
  subscribeToRemoteChanges(): void;
  unsubscribe(): void;
} {
  let words = $state<DictionaryWordDto[]>([]);
  let isLoading = $state(false);
  let error = $state<string | null>(null);
  let realtimeUnsub: (() => void) | null = null;
  let dictSync: SupabaseDictionarySync | null = null;
  let syncEnabled = $state(true);

  async function load(): Promise<void> {
    isLoading = true;
    error = null;
    try {
      words = await invoke<DictionaryWordDto[]>('listDictionaryWords');
    } catch (e) {
      error = e instanceof Error ? e.message : 'Failed to load dictionary';
      words = [];
    } finally {
      isLoading = false;
    }
  }

  function queueOutbox(
    entityId: string,
    operation: 'UPSERT' | 'DELETE',
    payload: Record<string, unknown>,
  ): void {
    if (!syncEnabled || !hasLiveSession() || !authState.userId) return;
    const now = new Date().toISOString();
    const enriched = { ...payload, userId: authState.userId, updatedAt: now };
    // fire-and-forget coalesced outbox
    void invoke('addCoalescedSyncOutboxItem', {
      entityType: 'DICTIONARY_WORD',
      entityId,
      operation,
      payloadJson: JSON.stringify(enriched),
    }).catch(() => {});
  }

  async function add(
    word: string,
    opts?: {
      tags?: string[];
      srsStage?: number;
      evidence?: DictionaryEvidence | null;
    },
  ): Promise<DictionaryWordDto> {
    const evidence = opts?.evidence ?? null;
    const created: DictionaryWordDto = await invoke<DictionaryWordDto>('addDictionaryWord', {
      payload: {
        word,
        tags: opts?.tags,
        srsStage: opts?.srsStage,
        userId: authState.userId ?? undefined,
        // REQ-DRE-003: the six evidence fields are sent explicitly. When the
        // branch has no evidence (non-EPUB, no quote) they are an explicit
        // null rather than an omitted key.
        quote: evidence?.quote ?? null,
        sourceBookId: evidence?.sourceBookId ?? null,
        sourceBookTitle: evidence?.sourceBookTitle ?? null,
        sourceBookAuthor: evidence?.sourceBookAuthor ?? null,
        sourceChapter: evidence?.sourceChapter ?? null,
        sourceLocator: evidence?.sourceLocator ?? null,
      },
    });
    words = [...words, created].sort((a, b) => (a.word ?? '').localeCompare(b.word ?? ''));
    queueOutbox(created.id, 'UPSERT', dictionaryOutboxPayload(created));
    return created;
  }

  /**
   * REQ-DRE-008 / REQ-DSI-003: the re-capture write. Only the six evidence
   * columns travel, so a capture can never clear a user-authored field; the
   * outbox row is still the full snapshot so the remote row keeps them too.
   */
  async function capture(
    entryId: string,
    evidence: DictionaryEvidence,
  ): Promise<DictionaryWordDto> {
    const updated: DictionaryWordDto = await invoke<DictionaryWordDto>('updateDictionaryEvidence', {
      payload: { id: entryId, ...evidence },
    });
    words = words.map((w) => (w.id === entryId ? updated : w));
    queueOutbox(entryId, 'UPSERT', dictionaryOutboxPayload(updated));
    return updated;
  }

  async function update(
    id: string,
    patch: {
      word?: string;
      tags?: string[];
      srsStage?: number;
      definition?: string | null;
      partOfSpeech?: string | null;
      phonetic?: string | null;
      example?: string | null;
    },
  ): Promise<DictionaryWordDto> {
    const updated: DictionaryWordDto = await invoke<DictionaryWordDto>('updateDictionaryWord', {
      payload: { id, ...patch },
    });
    words = words.map((w) => (w.id === id ? updated : w));
    queueOutbox(id, 'UPSERT', dictionaryOutboxPayload(updated));
    return updated;
  }

  async function remove(id: string): Promise<void> {
    const removed = words.find((w) => w.id === id);
    await invoke('removeDictionaryWord', { id });
    words = words.filter((w) => w.id !== id);
    const now = new Date().toISOString();
    if (syncEnabled && hasLiveSession() && authState.userId) {
      void invoke('addCoalescedSyncOutboxItem', {
        entityType: 'DICTIONARY_WORD',
        entityId: id,
        operation: 'DELETE',
        payloadJson: JSON.stringify({
          userId: authState.userId,
          updatedAt: now,
          deletedAt: now,
          // REQ-DSI-002: the remote delete targets the natural key, not the id.
          normalizedWord: normalizeDictionaryKey(removed?.word ?? ''),
        }),
      }).catch(() => {});
    }
  }

  function search(query: string, limit = 20): DictionaryWordDto[] {
    const q = normalizedForSearch(query);
    if (!q) return words.slice(0, limit);
    const scored = words
      .map((w) => {
        const n = normalizedForSearch(w.word ?? '');
        let score = 99;
        if (n === q) score = 0;
        else if (n.startsWith(q)) score = 1;
        else if (n.includes(q)) score = 2;
        else if (levenshtein(n, q) <= 2) score = 3;
        else score = 99;
        return { w, score };
      })
      .filter((x) => x.score < 99)
      .sort((a, b) => a.score - b.score || (b.w.updatedAt ?? '').localeCompare(a.w.updatedAt ?? ''))
      .slice(0, limit)
      .map((x) => x.w);
    return scored;
  }

  async function exportData(format: 'json' | 'csv'): Promise<string> {
    return await invoke<string>('exportDictionary', { format });
  }

  async function importData(
    payload: string,
    format: 'json' | 'csv',
  ): Promise<{ imported: number; errors: { row: number; reason: string }[] }> {
    const res = await invoke<{ imported: number; errors: { row: number; reason: string }[] }>(
      'importDictionary',
      {
        payload,
        format,
        userId: authState.userId ?? null,
      },
    );
    await load();
    return res;
  }

  function subscribeToRemoteChanges(): void {
    if (!authState.userId || !hasLiveSession()) return;
    if (realtimeUnsub) return;
    dictSync = new SupabaseDictionarySync(authState.userId);
    realtimeUnsub = dictSync.subscribeToDictionary((row) => {
      // LWW: keep newer updatedAt, tie breaker createdAt
      const local = words.find(
        (w) => w.id === row.id || normalizedForSearch(w.word ?? '') === row.normalizedWord,
      );
      if (row.deletedAt) {
        // REQ-DSI-002: the remote row's id may belong to another device, so the
        // natural key decides too — otherwise the local entry stays visible.
        words = words.filter(
          (w) => w.id !== row.id && normalizeDictionaryKey(w.word ?? '') !== row.normalizedWord,
        );
        return;
      }
      if (local) {
        const localUpdated = local.updatedAt ?? local.createdAt;
        if (row.updatedAt > localUpdated) {
          words = words.map((w) =>
            w.id === local.id
              ? {
                  ...w,
                  word: row.word,
                  tags: row.tags,
                  srsStage: row.srsStage,
                  updatedAt: row.updatedAt,
                  // REQ-DSI-003 scenario 2: the ten rich-entry fields travel on
                  // every winning realtime update, never a subset.
                  ...evidenceFields(row),
                }
              : w,
          );
        } else if (row.updatedAt === localUpdated && row.createdAt > (local.createdAt ?? '')) {
          words = words.map((w) =>
            w.id === local.id ? { ...w, word: row.word, ...evidenceFields(row) } : w,
          );
        }
      } else {
        const dto: DictionaryWordDto = {
          id: row.id,
          word: row.word,
          createdAt: row.createdAt,
          normalizedWord: row.normalizedWord,
          userId: row.userId,
          tags: row.tags,
          srsStage: row.srsStage,
          updatedAt: row.updatedAt,
          deletedAt: row.deletedAt ?? null,
          syncedAt: null,
        } as unknown as DictionaryWordDto;
        words = [...words, dto].sort((a, b) => (a.word ?? '').localeCompare(b.word ?? ''));
      }
    });
  }

  function unsubscribe(): void {
    if (realtimeUnsub) {
      try {
        realtimeUnsub();
      } catch {}
      realtimeUnsub = null;
    }
    if (dictSync) {
      try {
        dictSync.destroy();
      } catch {}
      dictSync = null;
    }
  }

  return {
    get words() {
      return words;
    },
    get isLoading() {
      return isLoading;
    },
    get error() {
      return error;
    },
    get syncEnabled() {
      return syncEnabled;
    },
    set syncEnabled(v: boolean) {
      syncEnabled = v;
    },
    load,
    add,
    capture,
    update,
    remove,
    search,
    exportData,
    importData,
    subscribeToRemoteChanges,
    unsubscribe,
  };
}

export type DictionaryStateApi = ReturnType<typeof createDictionaryState>;

export const dictionaryState = createDictionaryState();
