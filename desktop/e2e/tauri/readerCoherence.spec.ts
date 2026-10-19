/**
 * Progress and navigation coherence, against a book actually being read.
 *
 * ## Why this exists, and why nothing else can prove it
 *
 * The footer percentage and the header chapter/page were both rewritten in the
 * two changes this feature follows (`reader-epub-progress-accuracy`,
 * `reader-chrome-layout`). The defect that motivated the first was precisely
 * that the two **disagreed**: the footer streamed a percentage from the viewer's
 * location while the header read a chapter index derived elsewhere, and nothing
 * forced them to tell the same story about where the reader was.
 *
 * The jsdom tests assert each surface against synthetic props — they set
 * `bookProgress` and `currentPage` by hand and check they render, and one even
 * feeds `handleEpubLocationChange('chapter:3', …)`, a locator shape **the real
 * viewer never emits**. They never check that, for a *real* book navigated for
 * *real*, the numbers the app computes from the viewer stay coherent — that a
 * step forward moves both surfaces, in the same direction, without either
 * freezing. This spec does.
 *
 * ## The invariant
 *
 * The reader computes progress on two surfaces from **different units**, by
 * design:
 *
 *     footer %      = ((spineIndex + progression) / totalSpineChapters) * 100
 *     header page   = TOC entry index (`currentChapterIndex + 1`) of TOC length
 *
 * (`useEpubBridge.emitPreciseLocation` for the footer, `EpubNativeViewer`
 * `displayCurrentPage` / `displayTotal` for the header.)
 *
 * These are NOT interchangeable. A book whose TOC nests entries the spine does
 * not (Parts, Prologue) has `tocIndex != spineIndex`, so a header reading
 * `17 / 33` and a footer reading `54%` are both correct. An earlier revision of
 * this spec asserted `(N - 1) / T * 100 <= pct <= N / T * 100`, which pretends
 * the two are the same quantity AND that chapters have equal length. That is a
 * false property: chapters are not `1/T` each, and the units differ on purpose.
 * It failed on every run. See the second test's docstring for the full story.
 *
 * What actually holds, and what this spec proves:
 *
 *   - the footer `%` and its bar width read the same value, stay in `[0, 100]`,
 *     and never decrease under forward navigation;
 *   - one Next advances the header by exactly one TOC entry, and the footer
 *     percentage advances with it — the two surfaces are coherent because they
 *     move together, not because their values coincide.
 *
 * ## What it proves, and what it does not
 *
 * The first test proves the footer-only invariants: the numeric `%` label and
 * the bar's width agree, the value is monotonic under forward navigation, and
 * it stays in range. The second proves that one forward page advances the
 * header by one and the footer percentage with it — the cross-surface coherence
 * the acceptance criterion is really about.
 *
 * Neither covers PDF progress (a different code path; the EPUB one is the one
 * both changes touched).
 *
 * ## Local only
 *
 * Needs the real Tauri app under `bun run tauri:dev:debug` (Windows only). Not a
 * CI gate.
 *
 *   bun run tauri:dev:debug      # terminal 1
 *   bun run test:e2e:cdp         # terminal 2
 */
import { expect, test } from './harness/connectApp';
import type { Page } from '@playwright/test';
import { openBook, pickEpub } from './harness/readerFlows';

type FooterState = {
  /** The numeric percentage label next to the footer bar (spine-weighted). */
  percentLabel: number;
  /** The bar's `width` value (in %). */
  barWidth: number;
  /** The page field's current value (a TOC entry index, from the header). */
  currentPage: number;
  /** The `/ total` span's value (the TOC length). */
  totalPages: number;
  /** The footer's left location label (chapter title, or book title fallback). */
  locationLabel: string;
};

/**
 * Reads the coherence surfaces in one pass: the footer's `%` bar and label
 * (spine-weighted), plus the header's page field and total (TOC entries).
 *
 * The two groups measure different units — see the module docstring — which is
 * exactly why this reads both rather than deriving one from the other.
 */
async function readFooter(page: Page): Promise<FooterState> {
  return page.evaluate(() => {
    const controls = document.querySelector('[data-testid="bottom-bar-controls"]');
    const pageInput = controls?.querySelector('input') as HTMLInputElement | null;
    const totalSpan = controls?.querySelector('span[aria-hidden="true"]') as HTMLElement | null;

    const bar = document.querySelector('[data-testid="footer-progress-bar"]') as HTMLElement | null;
    // The `%` label is the bar's next sibling span within the same cluster.
    const percentSpan = bar?.parentElement?.parentElement?.querySelector(
      'span.tabular-nums',
    ) as HTMLElement | null;

    const barWidth = bar ? Number.parseFloat(bar.style.width) || 0 : 0;
    const percentLabel = percentSpan ? Number.parseFloat(percentSpan.textContent ?? '') || 0 : 0;

    const totalText = totalSpan?.textContent ?? '';
    const currentPage = pageInput ? Number.parseInt(pageInput.value, 10) || 0 : 0;
    const totalPages = Number.parseInt(totalText.replace(/[^0-9]/g, ''), 10) || 0;

    // The location label is the footer's first span (justify-self-start).
    const footer = document.querySelector('footer');
    const locationLabel = footer?.querySelector('span')?.textContent?.trim() ?? '';

    return { percentLabel, barWidth, currentPage, totalPages, locationLabel };
  });
}

/** Opens an EPUB and skips the caller when the book is missing or single-chapter. */
async function openMultiChapterBook(page: Page): Promise<FooterState> {
  const epub = await pickEpub(page);
  test.skip(epub === null, 'No EPUB book in the library — cannot measure progress.');

  await openBook(page, epub!);

  // The two surfaces settle on different clocks, and reading before they do
  // measures a transient state rather than the app.
  //
  // The footer `%` is available at first paint: the workspace falls back to the
  // book's persisted `percentage` until the viewer reports a location. The
  // header chapter is not — it can only come from the viewer's first
  // `epubcfi(/6/N!…)`, which arrives after the chapter iframe has parsed. So
  // immediately after `openBook` the footer can read 33% while the header still
  // reads 1/23, and that pair is correct for that instant, not a defect.
  //
  // Wait for the header to leave page 1 rather than sleeping a fixed span: a
  // fixed wait either flakes or wastes time, and this one fails loudly if the
  // chapter never arrives.
  await expect
    .poll(async () => (await readFooter(page)).currentPage, { timeout: 15_000 })
    .toBeGreaterThan(1);

  const before = await readFooter(page);

  // A single-chapter book cannot show coherence *across* a move, and the
  // co-movement invariant is trivial at totalPages === 1. Skip rather than
  // assert a degenerate case.
  test.skip(
    before.totalPages < 2,
    `The book reports ${before.totalPages} pages — no chapter navigation to check coherence across.`,
  );

  return before;
}

/** The page's own Next control (prev is index 0, next is index 1). */
function nextButton(page: Page) {
  return page.locator('[data-testid="bottom-bar-controls"] button').nth(1);
}

test('the footer percentage is self-consistent and monotonic across a navigation', async ({
  appPage,
}) => {
  test.setTimeout(180_000);

  const before = await openMultiChapterBook(appPage);

  // The two footer surfaces are the same `bookProgress` and must read the same.
  expect(before.barWidth).toBe(before.percentLabel);
  expect(before.percentLabel).toBeGreaterThanOrEqual(0);
  expect(before.percentLabel).toBeLessThanOrEqual(100);
  expect(before.currentPage).toBeGreaterThanOrEqual(1);
  expect(before.currentPage).toBeLessThanOrEqual(before.totalPages);

  const next = nextButton(appPage);
  await expect(next).toBeEnabled({ timeout: 10_000 });
  await next.click();

  // Let the new location stream in, then read the settled state once.
  await appPage.waitForTimeout(800);
  const after = await readFooter(appPage);

  expect(after.barWidth).toBe(after.percentLabel);
  // Progress is monotonic under forward navigation: a footer that dropped after
  // Next would be a regression in the streaming path.
  expect(after.percentLabel).toBeGreaterThanOrEqual(before.percentLabel);
  expect(after.percentLabel).toBeLessThanOrEqual(100);

  // The location label is never blank (chapter title, or book-title fallback).
  expect(after.locationLabel.length).toBeGreaterThan(0);
});

test('the footer percentage and the header page move together after a navigation', async ({
  appPage,
}) => {
  test.setTimeout(180_000);

  // ## Why this test does NOT assert that the two numbers agree
  //
  // It used to. The old assertion required the footer percentage to fall inside
  // `[(N - 1) / T, N / T]` for the header's `N` of `T`, i.e. it assumed chapter
  // `N` occupies an equal `1/T` slice of the book. That assumption is FALSE:
  // EPUB chapters do not have equal length — a two-page prologue and a
  // forty-page chapter share the same `1/T`.
  //
  // Worse, the two surfaces count *different units on purpose*:
  //
  //   - The header page field (`PageInput` inside `bottom-bar-controls`) is
  //     driven by `EpubNativeViewer.displayCurrentPage` / `displayTotal`
  //     (EpubNativeViewer.svelte), which count TABLE OF CONTENTS entries:
  //     `spine.getToc().length` and `navigation.currentChapterIndex + 1`.
  //   - The footer bar and `%` label read `bookProgress`, which is the
  //     spine-weighted location streamed from `emitPreciseLocation`:
  //     `((spineIndex + progression) / totalSpineChapters) * 100`.
  //
  // In a book like *Don Quijote* the TOC has nested entries (Parts, Prologue)
  // the spine does not, so `tocIndex 12 !== spineIndex 12` and the two numbers
  // legitimately diverge — measured live: the header read `17 / 33` while the
  // footer read `54%`, a pair outside the old `[47.5%, 52.5%]` window even
  // though BOTH numbers correctly describe where the reader is. The old
  // assertion was measuring a false property and could only ever fail.
  //
  // So this test asserts the real invariant instead: navigation works and the
  // two surfaces stay coherent *with each other* without demanding that their
  // units coincide. Concretely, advancing the reader one chapter must move the
  // header by exactly one TOC entry AND advance the footer percentage — a
  // frozen or desynced footer (the defect class this feature cares about) still
  // fails here, because the footer is required to make progress too.
  //
  // DO NOT "fix" this back into an equality/containment check between the two
  // numbers. They are not the same quantity; see the units above.

  const before = await openMultiChapterBook(appPage);

  // Both surfaces are in range on open. Same range checks the sibling test
  // applies, kept here as the second test's own precondition.
  expect(before.percentLabel).toBeGreaterThanOrEqual(0);
  expect(before.percentLabel).toBeLessThanOrEqual(100);
  expect(before.currentPage).toBeGreaterThanOrEqual(1);
  expect(before.currentPage).toBeLessThanOrEqual(before.totalPages);

  const next = nextButton(appPage);
  await expect(next).toBeEnabled({ timeout: 10_000 });
  await next.click();

  // The header page must advance; wait for it, because the location streams asynchronously.
  await expect
    .poll(async () => (await readFooter(appPage)).currentPage, { timeout: 15_000 })
    .toBeGreaterThan(before.currentPage);

  await appPage.waitForTimeout(500);
  const after = await readFooter(appPage);

  // The header advanced exactly one TOC entry across a single Next.
  expect(
    after.currentPage,
    `header moved ${before.currentPage}/${before.totalPages} -> ${after.currentPage}/${after.totalPages}`,
  ).toBe(before.currentPage + 1);
  expect(after.totalPages).toBe(before.totalPages);

  // ...and the footer moved with it. A header that advanced while the footer
  // stood still (or went backwards) is a desync, and this is where it surfaces.
  expect(
    after.percentLabel,
    `header advanced ${before.currentPage} -> ${after.currentPage} but footer stayed at ` +
      `${before.percentLabel}% -> ${after.percentLabel}%`,
  ).toBeGreaterThan(before.percentLabel);
  expect(after.percentLabel).toBeLessThanOrEqual(100);
});
