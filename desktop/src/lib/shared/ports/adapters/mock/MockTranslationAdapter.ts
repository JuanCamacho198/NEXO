import type { TranslationPort } from '$lib/shared/ports/TranslationPort';

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
}
