/**
 * Discover trending-chip taxonomy + client-side chip filtering.
 *
 * `CHIP_KEYWORDS` is the single source of truth for the chip id -> English
 * search-term mapping used by client-side chip filtering. The rail rotation no
 * longer derives from these tables (DISC-04b): rails are curated author shelves
 * with their own terms, and chips filter loaded books by subject substring.
 *
 * The taxonomy is keyed by a stable `id`, never by the visible label: the label
 * is locale-dependent and lives in i18n, while the id is what the selection
 * state, the keyword table, and the tests carry.
 */
import type { MessageKey } from '$lib/shared/i18n/messages.en';
import type { CatalogBook } from '$lib/shared/services/catalog';

/** A genre chip: stable selection id + localized label + English subject terms. */
export interface DiscoverChip {
  /** Stable selection value and keyword-table key; locale-independent. */
  readonly id: string;
  /** i18n label key, shared with the thematic rails for the same genre. */
  readonly labelKey: MessageKey;
  /** Locale-less fallback, kept in sync with the ES label. */
  readonly fallback: string;
  /** English subject keywords the catalog actually serves. */
  readonly keywords: readonly string[];
}

/** Seven static chips; selection filters loaded rails client-side only (no catalog call). */
export const CHIP_TAXONOMY: readonly DiscoverChip[] = [
  {
    id: 'fiction',
    labelKey: 'discover.rail.thematic.fiction',
    fallback: 'Ficción',
    keywords: ['fiction'],
  },
  {
    id: 'classic',
    labelKey: 'discover.rail.thematic.classic',
    fallback: 'Clásicos',
    keywords: ['classic'],
  },
  {
    id: 'adventure',
    labelKey: 'discover.rail.thematic.adventure',
    fallback: 'Aventura',
    keywords: ['adventure'],
  },
  {
    id: 'mystery',
    labelKey: 'discover.rail.thematic.mystery',
    fallback: 'Misterio',
    keywords: ['mystery', 'detective'],
  },
  {
    id: 'romance',
    labelKey: 'discover.rail.thematic.romance',
    fallback: 'Romance',
    keywords: ['romance', 'love'],
  },
  {
    id: 'science',
    labelKey: 'discover.rail.thematic.science',
    fallback: 'Ciencia ficción',
    keywords: ['science'],
  },
  {
    id: 'history',
    labelKey: 'discover.rail.thematic.history',
    fallback: 'Historia',
    keywords: ['history'],
  },
];

/**
 * Legacy label list (the ES fallbacks), kept for consumers that hold the static
 * chip list as strings. Prefer `CHIP_TAXONOMY` for anything that renders or
 * selects a chip.
 */
export const TRENDING_CHIPS: readonly string[] = CHIP_TAXONOMY.map((chip) => chip.fallback);

/**
 * Chip id → English subject keywords. Catalog subjects arrive in English
 * (Open Library/Google Books), so a normalized substring check alone would miss
 * (`ficción` vs `fiction`); keywords bridge the locale gap. Pure client-side
 * filter — never triggers a catalog call.
 */
export const CHIP_KEYWORDS: Record<string, readonly string[]> = Object.fromEntries(
  CHIP_TAXONOMY.map((chip) => [chip.id, chip.keywords] as const),
);

function normalizeHaystack(value: string): string {
  return value
    .toLowerCase()
    .normalize('NFD')
    .replace(/[\u0300-\u036f]/g, '');
}

/**
 * Needles for a selection: the ES fallback plus the English keywords. The id
 * itself is never a needle (that would match the raw id inside English subject
 * text and change the result set). A value outside the taxonomy keeps the old
 * literal-substring behavior.
 */
function needlesFor(chip: string): readonly string[] {
  const entry = CHIP_TAXONOMY.find(
    (candidate) => candidate.id === chip || candidate.fallback === chip,
  );
  return entry ? [entry.fallback, ...entry.keywords] : [chip];
}

/** True when a book matches a trend chip id (or its legacy ES label). */
export function matchesChip(book: CatalogBook, chip: string): boolean {
  const haystack = normalizeHaystack(
    `${book.title} ${book.authors.join(' ')} ${book.subjects.join(' ')}`,
  );
  return needlesFor(chip).some((needle) => {
    const normalized = normalizeHaystack(needle);
    return normalized !== '' && haystack.includes(normalized);
  });
}

/** Client-side rail filter; `null` chip returns the slice untouched. */
export function filterBooksByChip(books: CatalogBook[], chip: string | null): CatalogBook[] {
  if (chip === null) return books;
  return books.filter((book) => matchesChip(book, chip));
}
