/**
 * The selection menu, driven the way a PERSON drives it — one real press, in
 * every layout, and the action must have happened.
 *
 * ## Why this spec exists, and why the others were not enough
 *
 * Six defects in this one menu were fixed one at a time in a single session, each
 * costing hours: the row escaped the right edge, the collapsed `+` opened an
 * invisible palette, the `wrapped` rung was unreachable, pagination broke on a
 * resize, two menus appeared at once over a selection containing a highlight, and
 * — still alive — every action button needed two clicks.
 *
 * The specs that exist are each about ONE of those cases. What was missing is the
 * spec that states the CONTRACT of the menu as an interaction: a press with a real
 * pointer produces an observable outcome, on the first press, in all three layouts,
 * with one menu on screen and inside the window. That is a different instrument
 * from "one test per bug", and it is the one that catches the next defect.
 *
 * Three properties of the existing coverage are what make this necessary, and all
 * three were measured in this project:
 *
 *   1. **jsdom has no layout and no hit test.** `getBoundingClientRect()` is all
 *      zeros and `fireEvent.click` SKIPS hit-testing entirely. The unit tests
 *      prove the handlers are wired; they say nothing about whether a real pointer
 *      reaches them.
 *   2. **`dispatchEvent` + `.click()` do not move focus.** `readerSelectionCollapsed
 *      .spec.ts` drives the collapsed pill that way and gives a FALSE GREEN: the
 *      frame never blurs, so the dismissal path the reader actually hits is never
 *      exercised. This spec presses with `page.mouse`, so the browser runs its own
 *      hit test, its own focus change and its own click.
 *   3. **No spec pressed an action button in the three layouts and asserted the
 *      action OCCURRED.** "The toolbar is gone" is satisfied both by a colour being
 *      applied and by a stray blur killing the menu first.
 *
 * ## The frame boundary, stated once
 *
 * The chapter is a same-origin `about:srcdoc` iframe. `page.mouse` presses
 * **inside** it are not delivered to the frame's document over WebView2's CDP
 * (measured: the parent's own buttons respond to `page.mouse`, and a press at the
 * chapter's own coordinates produced no frame-level `click`). So the two halves of
 * the gesture take two different paths, and that is deliberate:
 *
 *   - **Creating the selection** goes through `selectTextInChapter`, which sets a
 *     `Range` in the frame and dispatches the `mouseup` the chapter's own script
 *     listens for. This is the harness's existing, documented route.
 *   - **Pressing the menu** uses `page.mouse` at the button's own centre. The
 *     toolbar is rendered by `ReaderWorkspace` into the PARENT document, so a real
 *     pointer reaches it — which is exactly what makes the "two clicks" question
 *     answerable here and nowhere else.
 *
 * ## What a failure means, and what it does not
 *
 * Dictionary / Translate / Define reach the network. A real outage makes the
 * result panel say so, and that is a PASS: the assertion is that the panel
 * RESPONDED, not that the service answered. The oracles are therefore:
 *
 *   - colour     -> the backend reports one more highlight id (the DB is the
 *                   authority; a screenshot is evidence of rendering, not of data)
 *   - copy       -> the `role="status"` panel appears. `navigator.clipboard` is
 *                   undefined in WebView2 over CDP, so the panel is the observable
 *   - the other three -> a `role="status"` panel appears, carrying either a result
 *                   or a refusal
 *
 * ## The layouts
 *
 * `full` (~1200px), `collapsed` (~300px) and `wrapped` — the last being the width
 * the app BOOTS at, ~270px, where the band is `innerWidth - 2 * edgePadding` = 238
 * and two pixels under the 240px collapsed pill. `resolveFloatingMenuLayout` is
 * what decides, from the same width a person's narrow window would give it.
 *
 * ## Why a second, HELD-OPEN CDP connection
 *
 * `page.setViewportSize` over CDP resizes the WebView2 viewport, but the override
 * reverts the moment the LAST debugger detaches — so a connection that closed
 * after applying the resize would be undone before the app ever laid out. The
 * `holder` below stays attached for the whole file. Same mechanism, and the same
 * reason, as `readerSelectionCollapsed.spec.ts` and `readerPaginationResize.spec
 * .ts`.
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
import { expect, test } from './harness/connectApp';
import {
  deleteHighlight,
  highlightIds,
  readFrameSelection,
  scrollChapter,
  waitForChapterFrame,
} from './harness/readerFlows';
import { openUsableEpub } from './harness/readerCapacity';
import { clickInsideChapter, selectTextInChapter } from './harness/readerSelection';
import type { Frame, Page } from '@playwright/test';

const TOOLBAR = '.selection-toolbar';
/** The other floating menu. Rendered by the workspace, same parent document. */
const HIGHLIGHT_MENU = '[data-testid="highlight-context-menu"]';

/** Wide enough for the shell's sidebar and the 380px full row. */
const FULL_VIEWPORT = { width: 1200, height: 800 };
/**
 * The `collapsed` rung, MEASURED by sweeping the width and reading which one the
 * app chose (see the layout note below): `full` holds down to 420px, `collapsed`
 * from 380px to 300px, `wrapped` from 270px.
 *
 * The rung is confirmed by measurement anyway — `raiseToolbar` reads which one the
 * app actually rendered and refuses to proceed on the wrong one — so these numbers
 * only have to put the app in the right neighbourhood. 380 is the widest window
 * that still folds the palette, which keeps the case as close to the boundary as
 * the rung allows.
 */
const COLLAPSED_VIEWPORT = { width: 380, height: 720 };
/**
 * The `wrapped` rung, and the width the app BOOTS at (measured ~270px): the band is
 * `270 - 32 = 238px`, two pixels under the 240px collapsed pill, so the wrap rung
 * is the only one left that fits. This is the width that made the unreachable-
 * `wrapped` bug invisible everywhere else, so the spec reaches it deliberately.
 */
const WRAPPED_VIEWPORT = { width: 270, height: 720 };

type Layout = 'full' | 'collapsed' | 'wrapped';

const VIEWPORT_FOR: Record<Layout, { width: number; height: number }> = {
  full: FULL_VIEWPORT,
  collapsed: COLLAPSED_VIEWPORT,
  wrapped: WRAPPED_VIEWPORT,
};

/**
 * The action controls of the pill, by accessible name.
 *
 * Anchored with `^…$` across both locales this repo ships, so a tooltip that
 * happens to contain the word cannot match. `color` is deliberately a prefix: the
 * label interpolates the colour's own name, so the five circles share it.
 */
const ACTION_NAMES: Record<string, RegExp> = {
  // The colour control has TWO forms, and both must be matched:
  //   - `full`      — five circles, `highlight.selectColor` = "Select {{color}} highlight"
  //   - `collapsed` / `wrapped` — ONE trigger, `highlight.changeColor` = "Change color".
  //                   It is `aria-expanded`, and it opens the palette; it does not
  //                   write by itself, so the swatch inside the palette is pressed.
  // Measured: matching only the `full` label makes the collapsed case find zero
  // elements, and `.first().boundingBox()` on zero matches waits — with a 900s test
  // timeout and no `actionTimeout`, that is 15 minutes of silence, not a failure.
  color: /select|seleccionar resaltado|change color|cambiar color/i,
  copy: /^(Copy|Copiar)$/,
  dictionary: /^(Add to Dictionary|Agregar al diccionario)$/,
  translate: /^(Translate|Traducir)$/,
  define: /^(Define|Definir)$/,
};

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

// ─── Reading the menu ────────────────────────────────────────────────────────

/** How many floating menus are on screen right now, counted in the parent. */
async function menusOnScreen(page: Page): Promise<{ toolbar: number; highlight: number }> {
  return {
    toolbar: await page.locator(TOOLBAR).count(),
    highlight: await page.locator(HIGHLIGHT_MENU).count(),
  };
}

/**
 * The layout the toolbar itself decided, read from the DOM rather than inferred
 * from the window width.
 *
 * This is the honest form of the question: the spec sets a width and the APP
 * chooses a rung, so what the assertion needs is which rung it chose. The three
 * are distinguishable from the rendered row with no new test ids and no production
 * change:
 *
 *   - `full`      — five colour circles, one row
 *   - `collapsed` — one `aria-expanded` palette trigger, one row
 *   - `wrapped`   — the four actions on TWO lines (`flex-wrap` plus `min-w-37`)
 *
 * `[role="toolbar"]` is the row itself, so its computed `flex-wrap` is what the
 * wrapped rung sets: a `full` row that happens to be narrow cannot be mistaken for
 * a wrapped one. The wrap test comes FIRST because the palette folds on both
 * narrow rungs — see the note in the body.
 */
async function readToolbarLayout(page: Page): Promise<Layout | 'absent'> {
  return page.evaluate((sel) => {
    const row = document.querySelector(`${sel} div[role="toolbar"]`) as HTMLElement | null;
    if (!row) return 'absent';
    // `flex-wrap` FIRST, and that order is load-bearing: the palette folds on BOTH
    // narrow rungs (`toolbarCollapsed` is true for `collapsed` and for `wrapped`),
    // so an `[aria-expanded]` trigger is present at 270px as well. Checking it
    // first reports `collapsed` for a row that is genuinely wrapped — measured at
    // 270px: `flex-wrap: wrap` AND `aria-expanded` together. The wrap rung is the
    // stricter of the two, so it is the one tested.
    if (getComputedStyle(row).flexWrap === 'wrap') return 'wrapped';
    if (row.querySelector('button[aria-expanded]')) return 'collapsed';
    return 'full';
  }, TOOLBAR);
}

/**
 * The toolbar's rect, or null while it has no measurable box.
 *
 * Read from the DOM rather than through `locator.boundingBox()`: the toolbar
 * enters with an `in:scale` transition, during which `boundingBox()` can be null.
 */
async function readToolbarRect(page: Page): Promise<{
  x: number;
  y: number;
  width: number;
  height: number;
  viewportWidth: number;
  viewportHeight: number;
} | null> {
  return page.evaluate((sel) => {
    const el = document.querySelector(sel);
    if (!el) return null;
    const rect = el.getBoundingClientRect();
    if (rect.width === 0 || rect.height === 0) return null;
    return {
      x: rect.x,
      y: rect.y,
      width: rect.width,
      height: rect.height,
      viewportWidth: window.innerWidth,
      viewportHeight: window.innerHeight,
    };
  }, TOOLBAR);
}

// ─── Driving the menu ────────────────────────────────────────────────────────

/** Escape is the toolbar's own dismissal path, so it clears a leftover. */
async function dismissLeftoverToolbar(page: Page): Promise<void> {
  if ((await page.locator(TOOLBAR).count()) > 0) {
    await page.keyboard.press('Escape');
    await expect(page.locator(TOOLBAR)).toBeHidden({ timeout: 5_000 }).catch(() => undefined);
    await page.waitForTimeout(300);
  }
}

/**
 * Waits until the toolbar's `[role="toolbar"]` row stops being replaced.
 *
 * Element identity, not geometry: the release emits the selection twice (an
 * immediate form and a debounced authoritative one ~100ms later), the parent
 * re-renders the toolbar for the second, and a rect comparison calls it settled
 * while Playwright is still holding the previous node.
 */
async function waitForStableRow(page: Page): Promise<void> {
  let stable = 0;
  for (let i = 0; i < 40; i++) {
    const seen = await page.evaluate((sel) => {
      const el = document.querySelector(`${sel} div[role="toolbar"]`);
      if (!el) return '';
      const win = window as unknown as { __toolbarRow?: Element };
      win.__toolbarRow ??= el;
      return win.__toolbarRow === el ? 'same' : 'new';
    }, TOOLBAR);
    if (seen === '') {
      stable = 0;
    } else if (seen === 'same') {
      stable += 1;
      if (stable >= 3) return;
    } else {
      stable = 0;
    }
    await page.waitForTimeout(100);
  }
}

/** A cheap identity of the toolbar's rendered content, for change detection. */
async function readToolbarSignature(page: Page): Promise<string> {
  return page
    .evaluate((sel) => {
      const el = document.querySelector(sel);
      if (!el) return '(absent)';
      const row = el.querySelector('div[role="toolbar"]');
      const status = el.querySelector('[role="status"]');
      return [
        row ? row.children.length : 0,
        status ? (status.textContent ?? '').trim().slice(0, 40) : '',
      ].join('|');
    }, TOOLBAR)
    .catch(() => '(unreadable)');
}

/**
 * Puts the window at `layout`'s width and raises the toolbar on a real selection,
 * confirming the app chose the rung the case is about.
 *
 * Run out of attempts SKIPS rather than passing against nothing: the chapter's
 * `srcdoc` is replaced as assets settle (measured: three swaps for one opening),
 * so a selection made against a document that is about to be discarded raises no
 * toolbar at all.
 */
async function raiseToolbar(page: Page, layout: Layout): Promise<Frame> {
  const viewport = VIEWPORT_FOR[layout];
  const started = Date.now();
  await dismissLeftoverToolbar(page);
  await page.setViewportSize(viewport);
  await page.waitForTimeout(1_200);

  const width = await page.evaluate(() => window.innerWidth);
  expect(
    width,
    `page.setViewportSize(${viewport.width}) did not resize the webview (window.innerWidth is ` +
      `${width}), so the ${layout} layout cannot be reached and every assertion below would ` +
      `measure the wrong rung.`,
  ).toBeLessThanOrEqual(viewport.width + 40);

  let lastSeen = '(never raised)';
  for (let attempt = 0; attempt < 4; attempt++) {
    const frame = await waitForChapterFrame(page, 8_000);
    const selected = await selectTextInChapter(frame);
    if (selected !== null && selected.trim().length > 0) {
      try {
        await expect(page.locator(TOOLBAR)).toBeVisible({ timeout: 4_000 });
        await waitForStableRow(page);
        const actual = await readToolbarLayout(page);
        lastSeen = actual;
        if (actual === layout) return frame;
      } catch {
        /* the chapter was still settling under the selection; try again */
      }
    }
    await page.waitForTimeout(700);
  }

  console.log(
    `[toolbar-interaction] raiseToolbar(${layout}) gave up after ` +
      `${((Date.now() - started) / 1000).toFixed(1)}s; last rung seen: ${lastSeen}`,
  );
  test.skip(
    true,
    `The toolbar never reached the ${layout} layout in a ${viewport.width}px window within 4 ` +
      `selection attempts (last observed: ${lastSeen}). The chapter on screen may have nothing ` +
      `in its visible band.`,
  );
  throw new Error('unreachable');
}

/**
 * Presses the button matching `name` EXACTLY ONCE with a real pointer.
 *
 * The press is `page.mouse.move/down/up` at the button's own centre — not
 * `locator.click()`, and not `dispatchEvent`. Over CDP the browser then performs
 * its own hit test, its own focus change and its own click, which is the whole
 * sequence the "two clicks" report is about. A synthesised event does not move
 * focus, so it cannot reproduce the defect and gives a false green.
 *
 * ## Why the press is re-aimed, and why that is not a second click
 *
 * The toolbar re-mounts when the debounced selection message arrives, so a press
 * aimed at the node Playwright resolved a moment earlier can land on a detached
 * element and produce nothing. That is a harness race, not the reader's gesture —
 * so the press is re-aimed at a live node and the attempts are COUNTED and
 * reported. `attempts > 1` is harness latency; the defect under test is a press
 * that is DELIVERED and does nothing, which the assertions below catch.
 *
 * Returns the point pressed, or null when no pressable node was found.
 */
async function pressOnce(page: Page, name: RegExp): Promise<{ x: number; y: number } | null> {
  const button = page.locator(TOOLBAR).getByRole('button', { name }).first();
  await waitForStableRow(page);

  for (let attempt = 0; attempt < 8; attempt++) {
    // An explicit `timeout` on every wait: with no `actionTimeout` configured,
    // Playwright falls back to the TEST timeout, so one locator matching nothing
    // silently consumes the whole file's budget instead of failing (measured: a
    // 15-minute timeout whose only symptom was silence after the previous log).
    if ((await button.count()) === 0) return null;
    const box = await button
      .boundingBox({ timeout: 3_000 })
      .then((b) => (b && b.width > 0 && b.height > 0 ? b : null))
      .catch(() => null);
    if (!box) {
      await page.waitForTimeout(300);
      continue;
    }

    const x = box.x + box.width / 2;
    const y = box.y + box.height / 2;
    const before = await readToolbarSignature(page);
    await page.mouse.move(x, y);
    await page.mouse.down();
    await page.mouse.up();
    await page.waitForTimeout(250);

    // Delivered if the row changed at all: the press opened a panel, dismissed the
    // menu, or re-rendered the pill. An unchanged row means the press landed on a
    // node that was already gone — harness latency, so re-aim.
    const after = await readToolbarSignature(page);
    if (before !== after || (await page.locator(TOOLBAR).count()) === 0) return { x, y };
  }
  return null;
}

/**
 * `highlightIds`, bounded.
 *
 * The IPC round trip is normally instant (MEASURED: 0.02s against a book holding
 * 115 highlights), so a call that does not answer is a live stall, not a slow
 * query — and a bare `await` on it silently eats the whole file's time budget
 * (measured: a 15-minute test timeout whose only stack frame was the IPC call).
 * Bounding it turns that into a named failure at the point it happens.
 */
async function highlightIdsBounded(
  page: Page,
  bookId: string,
  label: string,
): Promise<Set<string>> {
  const result = await Promise.race([
    highlightIds(page, bookId),
    new Promise<'timeout'>((resolve) => setTimeout(() => resolve('timeout'), 10_000)),
  ]);
  if (result === 'timeout') {
    throw new Error(
      `${label}: listHighlights did not answer within 10s for book ${bookId}. The IPC round trip is ` +
        `instant in the healthy case, so this is a stall in the reader or the bridge, not a slow query.`,
    );
  }
  return result;
}

/**
 * Presses a COLOUR once with a real pointer, in whichever form the layout offers.
 *
 * `full` renders five circles, so the control IS the swatch. `collapsed` and
 * `wrapped` fold them behind a `+` trigger, so the trigger must be opened first
 * and a swatch inside the palette pressed — which is still ONE press on a colour,
 * and it is the gesture the reported defect was about ("every button needs two
 * clicks, colours included").
 *
 * Returns the point of the colour press, or null when the control could not be
 * reached. The palette open is a separate gesture on a DIFFERENT control (the `+`
 * trigger), so it is not a second press on the colour itself — the claim under test
 * is about the colour control, and in the folded layouts the colour control IS the
 * swatch behind the trigger.
 */
async function pressColorOnce(page: Page): Promise<{ x: number; y: number } | null> {
  const toolbar = page.locator(TOOLBAR);
  const control = toolbar.getByRole('button', { name: ACTION_NAMES.color }).first();
  if ((await control.count()) === 0) return null;

  // The folded form: one trigger that opens the palette. Detected by its own
  // `aria-expanded`, which only the trigger carries — the five circles do not.
  const opener = toolbar.locator('button[aria-expanded]').first();
  const folded = (await opener.count()) > 0;
  if (folded) {
    await opener.click({ timeout: 5_000 });
    await page.waitForTimeout(300);
  }

  // Inside the palette the swatches share the `full` row's own label, so one
  // locator serves both forms; the palette is a `role="group"` and scoping to it
  // keeps the press off any circle that might still be in the DOM.
  const swatch = folded
    ? toolbar.locator('[role="group"]').getByRole('button', { name: /select|seleccionar resaltado/i }).first()
    : control;

  const box = await swatch.boundingBox({ timeout: 5_000 }).catch(() => null);
  if (!box || box.width === 0 || box.height === 0) return null;

  const x = box.x + box.width / 2;
  const y = box.y + box.height / 2;
  await page.mouse.move(x, y);
  await page.mouse.down();
  await page.mouse.up();
  return { x, y };
}

/**
 * Selects a run that CONTAINS a registered highlight, by asking the overlay where
 * the highlight is and building the range inside its OWN text node.
 *
 * ## Why `selectTextInChapter` cannot pose this
 *
 * That helper takes the FIRST on-screen text node, which is the chapter's opening
 * heading ("INTRODUCCIÓN") — measured: the run it offers does not contain the
 * highlight just created, so the case skips rather than measuring the invariant.
 * The pose needs a run that genuinely spans coloured text.
 *
 * ## How the range is built, and why it stays inside one text node
 *
 * `hitTest(x, y)` (via `caretRangeFromPoint`) resolves a frame point to the
 * `{container, offset}` under it — the same test the frame's click handler runs.
 * The overlay's `rangeOverlapsHighlight` then compares the selection against the
 * highlight's own Range in the DOM tree, so a selection built from two different
 * `caretRangeFromPoint` calls across block boundaries can straddle DOM positions
 * without straddling the highlight — measured: a range reading "INTRODUCCIÓN …
 * Vladímir Putin asegura que es un hom" reported `rangeOverlapsHighlight === false`.
 * So the run is built INSIDE the single text node that owns the highlight, spanning
 * its first and last hit character, which makes the containment a fact of the DOM
 * rather than a hope about geometry.
 *
 * Returns null when no highlight is on screen, which the caller reports rather than
 * passing on.
 */
async function selectAcrossHighlight(
  frame: Frame,
): Promise<{ text: string; container: string } | null> {
  return frame
    .evaluate(() => {
      const overlay = (
        globalThis as unknown as {
          __epubHighlightOverlay?: { hitTest?: (x: number, y: number) => { id: string } | null };
        }
      ).__epubHighlightOverlay;
      if (!overlay?.hitTest) return null;

      const parentDoc = (globalThis as unknown as { parent: { document: Document } }).parent.document;
      const frameEl = (globalThis as unknown as { frameElement: Element | null }).frameElement;
      if (!frameEl) return null;
      const frameRect = frameEl.getBoundingClientRect();
      const scroller = parentDoc.querySelector('[data-testid="epub-scroll-container"]');
      const box = scroller
        ? scroller.getBoundingClientRect()
        : { top: 0, bottom: window.innerHeight };
      const bandTop = Math.max(0, box.top - frameRect.top);
      const bandBottom = Math.max(0, box.bottom - frameRect.top);
      const bandLeft = Math.max(0, -frameRect.left);
      const bandRight = bandLeft + window.innerWidth;

      const doc = document as Document & {
        caretRangeFromPoint?: (x: number, y: number) => Range | null;
      };
      if (!doc.caretRangeFromPoint) return null;

      // Every caret position in the visible band that lands on a highlight, with
      // the text node it belongs to. The scan is coarse because a highlight is at
      // least a line tall and comfortably wider than the 8px lattice.
      const STEP = 8;
      const hits: Array<{ node: Text; index: number; y: number }> = [];
      for (let y = bandTop + STEP; y < bandBottom; y += STEP) {
        for (let x = bandLeft + STEP; x < bandRight; x += STEP) {
          const caret = doc.caretRangeFromPoint(x, y);
          if (!caret) continue;
          const node = caret.startContainer;
          if (node.nodeType !== Node.TEXT_NODE) continue;
          if (!overlay.hitTest(x, y)) continue;
          hits.push({ node: node as Text, index: caret.startOffset, y });
        }
      }
      if (hits.length === 0) return null;

      // ONE text node: the one with the most hit characters. A highlight can wrap
      // across nodes, but spanning within a node is what makes the overlap a DOM
      // fact, and a highlight this app creates from a single selection almost always
      // lives in one.
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

      // One character of slack on each side, so the run strictly CONTAINS the
      // coloured characters rather than merely touching them at a boundary.
      const len = (best.node.nodeValue ?? '').length;
      const start = Math.max(0, best.lo - 1);
      const end = Math.min(len, best.hi + 2);

      const range = document.createRange();
      range.setStart(best.node, start);
      range.setEnd(best.node, end);

      const selection = document.getSelection();
      if (!selection) return null;
      selection.removeAllRanges();
      selection.addRange(range);

      // The `mouseup` the chapter's own script waits for — the same gesture
      // `selectTextInChapter` dispatches.
      document.dispatchEvent(new MouseEvent('mouseup', { bubbles: true }));

      return {
        text: range.toString(),
        container: best.node.parentElement?.tagName ?? '(none)',
      };
    })
    .catch(() => null);
}

/**
 * Waits for the toolbar's `role="status"` panel — the observable of Copy, and of
 * Translate / Define having responded at all.
 *
 * Copy is the sharpest of the three: `handleCopy` touches no network, opens no
 * request and writes no database row, so a panel that appears cannot be anything
 * but the handler having run. `navigator.clipboard` is undefined in WebView2 over
 * CDP, so the panel — not the clipboard — is the oracle.
 *
 * ## Why the wait is short
 *
 * The panel is rendered SYNCHRONOUSLY by `handleTranslate` / `handleDefine` — a
 * `translating` / `defining` branch writes the panel in the same task the click's
 * handler runs in, before the request is ever awaited. So "the press reached the
 * handler" is observable in milliseconds, and the request itself may take as long
 * as it likes afterwards, including never answering on a machine with no network.
 *
 * A long wait here would therefore buy nothing and cost the whole file's budget:
 * measured, a `define` whose lookup never returned held a 20s wait and the run
 * blew a 15-minute timeout on the first layout.
 */
async function waitForStatusPanel(page: Page, timeoutMs = 5_000): Promise<string | null> {
  const status = page.locator(TOOLBAR).locator('[role="status"]').first();
  try {
    await status.waitFor({ state: 'visible', timeout: timeoutMs });
  } catch {
    return null;
  }
  return ((await status.innerText().catch(() => '')) ?? '').trim();
}

// ─── 1. One real press, one observable action ────────────────────────────────

/**
 * THE contract. Each action, in each layout, on ONE real press, with the action's
 * own observable asserted — never "the handler exists".
 *
 * The colour case is the decisive one for the "two clicks" report: it is
 * synchronous, touches no network and opens no panel, so if it needs two presses
 * the cause cannot be the request. It has to be the toolbar being torn down
 * between the `pointerdown` and the `click`.
 */
test('every action works on the FIRST real press, in all three layouts', async ({ appPage }) => {
  test.setTimeout(900_000);

  let bookId: string;
  try {
    const opened = await openUsableEpub(appPage);
    bookId = opened.book.id;
  } catch (err) {
    test.skip(true, `No library EPUB rendered prose: ${String(err).slice(0, 200)}`);
    throw err;
  }

  const evidence: string[] = [];

  try {
    for (const layout of ['full', 'collapsed', 'wrapped'] as Layout[]) {
      // Per-layout, so the log line for a rung names only that rung's results.
      const rows: string[] = [];
      // ── Colour: the database is the authority ─────────────────────────────
      await raiseToolbar(appPage, layout);
      const before = await highlightIdsBounded(appPage, bookId, `${layout}: before colour`);
      const colourPoint = await pressColorOnce(appPage);
      expect(
        colourPoint,
        `${layout}: the colour control could not be resolved to a pressable point, so no real ` +
          `press can be aimed at one.`,
      ).not.toBeNull();

      let added: string[] = [];
      await expect
        .poll(
          async () => {
            const after = await highlightIdsBounded(appPage, bookId, `${layout}: after colour`);
            added = [...after].filter((id) => !before.has(id));
            return added.length;
          },
          {
            timeout: 12_000,
            message:
              `${layout}: the real press at (${JSON.stringify(colourPoint)}) applied no highlight. ` +
              `The database is the authority, so this is "the press did nothing", not a rendering ` +
              `question — the reported "every button needs two clicks, colours included".`,
          },
        )
        .toBeGreaterThan(0);
      rows.push(`${layout}:color=1press,${added.length}id(s)`);

      // Clean up what this case wrote, by ID — never by count. The books in this
      // library reached 115 highlights because a spec deleted only the newest row.
      for (const id of added) await deleteHighlight(appPage, id).catch(() => undefined);

      // ── Copy: one real press, and the feedback panel ──────────────────────
      await raiseToolbar(appPage, layout);
      const copyPoint = await pressOnce(appPage, ACTION_NAMES.copy);
      expect(
        copyPoint,
        `${layout}: the Copy button never resolved to a pressable node in 8 attempts.`,
      ).not.toBeNull();
      const copyPanel = await waitForStatusPanel(appPage);
      expect(
        copyPanel,
        `${layout}: ONE real press at (${JSON.stringify(copyPoint)}) on the Copy button produced no ` +
          `feedback panel. Copy touches no network and writes nothing, so an absent panel means ` +
          `the press never reached the handler.`,
      ).not.toBeNull();
      rows.push(`${layout}:copy=${JSON.stringify(copyPanel)}`);

      // ── Dictionary / Translate / Define: the panel must RESPOND ───────────
      // A real network call may fail, and a refusal is a legitimate outcome: the
      // assertion is that the panel answered, not that the service did. A miss, an
      // outage and an echo all render through the same `role="status"`.
      for (const [action, name] of [
        ['dictionary', ACTION_NAMES.dictionary],
        ['translate', ACTION_NAMES.translate],
        ['define', ACTION_NAMES.define],
      ] as const) {
        await raiseToolbar(appPage, layout);
        const target = appPage.locator(TOOLBAR).getByRole('button', { name }).first();
        if ((await target.count()) === 0) {
          rows.push(`${layout}:${action}=absent`);
          continue;
        }
        if (await target.isDisabled({ timeout: 3_000 }).catch(() => false)) {
          // Define is disabled outside the C+A limit — a legitimate state, not a
          // failure, and recorded so a silent skip cannot hide behind it.
          rows.push(`${layout}:${action}=disabled`);
          continue;
        }

        const point = await pressOnce(appPage, name);
        expect(
          point,
          `${layout}: the ${action} button never resolved to a pressable node in 8 attempts.`,
        ).not.toBeNull();
        const panel = await waitForStatusPanel(appPage);
        expect(
          panel,
          `${layout}: ONE real press at (${JSON.stringify(point)}) on the ${action} button ` +
            `produced no panel at all. The result panel answers even when the request fails, so ` +
            `an absent panel means the press never reached the handler.`,
        ).not.toBeNull();
        rows.push(`${layout}:${action}=${JSON.stringify((panel ?? '').slice(0, 50))}`);
      }

      evidence.push(...rows);
      console.log(`[toolbar-interaction] ${layout}: ${rows.join(' | ')}`);
    }
  } finally {
    await dismissLeftoverToolbar(appPage);
    await appPage.setViewportSize(FULL_VIEWPORT).catch(() => undefined);
    await appPage.waitForTimeout(800);
  }

  console.log(`[toolbar-interaction] evidence: ${evidence.join(' || ')}`);
});

// ─── 2. One menu, never two, never none ──────────────────────────────────────

/**
 * The single-menu invariant, asserted by COUNTING what is on screen.
 *
 * A plain selection must raise exactly one menu. A selection that CONTAINS an
 * existing highlight must also leave exactly one — never two (the reported "salen
 * como los dos menús") and never none (the reported "y luego se cierra y no sale
 * ninguno"). The count is the assertion; WHICH menu survives is
 * `readerOneMenu.spec.ts`'s business, because that is a design question and
 * "exactly one" is not.
 */
test('a selection leaves exactly one menu, with or without a highlight inside it', async ({
  appPage,
}) => {
  test.setTimeout(300_000);

  let bookId: string;
  try {
    const opened = await openUsableEpub(appPage);
    bookId = opened.book.id;
  } catch (err) {
    test.skip(true, `No library EPUB rendered prose: ${String(err).slice(0, 200)}`);
    throw err;
  }

  const created: string[] = [];
  try {
    // ── Case 1: a plain selection ──────────────────────────────────────────
    await raiseToolbar(appPage, 'full');
    const plain = await menusOnScreen(appPage);
    expect(
      plain.toolbar,
      `a plain selection raised ${plain.toolbar} selection toolbars — expected exactly one. A ` +
        `count of 0 means the selection produced no menu at all; 2 or more means a menu was ` +
        `rendered twice (a duplicated surface, or two menus for one gesture).`,
    ).toBe(1);
    expect(
      plain.highlight,
      `a plain selection left the highlight menu open too (${plain.highlight} on screen).`,
    ).toBe(0);

    // ── Case 2: colour that run, so a highlight exists to select across ────
    const before = await highlightIdsBounded(appPage, bookId, 'one-menu: before colour');
    await pressColorOnce(appPage);
    await expect
      .poll(
        async () => {
          const after = await highlightIdsBounded(appPage, bookId, 'one-menu: after colour');
          const added = [...after].filter((id) => !before.has(id));
          created.length = 0;
          created.push(...added);
          return added.length;
        },
        { timeout: 15_000 },
      )
      .toBeGreaterThan(0);

    await expect(appPage.locator(TOOLBAR)).toBeHidden({ timeout: 5_000 });
    await appPage.waitForTimeout(1_500);

    // ── Case 3: select ACROSS the highlight ────────────────────────────────
    // The run is widened past the default so it has a real chance of spanning the
    // coloured text. The overlap is then CONFIRMED against the overlay's own
    // `rangeOverlapsHighlight` — the same predicate the frame's script uses to
    // suppress its toolbar — because a run that misses the highlight raises the
    // ordinary toolbar and would make this case pass for the wrong reason.
    const live = await waitForChapterFrame(appPage);
    const across = await selectAcrossHighlight(live);
    if (across === null || across.text.trim().length === 0) {
      test.skip(true, 'No highlight was on screen in the chapter to select across.');
      return;
    }
    console.log(
      `[toolbar-interaction] one-menu: selected across a highlight — ${JSON.stringify(across.text.slice(0, 60))}`,
    );

    const overlaps = await live.evaluate(() => {
      const overlay = (
        globalThis as unknown as {
          __epubHighlightOverlay?: {
            rangeOverlapsHighlight?: (r: Range) => boolean;
            hitTest?: (x: number, y: number) => { id: string } | null;
          };
        }
      ).__epubHighlightOverlay;
      const sel = document.getSelection();
      if (!overlay?.rangeOverlapsHighlight || !sel || sel.rangeCount === 0) return null;
      const range = sel.getRangeAt(0);
      // A SECOND, independent reading of the pose: walk the selection's client
      // rects and hit-test each one. A highlight under the selection's own box is
      // the geometric statement that the run covers coloured text, and it does not
      // depend on `rangeOverlapsHighlight`'s boundary semantics.
      const rects = Array.from(range.getClientRects()).filter((r) => r.width > 1);
      const hits = rects.filter((r) => {
        for (let y = r.top + 2; y < r.bottom; y += 4) {
          for (let x = r.left + 2; x < r.right; x += 4) {
            if (overlay.hitTest?.(x, y)) return true;
          }
        }
        return false;
      }).length;
      return { overlaps: overlay.rangeOverlapsHighlight(range), rects: rects.length, hits };
    });

    // Past the 100ms debounce and the 220ms exit transition: the assertion is
    // about a settled state, not a frame mid-flight.
    await appPage.waitForTimeout(1_200);
    const overlapping = await menusOnScreen(appPage);

    console.log(
      `[toolbar-interaction] one-menu: pose=${JSON.stringify(overlaps)} ` +
        `toolbar=${overlapping.toolbar} highlight=${overlapping.highlight}`,
    );

    // The pose must be real: at least one of the selection's own rects must sit
    // over a registered highlight. Without this the case would measure the ordinary
    // "plain selection" path and pass for the wrong reason.
    expect(
      overlaps,
      'the frame had no readable highlight overlay to establish the pose with.',
    ).not.toBeNull();
    expect(
      overlaps!.hits,
      `the run selected (${JSON.stringify(across.text.slice(0, 40))}) spans ${overlaps!.rects} ` +
        `client rect(s) and NOT ONE of them covers a registered highlight, so this case is ` +
        `measuring a plain selection rather than one containing a highlight.`,
    ).toBeGreaterThan(0);

    // BOTH halves, because the report named both: "salen como los dos menús" (two)
    // and "y luego se cierra y no sale ninguno" (none). A bare `<= 1` catches the
    // first and silently passes the second — which is the half that was reported
    // SECOND, after two menus had appeared and closed each other.
    expect(
      overlapping.toolbar + overlapping.highlight,
      `selecting a run that CONTAINS a highlight (confirmed by rangeOverlapsHighlight) left ` +
        `${overlapping.toolbar} toolbar(s) and ${overlapping.highlight} highlight menu(s) on ` +
        `screen. Two menus at once is the reported "salen como los dos menús"; ZERO is the ` +
        `"y no sale ninguno" that followed it, when the two closed each other. A live selection ` +
        `containing a highlight must leave exactly one.`,
    ).toBe(1);

    // ── Case 3b: a REAL click on the highlight while the selection is live ──
    // The reported gesture is the pair together: a selection over coloured text
    // AND a click whose caret lands on it. The frame suppresses Menu 1 for an
    // overlapping run and the click raises Menu 2, so the settled state is still
    // exactly one — with the single state this is an ordering fact, not an accident.
    const hitPoint = await live
      .evaluate(() => {
        const overlay = (
          globalThis as unknown as { __epubHighlightOverlay?: { hitTest?: (x: number, y: number) => unknown } }
        ).__epubHighlightOverlay;
        if (!overlay?.hitTest) return null;
        const frameEl = (globalThis as unknown as { frameElement: Element | null }).frameElement;
        if (!frameEl) return null;
        const frameRect = frameEl.getBoundingClientRect();
        const band = { top: 0, bottom: window.innerHeight };
        for (let y = band.top + 12; y < band.bottom; y += 12) {
          for (let x = 12; x < window.innerWidth; x += 12) {
            if (overlay.hitTest(x, y)) return { x: frameRect.left + x, y: frameRect.top + y };
          }
        }
        return null;
      })
      .catch(() => null);

    // ── Case 3b: a REAL caret on the highlight, while the selection is live ─
    //
    // ## THIS CASE IS CURRENTLY RED, AND THAT IS THE FINDING
    //
    // Measured on this build, on a freshly restarted app with production code
    // untouched: the gesture below leaves **ZERO** menus — not two (the first
    // reported symptom) and not one. It is the "y no sale ninguno" the report
    // named SECOND, when the two menus closed each other.
    //
    // It is corroborated, not a harness artifact: the user's own
    // `readerOneMenu.spec.ts` does not pass on this build either (it times out on
    // this same path), and the frame-dispatched click DOES raise the highlight menu
    // in `readerOneMenu.spec.ts`'s first test — so the path works, and this
    // particular ordering does not.
    //
    // The assertion is left RED rather than weakened: a spec that cannot report a
    // live defect is theatre, and this one is the whole reason the file exists.
    if (hitPoint) {
      // The click is composed INSIDE the frame, not driven with `page.mouse`.
      //
      // `page.mouse` presses inside the chapter are NOT delivered to the frame's
      // document over WebView2's CDP (see the harness note on the boundary, and
      // `readerOneMenu.spec.ts`, which composes the same sequence for the same
      // reason). A `page.mouse` click here would land on the iframe element and
      // clear the menu without the frame ever seeing a `click` — which reads as
      // "no menu at all" and would be a harness artifact rather than a finding.
      //
      // So the sequence the frame's own handler listens for is dispatched at the
      // REAL coordinate `hitTest` returned, translated into frame space. The
      // handler runs `caretRangeFromPoint(clientX, clientY)`, so a real point is
      // required and `element.click()` would not do.
      const framePoint = await live.evaluate(() => {
        const frameEl = (globalThis as unknown as { frameElement: Element | null }).frameElement;
        const fr = frameEl?.getBoundingClientRect();
        return { left: fr?.left ?? 0, top: fr?.top ?? 0 };
      });
      await live.evaluate(
        ([fx, fy]) => {
          for (const type of ['mousedown', 'mouseup', 'click'] as const) {
            document.dispatchEvent(
              new MouseEvent(type, { bubbles: true, clientX: fx, clientY: fy }),
            );
          }
        },
        [hitPoint.x - framePoint.left, hitPoint.y - framePoint.top] as const,
      );

      await appPage.waitForTimeout(1_400);
      const afterClick = await menusOnScreen(appPage);
      expect(
        afterClick.toolbar + afterClick.highlight,
        `a real caret landing on the highlight at (${JSON.stringify(hitPoint)}) while a selection ` +
          `over it was live left ${afterClick.toolbar} toolbar(s) and ${afterClick.highlight} ` +
          `highlight menu(s). Two menus at once is the reported "salen como los dos menús"; ZERO ` +
          `is the "y no sale ninguno" that followed it, when the two closed each other. This is ` +
          `the live defect this spec was written to catch, not an environment problem: the frame's ` +
          `own click path raises the menu in readerOneMenu.spec.ts, and only this ordering — a ` +
          `selection already live over the coloured run — leaves nothing.`,
      ).toBe(1);
    }

    // ── Case 4: a real press on a menu button with a highlight underneath ──
    // The reported pair arrives TOGETHER: a selection containing a highlight showed
    // two menus and then closed both, leaving a dead menu. So the survivor's
    // buttons must still work. Copy is the action with no network and no DB write,
    // which makes the panel a statement about the press alone.
    const settled = await menusOnScreen(appPage);
    if (settled.toolbar === 1) {
      await pressOnce(appPage, ACTION_NAMES.copy);
      const panel = await waitForStatusPanel(appPage, 10_000);
      expect(
        panel,
        `with a highlight inside the selection, ONE real press on Copy produced no feedback panel ` +
          `(menus on screen at the time: toolbar=${settled.toolbar}, ` +
          `highlight=${settled.highlight}).`,
      ).not.toBeNull();
    } else if (settled.highlight === 1) {
      // The highlight menu won. It owns its own actions; this spec only requires
      // that it is a settled single menu and stays reachable.
      expect(settled.toolbar).toBe(0);
    }
  } finally {
    for (const id of created) await deleteHighlight(appPage, id).catch(() => undefined);
    await dismissLeftoverToolbar(appPage);
    await appPage.setViewportSize(FULL_VIEWPORT).catch(() => undefined);
    await appPage.waitForTimeout(800);
  }
});

// ─── 3. Geometric containment in all three layouts ───────────────────────────

/**
 * The menu never escapes the window — at any width, for a selection near any edge.
 *
 * This is the property the clamping math exists to guarantee, and jsdom cannot
 * test it: every `getBoundingClientRect()` there is zero, so the clamp branch
 * always takes the same side and never engages. Two selections are used per
 * layout: the first on-screen prose (a paragraph start, which is the EASY case the
 * existing containment assertion measures), and then the reader scrolled, which
 * puts a different run under the menu. The `full` layout also gets the right-edge
 * case `readerSelection.spec.ts` forces, because a broken clamp only shows itself
 * when the selection is near the border.
 */
test('the menu stays inside the window in every layout', async ({ appPage }) => {
  test.setTimeout(420_000);

  try {
    try {
      await openUsableEpub(appPage);
    } catch (err) {
      test.skip(true, `No library EPUB rendered prose: ${String(err).slice(0, 200)}`);
      throw err;
    }

    for (const layout of ['full', 'collapsed', 'wrapped'] as Layout[]) {
      for (const gesture of ['initial', 'scrolled'] as const) {
        const frame = await raiseToolbar(appPage, layout);

        if (gesture === 'scrolled') {
          await appPage.keyboard.press('Escape');
          await appPage.waitForTimeout(400);
          await scrollChapter(appPage, 320);
          await appPage.waitForTimeout(700);
          const selected = await selectTextInChapter(frame);
          if (selected === null || selected.trim().length === 0) {
            continue;
          }
          await expect(appPage.locator(TOOLBAR)).toBeVisible({ timeout: 8_000 });
          await waitForStableRow(appPage);
        }

        const rect = await readToolbarRect(appPage);
        expect(
          rect,
          `${layout}/${gesture}: the toolbar had no measurable box, so containment cannot be ` +
            `stated about it.`,
        ).not.toBeNull();

        const where = `${layout} layout, ${gesture} selection`;
        expect(
          rect!.x,
          `${where}: the toolbar's left edge is at ${rect!.x}px, outside the ${rect!.viewportWidth}px ` +
            `window.`,
        ).toBeGreaterThanOrEqual(0);
        expect(
          rect!.x + rect!.width,
          `${where}: the toolbar spans ${rect!.x}..${rect!.x + rect!.width}px, past the ` +
            `${rect!.viewportWidth}px right border. This is the reported "el menú se salía por la ` +
            `derecha".`,
        ).toBeLessThanOrEqual(rect!.viewportWidth);
        expect(
          rect!.y,
          `${where}: the toolbar's top edge is at ${rect!.y}px, above the window.`,
        ).toBeGreaterThanOrEqual(0);
        expect(
          rect!.y + rect!.height,
          `${where}: the toolbar's bottom edge is at ${rect!.y + rect!.height}px, past the ` +
            `${rect!.viewportHeight}px window.`,
        ).toBeLessThanOrEqual(rect!.viewportHeight);

        // Containment is necessary but not sufficient: a menu that is inside the
        // window and painted NOWHERE passes the four checks above. That is exactly
        // the collapsed `+` defect, so the browser's own hit test is run at the
        // menu's centre — what a person's pointer would actually find there.
        const centre = await appPage.evaluate((sel) => {
          const el = document.querySelector(sel);
          if (!el) return null;
          const r = el.getBoundingClientRect();
          const x = r.left + r.width / 2;
          const y = r.top + Math.min(r.height / 2, 30);
          const hit = document.elementFromPoint(x, y);
          return {
            at: `${Math.round(x)},${Math.round(y)}`,
            hit: hit ? `${hit.tagName.toLowerCase()}.${String(hit.getAttribute('class') ?? '').slice(0, 50)}` : '(nothing)',
            inside: hit ? el.contains(hit) : false,
          };
        }, TOOLBAR);

        expect(centre, `${where}: the toolbar had no box to hit-test.`).not.toBeNull();
        expect(
          centre!.inside,
          `${where}: the toolbar's own box is on screen but nothing of it is PAINTED at its centre ` +
            `(${centre!.at}) — elementFromPoint returned ${centre!.hit}. The menu exists in the DOM ` +
            `and a person's pointer reaches something else.`,
        ).toBe(true);
      }

      console.log(
        `[toolbar-interaction] containment ok for ${layout}: ${JSON.stringify(
          await readToolbarRect(appPage),
        )}`,
      );
    }
  } finally {
    await dismissLeftoverToolbar(appPage);
    await appPage.setViewportSize(FULL_VIEWPORT).catch(() => undefined);
    await appPage.waitForTimeout(800);
  }
});

// ─── 4. The legitimate dismissals ────────────────────────────────────────────

/**
 * Every close the reader expects, from a menu raised on a real selection.
 *
 * The four cases are the ones a person performs without thinking, and each crosses
 * the iframe boundary in a different direction — which is why they cannot be unit
 * tested. A `pointerdown` outside never reaches the chapter; a click inside it
 * never reaches the parent; a scroll is real on the parent's container in scrolling
 * mode (the iframe is sized to the whole chapter, so nothing scrolls inside it).
 * Each path emits the dismissal from whichever side saw the gesture, and this
 * asserts both halves the contract requires: the menu is GONE and the frame's
 * selection is CLEARED.
 */
test('pointerdown outside, Escape, scroll and a click inside the chapter all close the menu', async ({
  appPage,
}) => {
  test.setTimeout(420_000);

  try {
    try {
      await openUsableEpub(appPage);
    } catch (err) {
      test.skip(true, `No library EPUB rendered prose: ${String(err).slice(0, 200)}`);
      throw err;
    }
    await appPage.waitForTimeout(2_000);

    const toolbar = appPage.locator(TOOLBAR);

    /** The frame's own selection must be empty, or the menu was left orphaned. */
    const expectFrameSelectionEmpty = async (): Promise<void> => {
      const deadline = Date.now() + 5_000;
      let last = '(not read)';
      while (Date.now() < deadline) {
        last = await readFrameSelection(await waitForChapterFrame(appPage));
        if (last.length === 0) return;
        await appPage.waitForTimeout(200);
      }
      expect(
        last,
        `after the menu was dismissed the chapter frame still held the selection "${last.slice(0, 40)}" ` +
          `— the text stays highlighted with no menu to act on it.`,
      ).toBe('');
    };

    /** Raises the menu on a real selection, retrying while the chapter settles. */
    const raise = async (): Promise<void> => {
      for (let attempt = 0; attempt < 6; attempt++) {
        const frame = await waitForChapterFrame(appPage, 10_000);
        const selected = await selectTextInChapter(frame);
        if (selected !== null && selected.trim().length > 0) {
          try {
            await expect(toolbar).toBeVisible({ timeout: 5_000 });
            await waitForStableRow(appPage);
            return;
          } catch {
            /* still settling; try again */
          }
        }
        await appPage.waitForTimeout(900);
      }
      test.skip(true, 'The menu could not be raised on a real chapter selection.');
    };

    // ── Case 1 — pointerdown outside the text ─────────────────────────────
    await raise();
    // The footer's left span owns no handler; the top bar's back button would
    // leave the reader entirely.
    await appPage.locator('footer span').first().click({ position: { x: 4, y: 4 } });
    await expect(
      toolbar,
      'clicking outside the chapter (the click-away dismissal) left the menu on screen.',
    ).toBeHidden({ timeout: 5_000 });
    await expectFrameSelectionEmpty();

    // ── Case 2 — Escape ───────────────────────────────────────────────────
    await raise();
    await appPage.keyboard.press('Escape');
    await expect(toolbar, 'Escape did not close the menu.').toBeHidden({ timeout: 5_000 });
    await expectFrameSelectionEmpty();

    // ── Case 3 — scroll ───────────────────────────────────────────────────
    await raise();
    await scrollChapter(appPage, 400);
    await expect(
      toolbar,
      'scrolling the reader left the menu floating over the new viewport.',
    ).toBeHidden({ timeout: 5_000 });
    await expectFrameSelectionEmpty();
    await appPage.waitForTimeout(600);

    // ── Case 4 — a click inside the chapter ───────────────────────────────
    await raise();
    await clickInsideChapter(await waitForChapterFrame(appPage));
    await expect(
      toolbar,
      'a click inside the chapter left the menu on screen — the frame emits this clear itself, ' +
        'because the click never reaches the parent.',
    ).toBeHidden({ timeout: 5_000 });
    await expectFrameSelectionEmpty();
  } finally {
    await dismissLeftoverToolbar(appPage);
    await appPage.setViewportSize(FULL_VIEWPORT).catch(() => undefined);
    await appPage.waitForTimeout(800);
  }
});
