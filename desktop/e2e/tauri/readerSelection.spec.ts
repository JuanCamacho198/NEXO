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
 * cleared.
 *
 * It does NOT prove the toolbar's exact pixel position, the colour-swatch
 * rendering, the copy/dictionary actions, or that a highlight is created from
 * the toolbar — those are separate concerns (`readerHighlights` covers the
 * render path). It also does not prove a selection on a *paged* layout: the
 * chapter here is the single scrolled iframe the EPUB viewer builds.
 *
 * ## Known defect NOT asserted here (measured, reported separately)
 *
 * The **Escape** dismissal closes the toolbar but does **not** collapse the
 * chapter frame's selection — only the click-away path does (it runs through
 * `handleWorkspacePointerDown`, which calls `clearIframeSelection()`). This was
 * measured against the live app (frame `getSelection()` still returned the
 * selected run after Escape). The Escape test below therefore asserts only that
 * the toolbar is dismissed; asserting the selection clears after Escape would
 * encode a `src/` bug fix this change is not authorised to make. Fixing it is a
 * one-line change in `EpubNativeViewer.handleKeydown` (call `clearIframeSelection`
 * alongside `onselectionclear`), recorded for the owner rather than applied here.
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
  openBook,
  pickEpub,
  readFrameSelection,
  readToolbarRect,
  selectTextInChapter,
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
  await expect.poll(async () => (await readToolbarRect(appPage)) !== null, { timeout: 5_000 }).toBe(true);
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
  // Escape closes the toolbar and nothing else.
  //
  // Only the toolbar is asserted here: the Escape path does NOT collapse the
  // chapter's frame selection (measured; see the header). Asserting it would
  // encode a src/ defect this change is not authorised to fix — the click-away
  // block below proves the selection-clearing contract on the path that
  // implements it.
  await appPage.keyboard.press('Escape');
  await expect(toolbar).toBeHidden({ timeout: 5_000 });

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
  await appPage.locator('footer span').first().click({ position: { x: 4, y: 4 } });
  await expect(toolbar).toBeHidden({ timeout: 5_000 });

  // The chapter's own selection must be gone too — the toolbar going away while
  // the text stays highlighted would leave an orphaned selection.
  await expect.poll(() => readFrameSelection(chapter), { timeout: 5_000 }).toBe('');
});
