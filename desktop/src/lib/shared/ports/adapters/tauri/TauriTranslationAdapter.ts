import type { TranslationPort } from '$lib/shared/ports/TranslationPort';
import type { TranslationCacheEntryDto, TranslationPreferencesDto } from '$lib/shared/types';
import * as tauriClient from '$lib/shared/api/tauriClient';

export class TauriTranslationAdapter implements TranslationPort {
  translate(input: { text: string; sourceLang: string; targetLang: string }): Promise<string> {
    return tauriClient.translateSelection(input);
  }

  getPreferences(): Promise<TranslationPreferencesDto | null> {
    return tauriClient.getTranslationPreferences();
  }

  savePreferences(input: {
    sourceLanguage: string | null;
    targetLanguage: string | null;
  }): Promise<TranslationPreferencesDto> {
    return tauriClient.saveTranslationPreferences(input);
  }

  listHistory(): Promise<TranslationCacheEntryDto[]> {
    return tauriClient.listTranslationHistory();
  }

  clearHistory(): Promise<number> {
    return tauriClient.clearTranslationHistory();
  }
}
