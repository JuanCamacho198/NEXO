import type { TranslationPort } from '$lib/shared/ports/TranslationPort';
import * as tauriClient from '$lib/shared/api/tauriClient';

export class TauriTranslationAdapter implements TranslationPort {
  translate(input: { text: string; sourceLang: string; targetLang: string }): Promise<string> {
    return tauriClient.translateSelection(input);
  }
}
