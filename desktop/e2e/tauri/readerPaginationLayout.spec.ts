/**
 * The page count must follow the text layout, and a recount must never move the
 * reader.
 *
 * ## The defect this exists for
 *
 * In paginated mode the "of N" total went STALE when the layout changed on an
 * already-open chapter — not for a frame, but indefinitely. Two mechanisms, and
 * only the second one is the interesting one:
 *
 * 1. `refresh()` early-returns when the measured page box is unchanged, and a
 *    multicolumn reflow does NOT change the chapter root's border box, so the
 *    content `ResizeObserver` never fires either. Nothing revisits the count.
 *
 * 2. `refresh()` restyles and then reads `scrollWidth` in the SAME task. For a
 *    step that changes the effective font size (zoom is
 *    `fontSize * zoomLevel / 100`), that synchronous read returns a layout the
 *    browser has not committed yet and never comes back to. Measured on the real
 *    app, emulating 100% -> 75% and reading at increasing delays:
 *
 *        read                      scrollWidth   derived count
 *        sync after the write      41070         37   <- what the field showed
 *        2nd forced read, same task 41070         37
 *        one animation frame later 42180         38   <- the real layout
 *        settled                   42180         38
 *
 *    41070 is a genuine transient the browser passes through between 100%
 *    (54390) and settled 75% (42180), which is why the count was off by exactly
 *    one page and why it did not self-correct.
 *
 * ## What a recount must and must not do
 *
 * The reader's page survives it. `recount()` only CLAMPS: a new count that is
 * larger leaves the page alone, and a count smaller than the current page moves
 * the reader to the last page. It deliberately does NOT re-derive the page from
 * the scroll offset — that is the phase-1 fix for "the reader was thrown to page
 * 1 on every layout tick" — and these tests exist to keep it that way.
 *
 * ## Why the column count is measured at the document root
 *
 * The injected pagination rule targets `html`. `body` always reports
 * `column-width: auto` and `scrollWidth === clientWidth`, so measuring there
 * returns a confident "1 column" for a chapter with 49. See
 * `harness/readerPagination.ts`.
 *
 * ## Self-baselining
 *
 * The reader's settings are ambient: a previous run can leave the zoom at 200%
 * and the margins at their maximum, and a spec that assumed 100% would measure a
 * different chapter than the one it thinks it is measuring. So each test parks,
 * reads the field's total against the real count first, and reports BOTH numbers
 * when the baseline is already wrong. Everything a test changes is restored from
 * a snapshot of the persisted settings.
 *
 * ## Local only
 *
 * Needs the real Tauri app under `bun run tauri:dev:debug` (Windows only).
 *
 *   bun run tauri:dev:debug      # terminal 1
 *   bun run test:e2e:cdp         # terminal 2
 */
import { expect, test } from './harness/connectApp';
import type { Page } from '@playwright/test';
import {
  closeTextSettings,
  openPaginatedMode,
  openTextSettings,
  readBookRow,
  readColumnLayout,
  readPageFieldClaim,
  readZoomPercent,
} from './harness/readerPagination';
import { findChapterFrame, openBook } from './harness/readerFlows';
import { readSettings, reloadApp, restoreSettings, seedSetting } from './harness/readerReset';

/**
 * A book whose chapters hold 100k+ characters of continuous prose, so a chapter
 * lays out dozens of columns and a parked page is deep enough for a clamp to
 * mean something. Measured: 49 columns at 100% zoom.
 */
const BOOK = /^Prisioneros/;

/** Deep enough inside a 40+ column chapter that a zoom-out has to clamp. */
const PARKED = 6;

/** The chapter's real column count, derived exactly the way the app derives it. */
async function realColumnCount(page: Page): Promise<number> {
  const frame = await findChapterFrame(page);
  const layout = frame ? await readColumnLayout(frame) : null;
  if (!layout || layout.columnWidth <= 0) return 0;
  // `Math.ceil(ratio - 1e-3)` rather than a rounded ratio: the app's own
  // `derivePageCount` subtracts a sub-pixel epsilon, because a chapter that ends
  // flush measures a few hundredths of a pixel past an exact multiple.
  return Math.ceil(layout.scrollWidth / layout.columnWidth - 1e-3);
}

/**
 * Jumps to a chapter of real prose using the reader's own table of contents.
 *
 * Not a page-field seek: in paginated mode the field counts COLUMNS, so
 * "go to page 16" walks 16 columns inside the current document and never leaves
 * a short chapter. Measured: a book opens on its synopsis, which fits in one page
 * box, so its own Next control is disabled and there is nothing to page inside.
 *
 * The panel is a `role="dialog"`: it stays OPEN after a chapter is chosen and
 * would swallow every later click on the reader, so it is closed through its own
 * close control before returning.
 */
async function seekProseChapter(page: Page): Promise<void> {
  const open = page.getByRole('button', { name: /^(Tabla de contenidos|Table of Contents)$/ });
  await expect(open.first()).toBeVisible({ timeout: 20_000 });
  await open.first().click();

  const panel = page.getByRole('dialog', { name: /^(Tabla de contenidos|Table of Contents)$/ });
  await expect(panel).toBeVisible({ timeout: 10_000 });
  const entries = panel.locator('nav button');
  const count = await entries.count();
  expect(count, 'the table of contents offered no chapters').toBeGreaterThan(1);

  // Skip the first few entries: the opening documents of these books are the
  // cover, the title page and the synopsis, each short enough for one page box.
  const tried: string[] = [];
  for (let index = Math.min(3, count - 1); index < count; index++) {
    const label = ((await entries.nth(index).textContent()) ?? '').trim();
    await entries.nth(index).click();
    await page.waitForTimeout(3_000);
    const frame = await findChapterFrame(page);
    const layout = frame ? await readColumnLayout(frame) : null;
    tried.push(`${index}:${label.slice(0, 24)}=${layout?.pageCount ?? '?'}`);
    if (layout && layout.pageCount > 2) break;
  }

  // The close control is INSIDE the panel; the top bar's toggle is behind it.
  const close = panel.getByRole('button', { name: /^(Cerrar|Close)$/ });
  if ((await close.count()) > 0) await close.first().click();
  await expect(panel).toBeHidden({ timeout: 10_000 });

  const frame = await findChapterFrame(page);
  const layout = frame ? await readColumnLayout(frame) : null;
  expect(
    layout?.pageCount ?? 0,
    `no table-of-contents entry laid out in several columns (tried ${tried.join(', ')}).`,
  ).toBeGreaterThan(2);
}

/**
 * Opens a prose chapter of a SPECIFIC book, enters paginated mode at a known
 * zoom and parks the reader on a page well inside it.
 *
 * The book is pinned by title because `openUsableEpub` picks "the first EPUB
 * with enough prose", which is ordered by `updated_at` — measured: a run opened
 * *Prisioneros* and the next opened *Armas, gérmenes y acero*, so a spec that
 * seeks a chapter by number is driving a different book each run.
 *
 * The zoom is SEEDED and then reloaded, not just seeded: `upsertSettings` writes
 * the database, but the running app already read its settings into a store at
 * boot and is not subscribed to the table (`readerReset.ts` documents this).
 * Seeding without the reload measured 110% zoom while the seed said 100%.
 */
async function park(page: Page): Promise<{ real: number; fieldTotal: number; zoom: number | null }> {
  const book = await readBookRow(page, BOOK);
  test.skip(book === null, `no library EPUB is titled ${BOOK}, so there is nothing to measure`);
  await seedSetting(page, 'reader.epub.fontSize', 100);
  await reloadApp(page);
  await openBook(page, book!);
  await openPaginatedMode(page);
  await page.waitForTimeout(2_500);
  // The stored position is the synopsis: measured 1 column, 3% into the book,
  // because a synopsis is short enough to fit in one page box and a short
  // chapter has nothing to page inside. Seek a chapter of real prose instead.
  await seekProseChapter(page);
  await closeTextSettings(page);

  const input = page.locator('[data-testid="bottom-bar-controls"] input').first();
  await input.fill(String(PARKED));
  await input.blur();
  await expect
    .poll(async () => (await readPageFieldClaim(page)).current, { timeout: 20_000 })
    .toBe(PARKED);

  // Let the chapter finish any asset-driven re-render before the baseline is
  // read, so a swap is not mistaken for the trigger under test.
  await page.waitForTimeout(2_000);
  const claim = await readPageFieldClaim(page);
  const real = await realColumnCount(page);
  const zoom = await readZoomPercent(page);
  return { real, fieldTotal: claim.total ?? 0, zoom };
}

/** Picks a zoom level by its label, through the bottom bar's menu. */
async function pickZoom(page: Page, percent: number): Promise<void> {
  await page.locator('[data-testid="zoom-dropdown-trigger"]').click();
  const options = page.locator('[data-testid="zoom-dropdown-list"] [role="option"]');
  const count = await options.count();
  for (let index = 0; index < count; index++) {
    const label = (await options.nth(index).textContent()) ?? '';
    if (Number.parseInt(label.replace(/[^0-9]/g, ''), 10) === percent) {
      await options.nth(index).click();
      return;
    }
  }
  throw new Error(`the zoom menu offered no ${percent}% level (${count} option(s) seen)`);
}

/**
 * Asserts the field's total equals the chapter's real column count, and reports
 * both numbers when it does not.
 *
 * The wait is generous on purpose. The fix is synchronous-or-next-frame, so a
 * correct count is there within a frame; a wait that only ever needed one frame
 * would still pass if the count were coalesced a beat later, and that is a
 * legitimate implementation detail. What must NOT pass is a count that never
 * arrives — which is exactly the defect, and it did not arrive in three seconds
 * when it was measured.
 *
 * A sample with no readable frame is SKIPPED rather than counted as a mismatch:
 * the chapter's `srcdoc` is swapped as its assets settle, so a poll can land in
 * the window where the frame is being replaced. That window is the harness's
 * problem, not the count's.
 */
async function expectTotalMatchesLayout(page: Page, label: string): Promise<number> {
  const deadline = Date.now() + 8_000;
  let real = 0;
  let total: number | null = null;
  let samples = 0;

  while (Date.now() < deadline) {
    const frame = await findChapterFrame(page);
    const layout = frame ? await readColumnLayout(frame) : null;
    if (layout && layout.columnWidth > 0) {
      // `Math.ceil(ratio - 1e-3)`, the app's own derivation: a chapter that ends
      // flush measures a few hundredths of a pixel past an exact multiple.
      real = Math.ceil(layout.scrollWidth / layout.columnWidth - 1e-3);
      total = (await readPageFieldClaim(page)).total;
      samples++;
      if (total === real) return real;
    }
    await page.waitForTimeout(200);
  }

  throw new Error(
    `the field never caught up with the layout after ${label}: after ${samples} readable ` +
      `sample(s) the chapter lays out ${real} columns while the field still claims ` +
      `"of ${total}". A recount that reads the previous layout, or that is never asked for, ` +
      `leaves the total on an earlier setting indefinitely.`,
  );
}

test.describe('the page count follows the layout', () => {
  test('a zoom step recounts instead of keeping the previous zoom’s total', async ({ appPage }) => {
    test.setTimeout(420_000);
    const settingsBefore = await readSettings(appPage);

    try {
      const start = await park(appPage);
      expect(
        start.fieldTotal,
        `the baseline itself is stale: the field claims "of ${start.fieldTotal}" over a ` +
          `chapter that lays out ${start.real} columns at ${start.zoom}% zoom. A stale ` +
          `baseline cannot distinguish a recount that works from one that does not.`,
      ).toBe(start.real);

      const pageBefore = (await readPageFieldClaim(appPage)).current;

      // 75% first: it is the stop that was measured to land the synchronous
      // read on the transient (37 vs 38), so it is the one that reproduces.
      await pickZoom(appPage, 75);
      const real75 = await expectTotalMatchesLayout(appPage, 'a step to 75%');
      const zoom75 = await readZoomPercent(appPage);
      expect(
        real75,
        `the step to 75% did not change the layout (still ${real75} columns), so there was ` +
          `nothing for a recount to notice.`,
      ).not.toBe(start.real);
      expect(zoom75, 'the zoom menu did not apply 75%').toBe(75);
      expect(
        (await readPageFieldClaim(appPage)).current,
        `recounting for 75% moved the reader from page ${pageBefore}. A recount may only ` +
          `clamp: the new count (${real75}) is larger than the reader's page.`,
      ).toBe(pageBefore);

      await pickZoom(appPage, 50);
      const real50 = await expectTotalMatchesLayout(appPage, 'a step to 50%');
      expect(real50, 'the step to 50% did not reflow the chapter').not.toBe(real75);

      // And back, so a count that only moves one direction cannot pass.
      await pickZoom(appPage, 100);
      const real100 = await expectTotalMatchesLayout(appPage, 'a step back to 100%');
      expect(
        real100,
        `returning to 100% laid the chapter out as ${real100} columns but the baseline was ` +
          `${start.real}; the recount is following something other than the layout.`,
      ).toBe(start.real);
    } finally {
      await restoreSettings(appPage, settingsBefore);
    }
  });

  test('a font-size change recounts, and keeps the reader’s page', async ({ appPage }) => {
    test.setTimeout(420_000);
    const settingsBefore = await readSettings(appPage);

    try {
      const start = await park(appPage);
      const pageBefore = (await readPageFieldClaim(appPage)).current;

      await openTextSettings(appPage);
      await appPage.locator('[data-testid="font-size-inc"]').click();
      // The total is read while the panel is still open: closing it is a
      // separate concern, and the recount must not depend on it.
      const real = await expectTotalMatchesLayout(appPage, 'a font-size step');
      await closeTextSettings(appPage);

      expect(
        real,
        `the font-size step did not change the layout (${real} columns before and after), ` +
          `so there was nothing for a recount to notice.`,
      ).not.toBe(start.real);
      expect(
        (await readPageFieldClaim(appPage)).current,
        `recounting for a larger font moved the reader from page ${pageBefore}. A recount ` +
          `may only clamp.`,
      ).toBe(pageBefore);
    } finally {
      await restoreSettings(appPage, settingsBefore);
    }
  });

  test('a recount that shrinks the count clamps to the last page', async ({ appPage }) => {
    test.setTimeout(420_000);
    const settingsBefore = await readSettings(appPage);

    try {
      const start = await park(appPage);

      // 50% halves the effective font size, so the chapter needs far fewer
      // columns than the parked page. The reader is then past the new end.
      await pickZoom(appPage, 50);
      const real = await expectTotalMatchesLayout(appPage, 'a step to 50%');

      expect(
        real,
        `zooming to 50% did not shrink the chapter below the parked page ` +
          `(${real} columns against ${start.real}), so the reader is not past the new end ` +
          `and there is nothing to clamp.`,
      ).toBeLessThan(PARKED);

      const claim = await readPageFieldClaim(appPage);
      expect(
        claim.current,
        `the reader was parked on page ${PARKED} of ${start.real} and the chapter shrank to ` +
          `${real} columns; the page must clamp to ${real} — not to page 1, and not past the ` +
          `end — and it reads ${claim.current}.`,
      ).toBe(real);

      const frame = await findChapterFrame(appPage);
      const layout = frame ? await readColumnLayout(frame) : null;
      expect(
        layout?.page,
        `the field says page ${claim.current} but the chapter's own offset puts the reader ` +
          `on ${layout?.page} (scrollLeft ${layout?.scrollLeft} of a ${layout?.columnWidth}px box).`,
      ).toBe(real);
    } finally {
      await restoreSettings(appPage, settingsBefore);
    }
  });

  test('scrolling mode still counts documents', async ({ appPage }) => {
    test.setTimeout(420_000);
    const settingsBefore = await readSettings(appPage);

    try {
      const paginated = await park(appPage);

      await openTextSettings(appPage);
      const select = appPage.locator('[data-testid="reading-mode-select"]');
      await select.locator('button').first().click();
      const option = appPage.getByRole('option', { name: /^(Desplazamiento|Scroll)$/ }).first();
      await expect(option).toBeVisible({ timeout: 10_000 });
      await option.click();
      await closeTextSettings(appPage);
      await appPage.waitForTimeout(2_500);

      const after = await readPageFieldClaim(appPage);
      const frame = await findChapterFrame(appPage);
      const layout = frame ? await readColumnLayout(frame) : null;

      expect(
        layout?.pageCount,
        `after leaving paginated mode the chapter still lays out as ${layout?.pageCount} ` +
          `columns, so the reader is not in scrolling mode and its total says nothing.`,
      ).toBeLessThanOrEqual(1);
      expect(
        after.total,
        `scrolling mode claims "of ${after.total}", the same number the paginated chapter ` +
          `reported (${paginated.real} columns). Scrolling mode counts documents.`,
      ).not.toBe(paginated.real);
      expect(after.total).toBeGreaterThan(0);
    } finally {
      await restoreSettings(appPage, settingsBefore);
    }
  });
});
