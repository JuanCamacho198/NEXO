/**
 * The wheel turns the page in paginated mode — and only there.
 *
 * ## Why this spec exists
 *
 * Paginated mode lays a chapter out as **columns** and moves them horizontally,
 * and the chapter root has `overflow-y: hidden` on purpose so the columns stay
 * aligned. A vertical wheel therefore had nowhere to go and did nothing at all.
 * Measured in the running app:
 *
 *     after Next    scrollLeft 1276   page 2 of 4
 *     after wheel   scrollLeft 1276   page 2 of 4     <-- unchanged
 *
 * The wheel is the third way to turn a page after the Next control and the arrow
 * keys, and it is the one the reader reported missing.
 *
 * ## The five claims
 *
 *  1. One notch down moves exactly one page: the field +1 and `scrollLeft` + one
 *     column width.
 *  2. One notch up moves back exactly one.
 *  3. A rapid burst does not skip pages, because one physical notch is several
 *     `wheel` events on Windows.
 *  4. Ctrl+wheel still zooms and does NOT turn. `epubZoomWheel.ts` owns that
 *     gesture; a page-turn listener that claimed it too would break the zoom
 *     that was just fixed.
 *  5. Scrolling mode is untouched: the wheel still scrolls the chapter.
 *
 * ## Why the gesture is dispatched INSIDE the chapter document
 *
 * The chapter is a `srcdoc` iframe. A real wheel over it is delivered to the
 * frame's document, and the listener under test lives there — so
 * `wheelInsideFrame` is the honest way to exercise it. `page.mouse.wheel()` would
 * be delivered to the parent and would not prove the in-frame listener works.
 *
 * ## Local only
 *
 * Needs the real Tauri app under `bun run tauri:dev:debug` (Windows only) and a
 * library book with a chapter long enough to paginate.
 */
import { expect, test } from './harness/connectApp';
import type { Page } from '@playwright/test';
import {
  closeTextSettings,
  openPaginatedBook,
  openTextSettings,
  readColumnLayout,
  readPageFieldClaim,
  settledColumnLayout,
} from './harness/readerPagination';
import { findChapterFrame, waitForChapterFrame } from './harness/readerFlows';

/** The book the pagination harness documents as long enough to have columns. */
const BOOK = /Prisioneros/;

/**
 * Dispatches wheel events INSIDE the chapter document, where the reader's
 * listener is attached and where a real gesture over the text lands.
 *
 * `count` events at `gapMs` apart model a Windows mouse: ONE physical notch
 * emits several of them, which is exactly what the coalescing must absorb. A
 * `gapMs` of 0 lands the whole burst inside one frame.
 */
async function wheelInsideFrame(
  page: Page,
  notch: { deltaY: number; ctrlKey?: boolean },
  options: { count?: number; gapMs?: number } = {},
): Promise<void> {
  const count = options.count ?? 1;
  const gapMs = options.gapMs ?? 0;
  const frame = await waitForChapterFrame(page, 10_000);
  await frame.evaluate(
    ({ notch, count, gapMs }) => {
      for (let index = 0; index < count; index++) {
        window.setTimeout(() => {
          document.body.dispatchEvent(
            new WheelEvent('wheel', {
              ctrlKey: notch.ctrlKey ?? false,
              metaKey: false,
              deltaY: notch.deltaY,
              deltaMode: 0,
              cancelable: true,
              bubbles: true,
            }),
          );
        }, index * gapMs);
      }
    },
    { notch, count, gapMs },
  );
  // Let the dispatched events run and the turn settle. A paginated turn writes
  // `scrollLeft` synchronously, so this is generous rather than load-bearing.
  await page.waitForTimeout(200 + count * gapMs);
}

/**
 * Closes whatever reader panel or modal is up, so the bottom bar is clickable.
 *
 * Several surfaces can be open over the reader and each intercepts pointer
 * events: the notifications panel (a `[data-dialog-overlay]`) opens on its own,
 * and the reader's side panels — text settings, TOC, search, bookmarks — render
 * as `role="presentation"` → `role="dialog"` walls with no overlay attribute at
 * all. A click retried through one of them burns the whole action timeout
 * (measured: 566 retries).
 *
 * Escape is tried first because every one of them closes on it. The panel's own
 * close button is the fallback, for a surface whose Escape handler is on a
 * focused descendant rather than the window.
 */
async function dismissOverlay(page: Page): Promise<void> {
  for (let attempt = 0; attempt < 4; attempt++) {
    const dialogs = page.getByRole('dialog');
    const open = (await dialogs.count()) > 0;
    if (!open) return;
    await page.keyboard.press('Escape');
    await page.waitForTimeout(350);
  }
  // Still up: close it through its own control.
  const closeSettings = page.getByRole('button', { name: /^(Cerrar ajustes|Close settings)$/ });
  if ((await closeSettings.count()) > 0) await closeSettings.first().click();
  await page.waitForTimeout(350);
}

/** The zoom the reader's own control displays, e.g. "100%". */
async function readZoomLabel(page: Page): Promise<string> {
  return page.evaluate(
    () =>
      document.querySelector('[data-testid="zoom-dropdown-trigger"]')?.textContent?.trim() ?? '',
  );
}

/** Reads zoom, page field and the chapter's column offset in one sample. */
async function sample(
  page: Page,
): Promise<{ page: number | null; total: number | null; scrollLeft: number; columnWidth: number }> {
  const claim = await readPageFieldClaim(page);
  const frame = await findChapterFrame(page);
  const layout = frame ? await readColumnLayout(frame) : null;
  return {
    page: claim.current,
    total: claim.total,
    scrollLeft: layout?.scrollLeft ?? -1,
    columnWidth: layout?.columnWidth ?? 0,
  };
}

test('the wheel turns one page per notch in paginated mode, and never steals ctrl+wheel', async ({
  appPage,
}) => {
  test.setTimeout(300_000);

  // The notifications panel opens on its own and its overlay swallows clicks;
  // measured: the harness's back-navigation retried 566 times against it.
  await dismissOverlay(appPage);

  const opened = await openPaginatedBook(appPage, BOOK);
  const where = `"${opened.title}"`;
  expect(
    opened.layout.columnWidth,
    `${where}: the chapter reports no page box width`,
  ).toBeGreaterThan(0);
  // The book opens with the reader's text-settings panel up, and its overlay
  // swallows clicks aimed at the chrome behind it (measured: the Next click
  // retried 553 times against it). It must be closed before any control in the
  // bottom bar is used.
  await dismissOverlay(appPage);

  // Park on a page with room in BOTH directions: the book's stored position can
  // be its first page, where "one notch up" correctly clamps at page 1 and a
  // one-page-back assertion would be measuring the clamp rather than the wheel.
  //
  // Turned through the reader's own Next control, not by typing in the page
  // field. In paginated mode the field's number is read as a COLUMN and
  // `goToPage` routes through `applyPage`, which snaps to the grid and then
  // RE-DERIVES `currentPage` from the offset — measured: filling "2" from page 1
  // left the field reading 1 over an offset of one column. The Next control is
  // the same `goToNextPage` path the wheel uses, so parking through it also
  // proves the wheel starts from the state a real reader would be in.
  const nextControl = appPage.locator('[data-testid="bottom-bar-controls"] button').nth(1);
  const parkedFrom = (await readPageFieldClaim(appPage)).current;
  if (parkedFrom === 1) {
    await nextControl.click();
    await expect
      .poll(async () => (await readPageFieldClaim(appPage)).current, { timeout: 15_000 })
      .toBe(2);
  }
  await appPage.waitForTimeout(800);
  // Re-confirm after the chapter has settled: a recount can still clamp, and the
  // measurements below are only meaningful from a page with a predecessor.
  const parked = await readPageFieldClaim(appPage);
  expect(
    parked.current,
    `${where}: could not park the reader on a page with a predecessor (field reads ` +
      `${parked.current} of ${parked.total}), so "one notch up" would measure a clamp at ` +
      `the first page.`,
  ).toBeGreaterThan(1);

  await openTextSettings(appPage);
  const zoomAtStart = await readZoomLabel(appPage);
  await closeTextSettings(appPage);

  const beforeNext = await sample(appPage);

  // ---- (1) ONE NOTCH DOWN = ONE PAGE -------------------------------------
  const pageBeforeWheel = beforeNext.page;
  await wheelInsideFrame(appPage, { deltaY: 120 });
  const afterWheel = await sample(appPage);
  // Guard against measuring a chapter whose end was already on screen: the turn
  // falls through to the NEXT chapter there, which changes the field but not by
  // one column. The harness parks on a settled multi-column chapter; if the last
  // page was reached the caller gets an honest skip reason instead of a flake.
  const atChapterEnd = beforeNext.page !== null && beforeNext.page === beforeNext.total;
  expect(
    atChapterEnd,
    `${where}: started the wheel measurement at the chapter's LAST page ` +
      `(${beforeNext.page} of ${beforeNext.total}), where a forward turn is a chapter jump.`,
  ).toBe(false);

  expect(
    afterWheel.page,
    `${where}: one wheel notch advanced the page field from ${pageBeforeWheel} to ` +
      `${afterWheel.page}. In paginated mode a notch must turn exactly one page.`,
  ).toBe((pageBeforeWheel ?? 0) + 1);
  expect(
    afterWheel.scrollLeft,
    `${where}: one wheel notch moved scrollLeft ${beforeNext.scrollLeft} -> ` +
      `${afterWheel.scrollLeft}, which is not one column (${afterWheel.columnWidth}px). ` +
      `The field advanced but the columns did not follow, or the wheel was counted more ` +
      `than once.`,
  ).toBe(beforeNext.scrollLeft + afterWheel.columnWidth);

  // ---- (2) ONE NOTCH UP = ONE PAGE BACK ----------------------------------
  await wheelInsideFrame(appPage, { deltaY: -120 });
  const back = await sample(appPage);
  expect(
    back.page,
    `${where}: one wheel notch up moved the page field from ${afterWheel.page} to ` +
      `${back.page}; expected exactly one page back. A clamp at page 1 would mean the ` +
      `measurement never left the chapter's first page.`,
  ).toBe((afterWheel.page ?? 0) - 1);
  expect(
    back.scrollLeft,
    `${where}: one wheel notch up moved scrollLeft ${afterWheel.scrollLeft} -> ` +
      `${back.scrollLeft}; expected it back on the ${beforeNext.scrollLeft} it started from.`,
  ).toBe(beforeNext.scrollLeft);

  // ---- (3) A RAPID BURST DOES NOT SKIP PAGES ------------------------------
  // Four events, zero gap: one physical notch on a high-delta mouse.
  await wheelInsideFrame(appPage, { deltaY: 30 }, { count: 4, gapMs: 0 });
  const burst = await sample(appPage);
  expect(
    burst.page,
    `${where}: a burst of four wheel events from ONE notch advanced the page field to ` +
      `${burst.page}, starting from ${back.page}. One gesture must be one page — counting ` +
      `each event is what makes one click of the wheel skip three pages.`,
  ).toBe((back.page ?? 0) + 1);
  expect(
    burst.scrollLeft,
    `${where}: the burst moved scrollLeft ${back.scrollLeft} -> ${burst.scrollLeft}; ` +
      `expected one column (${burst.columnWidth}px).`,
  ).toBe(back.scrollLeft + burst.columnWidth);

  // ---- (4) CTRL+WHEEL STILL ZOOMS, AND DOES NOT TURN ----------------------
  const beforeCtrl = await sample(appPage);
  const zoomBefore = await readZoomLabel(appPage);
  await wheelInsideFrame(appPage, { deltaY: -120, ctrlKey: true });
  await expect.poll(() => readZoomLabel(appPage), { timeout: 10_000 }).not.toBe(zoomBefore);
  const zoomAfter = await readZoomLabel(appPage);
  const afterCtrl = await sample(appPage);
  expect(
    Number(zoomAfter.replace('%', '')) - Number(zoomBefore.replace('%', '')),
    `${where}: one ctrl+wheel notch moved zoom ${zoomBefore} -> ${zoomAfter}; expected one ` +
      `10-point step of the reader's zoom pipeline.`,
  ).toBe(10);
  expect(
    afterCtrl.page,
    `${where}: ctrl+wheel changed the page field from ${beforeCtrl.page} to ${afterCtrl.page}. ` +
      `Ctrl+wheel is the ZOOM gesture; turning a page on it as well would be the defect this ` +
      `spec exists to prevent.`,
  ).toBe(beforeCtrl.page);

  // Put zoom back where it was found. Done with NO panel open: the bottom bar's
  // own zoom control is not behind the settings sidebar, and clicking the
  // trigger while the sidebar is up is intercepted by its backdrop (measured:
  // 551 retries).
  if (zoomAtStart) {
    await dismissOverlay(appPage);
    const percent = Number.parseInt(zoomAtStart.replace('%', ''), 10);
    // The bottom bar exposes this control TWICE in the DOM when the sidebar is
    // up (the sidebar carries its own copy), so the one to drive is chosen by
    // being outside `role="dialog"`.
    const triggers = appPage.locator('[data-testid="zoom-dropdown-trigger"]');
    const count = await triggers.count();
    expect(count, `${where}: the reader renders no zoom control`).toBeGreaterThan(0);
    await triggers.nth(count - 1).click();
    await appPage.locator(`[data-testid="zoom-option-${percent}"]`).click();
    await expect.poll(() => readZoomLabel(appPage), { timeout: 10_000 }).toBe(zoomAtStart);
  }

  // ---- (5) SCROLLING MODE STILL SCROLLS ----------------------------------
  await dismissOverlay(appPage);
  await openTextSettings(appPage);
  const select = appPage.locator('[data-testid="reading-mode-select"]');
  await select.locator('button').first().click();
  const scrollOption = appPage.getByRole('option', { name: /^(Desplazamiento|Scroll)$/ }).first();
  await expect(scrollOption).toBeVisible({ timeout: 10_000 });
  await scrollOption.click();
  await closeTextSettings(appPage);
  await settledColumnLayout(appPage, { timeoutMs: 2_000 }).catch(() => null);
  await appPage.waitForTimeout(2_000);

  const container = appPage.locator('[data-testid="epub-scroll-container"]');
  const scrollModeBefore = {
    page: (await readPageFieldClaim(appPage)).current,
    top: await container.evaluate((el) => el.scrollTop),
    scrollable: await container.evaluate((el) => el.scrollHeight > el.clientHeight + 40),
  };
  // The wheel is left un-claimed in scrolling mode, so the frame's own document
  // must scroll. A synthetic event inside the frame cannot scroll it (the frame
  // is sized to the whole chapter), so the gesture is driven where a real one
  // lands: through the frame, then the container's scroll is what the reader
  // sees. Asserting the listener left the event ALONE is the part that can
  // regress; asserting pixels moved is the reader-visible consequence.
  const notCancelled = await waitForChapterFrame(appPage, 10_000).then((frame) =>
    frame.evaluate(() => {
      const event = new WheelEvent('wheel', {
        deltaY: 240,
        deltaMode: 0,
        cancelable: true,
        bubbles: true,
      });
      document.body.dispatchEvent(event);
      return event.defaultPrevented === false;
    }),
  );
  expect(
    notCancelled,
    `${where}: in scrolling mode the wheel was cancelled inside the chapter. It must fall ` +
      `through untouched or the reader cannot scroll the book any more.`,
  ).toBe(true);

  await appPage.mouse.move(700, 500);
  await appPage.mouse.wheel(0, 600);
  await appPage.waitForTimeout(600);
  const scrollModeAfter = {
    page: (await readPageFieldClaim(appPage)).current,
    top: await container.evaluate((el) => el.scrollTop),
  };
  if (scrollModeBefore.scrollable) {
    expect(
      scrollModeAfter.top,
      `${where}: in scrolling mode the wheel left scrollTop at ${scrollModeAfter.top} ` +
        `(started ${scrollModeBefore.top}). Scrolling must keep working.`,
    ).not.toBe(scrollModeBefore.top);
  }
  expect(
    scrollModeAfter.page,
    `${where}: in scrolling mode the page field moved ${scrollModeBefore.page} -> ` +
      `${scrollModeAfter.page} on a wheel gesture. The wheel turns pages only in paginated mode.`,
  ).toBe(scrollModeBefore.page);

  // Restore the mode the spec found, so the next run starts where this one did.
  await openTextSettings(appPage);
  const reopen = appPage.locator('[data-testid="reading-mode-select"]');
  await reopen.locator('button').first().click();
  const pageOption = appPage.getByRole('option', { name: /^(Páginas|Pages)$/ }).first();
  await expect(pageOption).toBeVisible({ timeout: 10_000 });
  await pageOption.click();
  await closeTextSettings(appPage);

  console.log(
    `[page-wheel] ${where}: column ${beforeNext.columnWidth}px; ` +
      `notch down ${beforeNext.scrollLeft} -> ${afterWheel.scrollLeft} (page ${pageBeforeWheel} -> ` +
      `${afterWheel.page}); notch up -> ${back.scrollLeft}; burst of 4 -> ${burst.scrollLeft} ` +
      `(page ${burst.page}); ctrl+wheel zoom ${zoomBefore} -> ${zoomAfter} (page ` +
      `${beforeCtrl.page} -> ${afterCtrl.page}); scroll mode wheel ${scrollModeBefore.top} -> ` +
      `${scrollModeAfter.top} (page ${scrollModeBefore.page} -> ${scrollModeAfter.page})`,
  );
});
