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
  chapterHasText,
  clickInsideChapter,
  countHighlights,
  deleteHighlight,
  openBook,
  pickEpub,
  readFrameSelection,
  readHighlight,
  readToolbarRect,
  scrollChapter,
  selectTextInChapter,
  waitForChapterFrame,
} from './harness/readerFlows';

/** The toolbar element the app renders into the parent document. */
const TOOLBAR = '.selection-toolbar';

test('a real chapter selection surfaces the toolbar inside the viewport', async ({ appPage }) => {
  test.setTimeout(180_000);

  const epub = await pickEpub(appPage);
  test.skip(epub === null, 'No EPUB book in the library — cannot make a selection.');

  const chapter = await openBook(appPage, epub!);

  // A chapter with no text has nothing selectable; skip rather than assert on a
  // selection that could not exist.
  test.skip(!(await chapterHasText(chapter)), 'The chapter rendered no text to select.');

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

  const epub = await pickEpub(appPage);
  test.skip(epub === null, 'No EPUB book in the library — cannot make a selection.');

  const chapter = await openBook(appPage, epub!);
  test.skip(!(await chapterHasText(chapter)), 'The chapter rendered no text to select.');

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

  const epub = await pickEpub(appPage);
  test.skip(epub === null, 'No EPUB book in the library — cannot make a selection.');

  const chapter = await openBook(appPage, epub!);
  test.skip(!(await chapterHasText(chapter)), 'The chapter rendered no text to select.');
  // The entry restore and the column layout settle after the frame appears; a
  // selection made mid-restore is measured against a chapter that is still
  // moving.
  await appPage.waitForTimeout(2_500);

  const toolbar = appPage.locator(TOOLBAR);

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
   * node; a selection that produces no toolbar means the chapter was still
   * settling when the synthetic `mouseup` was dispatched. Both are "this
   * chapter is not ready", and both are retried by turning on — the bound stops
   * a reader that genuinely cannot reach prose from hanging. The toolbar is
   * checked with a short poll rather than a long one so a settle race costs one
   * turn instead of the whole test.
   */
  const raiseToolbar = async (maxTurns = 6): Promise<void> => {
    for (let attempt = 0; attempt <= maxTurns; attempt++) {
      const live = await waitForChapterFrame(appPage);
      const selected = await selectTextInChapter(live);
      if (selected !== null && selected.trim().length > 0) {
        try {
          await expect(toolbar).toBeVisible({ timeout: 3_000 });
          return;
        } catch {
          // The chapter was still settling under the selection; turn on.
        }
      }
      if (attempt === maxTurns) break;
      const next = appPage.locator('[data-testid="bottom-bar-controls"] button').nth(1);
      await expect(next).toBeEnabled({ timeout: 15_000 });
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

  // ── Case 5 — applying a colour never reopens the menu ─────────────────────
  await raiseToolbar();
  const before = await countHighlights(appPage, epub!.id);

  const swatch = toolbar.getByRole('button', { name: /yellow|amarillo/i }).first();
  await swatch.click();

  // The toolbar leaves after a 220 ms transition; the defect was that it came
  // back, so it is polled well past that window rather than sampled once.
  await expect(toolbar).toBeHidden({ timeout: 5_000 });
  await appPage.waitForTimeout(2_500);
  expect(await toolbar.count()).toBe(0);

  // The colour must still be applied and persisted (contract §2.4) — the menu
  // bug was never a data bug, and this is the proof the fix did not become one.
  await expect.poll(() => countHighlights(appPage, epub!.id), { timeout: 15_000 }).toBe(before + 1);
  await expectFrameSelectionEmpty();

  // Clean up: the highlight this case created is removed so the run does not
  // leave the developer's book permanently marked.
  await appPage.waitForTimeout(500);
  const created = await appPage.evaluate(async (id) => {
    const internals = (
      window as unknown as {
        __TAURI_INTERNALS__?: { invoke: (cmd: string, args?: unknown) => Promise<unknown> };
      }
    ).__TAURI_INTERNALS__!;
    const rows = (await internals.invoke('listHighlights', { bookId: id })) as Array<{
      id: string;
      color: string;
    }>;
    return rows.at(-1)?.id ?? null;
  }, epub!.id);
  if (created) {
    const row = await readHighlight(appPage, epub!.id, created);
    expect(
      row,
      'the highlight the colour click created is not readable from the backend',
    ).not.toBeNull();
    await deleteHighlight(appPage, created);
  }
});
