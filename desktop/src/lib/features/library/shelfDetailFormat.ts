import type { LibraryBookDto, CollectionDto } from '$lib/shared/types';
import type { MessageKey } from '$lib/shared/i18n';

export type Translator = (key: MessageKey, params?: Record<string, string | number>) => string;

export const LANGUAGE_KEY_MAP: Record<string, MessageKey> = {
  es: 'shelf.langSpanish' as MessageKey,
  en: 'shelf.langEnglish' as MessageKey,
  fr: 'shelf.langFrench' as MessageKey,
  de: 'shelf.langGerman' as MessageKey,
  it: 'shelf.langItalian' as MessageKey,
  pt: 'shelf.langPortuguese' as MessageKey,
  ru: 'shelf.langRussian' as MessageKey,
  ja: 'shelf.langJapanese' as MessageKey,
  zh: 'shelf.langChinese' as MessageKey,
  ar: 'shelf.langArabic' as MessageKey,
  ko: 'shelf.langKorean' as MessageKey,
  nl: 'shelf.langDutch' as MessageKey,
  pl: 'shelf.langPolish' as MessageKey,
  sv: 'shelf.langSwedish' as MessageKey,
  tr: 'shelf.langTurkish' as MessageKey,
  vi: 'shelf.langVietnamese' as MessageKey,
  hi: 'shelf.langHindi' as MessageKey,
};

export function getCurrentStatus(book: LibraryBookDto | null): string {
  if (!book) return 'reading';
  if (book.readingStatus === 'completed') return 'completed';
  if (book.readingStatus === 'to_read') return 'to_read';
  return 'reading';
}

export function formatMinutes(minutes: number, t: Translator): string {
  if (minutes < 1) return t('shelf.lessThanMinute' as MessageKey);
  const h = Math.floor(minutes / 60);
  const m = minutes % 60;
  if (h > 0 && m > 0) return t('shelf.formatHoursMinutes' as MessageKey, { h, m });
  if (h > 0) return t('shelf.formatHours' as MessageKey, { h });
  return t('shelf.formatMins' as MessageKey, { m });
}

export function getCollectionNames(
  ids: number[] | undefined,
  collections: CollectionDto[],
): string[] {
  if (!ids || ids.length === 0) return [];
  const result: string[] = [];
  for (const id of ids) {
    const coll = collections.find((c) => c.id === id);
    if (coll) result.push(coll.name);
  }
  return result;
}

export function formatRelativeDate(iso: string, now: Date, t: Translator): string {
  try {
    const date = new Date(iso);
    const diffMs = now.getTime() - date.getTime();
    const diffMin = Math.floor(diffMs / (1000 * 60));
    const diffDays = Math.floor(diffMs / (1000 * 60 * 60 * 24));
    if (diffDays === 0) {
      if (diffMin < 1) return t('shelf.now' as MessageKey);
      if (diffMin < 60) return t('shelf.minutesAgo' as MessageKey, { n: diffMin });
      const hours = Math.floor(diffMin / 60);
      return t('shelf.hoursAgo' as MessageKey, { n: hours });
    }
    if (diffDays === 1) return t('shelf.yesterday' as MessageKey);
    if (diffDays < 7) return t('shelf.daysAgo' as MessageKey, { n: diffDays });
    if (diffDays < 30) return t('shelf.weeksAgo' as MessageKey, { n: Math.floor(diffDays / 7) });
    if (diffDays < 365) {
      const months = Math.floor(diffDays / 30);
      return months === 1
        ? t('shelf.monthAgo' as MessageKey)
        : t('shelf.monthsAgo' as MessageKey, { n: months });
    }
    const years = Math.floor(diffDays / 365);
    return years === 1
      ? t('shelf.yearAgo' as MessageKey)
      : t('shelf.yearsAgo' as MessageKey, { n: years });
  } catch {
    return '';
  }
}

export function getLanguageName(code: string, t: Translator): string {
  const key = LANGUAGE_KEY_MAP[code.toLowerCase()];
  return key ? t(key) : code.toUpperCase();
}

export function formatPublicationDate(iso: string): string {
  if (!iso) return '';
  try {
    const date = new Date(iso);
    if (isNaN(date.getTime())) return iso;
    return date.toLocaleDateString('es-ES', { year: 'numeric', month: 'short' });
  } catch {
    return iso;
  }
}
