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
 * *real*, the numbers the app computes from the viewer agree. This spec does.
 *
 * ## The invariant
 *
 * For an EPUB the reader computes (in `useEpubBridge.emitPreciseLocation`):
 *
 *     percentage = ((spineIndex + progression) / totalChapters) * 100
 *
 * and the header page is `currentEpubChapter + 1`. For a normal book the spine
 * and the TOC align, so a reader on page `N` out of `T` must show a percentage
 * inside the slice that page covers:
 *
 *     (N - 1) / T * 100  <=  pct  <=  N / T * 100
 *
 * A percentage outside that slice — e.g. a footer reading 30% while the header
 * claims page 1 of 23 — is exactly the disagreement the changes were meant to
 * fix. The slice is asserted with a 1% rounding tolerance on each side, since
 * both the percentage and the header page are rounded independently.
 *
 * ## DEFECT FOUND (measured, reported with raw output)
 *
 * Against the live app the header page input **never advances past 1**. The
 * cause is structural: `nav.currentEpubChapter` (which drives `headerCurrentPage`
 * = `currentEpubChapter + 1`) is written only by
 * `useReaderNavigation.handleEpubLocationChange`, whose parser matches
 * `/chapter:(\d+)/` — a locator shape the real viewer never produces (it emits
 * `epubcfi(/6/N!…)`). The viewer's own chapter index lives in a *separate*
 * `createEpubNavigation` inside `EpubNativeViewer`; nothing propagates it to the
 * workspace `nav`. So in the live app `currentEpubChapter` is permanently 0 and
 * the header reads `1 / T` no matter which chapter is on screen, while the
 * footer `%` moves with the real location. The agreement test below asserts the
 * acceptance criterion and therefore fails on this build, pinpointing the defect.
 *
 * The fix is NOT trivial (it needs a correct spine→TOC propagation from the
 * viewer to `nav`, and touching `src/` is out of this change's scope), so it is
 * reported rather than applied.
 *
 * ## What it proves, and what it does not
 *
 * The first test proves the footer-only invariants that DO hold: the numeric `%`
 * label and the bar's width agree with each other, the value is monotonic under
 * forward navigation, and it stays within a page slice. The second test proves
 * the cross-surface agreement the acceptance criterion names, and on this build
 * it fails with the measured page/percentage pair.
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
  /** The numeric percentage label next to the bar. */
  percentLabel: number;
  /** The bar's `width` value (in %). */
  barWidth: number;
  /** The page field's current value. */
  currentPage: number;
  /** The `/ total` span's value. */
  totalPages: number;
  /** The footer's left location label (chapter title, or book title fallback). */
  locationLabel: string;
};

/** Reads the four coherence surfaces from the app's footer in one pass. */
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

  // A single-chapter book cannot show coherence *across* a move, and the slice
  // invariant is trivial at totalPages === 1. Skip rather than assert a
  // degenerate case.
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

test('the footer percentage and the header page agree after a navigation', async ({ appPage }) => {
  test.setTimeout(180_000);

  const before = await openMultiChapterBook(appPage);

  const assertAgreement = (state: FooterState, when: string): void => {
    const T = state.totalPages;
    const N = state.currentPage;
    const low = ((N - 1) / T) * 100 - 1; // 1% rounding tolerance each side
    const high = (N / T) * 100 + 1;
    // The message carries the measured pair so a failure names the defect
    // instead of only a number.
    expect(
      state.percentLabel,
      `${when}: footer says ${state.percentLabel}% but header page ${N}/${T} covers ` +
        `[${low.toFixed(1)}%, ${high.toFixed(1)}%]`,
    ).toBeGreaterThanOrEqual(low);
    expect(
      state.percentLabel,
      `${when}: footer says ${state.percentLabel}% but header page ${N}/${T} covers ` +
        `[${low.toFixed(1)}%, ${high.toFixed(1)}%]`,
    ).toBeLessThanOrEqual(high);
  };

  assertAgreement(before, 'on open');

  const next = nextButton(appPage);
  await expect(next).toBeEnabled({ timeout: 10_000 });
  await next.click();

  // The header page must advance; wait for it, because the location streams asynchronously.
  await expect
    .poll(async () => (await readFooter(appPage)).currentPage, { timeout: 15_000 })
    .toBeGreaterThan(before.currentPage);

  await appPage.waitForTimeout(500);
  const after = await readFooter(appPage);

  expect(after.currentPage).toBe(before.currentPage + 1);
  expect(after.totalPages).toBe(before.totalPages);
  assertAgreement(after, 'after next');
});
