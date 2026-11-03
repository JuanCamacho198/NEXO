import type { TranslationPort } from '$lib/shared/ports/TranslationPort';
import type { TranslationCacheEntryDto, TranslationPreferencesDto } from '$lib/shared/types';

/**
 * In-memory translation double for tests.
 *
 * It mirrors the Rust command's contract, not the network: untranslatable
 * input, echoes and HTML entities all reject, so a caller cannot pass a test
 * here and fail in the app. `setResponse` lets a test pin the next answer;
 * without it a deterministic pseudo-translation is returned.
 */
export class MockTranslationAdapter implements TranslationPort {
  #responses = new Map<string, string | Error>();
  #calls: Array<{ text: string; sourceLang: string; targetLang: string }> = [];

  /** Pins the answer (or the rejection) for the next matching `text`. */
  setResponse(text: string, response: string | Error): void {
    this.#responses.set(text, response);
  }

  /** Every call received, oldest first — for assertions on wiring. */
  get calls(): ReadonlyArray<{ text: string; sourceLang: string; targetLang: string }> {
    return this.#calls;
  }

  async translate(input: {
    text: string;
    sourceLang: string;
    targetLang: string;
  }): Promise<string> {
    this.#calls.push({ ...input });
    const pinned = this.#responses.get(input.text);
    if (pinned instanceof Error) throw pinned;
    if (typeof pinned === 'string') return pinned;
    return `[${input.targetLang}] ${input.text.trim()}`;
  }

  // Preferences and history are account-scoped in Rust; the double keeps them
  // in memory so a test can drive both sides of the pair without a database.
  #preferences: TranslationPreferencesDto | null = null;
  #history: TranslationCacheEntryDto[] = [];

  async getPreferences(): Promise<TranslationPreferencesDto | null> {
    return this.#preferences;
  }

  async savePreferences(input: {
    sourceLanguage: string | null;
    targetLanguage: string | null;
  }): Promise<TranslationPreferencesDto> {
    this.#preferences = {
      sourceLanguage: input.sourceLanguage,
      targetLanguage: input.targetLanguage,
      updatedAt: '1970-01-01T00:00:00Z',
    };
    return this.#preferences;
  }

  async listHistory(): Promise<TranslationCacheEntryDto[]> {
    return [...this.#history];
  }

  async clearHistory(): Promise<number> {
    const removed = this.#history.length;
    this.#history = [];
    return removed;
  }
}
