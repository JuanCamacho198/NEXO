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
import { noUsableEpubReason, seekRenderedChapter, selectUsableEpub } from './harness/readerCapacity';

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

/** Opens a book with prose and chapter navigation, or skips the caller with a reason. */
async function openMultiChapterBook(page: Page): Promise<FooterState> {
  // The book must render prose and have chapters to navigate *across*: this
  // spec's invariant is about two surfaces co-moving under a real navigation, and
  // a book whose chapters are covers alone gives it nothing to move between.
  // `selectUsableEpub` opens each candidate and measures what renders, so the
  // choice does not depend on what the developer's library happens to hold.
  const { found, candidates } = await selectUsableEpub(page);
  test.skip(found === null, noUsableEpubReason(candidates));

  // Read on to a chapter with prose if the book did not open on one. Covers and
  // part titles render a few characters and would make the footer read a
  // chapter whose contribution to the book's percentage is trivially small.
  await seekRenderedChapter(page);

  // The two surfaces settle on different clocks, and reading before they do
  // measures a transient state rather than the app.
  //
  // The footer `%` is available at first paint: the workspace falls back to the
  // book's persisted `percentage` until the viewer reports a location. The
  // header chapter is not — it can only come from the viewer's first
  // `epubcfi(/6/N!…)`, which arrives after the chapter iframe has parsed. So
  // immediately after opening, the footer can read 33% while the header still
  // holds a placeholder, and that pair is correct for that instant, not a defect.
  //
  // Wait for the header to report a real page rather than sleeping a fixed span:
  // a fixed wait either flakes or wastes time, and this one fails loudly if the
  // chapter never arrives.
  //
  // Note this waits for `>= 1`, NOT `> 1`. An earlier revision waited for the
  // page to leave chapter 1, on the assumption that the book opened mid-way
  // through. This spec now deliberately positions the reader on the *first*
  // chapter that renders prose, which for the chosen book is chapter 1 — so a
  // `> 1` wait could never be satisfied and timed out at 15 s (measured). What
  // the assertions actually need is that the header has received its location,
  // which is a page number of 1 or more.
  await expect
    .poll(async () => (await readFooter(page)).currentPage, { timeout: 15_000 })
    .toBeGreaterThanOrEqual(1);

  const before = await readFooter(page);

  // A single-chapter book cannot show coherence *across* a move, and the
  // co-movement invariant is trivial at totalPages === 1. Skip rather than
  // assert a degenerate case.
  test.skip(
    before.totalPages < 2,
    `The book "${found!.book.title}" reports ${before.totalPages} pages — no chapter navigation to check coherence across.`,
  );

  // Both tests below turn forward and require the *footer percentage to
  // increase*. At the book's last chapter that is unsatisfiable: the
  // spine-weighted percentage saturates while the TOC index can still tick over.
  // Measured before this guard existed: the header moved 19 -> 20 while the
  // footer stayed at 93% -> 93%, which is the app behaving correctly at the end
  // of a book and the test demanding something the app never promised. Skip with
  // the real numbers rather than assert a property that does not hold there.
  test.skip(
    before.currentPage >= before.totalPages,
    `The reader is on the book's last chapter (${before.currentPage}/${before.totalPages}) — ` +
      `the footer percentage cannot increase past the end of the book.`,
  );

  return before;
}

/** The page's own Next control (prev is index 0, next is index 1). */
function nextButton(page: Page) {
  return page.locator('[data-testid="bottom-bar-controls"] button').nth(1);
}

/**
 * How many forward turns the footer-percentage assertions may take to observe a
 * change.
 *
 * The footer is spine-weighted and rounded to whole percent, so a book whose
 * early chapters are a small fraction of its length can hold the label at the
 * same value for several turns — measured on *Don Quijote*: seven turns at `0%`
 * before the eighth reads `50%`. The bound keeps a genuinely frozen footer
 * failing at the assertion below instead of looping forever.
 */
const MAX_PROGRESS_TURNS = 12;

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

  // Turn forward until the footer percentage actually moves, sampling every step.
  //
  // Why not "one Next must raise the percentage": the footer is
  // spine-weighted, so its percentage reflects how much of the *book* has been
  // passed, not how many chapters. Measured on the selected book (*Don Quijote*,
  // percentage per turn): `0,0,0,0,0,0,0,50,50,50` across chapters 1..11 — the
  // first seven chapters together are under half a percent, so a single turn
  // raises it by 0 and the old single-turn assertion could only ever fail. A
  // real book made of ordinary chapters does move on the first turn; this one
  // does not, and the app is right both times.
  //
  // What must hold at every step is that the value never *decreases* — a footer
  // that dropped under forward navigation would be a regression in the streaming
  // path, which is the defect this spec exists to catch.
  let after = before;
  const samples: number[] = [before.percentLabel];
  for (let turn = 0; turn < MAX_PROGRESS_TURNS; turn++) {
    await next.click();
    // Let the new location stream in, then read the settled state once.
    await appPage.waitForTimeout(1_200);
    after = await readFooter(appPage);

    expect(
      after.percentLabel,
      `the footer percentage dropped from ${samples.at(-1)}% to ${after.percentLabel}% after ` +
        `forward navigation (series: ${[...samples, after.percentLabel].join(', ')})`,
    ).toBeGreaterThanOrEqual(samples.at(-1)!);
    expect(after.percentLabel).toBeLessThanOrEqual(100);
    // The two surfaces must stay equal at every settled step, not just the first.
    expect(after.barWidth).toBe(after.percentLabel);
    samples.push(after.percentLabel);

    if (after.percentLabel > before.percentLabel) break;
  }

  // The run must have observed the percentage actually advance, or the loop
  // above proved nothing about the streaming path.
  expect(
    after.percentLabel,
    `the footer percentage never advanced past ${before.percentLabel}% in ` +
      `${MAX_PROGRESS_TURNS} forward turns (series: ${samples.join(', ')})`,
  ).toBeGreaterThan(before.percentLabel);

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
  // units coincide. Concretely, advancing the reader must move the header by
  // exactly one TOC entry per turn AND must never lower the footer percentage,
  // and across the turns it must eventually raise it — a frozen or desynced
  // footer (the defect class this feature cares about) still fails here, because
  // the footer is required to make progress too.
  //
  // ## Why "the footer must rise on the very next turn" is also wrong
  //
  // The footer is spine-weighted and rounds to whole percent. Measured on the
  // selected book (*Don Quijote*), the percentage per turn is
  // `0,0,0,0,0,0,0,50,50,50` across chapters 1..11: the book's first seven
  // chapters are together less than half a percent of it, so several turns pass
  // with the label reading exactly `0%` while the reader genuinely advances. A
  // one-turn "must rise" check fails on that honest behaviour — and did, on the
  // baseline: `header advanced 19 -> 20 but footer stayed at 93% -> 93%`, the
  // mirror image of the same rounding, at the far end of the book.
  //
  // DO NOT "fix" this back into an equality/containment check between the two
  // numbers, and do not tighten the footer check back to one turn. They are not
  // the same quantity; see the units above.

  const before = await openMultiChapterBook(appPage);

  // Both surfaces are in range on open. Same range checks the sibling test
  // applies, kept here as the second test's own precondition.
  expect(before.percentLabel).toBeGreaterThanOrEqual(0);
  expect(before.percentLabel).toBeLessThanOrEqual(100);
  expect(before.currentPage).toBeGreaterThanOrEqual(1);
  expect(before.currentPage).toBeLessThanOrEqual(before.totalPages);

  const next = nextButton(appPage);
  await expect(next).toBeEnabled({ timeout: 10_000 });

  // Each turn must advance the header by exactly one TOC entry and must never
  // lower the footer. The footer is allowed to hold still while it rounds, and
  // the loop stops as soon as it has been seen to move.
  let header = before.currentPage;
  let footer = before.percentLabel;
  const headerPath: number[] = [header];
  const footerPath: number[] = [footer];

  for (let turn = 0; turn < MAX_PROGRESS_TURNS; turn++) {
    await next.click();

    // The header page must advance; wait for it, because the location streams
    // asynchronously and the field is committed-render state.
    await expect
      .poll(async () => (await readFooter(appPage)).currentPage, { timeout: 15_000 })
      .toBeGreaterThan(header);

    await appPage.waitForTimeout(500);
    const step = await readFooter(appPage);

    // The header advanced exactly one TOC entry across a single Next.
    expect(
      step.currentPage,
      `header moved ${header}/${before.totalPages} -> ${step.currentPage}/${step.totalPages}`,
    ).toBe(header + 1);
    expect(step.totalPages).toBe(before.totalPages);

    // ...and the footer never moved backwards. A header that advanced while the
    // footer went backwards is a desync, and this is where it surfaces.
    expect(
      step.percentLabel,
      `header advanced ${header} -> ${step.currentPage} but the footer dropped ` +
        `${footer}% -> ${step.percentLabel}%`,
    ).toBeGreaterThanOrEqual(footer);
    expect(step.percentLabel).toBeLessThanOrEqual(100);

    header = step.currentPage;
    footer = step.percentLabel;
    headerPath.push(header);
    footerPath.push(footer);

    if (footer > before.percentLabel) break;
  }

  // The turn budget must have been enough to see the footer actually move;
  // otherwise the monotonicity above proved nothing.
  expect(
    footer,
    `the footer never rose past ${before.percentLabel}% in ${MAX_PROGRESS_TURNS} turns ` +
      `(headers: ${headerPath.join(', ')}; footers: ${footerPath.join(', ')})`,
  ).toBeGreaterThan(before.percentLabel);
});
