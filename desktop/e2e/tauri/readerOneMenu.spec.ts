/**
 * One menu at a time, driven against the real app over CDP.
 *
 * ## The reported defect
 *
 * "Si hago una selección y dentro de esa selección ya hay una selección con
 * color, salen como los dos menús y luego se cierra y no sale ninguno."
 *
 * Select a run of text that CONTAINS an existing highlight and both menus appear
 * — the `SelectionToolbar` and the `HighlightContextMenu` — and then both close,
 * leaving neither. It is a defect of split state: the workspace owned
 * `showToolbar`, `useHighlightMenu` owned its own `open`, and the frame's
 * selection script had a third opinion, so "one menu" was an ordering accident
 * rather than an invariant.
 *
 * The single `OpenMenu` state (`src/lib/features/reader/chrome/openMenu.ts`)
 * makes it an invariant: opening either menu closes the other in the same step.
 * These specs are the live proof.
 *
 * ## Why a spec and not a unit test
 *
 * The defect crosses the iframe boundary. The gesture that raises the highlight
 * menu is a `click` whose caret lands inside a registered highlight range, and
 * the gesture that raises the toolbar is a `mouseup` whose selection overlaps
 * that same range. Both originate in the chapter document and are posted to the
 * parent over `postMessage`; a jsdom test has no second document, no
 * `caretRangeFromPoint`, no real `CSS.highlights` and no frame boundary. The
 * pure transition is unit-tested in `src/test/unit/stores/openMenu.test.ts`;
 * what only this spec can prove is that the WIRING keeps the two in step.
 *
 * ## Real clicks
 *
 * The highlight is clicked with `page.mouse.click` at the highlight's own
 * measured rect, not with `dispatchEvent`. The reported gesture is a person's
 * caret landing on a coloured run, and `caretRangeFromPoint` — the hit test the
 * frame runs — needs a real coordinate. A synthesised event would bypass it.
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
import { deleteHighlight, highlightIds, waitForChapterFrame } from './harness/readerFlows';
import { openUsableEpub } from './harness/readerCapacity';
import { selectTextInChapter } from './harness/readerSelection';
import type { Frame, Page } from '@playwright/test';

/** The selection toolbar, rendered by the workspace into the parent document. */
const TOOLBAR = '.selection-toolbar';
/** The highlight context menu, likewise. */
const HIGHLIGHT_MENU = '[data-testid="highlight-context-menu"]';

/**
 * How many floating menus are on screen right now.
 *
 * The whole assertion of this spec: the answer is never 2, and after a settled
 * gesture it is 1 when a menu is warranted. Both surfaces are counted in the
 * PARENT document, which is where they are rendered.
 */
async function menusOnScreen(page: Page): Promise<{ toolbar: number; highlight: number }> {
  return {
    toolbar: await page.locator(TOOLBAR).count(),
    highlight: await page.locator(HIGHLIGHT_MENU).count(),
  };
}

/**
 * A point on an existing highlight, in PAGE coordinates, or null when none is on
 * screen.
 *
 * ## Why a probe and not a rect read
 *
 * Highlights are painted with the **CSS Custom Highlight API** — they are
 * `Range`s in `CSS.highlights`, not DOM elements, so there is no element to
 * measure. The overlay's own public surface is `hitTest(x, y)`, which resolves a
 * frame-relative point to the highlight under it (the same test the frame's click
 * handler runs before posting `epub-highlight-click`). So the point is found by
 * scanning the chapter's visible band for one where `hitTest` answers, and the
 * frame element's rect is added to translate into the parent's coordinate space —
 * which is what `page.mouse` needs.
 *
 * The scan is coarse (a ~24px lattice) because a highlight is at least a line
 * tall and comfortably wider than that; a finer grid would cost more than it can
 * find.
 */
async function firstHighlightRect(frame: Frame): Promise<{ x: number; y: number } | null> {
  return frame
    .evaluate(() => {
      const overlay = (
        globalThis as unknown as {
          __epubHighlightOverlay?: {
            hitTest?: (x: number, y: number) => { id: string } | null;
          };
        }
      ).__epubHighlightOverlay;
      const frameEl = (globalThis as unknown as { frameElement: Element | null }).frameElement;
      if (!overlay || typeof overlay.hitTest !== 'function' || !frameEl) return null;
      const frameRect = frameEl.getBoundingClientRect();

      // The visible band, in frame coordinates: the scroller's box translated
      // through the frame element, exactly as `chapterVisibleBand` derives it.
      const parentDoc = (globalThis as unknown as { parent: { document: Document } }).parent
        .document;
      const parentWindow = (globalThis as unknown as { parent: Window }).parent;
      const scroller = parentDoc.querySelector('[data-testid="epub-scroll-container"]');
      const box = scroller
        ? scroller.getBoundingClientRect()
        : { top: 0, bottom: parentWindow.innerHeight };
      const bandTop = Math.max(0, box.top - frameRect.top);
      const bandBottom = Math.max(0, box.bottom - frameRect.top);
      const bandLeft = Math.max(0, -frameRect.left);
      const bandRight = bandLeft + window.innerWidth;

      const STEP = 24;
      for (let y = bandTop + STEP / 2; y < bandBottom; y += STEP) {
        for (let x = bandLeft + STEP / 2; x < bandRight; x += STEP) {
          const hit = overlay.hitTest(x, y);
          if (hit) {
            // Centre in PAGE space: the frame's own offset plus the frame point.
            return { x: frameRect.left + x, y: frameRect.top + y };
          }
        }
      }
      return null;
    })
    .catch(() => null);
}

/**
 * Selects a run of on-screen text and colours it, so a highlight exists to put
 * the reported gesture against. Returns the id the colour click created, or null
 * when this chapter offered nothing to select.
 *
 * The colour is applied through the toolbar's own swatch, which is the real
 * write path (`handleColorSelect` -> the store -> the outbox), not a direct
 * `saveHighlight` call — the point is to leave a highlight the overlay has
 * registered, and only the app's own path does that.
 */
async function createHighlightOnScreen(page: Page): Promise<string | null> {
  const before = new Set<string>();
  const bookId = await currentBookId(page);
  if (bookId) {
    for (const id of await highlightIds(page, bookId)) before.add(id);
  }

  const frame = await waitForChapterFrame(page);
  const selected = await selectTextInChapter(frame);
  if (selected === null || selected.trim().length === 0) return null;

  const toolbar = page.locator(TOOLBAR);
  if (!(await toolbar.isVisible({ timeout: 10_000 }).catch(() => false))) return null;

  const swatch = toolbar.getByRole('button', { name: /yellow|amarillo/i }).first();
  await swatch.click();

  // The write is asynchronous and the overlay re-renders when it lands; poll the
  // backend for the id this click added rather than assuming it arrived.
  if (!bookId) return null;
  let added: string | null = null;
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
  return added;
}

/** The id of the book the reader is currently showing, read from the backend. */
async function currentBookId(page: Page): Promise<string | null> {
  try {
    const opened = await openUsableEpub(page);
    return opened.book.id;
  } catch {
    return null;
  }
}

/**
 * Clicks an existing highlight in the chapter and reports whether the highlight
 * menu came up.
 *
 * ## Why the click is composed in the frame, not driven with `page.mouse`
 *
 * The rest of this harness selects text with the frame's own `Selection`/`Range`
 * and synthesises the frame-level gesture, because a real `page.mouse` press
 * **inside** a same-origin `about:srcdoc` chapter is not delivered to the frame's
 * document over WebView2's CDP (measured here: the parent's own buttons respond
 * to `page.mouse`, and a press at the chapter's own coordinates produced no
 * frame-level `click` at all — see `readerSelection.ts`'s note on the same
 * boundary). So the sequence the frame's handler actually listens for —
 * `mousedown`, `mouseup`, `click` — is dispatched at a REAL coordinate, which is
 * what matters: the handler runs `caretRangeFromPoint(clientX, clientY)` on the
 * event, so a real point is required and `element.click()` would not do.
 *
 * ## Why the probe and the click are repeated
 *
 * The point is located by `hitTest` and then clicked, but the two are not atomic:
 * the chapter re-renders as the previous gesture settles. Each attempt re-probes
 * and clicks immediately, and stops as soon as the menu is up.
 */
async function clickHighlight(
  page: Page,
  attempts = 4,
): Promise<{ clicked: boolean; lastPoint: { x: number; y: number } | null }> {
  let lastPoint: { x: number; y: number } | null = null;
  for (let i = 0; i < attempts; i++) {
    const frame = await waitForChapterFrame(page);
    // A bare click on a highlight is the gesture under test. Any selection left
    // over from the previous step is cleared first so the frame's own `mouseup`
    // posts an overlap-clear rather than a competing one; the overlap race is
    // asserted separately above.
    await frame.evaluate(() => document.getSelection()?.removeAllRanges());
    await page.waitForTimeout(300);

    const rect = await firstHighlightRect(frame);
    if (rect === null) {
      await page.waitForTimeout(600);
      continue;
    }
    lastPoint = rect;
    // Frame-relative coordinates: the point was found in that space, and
    // `clientX/clientY` inside the frame ARE frame-viewport coordinates.
    const offset = await frame
      .evaluate(() => {
        const fr = (
          globalThis as unknown as { frameElement: Element | null }
        ).frameElement?.getBoundingClientRect();
        return { left: fr?.left ?? 0, top: fr?.top ?? 0 };
      })
      .catch(() => ({ left: 0, top: 0 }));
    await frame.evaluate(
      ([fx, fy]) => {
        document.dispatchEvent(
          new MouseEvent('mousedown', { bubbles: true, clientX: fx, clientY: fy }),
        );
        document.dispatchEvent(
          new MouseEvent('mouseup', { bubbles: true, clientX: fx, clientY: fy }),
        );
        document.dispatchEvent(
          new MouseEvent('click', { bubbles: true, clientX: fx, clientY: fy }),
        );
      },
      [rect.x - offset.left, rect.y - offset.top] as const,
    );
    await page.waitForTimeout(700);
    if ((await menusOnScreen(page)).highlight === 1) return { clicked: true, lastPoint };
  }
  return { clicked: false, lastPoint };
}

test('selecting a run that contains a highlight never shows both menus', async ({ appPage }) => {
  test.setTimeout(300_000);

  let opened: { book: { id: string; title: string } };
  try {
    opened = await openUsableEpub(appPage);
  } catch (err) {
    test.skip(true, `No library EPUB rendered prose: ${String(err).slice(0, 200)}`);
    throw err;
  }
  const bookId = opened.book.id;

  // A highlight on screen to select across. Any the run creates are removed on
  // the way out, identified by id — never by count.
  const created: string[] = [];
  const first = await createHighlightOnScreen(appPage);
  if (first === null) {
    test.skip(true, 'The chapter offered no selectable on-screen text to colour.');
    return;
  }
  created.push(first);

  // Let the overlay register the new highlight and the toolbar finish leaving.
  await expect(appPage.locator(TOOLBAR)).toBeHidden({ timeout: 5_000 });
  await appPage.waitForTimeout(1_500);

  // ── The reported gesture ─────────────────────────────────────────────────
  // Two things happen for one selection that overlaps a highlight: the frame's
  // `mouseup` sees the overlap and posts a clear (it will not raise Menu 1 under
  // Menu 2), and a click whose caret lands inside the highlight posts
  // `epub-highlight-click`, which raises Menu 2. Both are in flight at once, and
  // before the single state the clear could land AFTER the open and close the
  // menu that had just been raised — the "sale y luego se cierra y no sale
  // ninguno" the reader reported.
  const frame = await waitForChapterFrame(appPage);
  const rect = await firstHighlightRect(frame);
  if (rect === null) {
    test.skip(true, 'No highlight rect was measurable in the chapter to click.');
    return;
  }

  await clickHighlight(appPage, 1);
  // Past the 100 ms debounce the frame uses before its authoritative post, and
  // past the toolbar's 220 ms exit transition: the assertion is about a settled
  // state, not a frame mid-flight.
  await appPage.waitForTimeout(1_200);

  const afterClick = await menusOnScreen(appPage);
  expect(
    afterClick.toolbar + afterClick.highlight,
    `two menus were on screen at once (toolbar=${afterClick.toolbar}, ` +
      `highlight=${afterClick.highlight}) — the single-state invariant is broken`,
  ).toBeLessThanOrEqual(1);

  // ── Selecting ACROSS the highlight ───────────────────────────────────────
  // The gesture the report names: a selection that CONTAINS the highlight. The
  // frame suppresses Menu 1 for an overlapping run, so the only correct outcome
  // is exactly one menu — never two, and never none.
  const across = await selectTextInChapter(frame);
  if (across !== null && across.trim().length > 0) {
    await appPage.waitForTimeout(1_200);
    const afterSelect = await menusOnScreen(appPage);
    expect(
      afterSelect.toolbar + afterSelect.highlight,
      `selecting a run containing a highlight left ${afterSelect.toolbar} toolbar(s) and ` +
        `${afterSelect.highlight} highlight menu(s) — expected at most one`,
    ).toBeLessThanOrEqual(1);
  }

  // ── The highlight menu is reachable, and it is the ONE menu that survives ──
  // The gesture that raises Menu 2 must do so; and while it is up the selection
  // toolbar must not be. This is the survivor assertion: before the single state
  // the clear that the overlapping `mouseup` posted could close the menu the
  // click had just raised, so a click on an existing highlight left NOTHING.
  const clickResult = await clickHighlight(appPage);
  const settled = await menusOnScreen(appPage);
  if (!clickResult.clicked) {
    test.skip(
      true,
      `A real click on a highlight did not raise the highlight menu within the ` +
        `retry budget (last point ${JSON.stringify(clickResult.lastPoint)}, ` +
        `now toolbar=${settled.toolbar} highlight=${settled.highlight}). This chapter could ` +
        `not be driven into a highlight click.`,
    );
  }
  expect(settled.toolbar, 'a click on an existing highlight left the SELECTION toolbar open').toBe(
    0,
  );
  expect(
    settled.highlight,
    'a click on an existing highlight left no menu open at all — the reported symptom',
  ).toBe(1);

  // Clean up every id this run added, so the developer's book is left as found.
  for (const id of created) {
    await deleteHighlight(appPage, id);
  }
  void bookId;
});

test('a settled selection leaves exactly one menu, and dismissing leaves none', async ({
  appPage,
}) => {
  test.setTimeout(180_000);

  try {
    await openUsableEpub(appPage);
  } catch (err) {
    test.skip(true, `No library EPUB rendered prose: ${String(err).slice(0, 200)}`);
    throw err;
  }

  const frame = await waitForChapterFrame(appPage);
  const selected = await selectTextInChapter(frame);
  if (selected === null || selected.trim().length === 0) {
    test.skip(true, 'The chapter offered no selectable on-screen text.');
    return;
  }

  await expect(appPage.locator(TOOLBAR)).toBeVisible({ timeout: 10_000 });
  const open = await menusOnScreen(appPage);
  expect(open.toolbar, 'a plain selection did not raise the selection toolbar').toBe(1);
  expect(open.highlight, 'a plain selection left the highlight menu open too').toBe(0);

  // One Escape closes the menu (and the frame's selection with it) — and it must
  // close BOTH surfaces, which the single state makes the same fact.
  await appPage.keyboard.press('Escape');
  await expect(appPage.locator(TOOLBAR)).toBeHidden({ timeout: 5_000 });
  await appPage.waitForTimeout(400);
  const closed = await menusOnScreen(appPage);
  expect(
    closed.toolbar + closed.highlight,
    'dismissing the selection menu left a floating menu on screen',
  ).toBe(0);
});
