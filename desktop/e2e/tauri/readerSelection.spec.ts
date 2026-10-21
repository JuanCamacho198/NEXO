/**
 * Text selection and its toolbar, driven against the real app over CDP.
 *
 * ## Why this exists, and why nothing else can prove it
 *
 * Selecting text in the chapter and getting `SelectionToolbar` over it is the
 * reader's core interaction. The toolbar's placement is computed in
 * `ReaderWorkspace.svelte` (`computedToolbarX` / `computedToolbarY`) with
 * clamping against the container edges, and mirrored inside
 * `SelectionToolbar.svelte` (`toolbarX` / `toolbarY`). Every test of that
 * arithmetic today runs in jsdom, where **there is no layout engine**: every
 * `getBoundingClientRect()` is zero, so the clamping branch
 * (`selectionBounds.top > HEIGHT + OFFSET ? … : …`) always takes the same side
 * and the container-edge clamp never engages. jsdom proves the expression
 * evaluates; it cannot prove where the toolbar lands.
 *
 * This spec closes that gap: it selects a real run of characters in a real
 * chapter and asserts the toolbar is **inside the viewport**, which is the
 * property the clamping math exists to guarantee.
 *
 * ## Reaching the selection across the frame boundary
 *
 * The chapter is a same-origin `about:srcdoc` frame, and the selection has to be
 * made with **that frame's** `Selection`/`Range` — a Range cannot span two
 * documents, and the parent's `window.getSelection()` reports nothing about a
 * child frame's selection. The toolbar, by contrast, lives in the **parent**
 * document (`SelectionToolbar` is rendered by `ReaderWorkspace`). So this spec
 * deliberately asserts across a boundary: set the range in the frame, observe
 * the toolbar in the parent. That boundary is exactly where the app's own
 * `epub-selection` postMessage bridge lives.
 *
 * The gesture is not synthesised with `page.mouse`: a real drag would require
 * scrolling the iframe to real page coordinates, which is fragile and tests the
 * scroll position more than the toolbar. Instead the chapter's own selection
 * script is driven the way it expects — set the range, then dispatch `mouseup`
 * in the frame, which is the event that script listens for.
 *
 * ## What it proves, and what it does not
 *
 * It proves: a real selection in a real chapter surfaces the toolbar, the
 * toolbar sits within the viewport, and dismissing it removes the toolbar.
 * For the **click-away** dismissal it also proves the chapter selection is
 * cleared. The last test is the contract table of §2.2/§2.3 — the five cases
 * Android already passes and desktop did not.
 *
 * It does NOT prove the toolbar's exact pixel position, the colour-swatch
 * rendering, the copy/dictionary actions, or that a highlight is created from
 * the toolbar — those are separate concerns (`readerHighlights` covers the
 * render path). It also does not prove a selection on a *paged* layout: the
 * chapter here is the single scrolled iframe the EPUB viewer builds.
 *
 * ## Known defect, now fixed
 *
 * The **Escape** dismissal used to close the toolbar without collapsing the
 * chapter frame's selection — measured against the live app (frame
 * `getSelection()` still returned the selected run after Escape). The viewer's
 * Escape path now calls `clearIframeSelection()` alongside `onselectionclear`,
 * and the second test below asserts the collapse.
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
import {
  clickInsideChapter,
  deleteHighlight,
  highlightIds,
  readFrameSelection,
  readHighlight,
  readToolbarRect,
  scrollChapter,
  scrollToChapterProse,
  selectTextInChapter,
  waitForChapterFrame,
} from './harness/readerFlows';
import { openUsableEpub } from './harness/readerCapacity';
import type { LibraryRow } from './harness/readerFlows';
import type { Frame, Page } from '@playwright/test';

/** The toolbar element the app renders into the parent document. */
const TOOLBAR = '.selection-toolbar';

/**
 * A book whose chapter renders prose, opened and positioned on it.
 *
 * `openUsableEpub` is the spec-facing entry point, and it must be the one used
 * here rather than `selectUsableEpub` + a bare seek. The selection is cached for
 * the process (`workers: 1`), so after an earlier spec has run,
 * `selectUsableEpub` returns the book chosen then **without driving the app into
 * it** — while the reader is still showing whatever that earlier spec left open.
 * Measured, and it is what made this spec resolve *Don Quijote* while the screen
 * held *Prisioneros*: every highlight it then created was written to a book the
 * assertions never read, and case 5 failed on a count that never moved.
 *
 * `openUsableEpub` opens the chosen book and reads on past covers and part
 * titles, so the frame it returns is the one the returned `bookId` names.
 *
 * Skips the caller with a reason — never asserts — when the library holds no such
 * book. Returns the frame so the caller does not re-resolve it.
 */
async function openProseChapter(
  page: Page,
): Promise<{ frame: Frame; bookId: string; bookTitle: string }> {
  let opened: { book: LibraryRow; textLen: number; turns: number };
  try {
    opened = await openUsableEpub(page);
  } catch (err) {
    test.skip(true, `No library EPUB rendered prose: ${String(err).slice(0, 200)}`);
    throw err;
  }

  const frame = await waitForChapterFrame(page);
  return { frame, bookId: opened.book.id, bookTitle: opened.book.title };
}

test('a real chapter selection surfaces the toolbar inside the viewport', async ({ appPage }) => {
  test.setTimeout(180_000);

  const { frame: chapter } = await openProseChapter(appPage);

  const selected = await selectTextInChapter(chapter);
  test.skip(
    selected === null || selected.trim().length === 0,
    'No selectable text node was found in the chapter.',
  );

  // The toolbar is rendered by the PARENT workspace in response to the frame's
  // `epub-selection` postMessage. It must cross that boundary — assert it in the
  // parent document, not the frame.
  await expect(appPage.locator(TOOLBAR)).toBeVisible({ timeout: 10_000 });

  // Wait for a measurable rect: the toolbar enters with an `in:scale` transition
  // and `boundingBox()` is null while it is mid-flight.
  await expect
    .poll(async () => (await readToolbarRect(appPage)) !== null, { timeout: 5_000 })
    .toBe(true);
  const rect = (await readToolbarRect(appPage))!;

  // The toolbar must sit inside the viewport. Assert containment, not pixels:
  // a live window has no committed geometry, and the point of the clamping math
  // is that the toolbar never escapes the edges.
  expect(rect.x).toBeGreaterThanOrEqual(0);
  expect(rect.y).toBeGreaterThanOrEqual(0);
  expect(rect.x + rect.width).toBeLessThanOrEqual(rect.viewportWidth);
  expect(rect.y + rect.height).toBeLessThanOrEqual(rect.viewportHeight);

  // The frame genuinely holds a selection too: a Range set in the frame's own
  // document is readable back through the frame's own Selection, which is what
  // makes the boundary assertion above meaningful rather than a coincidence.
  const selectionInFrame = await readFrameSelection(chapter);
  expect(selectionInFrame.length).toBeGreaterThan(0);
});

test('dismissing the toolbar clears it, and the click-away path clears the selection', async ({
  appPage,
}) => {
  test.setTimeout(180_000);

  const { frame: chapter } = await openProseChapter(appPage);

  const selected = await selectTextInChapter(chapter);
  test.skip(
    selected === null || selected.trim().length === 0,
    'No selectable text node was found in the chapter.',
  );

  const toolbar = appPage.locator(TOOLBAR);
  await expect(toolbar).toBeVisible({ timeout: 10_000 });

  // Escape. The reader's Escape path runs `onselectionclear` before the
  // immersive chrome consumes it (`EpubNativeViewer.handleKeydown`), so one
  // Escape closes the toolbar and nothing else — and it collapses the chapter's
  // own selection with it, which it previously did not.
  await appPage.keyboard.press('Escape');
  await expect(toolbar).toBeHidden({ timeout: 5_000 });
  await expect.poll(() => readFrameSelection(chapter), { timeout: 5_000 }).toBe('');

  // Re-select and dismiss by clicking away in the parent chrome. The parent
  // listens on `window` pointerdown (`handleWorkspacePointerDown`), which calls
  // `clearIframeSelection()` (collapses the frame selection) and `dismissToolbar()`.
  const reselected = await selectTextInChapter(chapter);
  test.skip(
    reselected === null || reselected.trim().length === 0,
    'No selectable text node was found for the click-away dismissal.',
  );
  await expect(toolbar).toBeVisible({ timeout: 10_000 });
  expect((await readFrameSelection(chapter)).length).toBeGreaterThan(0);

  // Click a neutral spot in the parent chrome — the footer's left span, which
  // owns no handler. A click on the top bar's back button would leave the reader.
  await appPage
    .locator('footer span')
    .first()
    .click({ position: { x: 4, y: 4 } });
  await expect(toolbar).toBeHidden({ timeout: 5_000 });

  // The chapter's own selection must be gone too — the toolbar going away while
  // the text stays highlighted would leave an orphaned selection.
  await expect.poll(() => readFrameSelection(chapter), { timeout: 5_000 }).toBe('');
});

/**
 * The contract table of `READER-AND-SYNC-CONTRACT.md` §2.2/§2.3: the menu closes
 * when the selection ends, and applying a colour does not reopen it.
 *
 * ## Why this spec and not a unit test
 *
 * Every case crosses the iframe boundary, and that boundary is the whole bug:
 * a click outside the chapter never reaches the chapter's document, a click
 * inside it never reaches the parent, and the dismissal has to be emitted from
 * whichever side saw the gesture. A jsdom test cannot reproduce the boundary
 * (it has no separate documents, no `frameElement`, no real selection), so a
 * passing unit test there would prove nothing about the product.
 *
 * ## The five cases, measured
 *
 * Android passes all five (contract §2.2). This asserts desktop does too, in
 * the same words: the toolbar is gone AND the selection is cleared.
 *
 * Case 5 also asserts the colour was **persisted**, because the contract's
 * §2.4 records that the colour path works and that a screenshot once reported
 * otherwise. The database is the authority, so the assertion reads it over IPC
 * — and the highlight this test creates is deleted again on the way out.
 */
test('the selection menu closes in all five contract cases', async ({ appPage }) => {
  test.setTimeout(300_000);

  const { bookId, bookTitle } = await openProseChapter(appPage);
  // The entry restore and the column layout settle after the frame appears; a
  // selection made mid-restore is measured against a chapter that is still
  // moving.
  await appPage.waitForTimeout(2_500);
  // What the footer says is the book the reader is on, and every assertion in
  // case 5 reads highlights for `bookId`. If the reader and the id disagree, the
  // colour is written somewhere this spec is not looking — so the two are
  // required to match before any case runs.
  const shownTitle = (await appPage.locator('header').innerText()).replace(/\s+/g, ' ').trim();
  expect(
    shownTitle.includes(bookTitle),
    `the reader is showing "${shownTitle}" but the spec resolved the book as "${bookTitle}"`,
  ).toBe(true);

  const toolbar = appPage.locator(TOOLBAR);

  /** The footer's page field — the chapter the reader is showing. */
  const pageField = async (): Promise<number | null> =>
    appPage.evaluate(() => {
      const input = document.querySelector(
        '[data-testid="bottom-bar-controls"] input',
      ) as HTMLInputElement | null;
      const value = input ? Number.parseInt(input.value, 10) : Number.NaN;
      return Number.isFinite(value) ? value : null;
    });

  /**
   * Waits until the toolbar stops being replaced.
   *
   * A release emits its selection **twice** — an immediate form with no rects and
   * a debounced authoritative one (~100 ms later) carrying them. The parent
   * allows the second through (they differ, so it cannot dedupe them by payload)
   * and re-renders the toolbar for it. Measured: the element Playwright resolved
   * a moment earlier is then *detached*, and `click()` retries against a node that
   * no longer exists until the test times out.
   *
   * So the handle is used only after two consecutive geometry reads one frame
   * apart agree — the same rule the harness uses elsewhere: sample until the value
   * stops changing, then act.
   */
  const toolbarSettled = async (): Promise<void> => {
    let stableIdentity = 0;
    for (let i = 0; i < 40; i++) {
      // Element identity, not geometry: the debounced message re-mounts the
      // toolbar at the same spot, so a rect comparison calls it settled while
      // Playwright is still holding the previous node.
      const seen = await appPage.evaluate((sel) => {
        const el = document.querySelector(sel);
        if (!el) return '';
        (window as unknown as { __toolbarNode?: Element }).__toolbarNode ??= el;
        return (window as unknown as { __toolbarNode?: Element }).__toolbarNode === el
          ? 'same'
          : 'new';
      }, TOOLBAR);
      if (seen === '') {
        stableIdentity = 0;
        await appPage.evaluate(() => {
          delete (window as unknown as { __toolbarNode?: Element }).__toolbarNode;
        });
      } else if (seen === 'same') {
        stableIdentity += 1;
        if (stableIdentity >= 3) return;
      } else {
        stableIdentity = 0;
      }
      await appPage.waitForTimeout(100);
    }
  };

  /**
   * Raises the toolbar on a real run of text inside the chapter on screen.
   *
   * The chapter is re-resolved and re-selected on every attempt, because a
   * selection is only possible on prose that is currently laid out: the chapter
   * a turn lands on can be a part-title page with nothing to select, and the
   * `srcdoc` document is replaced (three times for one opening) as assets
   * settle. A capture of either would make the next case measure a document
   * that no longer exists.
   *
   * A `null` from `selectTextInChapter` means the chapter on screen has no text
   * node **in the visible band**; a selection that produces no toolbar means the
   * chapter was still settling when the synthetic `mouseup` was dispatched. Both
   * are "this chapter is not ready where it is", and both are retried.
   *
   * ## Why it scrolls before giving up
   *
   * A page turn leaves the container at the offset the chapter's CFI restore
   * names, and measured on this machine that can be the **bottom** of the new
   * chapter: `scrollTop` 26875 with `scrollHeight - clientHeight` also 26875, so
   * the visible band starts at the last text node and contains none of it. The
   * toolbar is anchored to the selection's rect, so selecting there is not an
   * option — there is nothing on screen to select. A person in that position
   * scrolls back to the text, which is what this does before turning on.
   *
   * The order is deliberate: try where the reader landed, then bring the prose
   * into view, then turn. Turning first would skip a chapter that was perfectly
   * usable once it was scrolled.
   *
   * ## Why running out of book SKIPS
   *
   * The book is opened on a chapter measured to contain prose, so the first
   * attempt normally succeeds. If it does not, the reader turns on — and at the
   * book's last chapter the Next control is **disabled** (measured: a previous
   * version of this helper failed with `unexpected value "disabled"` here). A
   * `disabled` control means there is no further chapter to look for prose in,
   * which is a library/book precondition this spec cannot satisfy, not a defect
   * in the code it covers. So it stops turning and the caller reports a skip
   * naming the book and the turn budget — never a green pass, never a failure
   * for the wrong reason.
   */
  const raiseToolbar = async (maxTurns = 6): Promise<void> => {
    // Forget the previous case's node so this call tracks only the toolbar it
    // raises, not one that was dismissed between cases.
    await appPage.evaluate(() => {
      delete (window as unknown as { __toolbarNode?: Element }).__toolbarNode;
    });
    for (let attempt = 0; attempt <= maxTurns; attempt++) {
      for (const scrollIntoView of [false, true]) {
        if (scrollIntoView) await scrollToChapterProse(await waitForChapterFrame(appPage), appPage);
        const live = await waitForChapterFrame(appPage);
        const selected = await selectTextInChapter(live);
        if (selected === null || selected.trim().length === 0) continue;
        try {
          await expect(toolbar).toBeVisible({ timeout: 3_000 });
          await toolbarSettled();
          return;
        } catch {
          // The chapter was still settling under the selection; try again.
        }
      }
      if (attempt === maxTurns) break;

      const next = appPage.locator('[data-testid="bottom-bar-controls"] button').nth(1);
      if (!(await next.isEnabled().catch(() => false))) {
        test.skip(
          true,
          `"${bookTitle}" has no further chapter with selectable prose — the reader reached ` +
            `the end of the book after ${attempt + 1} chapter(s).`,
        );
      }
      await next.click();
      await appPage.waitForTimeout(2_500);
    }
    test.skip(true, 'No chapter with selectable on-screen prose within the turn budget.');
  };

  /**
   * Waits until the chapter frame holds no selection.
   *
   * `expect.poll` needs a synchronous callback, and resolving the frame is
   * async, so the wait is spelled out: re-resolve, read, sleep, repeat. The
   * frame is re-resolved each attempt because a navigation replaces the
   * `srcdoc` document and a captured `Frame` would then be read stale.
   */
  const expectFrameSelectionEmpty = async (): Promise<void> => {
    const deadline = Date.now() + 5_000;
    let last = '(not read)';
    while (Date.now() < deadline) {
      last = await readFrameSelection(await waitForChapterFrame(appPage));
      if (last.length === 0) return;
      await appPage.waitForTimeout(200);
    }
    expect(last, 'the chapter frame still held a selection').toBe('');
  };

  // ── Case 1 — click outside the text ───────────────────────────────────────
  await raiseToolbar();
  await appPage
    .locator('footer span')
    .first()
    .click({ position: { x: 4, y: 4 } });
  await expect(toolbar).toBeHidden({ timeout: 5_000 });
  await expectFrameSelectionEmpty();

  // ── Case 2 — click inside the book but not selecting ──────────────────────
  await raiseToolbar();
  await clickInsideChapter(await waitForChapterFrame(appPage));
  await expect(toolbar).toBeHidden({ timeout: 5_000 });
  await expectFrameSelectionEmpty();

  // ── Case 3 — scroll ───────────────────────────────────────────────────────
  await raiseToolbar();
  await scrollChapter(appPage);
  await expect(toolbar).toBeHidden({ timeout: 5_000 });
  // Contract §2.2 requires BOTH halves for scrolling: "menu closes, selection
  // clears". The menu half passed before the fix; the selection half did not —
  // the parent's scroll path called `onselectionclear` (menu only) and never
  // `clearIframeSelection`, and the frame's own `scroll` clear is suppressed
  // while a drag is in flight. Assert the chapter selection is gone too.
  await expectFrameSelectionEmpty();

  // ── Case 4 — navigate (page turn) ─────────────────────────────────────────
  await raiseToolbar();
  await appPage.locator('[data-testid="bottom-bar-controls"] button').nth(1).click();
  await expect(toolbar).toBeHidden({ timeout: 5_000 });
  // The turn is a real backend chapter extraction plus an srcdoc swap, and it
  // commits seconds later. Raising the next toolbar while it is still landing
  // produced one that the turn's own dismissal then cleared underneath the
  // click — measured as `swatch.click()` waiting on a locator that had gone,
  // until the test timed out. Wait for the page field to stop moving first.
  await expect
    .poll(
      async () => {
        const a = await pageField();
        await appPage.waitForTimeout(250);
        const b = await pageField();
        return a !== null && a === b;
      },
      { timeout: 30_000 },
    )
    .toBe(true);

  // ── Case 5 — applying a colour never reopens the menu ─────────────────────
  await raiseToolbar();
  const before = await highlightIds(appPage, bookId);

  const swatch = toolbar.getByRole('button', { name: /yellow|amarillo/i }).first();
  await swatch.click();

  // The toolbar leaves after a 220 ms transition; the defect was that it came
  // back, so it is polled well past that window rather than sampled once.
  await expect(toolbar).toBeHidden({ timeout: 5_000 });
  await appPage.waitForTimeout(2_500);
  expect(await toolbar.count()).toBe(0);

  // The colour must still be applied and persisted (contract §2.4) — the menu
  // bug was never a data bug, and this is the proof the fix did not become one.
  //
  // Compared as ID SETS, not counts. A count is satisfied by any write that
  // happens to land in the window (a sync pull, a retried save from another
  // spec), and this book accumulates highlights across runs — measured at 115 on
  // one book in this library. The new id is what proves *this* click wrote.
  let added: string[] = [];
  await expect
    .poll(
      async () => {
        const after = await highlightIds(appPage, bookId);
        added = [...after].filter((id) => !before.has(id));
        return added.length;
      },
      { timeout: 15_000 },
    )
    .toBeGreaterThan(0);
  await expectFrameSelectionEmpty();

  // Clean up: every highlight this case created is removed, so the run does not
  // leave the developer's book permanently marked. Previously only the last row
  // was deleted, so a run that wrote more than one leaked the rest — which is how
  // the books in this library reached 115 highlights.
  for (const id of added) {
    const row = await readHighlight(appPage, bookId, id);
    expect(
      row,
      'the highlight the colour click created is not readable from the backend',
    ).not.toBeNull();
    await deleteHighlight(appPage, id);
  }
});
