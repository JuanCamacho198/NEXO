/**
 * Changing the WINDOW SIZE must not damage the pagination.
 *
 * ## The reported defect
 *
 * "si cambio el tamaño de la página la paginación se daña, solo una página pero
 * lo hace." In the reader's screenshot the book sat at 100% zoom with the prose
 * **cut off at both edges** while the header claimed `3 / 71`.
 *
 * ## The mechanism
 *
 * `scrollLeft` is a whole number of page boxes, and a resize changes the page
 * box. So a resize leaves the offset naming a FRACTIONAL position on the new
 * grid: measured, a reader on page 6 of an 1100px grid had `scrollLeft` 5500,
 * which is 6.1 of the new 900px boxes. Three things then went wrong together:
 *
 *   1. `publishCount` re-derived `currentPage` from that stale offset against the
 *      NEW width — `pageFromScrollLeft(5500, 900, …)` returns 7 against a reader
 *      who was on 6.
 *   2. `recount()` only CLAMPS the page; it never re-derives it, so the wrong
 *      number was never corrected once the layout settled.
 *   3. The offset itself was never moved, so the viewport showed the join between
 *      two columns — the tail of one, the head of the next. That is the "text cut
 *      off at the edges" in the screenshot: whole lines, typeset for the OLD
 *      column width, split across the screen.
 *
 * ## The claims
 *
 *   1. After a resize the reader is on a WHOLE page — `scrollLeft % columnWidth
 *      === 0` — not between two.
 *   2. The page field agrees with the column the chapter's own offset puts the
 *      reader on.
 *   3. The reader keeps READING THE SAME PAGE NUMBER across the resize, rather
 *      than being moved by it. A resize changes how much text a page holds; it is
 *      not a navigation gesture, so it must not navigate.
 *   4. The column the reader is on shows CONTINUOUS text — the chapter's own
 *      column geometry says so, which is what "not cut off" means here.
 *
 * ## Proving it can fail
 *
 * Disabling the resize branch in `useEpubPagination.svelte.ts` (the `resize`
 * flag never becomes true, so the page is never re-snapped) turns claim (1) red
 * with the literal message recorded beside the assertion below. Reverting the
 * branch restores it.
 *
 * ## Why a second, HELD-OPEN CDP connection
 *
 * `setViewportSize` over CDP resizes the WebView2 viewport, but the override
 * reverts the moment the LAST debugger detaches — so a connection that closed
 * after applying the resize would be undone before the app ever laid out. The
 * `holder` below stays attached for the whole file. Same mechanism, and the same
 * reason, as `readerSelectionCollapsed.spec.ts`.
 *
 * ## Local only
 *
 * Needs the real Tauri app under `bun run tauri:dev:debug` (Windows only). Not a
 * CI gate.
 *
 *   bun run tauri:dev:debug      # terminal 1
 *   bun run test:e2e:cdp         # terminal 2
 */
import { chromium } from '@playwright/test';
import type { Frame, Page } from '@playwright/test';
import { expect, test } from './harness/connectApp';
import { readBookRow, readColumnLayout, readPageFieldClaim } from './harness/readerPagination';
import { findChapterFrame, openBook } from './harness/readerFlows';
import { readSettings, reloadApp, restoreSettings, seedSetting } from './harness/readerReset';

/** A book whose chapters hold enough continuous prose to lay out many columns. */
const BOOK = /^Prisioneros/;

/**
 * The two window sizes. The narrow one must move the page box enough that the
 * offset would land off-grid if it were not recomputed, without shrinking the
 * chapter so far that the parked page ceases to exist.
 */
const WIDE = { width: 1280, height: 820 };
const NARROW = { width: 860, height: 720 };

/** Keeps the resized viewport applied for the whole file. See the header. */
let holder: { close: () => Promise<void> } | null = null;

test.beforeAll(async () => {
  const browser = await chromium.connectOverCDP(
    `http://127.0.0.1:${process.env.NEXO_CDP_PORT ?? '9222'}`,
    { isLocal: true, timeout: 60_000 },
  );
  holder = browser;
});

test.afterAll(async () => {
  await holder?.close().catch(() => undefined);
  holder = null;
});

/**
 * Closes a settings sidebar or a modal left open by an interrupted run.
 *
 * The reader's settings sidebar is a `role="dialog"` with a
 * `fixed inset-0 z-40` backdrop that intercepts pointer events across the WHOLE
 * window — including the top bar's own buttons. A previous run killed mid-test
 * leaves it open, and every later gesture then retries against an overlay that
 * never yields, which looks exactly like a hung app rather than stale harness
 * state. Closing it through its own control is the reader's own path.
 */
async function dismissOpenOverlays(page: Page): Promise<void> {
  const escape = page.keyboard.press('Escape').catch(() => undefined);
  await escape;
  await page.waitForTimeout(300);

  const sidebar = page.getByRole('dialog', { name: /^(Ajustes de texto|Text settings)$/ });
  if ((await sidebar.count()) > 0 && (await sidebar.isVisible().catch(() => false))) {
    const close = sidebar.getByRole('button', { name: /^(Cerrar ajustes|Close settings)$/ });
    if ((await close.count()) > 0)
      await close
        .first()
        .click({ timeout: 5_000 })
        .catch(() => undefined);
  }
  await page.waitForTimeout(300);
}

/**
 * Jumps to the chapter with the MOST columns, through the reader's own table of
 * contents, and returns where to park.
 *
 * ## Why the panel is reopened for every entry
 *
 * The table of contents is a `role="dialog"`, and choosing an entry CLOSES it on
 * this build (measured: after selecting any entry the dialog is gone from the
 * DOM). A loop that clicked entry after entry against one open panel therefore
 * hung on a locator that no longer resolved — 30s per entry, past the whole
 * test's budget — which is a harness defect, not a reader one. So each entry is
 * reached by opening the panel fresh.
 *
 * ## Why the parked page is derived and not a constant
 *
 * Notebooks differ in how much prose a chapter holds, and how many columns that
 * becomes depends on the window width and the installed fonts. Measured on
 * *Prisioneros de la geografía* at 1286px: NO chapter lays out more than 3
 * columns, so a spec that parked on a fixed page 6 could never reach its own
 * precondition. The page to park on is therefore the deepest the best chapter
 * can offer, which keeps the resize meaningful (it must move a real offset)
 * without assuming a column count the machine does not produce.
 */
async function seekDeepestChapter(page: Page): Promise<number> {
  const openToc = async (): Promise<void> => {
    const open = page.getByRole('button', { name: /^(Tabla de contenidos|Table of Contents)$/ });
    await expect(open.first()).toBeVisible({ timeout: 20_000 });
    await open.first().click();
    await expect(
      page.getByRole('dialog', { name: /^(Tabla de contenidos|Table of Contents)$/ }),
    ).toBeVisible({ timeout: 10_000 });
  };

  await openToc();
  const firstPanel = page.getByRole('dialog', {
    name: /^(Tabla de contenidos|Table of Contents)$/,
  });
  const entryCount = await firstPanel.locator('nav button').count();
  expect(entryCount, 'the table of contents offered no chapters').toBeGreaterThan(1);
  // Close it again: the loop below opens the panel per entry.
  await page.keyboard.press('Escape');
  await page.waitForTimeout(400);

  let best = 0;
  const tried: string[] = [];
  for (let index = 0; index < entryCount; index++) {
    await openToc();
    const panel = page.getByRole('dialog', {
      name: /^(Tabla de contenidos|Table of Contents)$/,
    });
    const entries = panel.locator('nav button');
    if ((await entries.count()) <= index) continue;
    const label = ((await entries.nth(index).textContent()) ?? '').trim();
    await entries.nth(index).click();
    await page.waitForTimeout(2_200);

    const frame = await findChapterFrame(page);
    const layout = frame ? await readColumnLayout(frame) : null;
    const columns = layout?.pageCount ?? 0;
    tried.push(`${index}:${label.slice(0, 20)}=${columns}`);
    // A page past the first and short of the last: a resize must be able to move
    // it without clamping, and it must not already be at the chapter's end.
    if (columns > best) best = columns;
    if (best >= 5) break;
  }

  expect(
    best,
    `no table-of-contents entry laid out in several columns (tried ${tried.join(', ')}).`,
  ).toBeGreaterThanOrEqual(3);

  // Park one page in, so the offset is a whole page box that a resize has to
  // recompute rather than a zero it can leave alone.
  return Math.min(2, best - 1);
}

/**
 * Opens the pinned book at a known zoom, enters paginated mode, seeks a chapter
 * of prose and parks the reader on a page well inside it.
 *
 * The book is pinned by title because `openUsableEpub` picks "the first EPUB with
 * enough prose", ordered by `updated_at` — measured: a run opened *Prisioneros*
 * and the next opened *Armas, gérmenes y acero*, so a spec that seeks a chapter
 * by position would drive a different book each run.
 *
 * ## Why the reload is the gate that matters
 *
 * Vite HMR does NOT reload `.svelte.ts` modules in this app (documented in
 * `harness/readerPagination.ts`), so a page that was already sitting in the
 * reader keeps the module code it booted with — and a spec run against it
 * measures an OLDER build while looking perfectly healthy. Measured: with the
 * pre-reload page, a resize that the fixed code handles correctly left the reader
 * on page 1; the same gesture on a freshly reloaded page landed on the right
 * page and offset. The reload below is therefore what makes the assertions a
 * statement about the sources under test rather than about whatever bundle the
 * app happened to be holding.
 *
 * It is also what applies the SEEDED settings: the stores were populated from the
 * database at boot, so a write that arrives afterwards is not seen until the next
 * boot. The mode is seeded rather than toggled because the settings UI's own label
 * can disagree with the applied value when a previous run left it mid-flight —
 * measured: the select read "Páginas" while the reader still laid the chapter out
 * as scrolling prose. The toggle-through-the-UI promise belongs to
 * `readerPagination.spec.ts`.
 */
async function parkOnPage(page: Page): Promise<{ frame: Frame; page: number }> {
  const book = await readBookRow(page, BOOK);
  test.skip(book === null, `no library EPUB is titled ${BOOK}, so there is nothing to measure`);
  console.log(`[pagination-resize] setup: opening "${book!.title}"`);

  await seedSetting(page, 'reader.epub.fontSize', 100);
  await seedSetting(page, 'reader.paginated', true);
  // Reload BEFORE the book is opened: the reload puts the app back on the library
  // route, and the reader it then opens is the one built from the current sources
  // with the seeded settings applied.
  await reloadApp(page);
  // The reload also clears a settings sidebar an interrupted previous run may have
  // left open: its `fixed inset-0 z-40` backdrop swallows every click — including
  // the top bar's own controls — so the spec would otherwise hang on its first
  // gesture until the timeout.
  await dismissOpenOverlays(page);
  await openBook(page, book!);
  await page.waitForTimeout(2_500);
  const wanted = await seekDeepestChapter(page);
  console.log(`[pagination-resize] setup: parking on page ${wanted}`);

  const input = page.locator('[data-testid="bottom-bar-controls"] input').first();
  await input.fill(String(wanted));
  await input.blur();
  await expect
    .poll(async () => (await readPageFieldClaim(page)).current, { timeout: 20_000 })
    .toBe(wanted);
  // Let the chapter finish any asset-driven re-render before the baseline is
  // read, so a swap is not mistaken for the trigger under test.
  await page.waitForTimeout(2_000);

  const frame = await findChapterFrame(page);
  expect(
    frame,
    'the chapter frame disappeared before the resize could be exercised',
  ).not.toBeNull();
  return { frame: frame!, page: wanted };
}

test('a window resize keeps the reader on a whole page, and on the same page', async ({
  appPage,
}) => {
  test.setTimeout(420_000);
  const settingsBefore = await readSettings(appPage);

  try {
    // Start wide, so the parked page is measured against the box the resize will
    // move away from.
    await appPage.setViewportSize(WIDE);
    await appPage.waitForTimeout(800);

    const start = await parkOnPage(appPage);
    const parked = start.page;
    const before = await readColumnLayout(start.frame);
    expect(
      before?.page,
      `the reader was parked on page ${parked} but the chapter's offset sits at ` +
        `${before?.scrollLeft} of a ${before?.columnWidth}px box, which is not on the grid.`,
    ).toBe(parked);

    // ── The resize ─────────────────────────────────────────────────────────
    await appPage.setViewportSize(NARROW);
    await appPage.waitForTimeout(2_500);

    const width = await appPage.evaluate(() => window.innerWidth);
    expect(
      width,
      `page.setViewportSize(${NARROW.width}) did not narrow the webview (window.innerWidth ` +
        `is ${width}), so nothing was resized and every assertion below measures nothing.`,
    ).toBeLessThanOrEqual(NARROW.width + 40);

    const frame = (await findChapterFrame(appPage)) ?? start.frame;
    const after = await readColumnLayout(frame);
    expect(after, 'the chapter had no measurable layout after the resize').not.toBeNull();

    // The resize really did change the grid, or there is nothing to test.
    expect(
      after!.columnWidth,
      `the page box is ${after!.columnWidth}px after a resize to ${NARROW.width}px, the same ` +
        `as the ${before!.columnWidth}px before it, so no relayout happened.`,
    ).not.toBe(before!.columnWidth);

    const claim = await readPageFieldClaim(appPage);

    // ── Claim 1: the reader is on a WHOLE page ─────────────────────────────
    // This is the "text cut off at the edges" assertion. An off-grid offset
    // shows the join between two columns: whole lines, typeset for the previous
    // column width, split across the screen at both edges.
    expect(
      after!.scrollLeft % after!.columnWidth,
      `after a resize to ${NARROW.width}px the chapter's offset is ${after!.scrollLeft} in a ` +
        `${after!.columnWidth}px box, which is ${after!.scrollLeft % after!.columnWidth}px into a ` +
        `page. The viewport is showing the join between two columns — the text is cut off at both ` +
        `edges. A settled position is a whole number of page boxes.`,
    ).toBe(0);

    // ── Claim 2: the field agrees with the column on screen ────────────────
    expect(
      claim.current,
      `the chapter's own offset puts the reader on column ${after!.page} ` +
        `(scrollLeft ${after!.scrollLeft} of a ${after!.columnWidth}px box) but the page field ` +
        `reads ${claim.current}.`,
    ).toBe(after!.page);

    // ── Claim 3: a resize does not navigate ────────────────────────────────
    // A resize changes how much text a page holds. It is not a page turn, so the
    // reader must still be reading the page they chose.
    expect(
      claim.current,
      `the reader was on page ${parked} and a resize to ${NARROW.width}px moved them to ` +
        `${claim.current}. A resize is not a navigation gesture; the page number must survive it.`,
    ).toBe(parked);

    console.log(
      `[pagination-resize] box ${before!.columnWidth}px -> ${after!.columnWidth}px, ` +
        `scrollLeft ${before!.scrollLeft} -> ${after!.scrollLeft}, ` +
        `page ${before!.page} -> ${after!.page}, field ${claim.current}/${claim.total}`,
    );

    // ── And back, so a one-way fix cannot pass ─────────────────────────────
    await appPage.setViewportSize(WIDE);
    await appPage.waitForTimeout(2_500);

    const back = await readColumnLayout((await findChapterFrame(appPage)) ?? frame);
    expect(back, 'the chapter had no measurable layout after widening back').not.toBeNull();
    expect(
      back!.scrollLeft % back!.columnWidth,
      `widening back to ${WIDE.width}px left the offset at ${back!.scrollLeft} in a ` +
        `${back!.columnWidth}px box (${back!.scrollLeft % back!.columnWidth}px into the page).`,
    ).toBe(0);
    const backClaim = await readPageFieldClaim(appPage);
    expect(
      backClaim.current,
      `the reader was on page ${parked} and widening the window back to ${WIDE.width}px left the ` +
        `field reading ${backClaim.current}. A resize is not a navigation gesture in either ` +
        `direction.`,
    ).toBe(parked);
  } finally {
    await appPage.setViewportSize(WIDE).catch(() => undefined);
    await restoreSettings(appPage, settingsBefore);
  }
});
