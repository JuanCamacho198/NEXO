/**
 * Reader-driving helpers for the real-use smoke test.
 *
 * ## Why this is separate from `readerSmoke.spec.ts`
 *
 * Everything here drives the *live* reader through its own chrome and DOM — the
 * page field, the Next/Prev controls, the chapter frame. The spec owns the
 * read-cycle and its assertions; this module owns how a step is performed and
 * what "the step happened" means. Splitting them keeps the spec readable and
 * keeps the interaction semantics in one place, because several of them are
 * non-obvious (see `settleReader` and `turnOnce`).
 *
 * ## The two facts that shape every helper here
 *
 * 1. **The header page field is committed-render state.** `EpubNativeViewer`
 *    derives it from `lastRenderedChapter`, so it follows the chapter whose
 *    render has landed, not the navigation request. A turn in scroll mode is a
 *    real backend extraction plus an `srcdoc` swap: measured commit times range
 *    from ~0.5 s to ~9 s from a freshly opened book.
 *
 * 2. **A turn can bounce.** Occasionally the field moves and is then pulled back
 *    by the CFI restore (measured `4,4,4,4,4,6,4,4,4,4`, and roughly 1 seeded
 *    open in 6 needs more than one press). Retrying is the honest model: a
 *    person whose click was swallowed clicks again.
 *
 * Both are properties of the app under test, not of this harness — the retry
 * budgets are sized from measurement, and each is documented where it is used.
 */
import { expect, type Frame, type Page } from '@playwright/test';
import { findChapterFrame, goToLibrary, openBook, type LibraryRow } from './readerFlows';

/** A book the read-cycle opens, as the library reports it. */
export type SmokeBook = LibraryRow;

/** The two books a read-cycle needs: a first to read and a second to switch to. */
export type SmokeBooks = { first: SmokeBook; second: SmokeBook };

/**
 * Books the spec opens, read over the real IPC bridge (not guessed from a title).
 *
 * A real local EPUB only: a *sync stub* (`coverPath` absent) has no local file
 * and never renders, so it would fail at step 2 for a reason the smoke test is
 * not about.
 */
export async function pickTwoEpubs(page: Page): Promise<SmokeBooks | null> {
  const rows = (await page.evaluate(async () => {
    const internals = (
      window as unknown as {
        __TAURI_INTERNALS__?: { invoke: (cmd: string, args?: unknown) => Promise<unknown> };
      }
    ).__TAURI_INTERNALS__;
    if (!internals) throw new Error('__TAURI_INTERNALS__ is missing — not a Tauri webview');
    return (await internals.invoke('listLibraryBooks', {
      payload: { responseVersion: 1 },
    })) as unknown;
  })) as SmokeBook[];

  const epubs = rows.filter(
    (row) => (row.format ?? '').toLowerCase() === 'epub' && Boolean(row.coverPath),
  );
  if (epubs.length < 2) return null;
  return { first: epubs[0], second: epubs[1] };
}

/** The reader's header page field (a TOC entry index), or null when absent. */
export async function readHeaderPage(page: Page): Promise<number | null> {
  const input = page.locator('[data-testid="bottom-bar-controls"] input').first();
  if ((await input.count()) === 0) return null;
  const parsed = Number.parseInt(await input.inputValue(), 10);
  return Number.isFinite(parsed) ? parsed : null;
}

/** The EPUB font size the app is actually rendering with, from the zoom control. */
export async function readZoomPercent(page: Page): Promise<number | null> {
  const trigger = page.locator('[data-testid="zoom-dropdown-trigger"]');
  if ((await trigger.count()) === 0) return null;
  const label = (await trigger.textContent()) ?? '';
  const parsed = Number.parseInt(label.replace(/[^0-9]/g, ''), 10);
  return Number.isFinite(parsed) ? parsed : null;
}

/**
 * Waits until the header page field holds one value across consecutive reads,
 * then returns it.
 *
 * Used where the spec reads a position rather than driving a turn (the seeded
 * check, and the "the place was kept" comparisons). The field is committed
 * -render state, so a single sample taken while a chapter render or the entry
 * restore is still in flight can read the previous chapter. Waiting for the
 * value to stop moving is what makes a read a statement about where the reader
 * is, rather than about when it was asked.
 */
export async function settleReader(page: Page, timeoutMs = 20_000): Promise<number | null> {
  const deadline = Date.now() + timeoutMs;
  let previous: number | null = null;
  let stable = 0;
  while (Date.now() < deadline) {
    const current = await readHeaderPage(page);
    stable = current !== null && current === previous ? stable + 1 : 0;
    previous = current;
    if (stable >= 4) return current;
    await page.waitForTimeout(250);
  }
  return previous;
}

/**
 * Advances the reader `times` pages through the bottom bar's Next control.
 *
 * **Why this is not a fixed sleep, and not a one-shot poll.** The header page
 * field is committed-render state (`EpubNativeViewer.displayCurrentPage` reads
 * `lastRenderedChapter`), and a chapter turn in scroll mode is a real backend
 * extraction plus a render:
 *
 *   - the commit lands **seconds** after the click — measured 0.5 s at best and
 *     6-9 s from a freshly opened book, so any short wait reads the pre-turn
 *     value and concludes the click did nothing;
 *   - occasionally the field moves and then **falls back** (measured 1 run in 6:
 *     `4,4,4,4,4,6,4,4,4,4`), which is the reader's CFI restore winning a race
 *     against the turn. That is the product's behaviour, not this test's.
 *
 * So the turn is attempted until the field both **moves off** its previous
 * value and **stays off** it. Retrying is the honest model of the interaction:
 * a person whose first click was swallowed clicks again. The bound keeps a
 * genuinely wedged reader failing the caller's assertion instead of hanging.
 */
export async function turnForward(page: Page, times: number): Promise<void> {
  // prev is index 0, next is index 1 (`ReaderBottomBar`).
  const next = page.locator('[data-testid="bottom-bar-controls"] button').nth(1);
  for (let i = 0; i < times; i++) {
    await turnOnce(page, next);
  }
}

/**
 * Steps the reader back one page through the bottom bar's Prev control.
 *
 * Same rule as {@link turnForward}.
 */
export async function turnBackward(page: Page): Promise<void> {
  // prev is index 0, next is index 1 (`ReaderBottomBar`).
  await turnOnce(page, page.locator('[data-testid="bottom-bar-controls"] button').first());
}

/**
 * Presses one paging control until the header page field has moved and held.
 *
 * The `heldForMs` window is what distinguishes a committed turn from the
 * reader's bounce (moved, then pulled back by the CFI restore — measured
 * `4,4,4,4,4,6,4,4,4,4`), and `attempts` bounds the retries so a reader that
 * truly cannot turn fails at the caller's assertion instead of sleeping
 * forever. A person whose click was swallowed clicks again; that is the
 * interaction this models. The budget is sized from measurement, not taste: the
 * worst case over 10 independent seeded opens was 4 attempts (counts
 * 3,3,1,1,1,1,3,3,4,1), so 8 leaves real headroom.
 */
async function turnOnce(
  page: Page,
  control: ReturnType<Page['locator']>,
  attempts = 8,
  heldForMs = 3_000,
): Promise<void> {
  for (let attempt = 0; attempt < attempts; attempt++) {
    await expect(control).toBeEnabled({ timeout: 15_000 });
    const before = await readHeaderPage(page);
    await control.click();

    // Wait generously for the commit; a cold chapter render is a real
    // backend extraction plus srcdoc swap.
    const deadline = Date.now() + 12_000;
    let moved = false;
    while (Date.now() < deadline) {
      await page.waitForTimeout(250);
      const current = await readHeaderPage(page);
      if (current !== null && current !== before) {
        moved = true;
        break;
      }
    }
    if (!moved) continue; // never moved: try again

    // Confirm it stays off the original value, so a bounce is not read as a turn.
    const holdDeadline = Date.now() + heldForMs;
    let held = true;
    while (Date.now() < holdDeadline) {
      await page.waitForTimeout(250);
      if ((await readHeaderPage(page)) === before) {
        held = false;
        break;
      }
    }
    if (held) return;
  }
}

/** Closes the chapter and waits for the app shell to come back. */
export async function closeReader(page: Page): Promise<void> {
  // The reader top bar's first button is "back" (`onBackToHome`); its label is
  // i18n, so it is targeted by position, exactly as `goToLibrary` does.
  await page.locator('header button').first().click();
  await expect(page.locator('aside nav > button').first()).toBeVisible({ timeout: 30_000 });
  await expect(page.locator('iframe[title="chapter"]')).toHaveCount(0);
}

/**
 * Opens `book` through the shared harness entry point and asserts the chapter
 * actually rendered.
 *
 * `openBook` locates the read control by role + accessible name, never by
 * position: the card's first button is the *title*, whose handler opens the
 * detail modal. Re-driving that click inline here would re-introduce the exact
 * defect this spec exists to catch.
 *
 * The poll **re-resolves the frame** each attempt rather than reading the one
 * `openBook` returned. `useEpubRender` assigns `iframeEl.srcdoc` asynchronously
 * and replaces it again as the chapter's assets settle (measured: three swaps
 * for one opening), so a `Frame` captured at first paint points at a document
 * that is about to be discarded — polling it reads the stale, empty one.
 */
export async function openAndAssertChapter(page: Page, book: SmokeBook): Promise<void> {
  await openBook(page, book);
  await expect
    .poll(
      async () => {
        const live = await findChapterFrame(page);
        if (!live) return 0;
        return live.evaluate(() => document.body.innerText.trim().length).catch(() => 0);
      },
      { timeout: 45_000 },
    )
    .toBeGreaterThan(0);
}

/** The live chapter frame, re-resolved (never a stale reference). */
export async function liveChapterFrame(page: Page): Promise<Frame> {
  const frame = await findChapterFrame(page);
  if (!frame) throw new Error('the reader has no chapter frame on screen');
  return frame;
}

/** How much selectable, on-screen prose the chapter frame currently offers. */
async function chapterProseLength(frame: Frame): Promise<number> {
  const visibleHeight = await frame
    .locator('body')
    .evaluate(() => (globalThis as unknown as { parent: { innerHeight: number } }).parent.innerHeight)
    .catch(() => 800);

  return frame.evaluate((visible) => {
    const SKIP = new Set(['SCRIPT', 'STYLE', 'NOSCRIPT', 'TEMPLATE']);
    const walker = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT, {
      acceptNode(node) {
        let el: Node | null = node.parentElement;
        while (el) {
          if (SKIP.has(el.nodeName)) return NodeFilter.FILTER_REJECT;
          el = el.parentElement;
        }
        return NodeFilter.FILTER_ACCEPT;
      },
    });
    let best = 0;
    let node = walker.nextNode();
    while (node) {
      const trimmed = (node.nodeValue ?? '').trim();
      const rect = (node as Text).parentElement?.getBoundingClientRect();
      const onScreen = rect ? rect.top >= 0 && rect.bottom <= visible : false;
      if (onScreen && trimmed.length > best) best = trimmed.length;
      node = walker.nextNode();
    }
    return best;
  }, visibleHeight);
}

/**
 * Turns forward until the reader is on a chapter with enough on-screen prose to
 * select a run from.
 *
 * The highlight step needs selectable text, and the chapter a turn lands on is
 * not a choice the spec gets to make: this book's spine carries part-title pages
 * ("Primera parte" / "Del Edén a Cajamarca", the longest visible run 20 chars)
 * between prose chapters, so a fixed number of turns from the seeded prologue
 * reaches one and there is nothing to highlight. Reading on until there is
 * something to mark is what a person does, and it removes the dependency on
 * where the turns happened to land.
 */
export async function seekSelectableChapter(page: Page, maxTurns = 6): Promise<Frame> {
  for (let turn = 0; turn <= maxTurns; turn++) {
    const frame = await liveChapterFrame(page);
    if ((await chapterProseLength(frame)) >= 30) return frame;
    if (turn === maxTurns) break;
    const next = page.locator('[data-testid="bottom-bar-controls"] button').nth(1);
    await expect(next).toBeEnabled({ timeout: 15_000 });
    await next.click();
    await page.waitForTimeout(2_000);
  }
  throw new Error(
    `no chapter with selectable prose within ${maxTurns} forward turns of the reader`,
  );
}

/**
 * Selects a real run of book characters inside the chapter frame and returns it.
 *
 * The range is set with the **frame's own** `Selection` API (the parent cannot
 * select nodes the chapter owns) and the gesture the chapter's script waits for
 * is synthesised in the frame — the same boundary `readerSelection.spec.ts`
 * crosses. The chapter debounces that `mouseup` by 100 ms before it posts
 * `epub-selection`, so the caller polls for the toolbar rather than reading it
 * once.
 *
 * The walker skips `script` / `style` / `noscript` subtrees: the chapter
 * document is not just prose, the reader injects its own bridge script into it,
 * and the first text node ≥30 chars in document order was that script's source.
 * A selection over script source is not a user-visible run, so it produces no
 * toolbar.
 *
 * The candidate must also be within the reader's visible area. The toolbar is
 * positioned from the selection's rect and a run deep in a long chapter lands
 * off screen with it, where the swatch cannot be clicked ("element is outside of
 * the viewport", retried until the test times out). Note the frame's own
 * `window.innerHeight` is the **content** height, not the visible one — the
 * iframe is sized to the chapter (measured: 18 874 px) — so the visible band is
 * taken from the parent viewport and the frame's current scroll offset.
 */
export async function selectTextInChapter(frame: Frame, maxChars = 40): Promise<string | null> {
  const visibleHeight = await frame
    .locator('body')
    .evaluate(() => (globalThis as unknown as { parent: { innerHeight: number } }).parent.innerHeight)
    .catch(() => 800);

  const text = await frame.evaluate(([limit, visible]) => {
    const SKIP = new Set(['SCRIPT', 'STYLE', 'NOSCRIPT', 'TEMPLATE']);
    const walker = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT, {
      acceptNode(node) {
        let el: Node | null = node.parentElement;
        while (el) {
          if (SKIP.has(el.nodeName)) return NodeFilter.FILTER_REJECT;
          el = el.parentElement;
        }
        return NodeFilter.FILTER_ACCEPT;
      },
    });

    let node = walker.nextNode();
    while (node) {
      const value = node.nodeValue ?? '';
      const trimmed = value.trim();
      const rect = (node as Text).parentElement?.getBoundingClientRect();
      const onScreen = rect ? rect.top >= 0 && rect.bottom <= visible : false;
      if (trimmed.length >= 30 && onScreen) break;
      node = walker.nextNode();
    }
    if (!node?.nodeValue) return null;

    const data = node.nodeValue;
    const start = data.search(/\S/);
    if (start < 0) return null;
    let end = Math.min(data.length, start + limit);
    const ws = data.lastIndexOf(' ', end);
    if (ws > start + 4) end = ws;

    const range = document.createRange();
    range.setStart(node, start);
    range.setEnd(node, end);
    const selection = document.getSelection();
    if (!selection) return null;
    selection.removeAllRanges();
    selection.addRange(range);
    return range.toString();
  }, [maxChars, visibleHeight] as const);

  if (text === null) return null;
  await frame.evaluate(() => {
    document.dispatchEvent(new MouseEvent('mouseup', { bubbles: true }));
  });
  return text;
}

/** The highlights the backend reports for `bookId`, over the real IPC bridge. */
export async function listHighlights(page: Page, bookId: string): Promise<Array<{ id: string }>> {
  return page.evaluate(async (id) => {
    const internals = (
      window as unknown as {
        __TAURI_INTERNALS__?: { invoke: (cmd: string, args?: unknown) => Promise<unknown> };
      }
    ).__TAURI_INTERNALS__;
    if (!internals) throw new Error('__TAURI_INTERNALS__ is missing — not a Tauri webview');
    return (await internals.invoke('listHighlights', { bookId: id })) as Array<{ id: string }>;
  }, bookId);
}

/** Adds one highlight through the selection toolbar. Returns the text it used. */
export async function addHighlight(page: Page, chapter: Frame): Promise<string> {
  const selected = await selectTextInChapter(chapter);
  expect(selected, 'the chapter had no selectable text run to highlight').not.toBeNull();

  const toolbar = page.locator('.selection-toolbar');
  // The toolbar is rendered by the parent workspace after the frame's
  // `epub-selection` postMessage crosses the boundary, so a single read races it.
  await expect(toolbar).toBeVisible({ timeout: 15_000 });

  // The five colour swatches carry an i18n'd accessible name. Match the
  // palette's own label ("Yellow" / "Amarillo") so this does not depend on one
  // locale's copy.
  await toolbar
    .getByRole('button', { name: /yellow|amarillo/i })
    .first()
    .click();
  // The write is a real IPC round trip plus a re-render of the overlay.
  await expect(toolbar).toBeHidden({ timeout: 15_000 });
  return selected!;
}
