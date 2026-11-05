/**
 * The highlight context menu, driven the way a hand drives it over the real app.
 *
 * ## The two halves of the reported defect, and what this spec measures
 *
 * The reader reported "cuando selecciona un resultado ya con color, el menú
 * aparece por un mini segundo y luego desaparece", after having reported "salen
 * como los dos menús y luego se cierra y no sale ninguno". Two separate failures
 * live inside that sentence, and they need different evidence:
 *
 *   - **A — the menu is dismissed by the press itself.** `ReaderWorkspace`
 *     listens on `window` in the CAPTURE phase and dismisses both menus unless
 *     the press landed in `.selection-toolbar`. The highlight menu is not in that
 *     list, so pressing ANY of its buttons closes it on the `pointerdown`, and
 *     the `click` the button was waiting for has nothing left to land on. The
 *     `onclick={(e) => e.stopPropagation()}` inside the menu cannot prevent it:
 *     it is bubble-phase `click`, this is capture-phase `pointerdown`, and the
 *     capture listener always runs first.
 *   - **B — the menu opens and something closes it a moment later.** A frame
 *     gesture that ends on a highlight collapses the frame's own selection; the
 *     `selectionchange` handler debounces 200 ms and then posts a clear, which
 *     the parent reads as "the selection ended" and dismisses the menu with —
 *     even though the frame collapsed the selection itself, in order to raise
 *     that menu.
 *
 * Half A is asserted here as a CONTRACT: one real press on one real button of the
 * menu, and the action must have happened — the backend is the authority, so
 * Delete is the oracle (the row is gone or it is not; a screenshot proves
 * nothing about a database).
 *
 * Half B is MEASURED here before it is asserted: the recorder in
 * `harness/readerTrace.ts` logs every input, every raw frame message, every
 * `dismissToolbar` with its caller, and samples how many menus are on screen
 * every 50 ms. A menu that appears and disappears is then a sequence with
 * timestamps rather than a symptom, and the closing reason names itself.
 *
 * ## Why the presses are real
 *
 * A synthesised `click` skips the browser's hit test AND does not move focus,
 * and the defect is in the capture-phase pointer handling both menus share. The
 * menu is rendered by `ReaderWorkspace` into the PARENT document, so `page.mouse`
 * at the button's own centre is delivered to it — that is exactly the gesture the
 * reader makes and the one that finds this class of defect.
 *
 * ## Local only
 *
 * Needs the real Tauri app under `bun run tauri:dev:debug` (Windows only). Not a
 * CI gate.
 *
 *   bun run tauri:dev:debug                        # terminal 1
 *   bun run test:e2e:cdp -- readerHighlightMenu    # terminal 2
 */
import { expect, test } from './harness/connectApp';
import {
  deleteHighlight,
  highlightIds,
  openBook,
  readLibraryRows,
  waitForChapterFrame,
  type LibraryRow,
} from './harness/readerFlows';
import { scrollToChapterProse, selectTextInChapter } from './harness/readerSelection';
import {
  formatTrace,
  installTrace,
  readTrace,
  resetTrace,
  startMenuSampler,
  stopMenuSampler,
} from './harness/readerTrace';
import type { Frame, Page } from '@playwright/test';
import { PROSE_FLOOR, settledChapterTextLen } from './harness/readerCapacity';

const TOOLBAR = '.selection-toolbar';
const HIGHLIGHT_MENU = '[data-testid="highlight-context-menu"]';

/** The menu's own controls, by accessible name across both shipped locales. */
const MENU_ACTIONS = {
  delete: /^(Delete highlight|Eliminar resaltado)$/,
  copy: /^(Copy|Copiar)$/,
  changeColor: /^(Change color|Cambiar color)$/,
  tag: /^(Tag|Etiqueta)$/,
  note: /^(Note|Nota)$/,
} as const;

/**
 * Leaves neither floating menu on screen.
 *
 * Not just tidiness: the highlight menu renders a `fixed inset-0 z-[99]`
 * backdrop, the toolbar is `fixed z-50` over the top bar, and EITHER one
 * intercepts the next click aimed at the reader's own chrome — measured in this
 * suite as a click that retried for the whole test budget instead of failing.
 */
async function dismissOpenMenus(page: Page): Promise<void> {
  if (
    (await page.locator(TOOLBAR).count()) === 0 &&
    (await page.locator(HIGHLIGHT_MENU).count()) === 0
  ) {
    return;
  }
  await page.keyboard.press('Escape');
  await expect(page.locator(TOOLBAR))
    .toBeHidden({ timeout: 5_000 })
    .catch(() => undefined);
  await expect(page.locator(HIGHLIGHT_MENU))
    .toBeHidden({ timeout: 5_000 })
    .catch(() => undefined);
}

/** The selection toolbar's colour control, in both of its rendered forms. */
const COLOR_NAME = /select|seleccionar resaltado|change color|cambiar color/i;

/**
 * A book this harness knows renders prose, chosen from the REAL library rows
 * over IPC and opened through the reader's own UI.
 *
 * Deliberately NOT `openUsableEpub`: that one walks the whole library — one open
 * plus one prose sweep per candidate, measured at 3-8 s an open over seven
 * candidates with a sweep of up to 24 chapters at 6 s each behind it. Paid inside
 * a spec it blew a 900 s budget before the spec's own first line ran and left the
 * app sitting on the library screen. The titles below are the ones
 * `readerCapacity.ts` measured as carrying prose (its own table); a library
 * holding none of them SKIPS with a reason instead of failing for the wrong one.
 */
const PROSE_BOOK = /Quijote|Freakonomics|Armas|Prisioneros|Colombia/i;

async function openProseBook(page: Page): Promise<string> {
  const rows = await readLibraryRows(page);
  const book = rows.find(
    (candidate) =>
      (candidate.format ?? '').toLowerCase() === 'epub' &&
      (candidate.filePath ?? '').length > 0 &&
      PROSE_BOOK.test(candidate.title),
  );
  console.log(
    `[highlight-menu] library rows: ${JSON.stringify(
      rows.map((r) => ({
        t: r.title.slice(0, 28),
        f: r.format,
        local: (r.filePath ?? '').length > 0,
      })),
    )}`,
  );

  test.skip(
    book === undefined,
    `No library EPUB matches ${PROSE_BOOK} with a local file. This spec needs a book whose ` +
      `chapters render prose, and a book with none cannot serve it.`,
  );

  // A toolbar left up by a previous run is `fixed z-50` over the top bar, and
  // the highlight menu additionally renders a `fixed inset-0 z-[99]` backdrop —
  // either one intercepts the click that leaves the reader.
  await dismissOpenMenus(page);

  const row = book as LibraryRow;
  console.log(`[highlight-menu] opening "${row.title}" (${row.id}) at ${page.url()}`);
  await openBook(page, row);

  // `settledChapterTextLen`, not a single read: the reader replaces the chapter
  // `srcdoc` as assets arrive, so a document is briefly EMPTY before it is
  // refilled — and one sample of it reads "this chapter has no text" for a
  // chapter that is about to have plenty. That is exactly how this spec skipped
  // two tests in a row before the reading was settled.
  const textLen = await settledChapterTextLen(page, 12_000);
  console.log(`[highlight-menu] chapter text after settle: ${textLen}`);
  test.skip(
    textLen < PROSE_FLOOR,
    `"${row.title}" rendered ${textLen} characters of text in the chapter it opened on, so there ` +
      `is nothing here to select or highlight.`,
  );

  return row.id;
}

/** How many floating menus are on screen right now, counted in the parent. */
async function menusOnScreen(page: Page): Promise<{ toolbar: number; highlight: number }> {
  return {
    toolbar: await page.locator(TOOLBAR).count(),
    highlight: await page.locator(HIGHLIGHT_MENU).count(),
  };
}

/**
 * A point on an existing highlight, in frame coordinates, or null when none is
 * on screen.
 *
 * Highlights are painted with the CSS Custom Highlight API — `Range`s, not DOM
 * elements — so there is no element to measure. The overlay's own `hitTest(x, y)`
 * is the same test the frame's click handler runs before posting
 * `epub-highlight-click`, so a point it accepts is exactly a point where a
 * person's caret lands on a highlight.
 *
 * `wantId` restricts the scan to ONE highlight, and that is not a convenience:
 * a chapter can hold highlights this spec did not create, and the scanner finds
 * whichever comes first in reading order. A press aimed at the wrong one deletes
 * the developer's own annotation — a harness that damages the library it measures
 * is worse than one that skips — and makes the id-keyed assertion read the wrong
 * row. So the caller passes the id it is about to assert on.
 */
async function firstHighlightPoint(
  frame: Frame,
  wantId?: string,
): Promise<{ x: number; y: number; id: string } | null> {
  return frame
    .evaluate((only) => {
      const overlay = (
        globalThis as unknown as {
          __epubHighlightOverlay?: {
            hitTest?: (x: number, y: number) => { id: string } | null;
          };
        }
      ).__epubHighlightOverlay;
      const frameEl = (globalThis as unknown as { frameElement: Element | null }).frameElement;
      if (!overlay?.hitTest || !frameEl) return null;

      const parentDoc = (globalThis as unknown as { parent: { document: Document } }).parent
        .document;
      const frameRect = frameEl.getBoundingClientRect();
      const scroller = parentDoc.querySelector('[data-testid="epub-scroll-container"]');
      const box = scroller
        ? scroller.getBoundingClientRect()
        : { top: 0, bottom: window.innerHeight };
      const bandTop = Math.max(0, box.top - frameRect.top);
      const bandBottom = Math.max(0, box.bottom - frameRect.top);

      const STEP = 12;
      for (let y = bandTop + STEP; y < bandBottom; y += STEP) {
        for (let x = STEP; x < window.innerWidth; x += STEP) {
          const hit = overlay.hitTest(x, y);
          if (!hit) continue;
          if (only && hit.id !== only) continue;
          return { x, y, id: hit.id };
        }
      }
      return null;
    }, wantId ?? null)
    .catch(() => null);
}

/**
 * Clicks an existing highlight, the way the reported gesture ends, and reports
 * whether the highlight menu came up.
 *
 * The click is composed INSIDE the frame rather than driven with `page.mouse`:
 * a real press inside a same-origin `about:srcdoc` chapter is not delivered to
 * the frame's document over WebView2's CDP (measured, and documented in
 * `readerSelection.ts`), so the frame's own handler would never see it — a
 * harness artifact, not a finding. The sequence dispatched is the one the handler
 * listens for, at a REAL coordinate, because it runs
 * `caretRangeFromPoint(clientX, clientY)` on the event.
 *
 * ## `clearSelectionFirst` is the difference between two different gestures
 *
 * A real caret landing on a highlight happens WITH A LIVE SELECTION: the click's
 * own handler is what collapses it (`selHit.removeAllRanges()`), immediately
 * before posting `epub-highlight-click`. Clearing the selection first — which is
 * what a spec does to make its own life easier — moves that collapse *earlier*
 * and silently hides the half of the defect that follows it. So the default is
 * NOT to clear, and a caller that wants a bare caret click asks for it.
 */
async function clickHighlight(
  page: Page,
  attempts = 4,
  options: { clearSelectionFirst?: boolean; highlightId?: string } = {},
): Promise<{ clicked: boolean; lastPoint: { x: number; y: number; id: string } | null }> {
  const clearFirst = options.clearSelectionFirst ?? false;
  let lastPoint: { x: number; y: number; id: string } | null = null;

  for (let i = 0; i < attempts; i++) {
    const frame = await waitForChapterFrame(page);
    if (clearFirst) {
      await frame.evaluate(() => document.getSelection()?.removeAllRanges());
      await page.waitForTimeout(300);
    }

    // `firstHighlightPoint` scans the VISIBLE band on purpose — a point outside it
    // is not a point a person could press — and a highlight can sit outside that
    // band at the reader's restored location (measured: a highlight that is
    // registered and painted, with the scan finding nothing until the prose is
    // brought into view). Bringing it into view is what a reader does.
    await scrollToChapterProse(frame, page);

    // The preferred highlight is tried first, so a press can only ever act on the
    // row this run knows about; the later attempts fall back to whatever is on
    // screen, which is what keeps the case from skipping on a chapter whose
    // freshly created highlight the overlay has not painted.
    const point = await firstHighlightPoint(frame, i < 2 ? options.highlightId : undefined);
    if (point === null) {
      await page.waitForTimeout(600);
      continue;
    }
    lastPoint = point;

    await frame.evaluate(
      ([fx, fy]) => {
        for (const type of ['mousedown', 'mouseup', 'click'] as const) {
          document.dispatchEvent(new MouseEvent(type, { bubbles: true, clientX: fx, clientY: fy }));
        }
      },
      [point.x, point.y] as const,
    );

    await page.waitForTimeout(900);
    if ((await menusOnScreen(page)).highlight === 1) return { clicked: true, lastPoint };
  }

  return { clicked: false, lastPoint };
}

/**
 * Selects a run and colours it through the toolbar's own swatch, so a highlight
 * exists for the gestures under test. Returns the id the backend added, or null.
 *
 * The colour is applied through the real write path (`handleColorSelect` → the
 * store → the outbox), not a direct `saveHighlight`: only the app's own path
 * leaves a highlight the overlay has registered.
 *
 * The swatch is pressed with a real pointer, in whichever form the layout offers
 * — five circles, or one trigger that opens the palette. A narrow window folds
 * the palette, and reaching into a closed popover would time out instead of
 * failing.
 *
 * `bookId` is passed in rather than resolved here, so the spec resolves the book
 * ONCE and the id it asserts against is the id whose chapter is on screen.
 */
async function createHighlightOnScreen(page: Page, bookId: string): Promise<string | null> {
  const before = await highlightIds(page, bookId);

  // The book opens on its stored location, which can be the bottom of a chapter:
  // the visible band then holds no text node and there is nothing to select. A
  // person scrolls back to the prose, so the second attempt does too.
  const toolbar = page.locator(TOOLBAR);
  let raised = false;
  for (const scrollIntoView of [false, true]) {
    if (scrollIntoView) await scrollToChapterProse(await waitForChapterFrame(page), page);
    const frame = await waitForChapterFrame(page);
    const selected = await selectTextInChapter(frame);
    console.log(
      `[highlight-menu] select attempt (scroll=${scrollIntoView}): ` +
        `text=${JSON.stringify((selected ?? '').slice(0, 30))} toolbar=${await toolbar.count()}`,
    );
    if (selected === null || selected.trim().length === 0) continue;
    if (await toolbar.isVisible({ timeout: 8_000 }).catch(() => false)) {
      raised = true;
      break;
    }
  }
  if (!raised) return null;
  const opener = toolbar.locator('button[aria-expanded]').first();
  const openerCount = await opener.count();
  if (openerCount > 0) {
    await opener.click({ timeout: 5_000 });
    await page.waitForTimeout(300);
  }
  const swatch = (
    openerCount > 0
      ? toolbar.locator('[role="group"]').getByRole('button', { name: COLOR_NAME })
      : toolbar.getByRole('button', { name: COLOR_NAME })
  ).first();
  const swatchCount = await swatch.count();
  console.log(
    `[highlight-menu] toolbar: folded=${openerCount > 0} swatches=${swatchCount} ` +
      `canonical=${await toolbar.getByRole('button', { name: /amarillo|yellow/i }).count()}`,
  );
  if (swatchCount === 0) return null;

  const box = await swatch.boundingBox({ timeout: 5_000 }).catch(() => null);
  console.log(`[highlight-menu] swatch box: ${JSON.stringify(box)}`);
  if (!box || box.width === 0) return null;
  await page.mouse.move(box.x + box.width / 2, box.y + box.height / 2);
  await page.mouse.down();
  await page.mouse.up();

  let added: string | null = null;
  try {
    await expect
      .poll(
        async () => {
          const after = await highlightIds(page, bookId);
          added = [...after].find((id) => !before.has(id)) ?? null;
          return added;
        },
        { timeout: 15_000 },
      )
      .not.toBeNull();
  } catch (err) {
    console.log(`[highlight-menu] colour press wrote nothing: ${String(err).slice(0, 300)}`);
    return null;
  }
  return added;
}

/**
 * Presses a button of the highlight menu ONCE with a real pointer and returns
 * the point pressed, or null when the control could not be resolved.
 *
 * Deliberately not `locator.click()`: the defect is in the capture-phase
 * `pointerdown` the app listens for on `window`, and only a real press runs the
 * browser's own hit test, its own focus change and its own `click` after it.
 */
async function pressMenuOnce(page: Page, name: RegExp): Promise<{ x: number; y: number } | null> {
  const button = page.locator(HIGHLIGHT_MENU).getByRole('button', { name }).first();
  if ((await button.count()) === 0) return null;

  const box = await button
    .boundingBox({ timeout: 3_000 })
    .then((b) => (b && b.width > 0 && b.height > 0 ? b : null))
    .catch(() => null);
  if (!box) return null;

  const x = box.x + box.width / 2;
  const y = box.y + box.height / 2;
  await page.mouse.move(x, y);
  await page.mouse.down();
  await page.mouse.up();
  return { x, y };
}

// ─── A. One real press, one performed action ─────────────────────────────────

/**
 * THE contract of the highlight menu as an interaction: a press on one of its
 * buttons performs that button's action.
 *
 * ## Why Note, and not Delete
 *
 * The action has to be observable, and it must not damage the library this spec
 * runs against. Delete is the sharpest observable there is — the row is gone or
 * it is not, and the database is the authority — but it can only be aimed at a
 * row this run created, and a chapter can hold highlights this run did not
 * (measured on the developer's copy: NINE registered in one band). Aiming a
 * destructive press at "whatever is under the caret" deletes a person's
 * annotation; failing to aim it at the right one makes the assertion read the
 * wrong row. So the observable here is the Note button's own outcome — the note
 * dialog — which is a first-press action, is unambiguous, and writes nothing.
 *
 * ## The press is the whole point
 *
 * Before the guard exempted this menu, the press was DELIVERED — the trace below
 * shows `input:pointerdown` inside the menu — and did nothing: the workspace's
 * capture-phase dismissal unmounted the button between the `pointerdown` and the
 * `click`, and the `click` landed on the backdrop behind it. The DOM cannot tell
 * that apart from "the handler is broken"; the dialog appearing can.
 */
test('ONE real press on a highlight-menu action performs it', async ({ appPage }) => {
  // Generous, because this spec opens a real book through the reader's own UI
  // and drives a real chapter: measured at ~25 s of work once the book is open,
  // but a cold library (a file dialog, a cover rebuild) adds to it. A 300 s
  // ceiling was measured to expire before the test's own first line ran.
  test.setTimeout(900_000);

  const bookId = await openProseBook(appPage);

  await installTrace(appPage);

  // A highlight for this run to interact with, through the app's own colour
  // path. Not every chapter renders one back (measured: a newly created row can
  // be absent from the overlay's hits in the band), which is why this is a
  // preference for the click below rather than a requirement of the case.
  const created = await createHighlightOnScreen(appPage, bookId);

  // The toolbar leaves after its 220 ms exit, and the overlay re-registers the
  // new highlight when the write lands. Both must have happened before the
  // highlight can be clicked at all.
  await expect(appPage.locator(TOOLBAR)).toBeHidden({ timeout: 5_000 });
  await appPage.waitForTimeout(1_500);

  // A BARE caret click: no selection is live, so the only gesture in flight is
  // the click that raises the menu. The reported pose (selection live over the
  // highlight) is the other test's business — there the menu has to survive the
  // collapse the click itself causes.
  const opened = await clickHighlight(appPage, 6, {
    clearSelectionFirst: true,
    highlightId: created ?? undefined,
  });
  if (!opened.clicked) {
    if (created) await deleteHighlight(appPage, created).catch(() => undefined);
    test.skip(
      true,
      `A click on a highlight did not raise the highlight menu within the retry budget ` +
        `(last point ${JSON.stringify(opened.lastPoint)}). This chapter could not be driven ` +
        `into a highlight click.`,
    );
    return;
  }

  await resetTrace(appPage);
  await startMenuSampler(appPage, 50);

  const point = await pressMenuOnce(appPage, MENU_ACTIONS.note);

  // The dialog mounts in the same task the click's handler runs in, and the menu
  // leaves behind a 100 ms exit transition.
  await appPage.waitForTimeout(2_000);
  await stopMenuSampler(appPage);

  const trace = await readTrace(appPage);
  const reasons = trace.filter((e) => e.kind === 'dismissToolbar').map((e) => e.detail);
  const dialogs = await appPage.locator('[data-dialog-content][data-state="open"]').count();

  console.log(
    `\n[highlight-menu] press on Note: pressed=${point !== null} ` +
      `point=${JSON.stringify(point)} actedOn=${opened.lastPoint?.id ?? '(unknown)'} ` +
      `created=${created ?? '(none)'} dialogs=${dialogs}\n` +
      `[highlight-menu] dismissals: ${JSON.stringify(reasons)}\n` +
      `[highlight-menu] trace:\n${formatTrace(trace)}\n`,
  );

  expect(
    point,
    'the Note control of the highlight menu never resolved to a pressable point, so no real ' +
      'press could be aimed at it.',
  ).not.toBeNull();

  // The guard's own dismissal is the defect's signature: it runs on the
  // `pointerdown` of the press, before the button's `click`, and the trace above
  // shows the menu unmounting between them.
  expect(
    reasons.join(' | '),
    `the press on the menu's Note button dismissed a menu with ` +
      `${JSON.stringify(reasons)}. A \`workspace-pointerdown\` in this list means the workspace's ` +
      `capture-phase guard ran on the press INSIDE the menu — the defect this case exists for.`,
  ).not.toContain('workspace-pointerdown');

  // The action's own outcome, on ONE press: the note dialog is up.
  expect(
    dialogs,
    `ONE real press at (${JSON.stringify(point)}) on the highlight menu's Note button left no ` +
      `note dialog on screen. The press was DELIVERED — see the trace above — so this is "the ` +
      `press did something and the action did not happen": the menu was dismissed under it and ` +
      `the click landed behind it. Dismissals seen: ${JSON.stringify(reasons)}.`,
  ).toBeGreaterThan(0);

  await appPage.keyboard.press('Escape');
  await dismissOpenMenus(appPage);
  if (created) await deleteHighlight(appPage, created).catch(() => undefined);
});

// ─── B. The gesture that opens the menu, measured end to end ─────────────────

/**
 * The reported gesture, measured: a selection that CONTAINS a highlight, and
 * then a caret that lands on it.
 *
 * ## What is asserted, and what is only reported
 *
 * The assertion is the invariant the single `OpenMenu` state exists for: after
 * the gesture settles, exactly ONE menu is on screen — never two, and never none.
 * Everything else this test prints is the measurement that says WHY: the input
 * sequence, every raw frame message, every dismissal with its caller, and a 50 ms
 * sample of how many menus were up.
 *
 * A menu that appears for a moment and disappears reads here as
 * `menus toolbar=? highlight=1` … `menus toolbar=0 highlight=0` with a
 * `dismissToolbar` line between them naming the path that ran.
 */
test('a click on a highlight leaves exactly one menu standing', async ({ appPage }) => {
  test.setTimeout(900_000);

  await openProseBook(appPage);

  await installTrace(appPage);

  try {
    // ── The pose: a selection that overlaps the highlight ──────────────────
    // `selectAcrossHighlight`-style: the run is built inside the single text node
    // that owns coloured characters, so the overlap is a fact of the DOM rather
    // than a hope about geometry, and the second reading (`hits`) confirms it
    // with the overlay's own `hitTest` — a highlight under the selection's own
    // boxes.
    //
    // Prose first: the pose and the click below both scan the VISIBLE band, and
    // a highlight can sit outside it at the reader's restored location.
    await scrollToChapterProse(await waitForChapterFrame(appPage), appPage);
    const frame = await waitForChapterFrame(appPage);
    const pose = await frame.evaluate(() => {
      const overlay = (
        globalThis as unknown as {
          __epubHighlightOverlay?: {
            hitTest?: (x: number, y: number) => { id: string } | null;
            rangeOverlapsHighlight?: (r: Range) => boolean;
          };
        }
      ).__epubHighlightOverlay;
      const doc = document as Document & {
        caretRangeFromPoint?: (x: number, y: number) => Range | null;
      };
      if (!overlay?.hitTest || !doc.caretRangeFromPoint) return null;

      const frameEl = (globalThis as unknown as { frameElement: Element | null }).frameElement;
      if (!frameEl) return null;
      const frameRect = frameEl.getBoundingClientRect();
      const parentDoc = (globalThis as unknown as { parent: { document: Document } }).parent
        .document;
      const scroller = parentDoc.querySelector('[data-testid="epub-scroll-container"]');
      const box = scroller
        ? scroller.getBoundingClientRect()
        : { top: 0, bottom: window.innerHeight };
      const bandTop = Math.max(0, box.top - frameRect.top);
      const bandBottom = Math.max(0, box.bottom - frameRect.top);

      const hits: Array<{ node: Text; index: number }> = [];
      const STEP = 8;
      for (let y = bandTop + STEP; y < bandBottom; y += STEP) {
        for (let x = STEP; x < window.innerWidth; x += STEP) {
          const caret = doc.caretRangeFromPoint(x, y);
          if (!caret || caret.startContainer.nodeType !== Node.TEXT_NODE) continue;
          const hit = overlay.hitTest(x, y);
          if (!hit) continue;
          hits.push({ node: caret.startContainer as Text, index: caret.startOffset });
        }
      }
      if (hits.length === 0) return null;

      const byNode = new Map<Text, number[]>();
      for (const hit of hits) {
        const list = byNode.get(hit.node) ?? [];
        list.push(hit.index);
        byNode.set(hit.node, list);
      }
      let best: { node: Text; lo: number; hi: number } | null = null;
      for (const [node, indexes] of byNode) {
        const lo = Math.min(...indexes);
        const hi = Math.max(...indexes);
        if (!best || hi - lo > best.hi - best.lo) best = { node, lo, hi };
      }
      if (!best) return null;

      // One character of slack on each side: a run that strictly CONTAINS the
      // coloured characters, rather than merely abutting them.
      const len = (best.node.nodeValue ?? '').length;
      const range = document.createRange();
      range.setStart(best.node, Math.max(0, best.lo - 1));
      range.setEnd(best.node, Math.min(len, best.hi + 2));

      const selection = document.getSelection();
      if (!selection) return null;
      selection.removeAllRanges();
      selection.addRange(range);
      document.dispatchEvent(new MouseEvent('mouseup', { bubbles: true }));

      // Two independent readings of the pose, because they disagree: `hits`
      // walks the selection's own client rects and hit-tests each one, while
      // `overlaps` is the predicate the frame uses to decide whether to suppress
      // its toolbar. A run whose boxes cover coloured text while the predicate
      // says "no overlap" is the measurement this test exists to print.
      const rects = Array.from(range.getClientRects()).filter((r) => r.width > 1);
      const covering = rects.filter((rect) => {
        for (let y = rect.top + 2; y < rect.bottom; y += 4) {
          for (let x = rect.left + 2; x < rect.right; x += 4) {
            if (overlay.hitTest?.(x, y)) return true;
          }
        }
        return false;
      }).length;

      return {
        text: range.toString().slice(0, 60),
        rects: rects.length,
        hits: covering,
        overlaps: overlay.rangeOverlapsHighlight?.(range) ?? null,
      };
    });

    if (pose === null || pose.hits === 0) {
      test.skip(
        true,
        'No selection covering a registered highlight could be posed in this chapter, so the ' +
          'reported gesture cannot be driven here.',
      );
      return;
    }

    await resetTrace(appPage);
    await startMenuSampler(appPage, 50);
    await appPage.waitForTimeout(1_200);

    const afterSelection = await menusOnScreen(appPage);

    // ── The click whose caret lands on the highlight ───────────────────────
    // With the selection still LIVE, which is the reported gesture: the frame's
    // own click handler is what collapses it, right before it posts
    // `epub-highlight-click`. A caller that clears the selection first moves that
    // collapse earlier and hides the defect that follows it.
    const live = await waitForChapterFrame(appPage);
    await scrollToChapterProse(live, appPage);
    const point = await firstHighlightPoint(live);
    let opened = { clicked: false, lastPoint: point };
    if (point !== null) {
      await live.evaluate(
        ([fx, fy]) => {
          for (const type of ['mousedown', 'mouseup', 'click'] as const) {
            document.dispatchEvent(
              new MouseEvent(type, { bubbles: true, clientX: fx, clientY: fy }),
            );
          }
        },
        [point.x, point.y] as const,
      );
      await appPage.waitForTimeout(1_800);
      opened = { clicked: (await menusOnScreen(appPage)).highlight === 1, lastPoint: point };
    }
    await stopMenuSampler(appPage);

    const settled = await menusOnScreen(appPage);
    const trace = await readTrace(appPage);
    const reasons = trace.filter((e) => e.kind === 'dismissToolbar').map((e) => e.detail);

    console.log(
      `\n[highlight-menu] pose=${JSON.stringify(pose)}\n` +
        `[highlight-menu] after selection: toolbar=${afterSelection.toolbar} ` +
        `highlight=${afterSelection.highlight}\n` +
        `[highlight-menu] after click: clicked=${opened.clicked} toolbar=${settled.toolbar} ` +
        `highlight=${settled.highlight}\n` +
        `[highlight-menu] dismissals: ${JSON.stringify(reasons)}\n` +
        `[highlight-menu] trace:\n${formatTrace(trace)}\n`,
    );

    expect(
      settled.toolbar + settled.highlight,
      `the gesture the report names — a selection over a coloured run, then a caret that lands ` +
        `on it — settled with ${settled.toolbar} selection toolbar(s) and ${settled.highlight} ` +
        `highlight menu(s) on screen. Two menus at once is the reported "salen como los dos ` +
        `menús"; ZERO is the "y no sale ninguno" that followed it. The dismissals that ran were ` +
        `${JSON.stringify(reasons)}; the trace above has the ordered sequence.`,
    ).toBe(1);
  } finally {
    await stopMenuSampler(appPage);
    await dismissOpenMenus(appPage);
    await appPage.waitForTimeout(300);
  }
});
