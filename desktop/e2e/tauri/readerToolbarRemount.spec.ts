/**
 * The selection menu survives the press that steals the chapter's focus.
 *
 * ## The reported defect, in the reader's own words
 *
 * "Hago una selección, le quiero poner un color, le doy clic a ese color y no
 * pasa nada. **El menú como que desaparece por un momento y luego vuelve a
 * aparecer**, y presiono otra vez ese mismo color y ahora sí se aplica."
 *
 * That is not "two clicks". The toolbar is **unmounted and remounted inside the
 * press**, the `click` lands on a node that is already gone, and the second press
 * lands on the fresh one.
 *
 * ## What was measured, and the one thing a plain selection does not do
 *
 * The chapter frame's `blur` handler posted `iframe-blur` whenever the frame lost
 * the caret. The toolbar is rendered by `ReaderWorkspace` into the PARENT
 * document, so pressing any of its buttons moves the caret out of the frame —
 * `document.hasFocus()` inside the frame goes `true` → `false` on the
 * `mousedown` — and the handler read that as "the selection ended". The parent
 * dismissed the toolbar, the live selection re-raised it a moment later, and the
 * release never reached the button. The SECOND press works because the frame no
 * longer holds the caret, so nothing blurs and nothing is dismissed.
 *
 * The precondition is the whole reason this was invisible to every earlier spec:
 * **the frame has to be FOCUSED for the blur to fire**, and the harness drives
 * selections programmatically, which never focuses anything. Measured here, with
 * the frame focused and the OLD guard in place:
 *
 *   t+ 34.2ms  pointerdown      span  <<IN-TOOLBAR>>   (the workspace guard holds)
 *   t+ 34.5ms  mousedown        span  <<IN-TOOLBAR>>
 *   t+ 39.6ms  epub-selection   textLen=0 reason=iframe-blur
 *   t+ 40.2ms  dismissToolbar   onselectionclear:iframe-blur
 *   t+ 50.4ms  epub-selection   textLen=12 fast=true   (the selection was still live)
 *
 *   the parent window saw a mouseup / click in that window: false / false
 *   highlights added on the first press: 0
 *
 * — and with the guard asking the TOP document instead (the fix): no clear, the
 * parent sees `mouseup` and `click`, and the colour is applied on one press.
 *
 * ## Why these are the assertions
 *
 * The colour path is the sharpest one the toolbar has: synchronous, no network,
 * no panel, one write. The backend is the authority on whether a press did
 * something, and the dismissal reasons are the authority on WHY the menu left —
 * a toolbar that vanishes because a stray clear killed it looks identical in the
 * DOM to one that vanished because the colour was applied.
 *
 * ## Local only
 *
 * Needs the real Tauri app under `bun run tauri:dev:debug` (Windows only).
 *
 *   bun run tauri:dev:debug                        # terminal 1
 *   bun run test:e2e:cdp -- readerToolbarRemount   # terminal 2
 */
import { chromium } from '@playwright/test';
import { expect, test } from './harness/connectApp';
import { deleteHighlight, highlightIds, waitForChapterFrame } from './harness/readerFlows';
import { openUsableEpub } from './harness/readerCapacity';
import { selectTextInChapter } from './harness/readerSelection';
// The recorder is shared with `readerHighlightMenu.spec.ts`: both run against
// the same never-reloaded app page, so two private installers would fight.
import { formatTrace, installTrace, readTrace, resetTrace } from './harness/readerTrace';
import type { Frame, Page } from '@playwright/test';

const TOOLBAR = '.selection-toolbar';
const COLOR_NAME = /select|seleccionar resaltado/i;
const WIDE_VIEWPORT = { width: 1200, height: 800 };

/**
 * A second, HELD-OPEN debugger connection.
 *
 * `page.setViewportSize` over CDP resizes the WebView2 viewport, but the
 * override reverts the moment the LAST debugger detaches — so a resize applied
 * by a connection that closed would be undone before the app ever laid out.
 * Same mechanism, and same reason, as `readerToolbarInteraction.spec.ts`.
 */
let holder: { close: () => Promise<void> } | null = null;

test.beforeAll(async () => {
  holder = await chromium.connectOverCDP(
    `http://127.0.0.1:${process.env.NEXO_CDP_PORT ?? '9222'}`,
    { isLocal: true, timeout: 60_000 },
  );
});

test.afterAll(async () => {
  await holder?.close().catch(() => undefined);
  holder = null;
});

/** The layout the toolbar actually rendered, read from the DOM. */
async function readLayout(page: Page): Promise<string> {
  return page.evaluate((sel) => {
    const row = document.querySelector(`${sel} div[role="toolbar"]`) as HTMLElement | null;
    if (!row) return 'absent';
    if (getComputedStyle(row).flexWrap === 'wrap') return 'wrapped';
    if (row.querySelector('button[aria-expanded]')) return 'collapsed';
    return 'full';
  }, TOOLBAR);
}

/** Escape is the toolbar's own dismissal path, so it clears a leftover. */
async function dismissLeftoverToolbar(page: Page): Promise<void> {
  if ((await page.locator(TOOLBAR).count()) > 0) {
    await page.keyboard.press('Escape');
    await expect(page.locator(TOOLBAR))
      .toBeHidden({ timeout: 5_000 })
      .catch(() => undefined);
    await page.waitForTimeout(300);
  }
}

/**
 * Raises the toolbar on a real selection and lets it settle past the 100ms
 * debounce the frame uses before its authoritative post.
 */
async function raiseToolbar(page: Page): Promise<string | null> {
  await dismissLeftoverToolbar(page);

  for (let attempt = 0; attempt < 6; attempt++) {
    const frame = await waitForChapterFrame(page, 10_000);
    const selected = await selectTextInChapter(frame);
    if (selected !== null && selected.trim().length > 0) {
      try {
        await expect(page.locator(TOOLBAR)).toBeVisible({ timeout: 6_000 });
        await page.waitForTimeout(700);
        return selected;
      } catch {
        /* the chapter was still settling under the selection; try again */
      }
    }
    await page.waitForTimeout(900);
  }
  return null;
}

/** The point to press for a colour, in whichever form the layout offers. */
async function colorPressPoint(
  page: Page,
): Promise<{ x: number; y: number; folded: boolean } | null> {
  const toolbar = page.locator(TOOLBAR);
  const opener = toolbar.locator('button[aria-expanded]').first();
  const folded = (await opener.count()) > 0;
  if (folded) {
    // The palette is opened by its own trigger — a different control, so this is
    // not a second press on the colour.
    await opener.click({ timeout: 5_000 });
    await page.waitForTimeout(300);
  }
  const swatch = (
    folded
      ? toolbar.locator('[role="group"]').getByRole('button', { name: COLOR_NAME })
      : toolbar.getByRole('button', { name: COLOR_NAME })
  ).first();
  if ((await swatch.count()) === 0) return null;
  const box = await swatch.boundingBox({ timeout: 5_000 }).catch(() => null);
  if (!box || box.width === 0) return null;
  return { x: box.x + box.width / 2, y: box.y + box.height / 2, folded };
}

/** Opens a usable book and leaves it reading prose. */
async function openBookOrSkip(page: Page): Promise<string | null> {
  try {
    const opened = await openUsableEpub(page);
    return opened.book.id;
  } catch (err) {
    test.skip(true, `No library EPUB rendered prose: ${String(err).slice(0, 200)}`);
    return null;
  }
}

/** One real press at `point`, with the browser's own hit test and click. */
async function realPress(page: Page, point: { x: number; y: number }): Promise<void> {
  await page.mouse.move(point.x, point.y);
  await page.mouse.down();
  await page.mouse.up();
}

/**
 * THE regression test for the reported defect.
 *
 * The chapter frame is FOCUSED first — the state a reader leaves it in after
 * dragging over text, and the state the blur handler branches on. One real press
 * on a colour must then apply the colour, and the toolbar must not have been
 * dismissed by `iframe-blur` on the way.
 *
 * Proving it can fail: restore the old guard (`if (document.hasFocus()) return;`)
 * in `epubSelectionScript.ts` and this test goes red on "no highlight", with the
 * `onselectionclear:iframe-blur` visible in the trace it prints.
 */
test('with the chapter frame focused, ONE real press on a colour applies it', async ({
  appPage,
}) => {
  test.setTimeout(300_000);

  const bookId = await openBookOrSkip(appPage);
  if (bookId === null) return;

  await appPage.setViewportSize(WIDE_VIEWPORT);
  await appPage.waitForTimeout(1_200);
  await installTrace(appPage);

  const selected = await raiseToolbar(appPage);
  if (selected === null) {
    test.skip(true, 'The toolbar could not be raised on a real chapter selection.');
    return;
  }

  // ── The precondition: the frame holds the caret ──────────────────────────
  // Without it the frame's `blur` never fires at all, the press is clean for a
  // reason that has nothing to do with the fix, and the test would pass against
  // the defect. `window.focus()` is how a programmatic runner reproduces the
  // state a reader's drag leaves behind (a click inside a frame focuses it).
  const frame = await afterFocus(appPage);
  const focus = await frame.evaluate(() => document.hasFocus());
  expect(
    focus,
    'the chapter frame could not be focused, so its `blur` handler can never fire and this test ' +
      'would pass against the defect it exists to catch. Measured: `window.focus()` inside the ' +
      'frame reports `document.hasFocus() === true` on this build.',
  ).toBe(true);

  const layout = await readLayout(appPage);
  const point = await colorPressPoint(appPage);
  expect(point, `the colour control had no pressable node in the ${layout} layout`).not.toBeNull();

  const before = await highlightIds(appPage, bookId);
  await resetTrace(appPage);

  await realPress(appPage, point!);
  await appPage.waitForTimeout(2_500);

  const trace = await readTrace(appPage);
  const after = await highlightIds(appPage, bookId);
  const added = [...after].filter((id) => !before.has(id));
  const reasons = trace.filter((e) => e.kind === 'dismissToolbar').map((e) => e.detail);

  console.log(
    `\n[remount] layout=${layout} folded=${point!.folded} applied=${added.length}\n` +
      `[remount] trace:\n${formatTrace(trace)}\n`,
  );

  for (const id of added) await deleteHighlight(appPage, id).catch(() => undefined);

  // The reason list is the difference between "the colour was applied" and "a
  // stray clear tore the menu down before the click landed", which the DOM alone
  // cannot tell apart.
  expect(
    reasons.join(' | '),
    `the first press on the colour dismissed the toolbar with ` +
      `${JSON.stringify(reasons)}. An \`onselectionclear:iframe-blur\` in this list means the ` +
      `frame read its own loss of the caret as "the selection ended" while the toolbar lives in ` +
      `the PARENT document — so pressing a button of the menu dismissed the menu. That is the ` +
      `reported "el menú desaparece por un momento y luego vuelve a aparecer". Trace:\n` +
      `${formatTrace(trace)}`,
  ).not.toContain('iframe-blur');

  expect(
    added.length,
    `the first real press on the colour at (${JSON.stringify(point)}) applied no highlight. ` +
      `The backend is the authority, so this is "the press did nothing" — the reported "le doy ` +
      `clic a ese color y no pasa nada". Trace:\n${formatTrace(trace)}`,
  ).toBeGreaterThan(0);

  await dismissLeftoverToolbar(appPage);
});

/** Focuses the chapter frame and hands it back. */
async function afterFocus(page: Page): Promise<Frame> {
  const frame = await waitForChapterFrame(page);
  await frame.evaluate(() => window.focus());
  await page.waitForTimeout(200);
  return frame;
}

/**
 * The same press with the frame NOT focused.
 *
 * The complement of the test above, and the reason the defect stayed hidden: a
 * programmatic selection never focuses the chapter, so this is the state every
 * earlier spec measured. It must keep passing — a fix that only worked because
 * the frame stopped being focusable would show up here as a selection menu that
 * no longer appears at all.
 */
test('the same press with the frame NOT focused still applies the colour', async ({ appPage }) => {
  test.setTimeout(300_000);

  const bookId = await openBookOrSkip(appPage);
  if (bookId === null) return;

  await appPage.setViewportSize(WIDE_VIEWPORT);
  await appPage.waitForTimeout(1_200);
  await installTrace(appPage);

  const selected = await raiseToolbar(appPage);
  if (selected === null) {
    test.skip(true, 'The toolbar could not be raised on a real chapter selection.');
    return;
  }

  const layout = await readLayout(appPage);
  const point = await colorPressPoint(appPage);
  expect(point, `the colour control had no pressable node in the ${layout} layout`).not.toBeNull();

  const before = await highlightIds(appPage, bookId);
  await resetTrace(appPage);
  await realPress(appPage, point!);
  await appPage.waitForTimeout(2_500);

  const trace = await readTrace(appPage);
  const after = await highlightIds(appPage, bookId);
  const added = [...after].filter((id) => !before.has(id));

  for (const id of added) await deleteHighlight(appPage, id).catch(() => undefined);

  expect(
    added.length,
    `the first real press on the colour at (${JSON.stringify(point)}) applied no highlight with ` +
      `the frame unfocused. Trace:\n${formatTrace(trace)}`,
  ).toBeGreaterThan(0);

  await dismissLeftoverToolbar(appPage);
});
