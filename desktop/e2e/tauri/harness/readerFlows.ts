/**
 * Session helpers for the reader-flow CDP specs.
 *
 * The three reader specs (`readerSelection`, `readerHighlights`,
 * `readerCoherence`) all need the same three things, and each of them is
 * subtle enough that three copies would drift:
 *
 *   1. Find the chapter document. The chapter is a same-origin
 *      `about:srcdoc` frame (`useEpubRender` assigns `iframeEl.srcdoc`), so it
 *      is reachable by enumerating `page.frames()` and keying on the injected
 *      `<style id="nexo-reader-overrides">` — the marker `buildChapterSrcdoc`
 *      writes and which cannot appear in the app document or in the inspector
 *      frontend. This is the same trick `reader.spec.ts` uses; it is repeated
 *      here rather than imported so the CSS spec stays untouched.
 *   2. Pick a real EPUB from the developer's library. The library is not a
 *      fixture: `listLibraryBooks` is called over the real IPC bridge and the
 *      row comes back from Rust. Rows that carry a `coverPath` are preferred,
 *      because a *sync stub* (a row synced from another device with no local
 *      file) never renders — the cover is only written by a real local import.
 *   3. Drive the app from wherever it is into the reader for that book. A
 *      previous spec may have left the app in the reader, so the entry point
 *      normalises back to the library first.
 *
 * None of these helpers assert; they return what they found and let the spec
 * decide whether that is something to assert or something to skip on. That is
 * deliberate: the skip discipline (never pass vacuously) belongs to the spec,
 * at the point where the missing precondition is meaningful.
 */
import { expect, type Frame, type Page } from '@playwright/test';

/** Subset of the Rust `listLibraryBooks` row this harness needs. */
export type LibraryRow = {
  id: string;
  title: string;
  format?: string;
  coverPath?: string | null;
  /**
   * Absolute path to the book file. Empty on a *sync stub* — a placeholder row
   * for a book that was never downloaded here (the Rust DTO documents this
   * explicitly: "Consumers treat an empty path as 'not a real book'"). Optional
   * so a caller can build a row by hand without it.
   */
  filePath?: string;
};

/**
 * Returns the chapter frame: the one whose document carries the reader-override
 * `<style id="nexo-reader-overrides">`. Polls because the frame is created and
 * its `srcdoc` parsed asynchronously after the click.
 *
 * This deliberately does NOT wait for rendered text: a chapter legitimately has
 * none (a cover page), and the specs that must skip on that case
 * (`chapterHasText`) need the frame back so they can decide. Callers that need a
 * *settled* document — anything that selects, because the reader swaps the
 * `srcdoc` again as assets arrive and drives a document that would be discarded
 * — wait for their own precondition; see `selectTextInChapter`.
 */
export async function waitForChapterFrame(page: Page, timeoutMs = 30_000): Promise<Frame> {
  const deadline = Date.now() + timeoutMs;

  while (Date.now() < deadline) {
    for (const frame of page.frames()) {
      const hasOverrides = await frame
        .evaluate(() => Boolean(document.getElementById('nexo-reader-overrides')))
        .catch(() => false);
      if (hasOverrides) return frame;
    }
    await new Promise((resolve) => setTimeout(resolve, 250));
  }

  const urls = page.frames().map((frame) => frame.url());
  throw new Error(
    `No chapter frame with #nexo-reader-overrides appeared within ${timeoutMs}ms. ` +
      `Frames seen: ${urls.length > 0 ? urls.join(', ') : '(none)'}.`,
  );
}

/** The chapter frame, or null when there is none on this screen. */
export async function findChapterFrame(page: Page): Promise<Frame | null> {
  try {
    return await waitForChapterFrame(page, 1_000);
  } catch {
    return null;
  }
}

/**
 * Candidate rows for "a book a reader spec can run against", in the order they
 * should be tried, before any capability has been measured.
 *
 * A *sync stub* — a row synced from another device with no local file — never
 * renders, so it is out by rule: the library reports it with an empty
 * `filePath` (the DTO documents "Consumers treat an empty path as 'not a real
 * book'"), and the Rust migration reports these rows verbatim. Matching the
 * title is a *belt* on top of that brace, because a stub is also what
 * `scripts/s1-bounce-scroll-mode.mts` filters out by name.
 *
 * The order is deliberately **not** the shelf order (`updated_at DESC`, which
 * is what `listLibraryBooks` returns): the most recently opened book on this
 * machine is the one with the text-sparse spine, so trying in shelf order costs
 * the whole traversal before reaching a book that serves. Books that carry a
 * `coverPath` are tried first, which is the same preference `pickEpub` always
 * had, now only as an ordering hint rather than as proof of capability.
 */
export function usableEpubCandidates(rows: LibraryRow[]): LibraryRow[] {
  const isEpub = (row: LibraryRow): boolean => (row.format ?? '').toLowerCase() === 'epub';
  const hasLocalFile = (row: LibraryRow): boolean => (row.filePath ?? '').length > 0;
  const isStub = (row: LibraryRow): boolean => /sync stub/i.test(row.title);

  const epubs = rows.filter((row) => isEpub(row) && hasLocalFile(row) && !isStub(row));
  return [
    ...epubs.filter((row) => Boolean(row.coverPath)),
    ...epubs.filter((row) => !row.coverPath),
  ];
}

/**
 * Picks a real EPUB from the library over the live IPC bridge. Returns null
 * when the library holds no EPUB (the spec then skips with a reason).
 *
 * `format` is only trustworthy from the Rust side, so it is read from the
 * command's answer rather than guessed from a title.
 *
 * **This is a metadata pick, not a capability one.** A row that passes it can
 * still render an empty chapter — measured on this machine, where the first row
 * it returned (*Prisioneros de la geografía*) renders 0 characters across ten
 * consecutive spine chapters. Specs that need a chapter to *contain something*
 * must establish that by measurement; see `readerCapacity.ts` and
 * {@link pickUsableEpub}.
 */
export async function pickEpub(page: Page): Promise<LibraryRow | null> {
  return page.evaluate(async (): Promise<LibraryRow | null> => {
    const internals = (
      window as unknown as {
        __TAURI_INTERNALS__?: { invoke: (cmd: string, args?: unknown) => Promise<unknown> };
      }
    ).__TAURI_INTERNALS__;

    if (!internals) throw new Error('__TAURI_INTERNALS__ is missing — not a Tauri webview');

    const rows = (await internals.invoke('listLibraryBooks', {
      payload: { responseVersion: 1 },
    })) as LibraryRow[];

    const epubs = rows.filter((row) => (row.format ?? '').toLowerCase() === 'epub');
    return epubs.find((row) => Boolean(row.coverPath)) ?? epubs[0] ?? null;
  });
}

/**
 * Every library row, over the live IPC bridge, unfiltered. Specs that need to
 * choose a book by measurement start from this rather than from `pickEpub`,
 * whose first match is chosen by metadata alone.
 */
export async function readLibraryRows(page: Page): Promise<LibraryRow[]> {
  return page.evaluate(async (): Promise<LibraryRow[]> => {
    const internals = (
      window as unknown as {
        __TAURI_INTERNALS__?: { invoke: (cmd: string, args?: unknown) => Promise<unknown> };
      }
    ).__TAURI_INTERNALS__;

    if (!internals) throw new Error('__TAURI_INTERNALS__ is missing — not a Tauri webview');

    return (await internals.invoke('listLibraryBooks', {
      payload: { responseVersion: 1 },
    })) as LibraryRow[];
  });
}

/**
 * Normalises the app back to the library screen. A previous spec may have left
 * it in the reader, where the sidebar does not exist. The reader top bar is a
 * `<header>` whose first button is "back" (`onBackToHome`), and its label is
 * i18n, so the back affordance is targeted by position, not text.
 */
export async function goToLibrary(page: Page): Promise<void> {
  if ((await page.locator('iframe[title="chapter"]').count()) > 0) {
    await page.locator('header button').first().click();
    await expect(page.locator('aside nav > button').first()).toBeVisible({ timeout: 20_000 });
  }

  // The sidebar label is i18n ("Library" / "Estantería"). Match the label across
  // the locales this repo ships; the card locator below still finds a shelf card
  // if the nav entry is renamed.
  const libraryNav = page.locator('aside nav').getByRole('button', {
    name: /^(Library|Estanter[ií]a)$/,
  });
  if ((await libraryNav.count()) > 0) {
    await libraryNav.first().click();
  }
}

/**
 * Accessible names of the controls that enter the reader, across the locales
 * this repo ships. The label depends on the surface and the book's progress:
 *
 *   - `app.read` ("Read" / "Leer") — `BookCard` (home rail) fresh book, and the
 *     detail modal's CTA.
 *   - `app.continue` ("Continue reading" / "Seguir leyendo") — `BookCard` for
 *     an in-progress book.
 *   - `shelf.openBook` ("Open book" / "Abrir libro") — `ShelfGrid` secondary
 *     button.
 *   - `shelf.read` ("Read" / "Abrir") — `ShelfList` secondary button.
 *   - `shelf.start` / `shelf.continueReading` ("Start" / "Continue reading" /
 *     "Empezar" / "Continuar lectura") — `ShelfGrid` accent button.
 *   - `shelf.startReading` ("Start reading" / "Empezar lectura") — `ShelfList`
 *     accent button.
 *
 * Anchored with `^…$` so a book whose *title* happens to contain "Read" or
 * "Leer" cannot match: the title is a `<button>` too (`BookCard` makes the card
 * body clickable), and an unanchored match would pick it and open the detail
 * modal instead of the reader.
 */
const READ_BUTTON_NAME =
  /^(Read|Continue reading|Open book|Start|Start reading|Leer|Seguir leyendo|Abrir|Abrir libro|Empezar|Continuar lectura|Empezar lectura)$/;

/**
 * Opens `book` and returns the live chapter frame.
 *
 * `BookCard` (home rail) and `ShelfGrid` / `ShelfList` (shelf screen) all render
 * the book inside an `<article>` that contains the title, so the card locator is
 * stable across both screens.
 *
 * The read control is located by **role + accessible name**, never by position.
 * The card's first `<button>` is NOT the read action: `BookCard` makes the whole
 * body a button whose `onclick` is `onSelect` -> `onOpenDetails`, i.e. it opens
 * the detail modal. Picking "the first button with visible text" therefore opens
 * the modal, not the reader. Ordering the buttons differently (or adding one
 * above) must not change which control this helper clicks, so the name is the
 * only stable handle.
 *
 * If no reader control is found on the card — the layout changed, or the surface
 * only offers the modal — the detail dialog is used as a fallback: its own CTA
 * is `app.read` and routes to the same `startReading`. The fallback is a
 * recovery path, not the main one.
 */
export async function openBook(page: Page, book: LibraryRow): Promise<Frame> {
  await goToLibrary(page);

  const card = page.locator('article').filter({ hasText: book.title }).first();
  await expect(card).toBeVisible({ timeout: 30_000 });

  const readButton = card.getByRole('button', { name: READ_BUTTON_NAME }).first();

  if ((await readButton.count()) > 0) {
    await readButton.click();
  } else {
    // No reader control in the card: open the detail modal and read from there.
    await card.click();
    const dialog = page.locator('[role="dialog"][data-state="open"]');
    await expect(dialog).toBeVisible({ timeout: 10_000 });
    await dialog.getByRole('button', { name: READ_BUTTON_NAME }).first().click();
  }

  return waitForChapterFrame(page);
}

/**
 * True when the chapter frame actually rendered chapter text (not just the
 * injected overrides style in an empty document). A selection spec that runs
 * against an empty chapter must skip, not pass.
 */
export async function chapterHasText(frame: Frame): Promise<boolean> {
  const text = await frame.evaluate(() => document.body.innerText.trim()).catch(() => '');
  return text.length > 0;
}

/**
 * Selection geometry and gestures live in their own module — they are the one
 * part of this harness whose correctness depends on translating between the
 * chapter frame's coordinate space and the parent's viewport. Re-exported here
 * so a spec has a single import for "drive the reader".
 */
export {
  chapterVisibleBand,
  clickInsideChapter,
  onScreenProseLength,
  readFrameSelection,
  scrollToChapterProse,
  selectTextInChapter,
} from './readerSelection';

/**
 * Scrolls the reader by `delta` pixels and returns nothing; the caller asserts.
 *
 * The scrolling surface is `[data-testid="epub-scroll-container"]` in scrolling
 * mode (the default). The synthetic `scroll` event is dispatched in addition to
 * setting `scrollTop`, because a programmatic assignment does not always fire
 * one in WebView2 — and this harness must not depend on which engine behaviour
 * it gets.
 */
export async function scrollChapter(page: Page, delta = 400): Promise<void> {
  await page.locator('[data-testid="epub-scroll-container"]').evaluate((el, by) => {
    el.scrollTop = el.scrollTop + by;
    el.dispatchEvent(new Event('scroll', { bubbles: true }));
  }, delta);
}

/**
 * The backend is the authority on a book's highlights — see `readerHighlights`.
 */
export { countHighlights, deleteHighlight, highlightIds, readHighlight } from './readerHighlights';

/**
 * The selection toolbar's rect, read from the DOM rather than through
 * `locator.boundingBox()`. The toolbar enters with an `in:scale` transition
 * (`SelectionToolbar.svelte`), and `boundingBox()` can return null while the
 * transition is mid-flight — the rect below is sampled once the element reports
 * a non-zero box, which is what the containment assertion needs.
 *
 * Returns null while the toolbar is absent or still has no measurable box.
 */
export async function readToolbarRect(page: Page): Promise<{
  x: number;
  y: number;
  width: number;
  height: number;
  viewportWidth: number;
  viewportHeight: number;
} | null> {
  return page.evaluate(() => {
    const el = document.querySelector('.selection-toolbar');
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
  });
}
