/**
 * Mapping between a reader's spine position and its TOC position, and the
 * locator parsing that needs it.
 *
 * The two counts are not interchangeable. The viewer reports where it is as a
 * **spine** index (the `N` in `epubcfi(/6/N!…)`), while the reader's header
 * counts **TOC entries**. A single spine file can carry several TOC entries — a
 * "Part" heading and its first chapter point at the same document — which is why
 * the Rust extractor resolves an explicit spine index per TOC entry in
 * `build_toc` rather than assuming the two positions line up.
 */

type SpineMappedEntry = {
  /** Spine index this entry points at, as the extractor resolved it. */
  index?: number;
};

/**
 * TOC position for a spine index, or `fallback` when the TOC cannot answer.
 *
 * `entries` is the only mapping the workspace holds. Entries that resolved to no
 * spine carry no `index` and are skipped, so a TOC built mostly from unresolved
 * nav points degrades to "keep the last known chapter" rather than jumping to
 * the top of the book.
 */
export function tocIndexForSpineIndex(
  spineIndex: number,
  entries: SpineMappedEntry[],
  fallback: number,
): number {
  if (!Number.isFinite(spineIndex) || spineIndex < 0) return fallback;
  const found = entries.findIndex((entry) => entry.index === spineIndex);
  return found === -1 ? fallback : found;
}

/** `epubcfi(/6/N!…)`, where N is the 1-based spine index. */
const SPINE_FROM_CFI = /epubcfi\(\/6\/(\d+)!/;

/** A shape no current producer emits; kept for older locators. */
const LEGACY_CHAPTER = /chapter:(\d+)/;

/**
 * Chapter index for an EPUB locator, or `null` when the locator cannot say.
 *
 * Returning `null` rather than the current value keeps "no information" distinct
 * from "the same chapter", so a caller never writes state it did not learn.
 */
export function chapterFromLocator(
  cfi: string,
  entries: SpineMappedEntry[],
  fallback: number,
): number | null {
  const legacy = LEGACY_CHAPTER.exec(cfi);
  if (legacy) return Number.parseInt(legacy[1], 10);

  const spine = SPINE_FROM_CFI.exec(cfi);
  if (!spine) return null;

  return tocIndexForSpineIndex(Number.parseInt(spine[1], 10) - 1, entries, fallback);
}
