import type {
  DefinitionResult,
  DictionaryLookupPort,
} from '$lib/shared/ports/DictionaryLookupPort';
import * as tauriClient from '$lib/shared/api/tauriClient';

export class TauriDictionaryLookupAdapter implements DictionaryLookupPort {
  define(input: {
    text: string;
    readerLang: string;
    wordLang: string | null;
  }): Promise<DefinitionResult> {
    return tauriClient.defineSelection(input);
  }
}
