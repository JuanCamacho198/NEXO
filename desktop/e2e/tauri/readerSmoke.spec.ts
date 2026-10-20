/**
 * The real-use smoke test: one reader, driven the way a person reads.
 *
 * ## Why this exists, and why nothing else in `e2e/` replaces it
 *
 * The project has 3031 unit tests and six defects nobody knew about, all of them
 * found by a person using the app (`odd/tasks/reader-confidence.md`). The unit
 * tests prove the code does what the code says; the other CDP specs each prove
 * one mechanism in isolation, on whatever state a previous run happened to leave
 * behind. Nothing exercised "open the app and read".
 *
 * The proof that this gap was real is in the harness itself: `openBook` used to
 * select the first button with visible text, which on a shelf card is the *title*
 * — whose handler opens the detail modal, not the reader. From a cold start the
 * whole CDP suite never entered the reader; it only passed when a previous run
 * had left the app inside one. **A smoke test catches that on its first run,
 * because "open a book" not opening a book is the very first step it asserts.**
 *
 * ## What it drives
 *
 * One continuous read-cycle, in the reader's own order:
 *
 *   1. open the app from a reset state and reach the library
 *   2. open a book and see its chapter render
 *   3. read: turn forward, then back
 *   4. change the zoom
 *   5. add a highlight
 *   6. close the book
 *   7. reopen it and check the position was kept
 *   8. switch to a second book
 *   9. return to the first
 *
 * Every step asserts something observable in the DOM. A step that does not
 * respond fails this spec — it does not skip. The only skip is the library
 * holding fewer than two local EPUBs, which is a missing precondition rather
 * than a broken step.
 *
 * ## Where the driving lives
 *
 * `harness/readerPaging.ts` owns how each step is performed and what "the step
 * happened" means — the page field, the turn controls, the chapter frame, the
 * selection. Several of those are non-obvious (the page field is committed
 * -render state and a turn can bounce), so the reasoning is documented there,
 * next to the code it constrains, rather than here.
 *
 * ## Why it resets its own state, and how
 *
 * The reader persists every movement: position, zoom, text settings and the
 * debug flag all survive a restart. A smoke test that inherited any of them
 * would be measuring the developer's last session. So the spec resets first, in
 * two moves — see `harness/readerReset.ts` for the full rationale:
 *
 *   1. seeds the persisted state it depends on through the app's own Tauri
 *      commands (`saveProgress`, `upsertSettings`), the way a user would;
 *   2. `page.reload()`s so the stores re-read what was just written. The write
 *      alone is invisible to the running app, because the stores are populated
 *      from SQLite at boot and are not subscribed to it.
 *
 * Nothing this spec asserts about comes from ambient state: the book, the
 * starting position, the zoom, the render mode and the debug flag are all values
 * the reset names, and the two books are read from the library over IPC rather
 * than assumed by title.
 *
 * ## What it does NOT prove
 *
 * Pixel fidelity, PDF rendering, and the correctness of the numbers themselves.
 * It answers one question — "can a person open this app and read?" — and it is
 * deliberately the only test that asks it end to end.
 *
 * ## Local only
 *
 * Needs the real Tauri app under `bun run tauri:dev:debug` (Windows only):
 *
 *   bun run tauri:dev:debug      # terminal 1
 *   bun run test:e2e:cdp         # terminal 2
 */
import { expect, test } from './harness/connectApp';
import { deleteHighlight, goToLibrary } from './harness/readerFlows';
import {
  addHighlight,
  closeReader,
  listHighlights,
  openAndAssertChapter,
  pickTwoEpubs,
  readZoomPercent,
  seekSelectableChapter,
  settleReader,
  turnBackward,
  turnForward,
  type SmokeBook,
} from './harness/readerPaging';
import type { Page } from '@playwright/test';
import {
  SMOKE_PROGRESS,
  readProgress,
  readSettings,
  reloadApp,
  restoreProgress,
  restoreSettings,
  seedEnvironment,
  seedProgress,
  seedSetting,
} from './harness/readerReset';

test('a reader can open a book, read it, highlight it, close it and find their place kept', async ({
  appPage,
}) => {
  // Opening a cold EPUB runs a real Rust extraction and a chapter render, and
  // the cycle visits two books three times.
  test.setTimeout(600_000);

  const books = await pickTwoEpubs(appPage);
  test.skip(books === null, 'The library needs two local EPUBs to run the read-cycle.');

  // ── Reset ────────────────────────────────────────────────────────────────
  // Everything this spec asserts about is seeded here, so nothing it measures
  // can have been inherited from the developer's last session. Both the
  // settings map and the two books' positions are captured first so the
  // teardown can put them back — this spec drives a real library, and leaving
  // it walked forward would be this test corrupting the state it was written to
  // protect against.
  const previousSettings = await readSettings(appPage);
  const previousPositions = await Promise.all(
    [books!.first.id, books!.second.id].map(async (id) => ({
      bookId: id,
      progress: await readProgress(appPage, id),
    })),
  );
  // Set when this spec creates its own highlight, so the teardown can delete
  // that one and only that one. Held in an object rather than a local so the
  // cycle can record it even when a later step throws before it returns —
  // otherwise a failed run would leak the highlight it created.
  const created: { highlightId: string | null } = { highlightId: null };

  await seedProgress(appPage, books!.first.id, SMOKE_PROGRESS);
  await seedSetting(appPage, 'reader.epub.fontSize', 100);
  await seedSetting(appPage, 'reader.paginated', false);
  // The diagnostic flag is read at boot and, when on, changes the reader's
  // chrome. The smoke test must not depend on the machine being left out of
  // that mode, so it turns it off here like any other setting.
  await seedEnvironment(appPage);

  // The writes above are invisible to the running app: the stores were populated
  // from SQLite at boot and are not subscribed to it. The reload is what makes
  // the seed observable.
  await reloadApp(appPage);
  await goToLibrary(appPage);

  try {
    await driveReadCycle(appPage, books!.first, books!.second, created);
  } finally {
    // ── Restore ────────────────────────────────────────────────────────────
    // Put the settings and both books' reading positions back the way they were
    // found. This spec drives a real library; leaving it walked forward would be
    // the test silently corrupting the developer's place in their own books —
    // the very kind of ambient-state damage it exists to eliminate. Runs even
    // when an assertion above failed, so a red run does not also damage state.
    await restoreSettings(appPage, previousSettings);
    for (const { bookId, progress } of previousPositions) {
      await restoreProgress(appPage, bookId, progress);
    }
    // Delete exactly the highlight this run created, and nothing else. The id
    // was captured by diffing before/after, so a pre-existing highlight — the
    // developer's own, or a leaked one — is never touched.
    if (created.highlightId) {
      await deleteHighlight(appPage, created.highlightId);
    }
  }
});

/**
 * The read-cycle the smoke test drives, extracted so the spec body can wrap it
 * in a `try/finally` and restore the developer's state even on a failed
 * assertion. Returns nothing; every step asserts what it observed.
 *
 * `onHighlightCreated` receives the id of the highlight this cycle created (or
 * null if it never got that far) so the caller can delete exactly that one.
 */
async function driveReadCycle(
  appPage: Page,
  first: SmokeBook,
  second: SmokeBook,
  created: { highlightId: string | null },
): Promise<void> {
  // ── 1. Open a book ───────────────────────────────────────────────────────
  await openAndAssertChapter(appPage, first);
  // Let the entry restore and the column layout settle before reading numbers.
  await appPage.waitForTimeout(2_500);

  // ── 2. Read: forward, then back ──────────────────────────────────────────
  const startPage = await settleReader(appPage);
  expect(startPage, 'the reader did not report a page after opening the book').not.toBeNull();
  expect(startPage).toBeGreaterThanOrEqual(1);

  await turnForward(appPage, 2);
  const afterForward = await settleReader(appPage);
  expect(
    afterForward,
    `two forward turns from page ${startPage} did not advance the reader`,
  ).not.toBe(startPage);

  await turnBackward(appPage);
  const afterBack = await settleReader(appPage);
  expect(
    afterBack,
    `a backward turn from page ${afterForward} did not move the reader back`,
  ).not.toBe(afterForward);

  // ── 3. Change the zoom ───────────────────────────────────────────────────
  const zoomBefore = await readZoomPercent(appPage);
  expect(zoomBefore, 'the reader exposed no zoom control').not.toBeNull();

  await appPage.locator('[data-testid="zoom-dropdown-trigger"]').click();
  const dropdown = appPage.locator('[data-testid="zoom-dropdown-list"]');
  await expect(dropdown).toBeVisible({ timeout: 10_000 });
  // Prefer 150%; fall back to the last option so the assertion does not depend
  // on the option list never changing.
  const preferred = dropdown.locator('[data-testid="zoom-option-150"]');
  const target =
    (await preferred.count()) > 0 ? preferred : dropdown.locator('[role="option"]').last();
  await target.click();
  await expect.poll(async () => readZoomPercent(appPage), { timeout: 15_000 }).not.toBe(zoomBefore);

  // ── 4. Add a highlight ───────────────────────────────────────────────────
  // The chapter a turn lands on is not a choice this spec gets to make, so the
  // reader is taken to one with visible prose to mark before selecting.
  //
  // The highlight this creates MUST be removed before the spec ends: a smoke
  // test that runs on every change and never cleans up after itself grows the
  // developer's book by one highlight per run (measured: a book reached 114
  // highlights this way). The id is captured by diffing the highlight set
  // before and after, so the teardown removes exactly this one and nothing the
  // developer already had.
  const highlightIdsBefore = new Set(
    (await listHighlights(appPage, first.id)).map((row) => row.id),
  );
  await addHighlight(appPage, await seekSelectableChapter(appPage));
  // The render path is asynchronous; the proof is that the backend now holds one
  // more highlight than it did before the click.
  await expect
    .poll(async () => (await listHighlights(appPage, first.id)).length, { timeout: 20_000 })
    .toBeGreaterThan(highlightIdsBefore.size);
  created.highlightId =
    (await listHighlights(appPage, first.id)).find((row) => !highlightIdsBefore.has(row.id))?.id ??
    null;

  // ── 5. Close the book ────────────────────────────────────────────────────
  // The position the reader must come back to is read *before* closing, so the
  // comparison is against a value the app kept rather than a mid-read number.
  const positionOnClose = await settleReader(appPage);
  await closeReader(appPage);

  // ── 6. Reopen and check the position was kept ────────────────────────────
  await openAndAssertChapter(appPage, first);
  await appPage.waitForTimeout(3_000);

  const positionOnReopen = await settleReader(appPage);
  expect(
    positionOnReopen,
    `the reader reopened at page ${positionOnReopen} instead of the page it closed on (${positionOnClose})`,
  ).toBe(positionOnClose);

  // ── 7. Switch books ──────────────────────────────────────────────────────
  await closeReader(appPage);
  await openAndAssertChapter(appPage, second);

  // ── 8. Return to the first book ──────────────────────────────────────────
  await closeReader(appPage);
  await openAndAssertChapter(appPage, first);
  await appPage.waitForTimeout(3_000);

  // The first book's position is the one it was left at, not the second book's.
  const positionAfterReturn = await settleReader(appPage);
  expect(
    positionAfterReturn,
    `returning to the first book showed page ${positionAfterReturn} instead of ${positionOnClose}`,
  ).toBe(positionOnClose);
}
