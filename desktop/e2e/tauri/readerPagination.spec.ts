/**
 * The page field must describe the unit the reader is actually reading in.
 *
 * ## The two units
 *
 * Scrolling mode gives the reader one long document per chapter, so the page
 * field counts **documents**: the number is a position in the reading order, and
 * "of N" is how many documents the book has. Paginated mode slices that same
 * chapter into **columns**, so the only honest number is which column is on
 * screen and how many columns this chapter has. Both are correct; they are just
 * not the same quantity, and a field that reports one while the reader is using
 * the other is wrong even when the number it prints is a real number.
 *
 * ## Why this spec exists
 *
 * It was written against a measured defect: in paginated mode the field was
 * **frozen**. Measured on *Prisioneros de la geografía*, chapter at reading
 * order 16, viewport 1200x779, real column count 25:
 *
 *     step          page field          real column
 *     start         16                  1
 *     turn +1..+5   16,16,16,16,16      2,3,4,5,6
 *     back -1..-3   16,16,16            5,4,3
 *
 * `scrollLeft` moved 0 -> 6660 -> 3330 while the field never changed: the
 * viewer's `displayCurrentPage` and `displayTotal` read the *navigation*
 * position and the document count, never `pagination.currentPage` /
 * `pagination.pageCount`. The correct count (25) was computed and then read only
 * by the diagnostics panel, which is not mounted.
 *
 * This spec therefore asserts two things a frozen field cannot satisfy: the
 * total equals the chapter's real column count, and a turn advances the field by
 * exactly the one column the screen moved.
 *
 * ## Why the column count is measured at the document root
 *
 * The injected pagination rule targets `html`. `body` always reports
 * `column-width: auto` and `scrollWidth === clientWidth`, so measuring there
 * returns a confident "1 column" for a chapter that has 25. See
 * `harness/readerPagination.ts`.
 *
 * ## Local only
 *
 * Needs the real Tauri app under `bun run tauri:dev:debug` (Windows only).
 *
 *   bun run tauri:dev:debug      # terminal 1
 *   bun run test:e2e:cdp         # terminal 2
 */
import { expect, test } from './harness/connectApp';
import {
  openPaginatedMode,
  readClaimAroundTurn,
  readColumnLayout,
  readPageFieldClaim,
  readZoomPercent,
  seekMultiColumnChapter,
  selectZoomOtherThan,
  settledColumnLayout,
} from './harness/readerPagination';
import { openUsableEpub } from './harness/readerCapacity';

test.describe('page field units', () => {
  test('scroll mode reports the document index, paginated mode reports the column', async ({
    appPage,
  }) => {
    test.setTimeout(420_000);

    // ── The scrolling unit ───────────────────────────────────────────────────
    // Asserted here rather than in `readerSmoke` because the claim under test is
    // the *pair* — two modes, two different correct units. Split across two
    // specs, either half can drift without the other noticing.
    const opening = await openUsableEpub(appPage);
    await expect
      .poll(async () => (await readPageFieldClaim(appPage)).current, { timeout: 20_000 })
      .toBeGreaterThanOrEqual(1);

    const scrollClaim = await readPageFieldClaim(appPage);
    test.skip(
      scrollClaim.total === null || scrollClaim.current === null,
      `The reader reported no page field (got ${JSON.stringify(scrollClaim)}), so there is no ` +
        `number to compare the two modes against.`,
    );

    // In scrolling mode the total is the READING ORDER — the number of documents
    // the book has — because that is the unit a turn steps through. This is the
    // claim paginated mode must not inherit: a chapter's column count is a
    // different quantity and can never be larger than the reading order for the
    // same chapter, yet the two are printed by the same field.
    const scrollTotal = scrollClaim.total!;
    expect(
      scrollTotal,
      `scrolling mode reported a total of ${scrollTotal}, which is not a document count.`,
    ).toBeGreaterThan(0);

    // ── The paginated unit ──────────────────────────────────────────────────
    await openPaginatedMode(appPage);
    // The chapter on screen after entering the mode is whatever the book
    // opened into, and a cover or part title fits in one page box. The chapter
    // is chosen by measurement — more than one column, settled — because that is
    // the only layout in which "which column am I on" has an answer.
    const { frame, layout } = await seekMultiColumnChapter(appPage);

    // The claimed total must BE the column count, not a document count.
    const paginatedClaim = await readPageFieldClaim(appPage);
    expect(
      paginatedClaim.total,
      `paginated mode: the field claims "of ${paginatedClaim.total}" while the chapter lays out ` +
        `${layout.pageCount} columns. A document count above a page count is a different unit ` +
        `wearing the page field's clothes.`,
    ).toBe(layout.pageCount);

    // ── A turn advances the column, and the field says which ────────────────
    // The turn always worked; the *field* was what froze. Asserting on
    // `scrollLeft` alone would therefore pass against the broken code.
    const startLayout = await readColumnLayout(frame);
    test.skip(
      startLayout?.page === null,
      `the columns sat at scrollLeft ${startLayout?.scrollLeft}, which is not a whole page box ` +
        `(columnWidth ${startLayout?.columnWidth}); there is no page index to compare against.`,
    );

    const turn = await readClaimAroundTurn(appPage, 1, { paginated: true });
    const afterLayout = await readColumnLayout(frame);

    expect(
      afterLayout?.page,
      `paginated mode: the turn moved the chapter from column ${startLayout?.page} to ` +
        `${afterLayout?.page} (scrollLeft ${startLayout?.scrollLeft} -> ${afterLayout?.scrollLeft}).`,
    ).toBe((startLayout?.page ?? 0) + 1);

    expect(
      turn.after.current,
      `paginated mode: the chapter advanced to column ${afterLayout?.page} ` +
        `(scrollLeft ${afterLayout?.scrollLeft}) but the page field still reads ` +
        `${turn.after.current} — the value it held before the turn ` +
        `(${turn.before.current} at column ${startLayout?.page}).`,
    ).toBe(afterLayout?.page);

    // The total must still be this chapter's columns after the turn: a re-render
    // must not swap a document count back in.
    expect(turn.after.total).toBe(layout.pageCount);
  });

  test('the count is the chapter column count at two zoom levels', async ({ appPage }) => {
    test.setTimeout(420_000);

    await openUsableEpub(appPage);
    await openPaginatedMode(appPage);
    await seekMultiColumnChapter(appPage);

    const zoomA = await readZoomPercent(appPage);
    const { layout: atA } = await settledColumnLayout(appPage, { timeoutMs: 30_000 });
    expect(
      (await readPageFieldClaim(appPage)).total,
      `paginated mode at ${zoomA}%: the field claims a different total than the ` +
        `${atA.pageCount} columns the chapter lays out.`,
    ).toBe(atA.pageCount);

    // A zoom change re-lays the chapter out. Phase 1 deliberately does NOT
    // recount on it (see `useEpubPagination`'s header: "Zoom, font-size and
    // orientation changes are deliberately NOT watched — that is phase 3"), so
    // the field legitimately still holds the previous count until the chapter is
    // re-measured. Leaving and re-entering the mode is the phase-1 trigger that
    // does re-measure, and it is the reader's own gesture.
    const zoomB = await selectZoomOtherThan(appPage, zoomA);
    await settledColumnLayout(appPage, { timeoutMs: 30_000 });
    const { layout: atB } = await settledColumnLayout(appPage, { timeoutMs: 30_000 });
    await openPaginatedMode(appPage);
    await settledColumnLayout(appPage, { timeoutMs: 30_000 });
    const { layout: remeasured } = await settledColumnLayout(appPage, { timeoutMs: 30_000 });
    const claimB = await readPageFieldClaim(appPage);

    // These are the numbers the phase needs a record of. The exact values depend
    // on the book, the viewport and the installed fonts, so they are reported
    // rather than pinned.
    console.log(
      `[pagination] measured columns vs claimed total — zoom A ${zoomA}%: ` +
        `${atA.pageCount} columns (scrollWidth ${atA.scrollWidth}, box ${atA.columnWidth}px); ` +
        `zoom B ${zoomB}%: ${atB.pageCount} columns ` +
        `(scrollWidth ${atB.scrollWidth}, box ${atB.columnWidth}px), re-measured ` +
        `${remeasured.pageCount}; field claims ${claimB.current}/${claimB.total}`,
    );

    // The zoom demonstrably changed the layout. The page box itself does NOT
    // change — it is the viewport, measured in the parent, and stays 1110px at
    // both levels (verified); what the zoom changes is the prose, so the same
    // chapter fits in fewer columns. Measured on this machine at 100% / 50%:
    // 20 -> 7.
    expect(
      atB.pageCount,
      `measuring at two zoom levels produced the same column count (${atB.pageCount} at ` +
        `${zoomB}%, against ${atA.pageCount} at ${zoomA}%). Either the zoom did not reach the ` +
        `chapter or the count is not derived from the live layout.`,
    ).not.toBe(atA.pageCount);

    // And once re-measured, the field claims the new count — not the old one,
    // which is what it would print if the total were not reading the layout.
    expect(
      claimB.total,
      `paginated mode at ${zoomB}%: after re-measuring, the field claims "of ${claimB.total}" ` +
        `while the chapter lays out ${remeasured.pageCount} columns ` +
        `(${atB.pageCount} before the re-measure).`,
    ).toBe(remeasured.pageCount);
  });
});
