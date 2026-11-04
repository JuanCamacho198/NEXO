import type {
  DefinitionResult,
  DefinitionSource,
  DictionaryLookupPort,
} from '$lib/shared/ports/DictionaryLookupPort';

/**
 * In-memory dictionary-lookup double for tests.
 *
 * It mirrors the Rust command's contract, not the network: a miss is an EMPTY
 * result with `source: null`, never a rejection, because that is what the
 * command actually returns when every source has nothing and a test must not be
 * able to pass on a shape the app never sees.
 *
 * `setResponse` pins a whole result; `setMiss` pins the honest empty one.
 * Without either, a deterministic native definition is returned so component
 * tests do not need to configure anything.
 *
 * The attribution default is the real CC BY-SA string from `commands/define.rs`
 * rather than a placeholder: a test asserting the license renders must fail if
 * the component drops it, and a fake string would make that assertion vacuous.
 */
export class MockDictionaryLookupAdapter implements DictionaryLookupPort {
  #responses = new Map<string, DefinitionResult | Error>();
  #calls: Array<{ text: string; readerLang: string; wordLang: string | null }> = [];

  /** Pins the answer (or the rejection) for the next matching `text`. */
  setResponse(text: string, response: DefinitionResult | Error): void {
    this.#responses.set(text, response);
  }

  /** Pins the "every source missed" answer for `text`. Not an error. */
  setMiss(text: string): void {
    this.setResponse(text, {
      word: text.trim(),
      definitions: [],
      source: null,
      attribution: WIKTIONARY_ATTRIBUTION,
    });
  }

  /** Pins a result with an explicit `source`, for the UI's warning contract. */
  setResult(
    text: string,
    source: DefinitionSource | null,
    definitions: DefinitionResult['definitions'],
  ): void {
    this.setResponse(text, {
      word: text.trim(),
      definitions,
      source,
      attribution: WIKTIONARY_ATTRIBUTION,
    });
  }

  /** Every call received, oldest first — for assertions on wiring. */
  get calls(): ReadonlyArray<{ text: string; readerLang: string; wordLang: string | null }> {
    return this.#calls;
  }

  async define(input: {
    text: string;
    readerLang: string;
    wordLang: string | null;
  }): Promise<DefinitionResult> {
    this.#calls.push({ ...input });
    const pinned = this.#responses.get(input.text);
    if (pinned instanceof Error) throw pinned;
    if (pinned) return pinned;
    return {
      word: input.text.trim(),
      definitions: [{ text: `[${input.readerLang}] ${input.text.trim()}`, partOfSpeech: null }],
      source: 'native',
      attribution: WIKTIONARY_ATTRIBUTION,
    };
  }
}

/** The real attribution string, copied from `commands/define.rs`. */
export const WIKTIONARY_ATTRIBUTION = 'Wiktionary, CC BY-SA 4.0 (https://www.wiktionary.org/)';
