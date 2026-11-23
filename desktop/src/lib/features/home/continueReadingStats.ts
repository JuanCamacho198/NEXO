import type { LibraryBookDto } from '$lib/shared/types';
import type { MessageKey } from '$lib/shared/i18n';
import { formatRelativeDate, type Translator } from '$lib/features/library/shelfDetailFormat';

export type ContinueStat = {
  key: 'chapter' | 'lastRead';
  label: string;
  value: string;
};

/**
 * The facts the Continue Reading hero shows beside the book: where the reader
 * left off and when. `chapter` comes from what the reader recorded (see
 * lastChapterStore); a row whose input is missing is left out, never faked.
 */
export function buildContinueStats(
  book: LibraryBookDto,
  chapter: string | null,
  now: Date,
  t: Translator,
): ContinueStat[] {
  const stats: ContinueStat[] = [];

  if (chapter) {
    stats.push({ key: 'chapter', label: t('home.continue.chapter' as MessageKey), value: chapter });
  }

  const lastIso = book.progressUpdatedAt ?? book.updatedAt;
  if (lastIso && !Number.isNaN(Date.parse(lastIso))) {
    const value = formatRelativeDate(lastIso, now, t);
    if (value) {
      stats.push({ key: 'lastRead', label: t('home.continue.lastRead' as MessageKey), value });
    }
  }

  return stats;
}
