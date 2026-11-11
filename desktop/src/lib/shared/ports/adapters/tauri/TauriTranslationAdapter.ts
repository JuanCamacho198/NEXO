import type { TranslationPort } from '$lib/shared/ports/TranslationPort';
import type {
  BookTranslationSourceDto,
  TranslationCacheEntryDto,
  TranslationPreferencesDto,
} from '$lib/shared/types';
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

  getBookSourceLanguage(bookId: string): Promise<string | null> {
    return tauriClient.getBookTranslationSource(bookId);
  }

  getBookSourceChoice(bookId: string): Promise<BookTranslationSourceDto | null> {
    return tauriClient.getBookTranslationSourceChoice(bookId);
  }

  saveBookSourceLanguage(input: {
    bookId: string;
    sourceLanguage: string | null;
  }): Promise<BookTranslationSourceDto> {
    return tauriClient.saveBookTranslationSource(input);
  }

  saveDefinitionLanguage(input: {
    definitionLanguage: string | null;
  }): Promise<TranslationPreferencesDto> {
    return tauriClient.saveDefinitionLanguage(input);
  }

  listHistory(): Promise<TranslationCacheEntryDto[]> {
    return tauriClient.listTranslationHistory();
  }

  clearHistory(): Promise<number> {
    return tauriClient.clearTranslationHistory();
  }
}
