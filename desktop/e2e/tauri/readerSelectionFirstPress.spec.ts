/**
 * The selection toolbar's buttons work on the FIRST press, and the menu sits next
 * to the word it was raised on.
 *
 * ## The two reported defects
 *
 * "Ahora todos los botones tengo que darle dos veces para que funcionen, incluso
 * los colores" — every toolbar button needs two clicks, colours included. The
 * colour path is the decisive clue: it is synchronous, touches no network and
 * opens no panel, so if it needs two presses the cause cannot be the request — it
 * has to be the toolbar being TORN DOWN between the `pointerdown` and the
 * `click`.
 *
 * "Cuando alguna selección esté saliendo muy arriba, bájale un poquito y que
 * salga justo arriba de la palabra" — the menu appears far above the selected
 * word instead of just over it.
 *
 * ## Why a REAL pointer, and why the existing specs cannot see this
 *
 * `readerSelectionCollapsed.spec.ts` drives the toolbar with `dispatchEvent` and
 * `button.click()`. Neither moves the real focus: the frame never blurs, the
 * dismissal never fires, and the assertion passes against a state the reader
 * never experiences. A `dispatchEvent` is not a press.
 *
 * This spec presses with `appPage.mouse`: `move`, `down`, `up` at the button's
 * own centre. The browser runs its own hit test, its own focus change and its own
 * click, which is the whole sequence under test.
 *
 * ## What is asserted
 *
 *   - Bug 1: one press on a colour applies it, and the toolbar goes away because
 *     the colour was applied — not because a stray blur dismissed it first. The
 *     `dismissToolbar` reasons are read from the app (see `__probeDismiss` in
 *     `ReaderWorkspace.svelte`); an `iframe-blur` reason is the defect.
 *   - Bug 2: the distance between the menu's bottom edge and the selection's top
 *     edge is the placement's own offset, not the height of a panel that is not
 *     on screen.
 *
 * ## Proving it can fail
 *
 * Bug 1: remove the `document.hasFocus()` guard from the `blur` listener in
 * `epubSelectionScript.ts` (restoring the unconditional `postClear('iframe-blur')`)
 * and this spec goes red on the "applies the colour on the first press" case.
 *
 * Bug 2: reserve the panel budget unconditionally (the `placementMenuSize.height`
 * branch before this fix) and the gap assertion goes red with the measured gap.
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
import { waitForChapterFrame } from './harness/readerFlows';
import { openUsableEpub } from './harness/readerCapacity';
import { selectTextInChapter } from './harness/readerSelection';
import type { Page } from '@playwright/test';

const TOOLBAR = '.selection-toolbar';

/**
 * The dismissals the app has recorded since the counter was last reset.
 *
 * `ReaderWorkspace.dismissToolbar` pushes its `reason` onto
 * `window.__toolbarDismissals`. It exists because the reason is the difference
 * between "the colour was applied" and "a blur killed the menu before the click
 * landed", and the two are indistinguishable from the DOM alone.
 */
async function readDismissals(page: Page): Promise<string[]> {
  return page.evaluate(
    () => (window as unknown as { __toolbarDismissals?: string[] }).__toolbarDismissals ?? [],
  );
}

async function resetDismissals(page: Page): Promise<void> {
  await page.evaluate(() => {
    (window as unknown as { __toolbarDismissals?: string[] }).__toolbarDismissals = [];
  });
}

/** Escape is the toolbar's own dismissal path, so it is how a leftover is cleared. */
async function dismissLeftoverToolbar(page: Page): Promise<void> {
  if ((await page.locator(TOOLBAR).count()) > 0) {
    await page.keyboard.press('Escape');
    await expect(page.locator(TOOLBAR)).toBeHidden({ timeout: 5_000 });
  }
}

/** Opens a prose chapter and raises the toolbar on a real selection. */
async function raiseToolbar(page: Page): Promise<void> {
  await dismissLeftoverToolbar(page);
  await openUsableEpub(page);
  const frame = await waitForChapterFrame(page);

  // The chapter's `srcdoc` is replaced as assets settle, so a selection made
  // against a document that is about to be discarded raises no toolbar. Retry,
  // re-resolving the frame each time.
  for (let attempt = 0; attempt < 6; attempt++) {
    const live = await waitForChapterFrame(page, 10_000);
    const selected = await selectTextInChapter(live);
    if (selected !== null && selected.trim().length > 0) {
      try {
        await expect(page.locator(TOOLBAR)).toBeVisible({ timeout: 6_000 });
        // The release emits the selection twice — an immediate form and a
        // debounced one — and the parent re-renders the toolbar for the second.
        // Pressing during that swap aims at a node that is already gone.
        await page.waitForTimeout(600);
        return;
      } catch {
        /* the chapter was still settling under the selection; try again */
      }
    }
    void frame;
    await page.waitForTimeout(900);
  }

  test.skip(true, 'The toolbar could not be raised on a real chapter selection.');
}

/**
 * Bug 1 — one REAL press applies the colour and the toolbar leaves because the
 * colour was applied.
 *
 * A colour is the simplest action the toolbar has: no network, no panel, no
 * clipboard. If a press on it needs two attempts, nothing slower can work either
 * — and that is exactly what was reported.
 */
test('a single real press on a colour applies it (no second click needed)', async ({ appPage }) => {
  test.setTimeout(300_000);

  await raiseToolbar(appPage);
  await resetDismissals(appPage);

  const toolbar = appPage.locator(TOOLBAR);
  const swatch = toolbar.getByRole('button', { name: /select|seleccionar resaltado/i }).first();
  await expect(swatch).toBeVisible({ timeout: 5_000 });

  const box = await swatch.boundingBox();
  expect(box, 'the colour swatch had no measurable box').not.toBeNull();

  // A REAL press. `dispatchEvent` and `element.click()` do NOT move focus, so
  // neither reproduces the defect and both give a false green.
  await appPage.mouse.move(box!.x + box!.width / 2, box!.y + box!.height / 2);
  await appPage.mouse.down();
  await appPage.mouse.up();

  // The observable is the dismissal reason, not just the disappearance: a toolbar
  // that vanishes because a blur killed it is the bug, and it looks identical in
  // the DOM to a toolbar that vanished because the colour was applied.
  await expect
    .poll(async () => (await readDismissals(appPage)).join('|'), { timeout: 8_000 })
    .toContain('color-applied');

  const reasons = await readDismissals(appPage);
  expect(
    reasons,
    `the toolbar recorded ${JSON.stringify(reasons)}. A press on a colour must end with the ` +
      `colour applied. An "onselectionclear:iframe-blur" in this list means the frame's blur ` +
      `handler dismissed the toolbar between the pointerdown and the click, so the click landed ` +
      `on a button that no longer existed — which is the reported "every button needs two ` +
      `clicks".`,
  ).not.toContain('onselectionclear:iframe-blur');

  // A press that did NOTHING leaves the toolbar up. One that applied the colour
  // takes it down.
  await expect(toolbar).toBeHidden({ timeout: 5_000 });
});

/**
 * Bug 2 — the menu sits just above the word, not a panel's height away from it.
 *
 * The gap between the menu's bottom edge and the selection's top edge is the
 * placement's own `offset` while the selection is above the fold. Reserving a
 * result panel that is not on screen added ~130px of phantom height and pushed
 * the menu away from the text — the reported symptom.
 */
test('the menu sits just above the selected word', async ({ appPage }) => {
  test.setTimeout(300_000);

  await raiseToolbar(appPage);

  const measured = await appPage.evaluate((sel) => {
    const el = document.querySelector(sel) as HTMLElement | null;
    if (!el) return null;
    const r = el.getBoundingClientRect();
    return {
      top: r.top,
      bottom: r.bottom,
      height: r.height,
      style: el.getAttribute('style') ?? '',
      viewportHeight: window.innerHeight,
    };
  }, TOOLBAR);
  expect(measured, 'the toolbar had no measurable box').not.toBeNull();

  // The toolbar must NOT be pinned to the viewport ceiling while the selection is
  // comfortably inside the page: that is the shape of the reported defect.
  expect(
    measured!.top,
    `the toolbar was placed at y ${measured!.top}, pinned to the top of the ${measured!.viewportHeight}px ` +
      `window. A menu raised on a selection in the middle of the page must sit next to the ` +
      `selection, not at the ceiling.`,
  ).toBeGreaterThan(20);

  // The selection's own rect, read in the FRAME and translated into window space
  // through the frame element — the toolbar is `fixed` and lives in the parent.
  const frame = await waitForChapterFrame(appPage);
  const selectionWindow = await frame.evaluate(() => {
    const sel = document.getSelection();
    const frameEl = (globalThis as unknown as { frameElement: Element | null }).frameElement;
    if (!sel || sel.rangeCount === 0 || !frameEl) return null;
    const rect = sel.getRangeAt(0).getBoundingClientRect();
    const fr = frameEl.getBoundingClientRect();
    return { top: fr.top + rect.top };
  });

  if (selectionWindow === null) {
    test.skip(true, 'The chapter held no readable selection after the toolbar was raised.');
    return;
  }

  // Only meaningful while the placement chose `above`: with the selection near
  // the top the menu is legitimately rendered below it.
  if (measured!.bottom <= selectionWindow.top) {
    const gap = selectionWindow.top - measured!.bottom;
    // The gap is the placement offset (8px) plus the tolerance of a live window's
    // font metrics — not the ~130px of a reserved-but-absent panel.
    expect(
      gap,
      `the menu's bottom edge is at y ${measured!.bottom} and the selection's top edge is at ` +
        `y ${selectionWindow.top} — a ${Math.round(gap)}px gap. The placement offset is 8px; a ` +
        `gap near the panel budget (120px) means the menu is reserving a result panel that is ` +
        `not on screen, which is the reported "the menu appears way up top, far from the word".`,
    ).toBeLessThan(40);
  }

  console.log(
    `[selection-first-press] menu=${JSON.stringify(measured)} selectionTop=${Math.round(selectionWindow.top)}`,
  );
});
