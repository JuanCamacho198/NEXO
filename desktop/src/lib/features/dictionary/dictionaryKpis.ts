import type { DictionaryWordDto } from '$lib/shared/types';

/**
 * The three Dictionary KPIs the `.pen` frame draws (Decision 15). Each one is
 * implementable with the schema this change adds; completeness is deliberately
 * not one of them (Decision 16 gives it to the per-row badge instead).
 */
export type DictionaryKpis = {
  total: number;
  thisWeek: number;
  referencedBooks: number;
};

const WEEK_MS = 7 * 24 * 60 * 60 * 1000;

function countWords(words: readonly DictionaryWordDto[]): number {
  return words.length;
}

/** Entries whose `created_at` falls inside the last seven days. */
function countWordsThisWeek(words: readonly DictionaryWordDto[], now: Date = new Date()): number {
  const cutoff = now.getTime() - WEEK_MS;
  return words.filter((word) => {
    const created = Date.parse(word.createdAt ?? '');
    return Number.isFinite(created) && created >= cutoff;
  }).length;
}

/** Distinct books referenced by evidence; entries without `sourceBookId` count for none. */
function countReferencedBooks(words: readonly DictionaryWordDto[]): number {
  const ids = new Set<string>();
  for (const word of words) {
    if (word.sourceBookId) ids.add(word.sourceBookId);
  }
  return ids.size;
}

export function deriveDictionaryKpis(
  words: readonly DictionaryWordDto[],
  now: Date = new Date(),
): DictionaryKpis {
  return {
    total: countWords(words),
    thisWeek: countWordsThisWeek(words, now),
    referencedBooks: countReferencedBooks(words),
  };
}
