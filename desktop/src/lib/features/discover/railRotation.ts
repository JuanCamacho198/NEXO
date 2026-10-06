import type { MessageKey } from '$lib/shared/i18n/messages.en';

/** One curated author shelf: the i18n title key plus its Google Books search term. */
export interface AuthorRailEntry {
  /** Stable key fragment used by `discover.rail.author.<slug>`. */
  readonly slug: string;
  readonly titleKey: MessageKey;
  readonly term: string;
}

/**
 * Curated author rail rotation.
 *
 * Vetting metric: share of the top 20 live Google Books hits whose
 * `volumeInfo.authors` contains the queried surname. Measured by-author share,
 * best first: doyle 100%, poe 95%, wilde 90%, verne 85%, kafka 70%, dickens
 * 65%, london 65%, wells 60%, twain 55%, melville 55%. Public-domain-era
 * authors are deliberate: DISC-04c resolves downloads from Gutendex, and every
 * one of these ten has Project Gutenberg editions.
 *
 * The former plain-word subject rails (`fiction`, `adventure`, `science`, …)
 * are NOT used here: measured on the live API, `fiction` returned only 10%
 * Fiction-categorised hits (first hit *The Art of Fiction*) and `classic`,
 * `science` and `history` returned 0%, i.e. a textbook/criticism shelf. The
 * operator that expresses "fiction in this genre" is `subject:`, and every
 * Google Books colon operator returns `totalItems: 0` with this key at every
 * encoding, so genre shelves are not expressible on this API. Never emit a
 * colon operator.
 *
 * Deliberately EXCLUDED after measuring them bad — do not "complete" the list
 * by adding famous names without re-measuring:
 *  - `jane austen` (35%; first hit *Jane Austen's Erotic Advice*, criticism)
 *  - `leo tolstoy` (30%; first hit is a biography titled *Leo Tolstoy*)
 */
export const AUTHOR_ROTATION: readonly AuthorRailEntry[] = [
  {
    slug: 'conan-doyle',
    titleKey: 'discover.rail.author.conan-doyle',
    term: 'arthur conan doyle',
  },
  { slug: 'poe', titleKey: 'discover.rail.author.poe', term: 'edgar allan poe' },
  { slug: 'wilde', titleKey: 'discover.rail.author.wilde', term: 'oscar wilde' },
  { slug: 'verne', titleKey: 'discover.rail.author.verne', term: 'jules verne' },
  { slug: 'kafka', titleKey: 'discover.rail.author.kafka', term: 'franz kafka' },
  { slug: 'dickens', titleKey: 'discover.rail.author.dickens', term: 'charles dickens' },
  { slug: 'london', titleKey: 'discover.rail.author.london', term: 'jack london' },
  { slug: 'wells', titleKey: 'discover.rail.author.wells', term: 'h g wells' },
  { slug: 'twain', titleKey: 'discover.rail.author.twain', term: 'mark twain' },
  { slug: 'melville', titleKey: 'discover.rail.author.melville', term: 'herman melville' },
];

/** 1-based local day-of-year; UTC arithmetic over local parts keeps it DST-proof. */
export function dayOfYear(date: Date): number {
  const startOfYear = Date.UTC(date.getFullYear(), 0, 1);
  const today = Date.UTC(date.getFullYear(), date.getMonth(), date.getDate());
  return Math.floor((today - startOfYear) / 86_400_000) + 1;
}

/**
 * Index into `AUTHOR_ROTATION` of the day's first (top) shelf.
 *
 * Formula: `(dayOfYear - 1) mod length`. The minus one makes the very first
 * local calendar day start at index 0, so the rotation is anchored to the top
 * of the curated list rather than already slid forward. The base advances by
 * exactly one slot per local calendar day, so the day's window of shelves
 * slides one author forward daily, is stable for a whole local day (no re-roll
 * across renders, retries or remounts), and wraps cleanly at the list length.
 * `mod` is normalized so a negative or fractional day index cannot escape the
 * list bounds.
 */
export function authorStartIndex(
  dayIndex: number,
  length: number = AUTHOR_ROTATION.length,
): number {
  if (!Number.isInteger(length) || length < 1) {
    throw new Error(`invalid author rotation length: ${length}`);
  }
  return (((Math.floor(dayIndex) - 1) % length) + length) % length;
}

/**
 * The day's shelves: `count` consecutive entries starting at
 * `authorStartIndex(dayOfYear(date))`, wrapping at the list end. Consecutive
 * offsets are always distinct while `count <= length`, so the three rails of a
 * day never repeat an author.
 */
export function authorEntriesFor(
  date: Date = new Date(),
  count: number = 3,
): readonly AuthorRailEntry[] {
  const length = AUTHOR_ROTATION.length;
  if (!Number.isInteger(count) || count < 1 || count > length) {
    throw new Error(`invalid author rail count: ${count} for ${length} authors`);
  }
  const start = authorStartIndex(dayOfYear(date), length);
  return Array.from({ length: count }, (_, offset) => AUTHOR_ROTATION[(start + offset) % length]!);
}
