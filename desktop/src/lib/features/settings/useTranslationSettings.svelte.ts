import type { TranslationCacheEntryDto } from '$lib/shared/types';
import type { TranslationPort } from '$lib/shared/ports/TranslationPort';
import { TauriTranslationAdapter } from '$lib/shared/ports/adapters/tauri/TauriTranslationAdapter';

/**
 * The settings surface's view of translation preferences and history (T3/T4).
 *
 * The pair is account-scoped in Rust: the account is read from
 * `AppState.active_user` and never sent from here, so there is nothing for this
 * hook to scope and nothing it could leak across accounts. Both sides default
 * to `null`, which means "infer" — source from the book, target from the app
 * locale — and is a real, working value, not a missing one.
 */
export type TranslationSettingsDeps = {
  port?: TranslationPort;
};

export function createTranslationSettings(deps: TranslationSettingsDeps = {}): {
  sourceLanguage: string | null;
  targetLanguage: string | null;
  history: TranslationCacheEntryDto[];
  isLoading: boolean;
  isSaving: boolean;
  isClearingHistory: boolean;
  error: string | null;
  load: () => Promise<void>;
  save: (sourceLanguage: string | null, targetLanguage: string | null) => Promise<void>;
  clearHistory: () => Promise<void>;
} {
  const port: TranslationPort = deps.port ?? new TauriTranslationAdapter();

  let sourceLanguage = $state<string | null>(null);
  let targetLanguage = $state<string | null>(null);
  let history = $state<TranslationCacheEntryDto[]>([]);
  let isLoading = $state(false);
  let isSaving = $state(false);
  let isClearingHistory = $state(false);
  let error = $state<string | null>(null);

  const toMessage = (cause: unknown): string =>
    cause instanceof Error ? cause.message : 'Translation settings command failed.';

  async function load(): Promise<void> {
    isLoading = true;
    error = null;
    try {
      const [preferences, entries] = await Promise.all([port.getPreferences(), port.listHistory()]);
      sourceLanguage = preferences?.sourceLanguage ?? null;
      targetLanguage = preferences?.targetLanguage ?? null;
      history = entries;
    } catch (cause) {
      error = toMessage(cause);
    } finally {
      isLoading = false;
    }
  }

  async function save(nextSource: string | null, nextTarget: string | null): Promise<void> {
    isSaving = true;
    error = null;
    try {
      const saved = await port.savePreferences({
        sourceLanguage: nextSource,
        targetLanguage: nextTarget,
      });
      sourceLanguage = saved.sourceLanguage ?? nextSource;
      targetLanguage = saved.targetLanguage ?? nextTarget;
    } catch (cause) {
      error = toMessage(cause);
      throw cause;
    } finally {
      isSaving = false;
    }
  }

  /**
   * Only the list is cleared locally, and only after the backend confirms: a
   * failed delete that emptied the UI would be a lie the reader cannot detect.
   */
  async function clearHistory(): Promise<void> {
    isClearingHistory = true;
    error = null;
    try {
      await port.clearHistory();
      history = [];
    } catch (cause) {
      error = toMessage(cause);
      throw cause;
    } finally {
      isClearingHistory = false;
    }
  }

  return {
    get sourceLanguage() {
      return sourceLanguage;
    },
    set sourceLanguage(v: string | null) {
      sourceLanguage = v;
    },
    get targetLanguage() {
      return targetLanguage;
    },
    set targetLanguage(v: string | null) {
      targetLanguage = v;
    },
    get history() {
      return history;
    },
    get isLoading() {
      return isLoading;
    },
    get isSaving() {
      return isSaving;
    },
    get isClearingHistory() {
      return isClearingHistory;
    },
    get error() {
      return error;
    },
    load,
    save,
    clearHistory,
  };
}
