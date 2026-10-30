import type { MessageKey } from '$lib/shared/i18n/messages.en';

/** One curated topic shelf: the i18n title key plus its Gutendex `topic` term. */
export interface TopicRailEntry {
  /** Stable key fragment used by `discover.rail.topic.<slug>`. */
  readonly slug: string;
  readonly titleKey: MessageKey;
  /** `topic` query value. Always a member of the closed table, never free text. */
  readonly term: string;
}

/**
 * Curated topic rail rotation.
 *
 * Every term is a Gutendex `topic` value, measured live against
 * `https://gutendex.com/books/?topic=<term>&page=1` before it entered this
 * table, sorted best-first so the most plentiful shelves are the ones a reader
 * sees most often. `count` is the upstream total the probe returned:
 *
 *  - history        23 577
 *  - adventure       9 005
 *  - travel          5 620
 *  - poetry          5 599
 *  - philosophy      3 538
 *  - science fiction 3 530
 *  - drama           3 249
 *  - romance         3 050
 *  - mystery         2 910
 *  - horror            283
 *
 * Every term answered HTTP 200 with a full first page (32 records), so each one
 * can fill the widest rail. `horror` is deliberately LAST despite being alive:
 * 283 books is ample for one rail but the thinnest shelf here — an acceptable
 * member, not a lead.
 *
 * The first two entries are pinned as the required shelves: `science fiction`
 * and `adventure` are always present on their own, and the rotation is an
 * offset into this same list, so neither can disappear from a day's plan.
 *
 * Why a closed table and not a free string: the codebase already documents the
 * failure mode on `CatalogFeaturedSort` — an unrecognized upstream `sort`
 * literal hangs the request. `topic` is the same class of parameter, so the
 * domain passes a value from this table and nothing else. Do NOT add a term
 * without re-running the probe above and updating the counts in this comment.
 */
export const TOPIC_ROTATION: readonly TopicRailEntry[] = [
  {
    slug: 'science-fiction',
    titleKey: 'discover.rail.topic.science-fiction',
    term: 'science fiction',
  },
  { slug: 'adventure', titleKey: 'discover.rail.topic.adventure', term: 'adventure' },
  { slug: 'history', titleKey: 'discover.rail.topic.history', term: 'history' },
  { slug: 'travel', titleKey: 'discover.rail.topic.travel', term: 'travel' },
  { slug: 'poetry', titleKey: 'discover.rail.topic.poetry', term: 'poetry' },
  { slug: 'philosophy', titleKey: 'discover.rail.topic.philosophy', term: 'philosophy' },
  { slug: 'drama', titleKey: 'discover.rail.topic.drama', term: 'drama' },
  { slug: 'romance', titleKey: 'discover.rail.topic.romance', term: 'romance' },
  { slug: 'mystery', titleKey: 'discover.rail.topic.mystery', term: 'mystery' },
  { slug: 'horror', titleKey: 'discover.rail.topic.horror', term: 'horror' },
];

/** 1-based local day-of-year; UTC arithmetic over local parts keeps it DST-proof. */
export function dayOfYear(date: Date): number {
  const startOfYear = Date.UTC(date.getFullYear(), 0, 1);
  const today = Date.UTC(date.getFullYear(), date.getMonth(), date.getDate());
  return Math.floor((today - startOfYear) / 86_400_000) + 1;
}

/**
 * Index into `TOPIC_ROTATION` of the day's first (top) shelf.
 *
 * Formula: `(dayOfYear - 1) mod length`. The minus one makes the very first
 * local calendar day start at index 0, so the rotation is anchored to the top
 * of the curated list rather than already slid forward. The base advances by
 * exactly one slot per local calendar day, so the day's window of shelves
 * slides one topic forward daily, is stable for a whole local day (no re-roll
 * across renders, retries or remounts), and wraps cleanly at the list length.
 * `mod` is normalized so a negative or fractional day index cannot escape the
 * list bounds.
 */
export function topicStartIndex(dayIndex: number, length: number = TOPIC_ROTATION.length): number {
  if (!Number.isInteger(length) || length < 1) {
    throw new Error(`invalid topic rotation length: ${length}`);
  }
  return (((Math.floor(dayIndex) - 1) % length) + length) % length;
}

/**
 * The day's topic shelves: `count` consecutive entries starting at
 * `topicStartIndex(dayOfYear(date))`, wrapping at the list end. Consecutive
 * offsets are always distinct while `count <= length`, so the two topic rails
 * of a day never repeat a topic.
 */
export function topicEntriesFor(
  date: Date = new Date(),
  count: number = 2,
): readonly TopicRailEntry[] {
  const length = TOPIC_ROTATION.length;
  if (!Number.isInteger(count) || count < 1 || count > length) {
    throw new Error(`invalid topic rail count: ${count} for ${length} topics`);
  }
  const start = topicStartIndex(dayOfYear(date), length);
  return Array.from({ length: count }, (_, offset) => TOPIC_ROTATION[(start + offset) % length]!);
}
