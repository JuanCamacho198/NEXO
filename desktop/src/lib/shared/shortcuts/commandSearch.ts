/**
 * Command-palette matching. Pure and dependency-free so the ranking rules can
 * be exercised without a DOM.
 *
 * Every row exposes two searchable strings: its `label` (the text the row
 * renders) and its `description` (the context it lives under). A match on the
 * label always ranks above a match that only reaches the description, and the
 * original order is kept within the same rank.
 */

/**
 * Light fuzzy match: true when every character of `needle` occurs in `haystack`
 * in order, gaps allowed, so a typo like `librry` still finds `Library`.
 * Case-insensitive; an empty needle matches everything.
 */
export function isSubsequence(needle: string, haystack: string): boolean {
  if (needle.length === 0) return true;
  let cursor = 0;
  for (const char of haystack) {
    if (char === needle[cursor]) {
      cursor += 1;
      if (cursor === needle.length) return true;
    }
  }
  return false;
}

/**
 * Rank for one row against `needle`: 0 when the label matches, 1 when only the
 * description matches, `null` when neither does. Both an exact substring and a
 * fuzzy subsequence count as a match for their field.
 */
export function matchRank(needle: string, label: string, description: string): number | null {
  const query = needle.toLowerCase();
  const name = label.toLowerCase();
  if (name.includes(query) || isSubsequence(query, name)) return 0;
  const context = description.toLowerCase();
  if (context.includes(query) || isSubsequence(query, context)) return 1;
  return null;
}

/**
 * Ranked, stable order of `items` for `needle`: unmatched rows are dropped,
 * label matches come first, then description matches, and the input order is
 * preserved within each rank.
 */
export function rankMatches<T>(
  items: readonly T[],
  needle: string,
  getLabel: (item: T) => string,
  getDescription: (item: T) => string,
): T[] {
  return items
    .map((item, index) => ({
      item,
      index,
      rank: matchRank(needle, getLabel(item), getDescription(item)),
    }))
    .filter((row): row is { item: T; index: number; rank: number } => row.rank !== null)
    .sort((a, b) => a.rank - b.rank || a.index - b.index)
    .map((row) => row.item);
}
