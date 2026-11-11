import type { TranslationPort } from '$lib/shared/ports/TranslationPort';
import type {
  BookTranslationSourceDto,
  TranslationCacheEntryDto,
  TranslationPreferencesDto,
} from '$lib/shared/types';

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
      // The pair's write does not touch the definition language: it is sent by
      // a different control (see `saveDefinitionLanguage`), and the double
      // mirrors that so a test cannot pass on a combined write the app never
      // performs.
      definitionLanguage: this.#preferences?.definitionLanguage ?? null,
      updatedAt: '1970-01-01T00:00:00Z',
    };
    return this.#preferences;
  }

  /**
   * The definition language is written on its own (D6), leaving the pair as it
   * was — the same split the repository enforces, so the double cannot let a
   * test pass on behaviour the backend would not produce.
   */
  async saveDefinitionLanguage(input: {
    definitionLanguage: string | null;
  }): Promise<TranslationPreferencesDto> {
    this.#preferences = {
      sourceLanguage: this.#preferences?.sourceLanguage ?? null,
      targetLanguage: this.#preferences?.targetLanguage ?? null,
      definitionLanguage: input.definitionLanguage,
      updatedAt: '1970-01-01T00:00:00Z',
    };
    return this.#preferences;
  }

  // The per-book source level (T12/T13). Keyed by book id because the real
  // table is, and the two levels are kept APART here exactly as Rust keeps
  // them: the double must not collapse the fallback into the book's own choice,
  // or a test could not tell "the reader chose this for the book" from "the
  // book inherited the account's preference" — which is the provenance the UI
  // reads to decide whether a choice is clearable.
  #bookSources = new Map<string, string | null>();

  async getBookSourceLanguage(bookId: string): Promise<string | null> {
    const own = this.#bookSources.get(bookId);
    if (own !== undefined && own !== null) return own;
    // The account fallback, in the same order `resolve_book_translation_source`
    // uses: the book's choice, then the general preference, then `null`.
    return this.#preferences?.sourceLanguage ?? null;
  }

  async getBookSourceChoice(bookId: string): Promise<BookTranslationSourceDto | null> {
    const own = this.#bookSources.get(bookId);
    if (own === undefined) return null;
    return { sourceLanguage: own, updatedAt: '1970-01-01T00:00:00Z' };
  }

  async saveBookSourceLanguage(input: {
    bookId: string;
    sourceLanguage: string | null;
  }): Promise<BookTranslationSourceDto> {
    // `null` is a REAL value, not a missing one: it clears this book's choice so
    // the resolution falls through to the general preference again — the same
    // contract `save_book_translation_source` documents.
    this.#bookSources.set(input.bookId, input.sourceLanguage);
    return { sourceLanguage: input.sourceLanguage, updatedAt: '1970-01-01T00:00:00Z' };
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
