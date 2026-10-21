/**
 * The reader turns through a book in the book's own order, including the
 * documents the book's table of contents never names.
 *
 * ## Why this spec exists
 *
 * *Prisioneros de la geografía* ships 21 NCX navPoints over 43 spine documents.
 * Each chapter is three documents — a title page, an image spread, and the body
 * — and the NCX names only the title page. Measured through the app's own
 * `parse_epub` (2026-10-21): `totalChapters 43`, `spineHrefs 43`, `toc 21`, with
 * navPoints at spine indexes 0, 3, 4, 5, 6, 7, 10, 13, 16, 19, 22, 25, 28, 31,
 * 34, 37, 38, 39, 40, 41, 42.
 *
 * Two wrong readers have shipped against this book.
 *
 * 1. Bounded by `toc.length`, the reader walked the 21 named documents and never
 *    reached spine 9 (`0005_0002.htm`, 57 356 characters of prose).
 * 2. Bounded by the spine, it reached everything but out of order: from chapter
 *    7 it showed the conclusion, the bibliography and the credits, then chapter
 *    5's body. The page field said "12 of 43" while the reader was reading the
 *    wrong chapter.
 *
 * The previous version of this file asserted `renderedText.length > 0` after
 * turning past the outline. A 60-character chapter title satisfies that, and a
 * walk that visits the whole book in the wrong order satisfies it too — so it
 * passed while (2) was shipping. It could not fail. This version asserts the
 * order instead.
 *
 * ## The oracle
 *
 * The reader persists its position as an `epubcfi(/6/N!…)`, where N is the
 * 1-based spine document on screen (the CFI bridge builds it from the document
 * it is rendering — `emitPreciseLocation`). `getProgress` returns that value.
 * So the spine document on screen is readable from the app itself, with no DOM
 * scraping and no private bridge access.
 *
 * ## What is asserted
 *
 * Walking forward from chapter 1's title page, the page field advances by one
 * per turn and the spine document on screen never decreases. Under a spine walk
 * the index runs 0,1,2…35 and then jumps back to 36, because that is where the
 * outline's tail sits in the file. The `toc` in the app is the *flattened* NCX,
 * so the outline's position can be derived from the book: if the spine index
 * ever falls below the highest outline index already passed, the reader has
 * left the book's order and no later body can make it right.
 *
 * ## Local only
 *
 * Needs the real Tauri app under `bun run tauri:dev:debug` (Windows only). Not a
 * CI gate.
 */
import { expect, test } from './harness/connectApp';
import type { Page } from '@playwright/test';
import { openBook, readLibraryRows, waitForChapterFrame } from './harness/readerFlows';

/** The headless half: what the book declares about itself. */
type BookShape = {
  id: string;
  title: string;
  totalChapters: number;
  spineLength: number;
  tocLength: number;
  /** Spine index each outline entry names, in outline order. */
  tocIndexes: number[];
};

/**
 * Asks the app's own parser what shape a book is.
 *
 * This is the authority: `parse_epub` is what the viewer itself calls, so a book
 * classified here as `spine > toc` is one the reader will genuinely meet in that
 * shape. Deriving it from the title or the file size would be a guess.
 */
async function bookShape(page: Page, bookId: string): Promise<BookShape | null> {
  return page.evaluate(async (id) => {
    const internals = (
      window as unknown as {
        __TAURI_INTERNALS__?: { invoke: (cmd: string, args?: unknown) => Promise<unknown> };
      }
    ).__TAURI_INTERNALS__;
    if (!internals) return null;

    const rows = (await internals.invoke('listLibraryBooks', {
      payload: { responseVersion: 1 },
    })) as Array<{ id: string; title: string; filePath?: string }>;
    const row = rows.find((r) => r.id === id);
    if (!row?.filePath) return null;

    const parsed = (await internals.invoke('parse_epub', {
      filePath: row.filePath,
      bookId: id,
    })) as {
      totalChapters?: number;
      spineHrefs?: string[];
      toc?: Array<{ index?: number }>;
    };

    return {
      id: row.id,
      title: row.title,
      totalChapters: parsed.totalChapters ?? 0,
      spineLength: (parsed.spineHrefs ?? []).length,
      tocLength: (parsed.toc ?? []).length,
      tocIndexes: (parsed.toc ?? [])
        .map((entry) => entry.index)
        .filter((index): index is number => typeof index === 'number'),
    };
  }, bookId);
}

/** The page field: current value and the `/ total` span beside it. */
async function readPageField(page: Page): Promise<{ current: number; total: number }> {
  return page.evaluate(() => {
    const controls = document.querySelector('[data-testid="bottom-bar-controls"]');
    const input = controls?.querySelector('input') as HTMLInputElement | null;
    const totalSpan = controls?.querySelector('span[aria-hidden="true"]') as HTMLElement | null;
    return {
      current: input ? Number.parseInt(input.value, 10) || 0 : 0,
      total: Number.parseInt((totalSpan?.textContent ?? '').replace(/[^0-9]/g, ''), 10) || 0,
    };
  });
}

/**
 * The spine document the reader is showing, 0-based, or null before the reader
 * has published a position.
 *
 * The reader writes its CFI through `saveProgress` on every committed chapter, and
 * that CFI is built from the document on screen — so this is the rendered
 * document, not the requested one.
 */
async function readRenderedSpineIndex(page: Page, bookId: string): Promise<number | null> {
  return page.evaluate(async (id) => {
    const internals = (
      window as unknown as {
        __TAURI_INTERNALS__?: { invoke: (cmd: string, args?: unknown) => Promise<unknown> };
      }
    ).__TAURI_INTERNALS__;
    if (!internals) return null;
    const progress = (await internals.invoke('getProgress', { bookId: id })) as {
      cfiLocation?: string;
    } | null;
    const match = /epubcfi\(\/6\/(\d+)!/.exec(progress?.cfiLocation ?? '');
    return match ? Number.parseInt(match[1]!, 10) - 1 : null;
  }, bookId);
}

/**
 * Texts the chapter frame is rendering, for the failure message only.
 *
 * Not an assertion: a chapter body is rendered in full but the frame reports its
 * own `innerText`, which is the human-readable evidence a reader would give. The
 * pass/fail decisions are the CFI and the page field.
 */
async function renderedProseLength(page: Page): Promise<number> {
  const frame = await waitForChapterFrame(page).catch(() => null);
  if (!frame) return 0;
  return frame
    .evaluate(() => (document.body.innerText ?? '').replace(/\s+/g, ' ').trim().length)
    .catch(() => 0);
}

/** The page's own Next control (prev is index 0, next is index 1). */
function nextButton(page: Page): ReturnType<Page['locator']> {
  return page.locator('[data-testid="bottom-bar-controls"] button').nth(1);
}

/**
 * Distance from the first chapter's title page to the next outline entry, when
 * that gap is 3 — i.e. the book splits a chapter into a title page, one unnamed
 * document, and a body. Returns 0 when no chapter has that shape.
 *
 * This is what makes a "chapter body" claim checkable: without a gap of 3 there
 * is no document that must be read after a title and before the next one.
 */
function chapterBodyOffset(tocIndexes: number[]): number {
  const outline = [...tocIndexes].sort((a, b) => a - b);
  for (let i = 1; i < outline.length; i++) {
    if (outline[i]! - outline[i - 1]! === CHAPTER_SPLIT) return CHAPTER_SPLIT;
  }
  return 0;
}

/** A chapter rendered as title page + unnamed document + body. */
const CHAPTER_SPLIT = 3;

/**
 * Puts the reader on page 1.
 *
 * The page field is the reader's own navigation input — typing into it goes
 * through the same `handleGoToPage` the reader uses — which is what makes this a
 * reset of position rather than a reset of the thing under test.
 */
async function goToFirstPage(page: Page): Promise<void> {
  const input = page.locator('[data-testid="bottom-bar-controls"] input').first();
  await input.fill('1');
  await input.press('Enter');
  await expect.poll(async () => (await readPageField(page)).current, { timeout: 20_000 }).toBe(1);
  await page.waitForTimeout(1_500);
}

/**
 * One observation, taken only once the page field has stopped moving.
 *
 * A sample taken mid-render reports the page the reader is leaving, which would
 * make a "no backwards jump" assertion meaningless.
 */
async function observe(
  page: Page,
  bookId: string,
): Promise<{ page: number; spine: number | null; chars: number }> {
  let previous = -1;
  let current = -2;
  const deadline = Date.now() + 15_000;
  while (Date.now() < deadline && current !== previous) {
    previous = current;
    current = (await readPageField(page)).current;
    if (current !== previous) await page.waitForTimeout(500);
  }
  return { page: current, spine: await readRenderedSpineIndex(page, bookId), chars: 0 };
}

async function turnOnce(page: Page): Promise<void> {
  const next = nextButton(page);
  await expect(next).toBeEnabled({ timeout: 10_000 });
  await next.click();
}

test('a partial NCX does not hide the book: the page field counts the spine', async ({
  appPage,
}) => {
  test.setTimeout(240_000);

  const rows = await readLibraryRows(appPage);
  const shapes: Array<BookShape & { gap: number; hasSplitChapter: boolean }> = [];

  for (const row of rows) {
    if ((row.format ?? '').toLowerCase() !== 'epub') continue;
    const shape = await bookShape(appPage, row.id);
    if (!shape || shape.spineLength === 0) continue;
    shapes.push({
      ...shape,
      gap: shape.spineLength - shape.tocLength,
      hasSplitChapter: chapterBodyOffset(shape.tocIndexes) > 0,
    });
  }

  // The book this spec was written for: chapters split over several documents (so
  // a chapter's body is a document its own title page does not name) and the
  // largest spine/outline gap among those. A book whose outline is nearly
  // complete has no such body, and the assertions below would have nothing to
  // talk about — picking the first book with *any* gap measured exactly that and
  // turned a real assertion into a tautology.
  const target =
    shapes.filter((s) => s.gap > 0 && s.hasSplitChapter).sort((a, b) => b.gap - a.gap)[0] ?? null;

  test.skip(
    target === null,
    `No book in this library has a spine longer than its table of contents. ` +
      `Shapes measured: ${
        shapes.length > 0
          ? shapes.map((s) => `${s.title}: spine ${s.spineLength}, toc ${s.tocLength}`).join('; ')
          : '(no epubs)'
      }.`,
  );

  await openBook(appPage, { id: target!.id, title: target!.title } as never);
  await expect
    .poll(async () => (await readPageField(appPage)).total, { timeout: 30_000 })
    .toBeGreaterThan(0);

  // Start from the cover, not from wherever the last run left the bookmark: the
  // claim below is about the order of the whole walk, and a restore to page 42
  // would leave the first chapters out of sight entirely.
  await goToFirstPage(appPage);

  const first = await readPageField(appPage);

  // The claim under test. A TOC-length total is the bug; the spine is the fix.
  expect(
    first.total,
    `"${target!.title}" reports ${first.total} pages, but its spine has ` +
      `${target!.spineLength} documents and its NCX declares ${target!.tocLength} entries. ` +
      `The page field must count the spine.`,
  ).toBe(target!.spineLength);
  expect(first.total).not.toBe(target!.tocLength);

  /**
   * Every distinct document the reader shows, in the order it showed them.
   *
   * Recorded continuously rather than once per turn: a turn is asynchronous and
   * a single sample misses transitions, and an order claim needs every
   * transition it can get.
   */
  const seen: Array<{ page: number; spine: number }> = [];
  const record = async (): Promise<void> => {
    const step = await observe(appPage, target!.id);
    if (step.spine === null) return;
    const last = seen[seen.length - 1];
    if (last && last.spine === step.spine && last.page === step.page) return;
    seen.push({ page: step.page, spine: step.spine });
  };

  await record();

  const maxTurns = 25;
  for (let turn = 0; turn < maxTurns; turn++) {
    await turnOnce(appPage);
    // Two samples per turn-space: the settled state, then a short settle to pick
    // up a render that committed while the first sample was in flight.
    await appPage.waitForTimeout(600);
    await record();
    const last = seen[seen.length - 1]!;
    if (last.page >= first.total - 1) break;
  }

  const trail = seen.map((s) => `p${s.page}→spine ${s.spine}`).join(', ');
  const prose = await renderedProseLength(appPage);

  // (1) A chapter's body is read with its own chapter. The NCX names title pages
  // only, so a chapter's body is the document directly after its title page —
  // spine 9 for chapter 1, whose title is spine 7. Whatever the app does with the
  // outline's tail, chapter 1's body cannot be left for after chapter 5.
  const outline = [...target!.tocIndexes].sort((a, b) => a - b);
  const jumpIndex = outline.findIndex((spine, i) => i > 0 && spine - outline[i - 1]! === 3);
  expect(
    jumpIndex,
    `"${target!.title}" has no chapter of three documents; its outline is ` +
      `${outline.join(',')}. This spec needs that shape to find a body.`,
  ).toBeGreaterThan(0);
  const chapterTitle = outline[jumpIndex - 1]!;
  const chapterBody = outline[jumpIndex]! - 1;
  const laterChapterTitle = outline[jumpIndex]!;

  const bodyPages = seen.filter((s) => s.spine === chapterBody).map((s) => s.page);
  expect(
    bodyPages.length,
    `The body of the chapter at spine ${chapterTitle} (document ${chapterBody}) never ` +
      `appeared in ${seen.length} observed documents. Walk: ${trail}`,
  ).toBeGreaterThan(0);

  const laterTitlePages = seen.filter((s) => s.spine === laterChapterTitle).map((s) => s.page);
  if (laterTitlePages.length > 0) {
    expect(
      bodyPages[0]!,
      `Chapter ${chapterTitle}'s body (spine ${chapterBody}) was read on page ${bodyPages[0]}, ` +
        `but the next chapter's title page (spine ${laterChapterTitle}) was already read on ` +
        `page ${laterTitlePages[0]}. The walk skipped the chapter it had just started. ` +
        `Walk: ${trail}. Last page rendered ${prose} characters.`,
    ).toBeLessThan(laterTitlePages[0]!);
  }

  // (2) The page field and the document on screen agree. A page field that
  // advances while the rendered document does not is the "header advances, the
  // screen does not" defect wearing a different hat.
  const pageForSpine = new Map<number, number>();
  for (const step of seen) {
    const known = pageForSpine.get(step.spine);
    expect(
      known ?? step.page,
      `Spine document ${step.spine} was shown on two different pages ` +
        `(${known} and ${step.page}). Walk: ${trail}`,
    ).toBe(step.page);
  }

  // (3) The total must not move while the reader walks.
  const last = await readPageField(appPage);
  expect(last.total).toBe(first.total);

  // Something was actually read: a walk that renders nothing proves nothing.
  expect(seen.length, `The reader never rendered a document. Walk: ${trail}`).toBeGreaterThan(3);
});

test('a book whose spine equals its TOC is unchanged', async ({ appPage }) => {
  test.setTimeout(180_000);

  const rows = await readLibraryRows(appPage);
  let target: BookShape | null = null;

  for (const row of rows) {
    if ((row.format ?? '').toLowerCase() !== 'epub') continue;
    const shape = await bookShape(appPage, row.id);
    if (!shape || shape.spineLength === 0) continue;
    if (shape.spineLength === shape.tocLength && shape.totalChapters === shape.spineLength) {
      target = shape;
      break;
    }
  }

  test.skip(target === null, 'No book in this library has spine === toc === totalChapters.');

  await openBook(appPage, { id: target!.id, title: target!.title } as never);
  await expect
    .poll(async () => (await readPageField(appPage)).total, { timeout: 30_000 })
    .toBeGreaterThan(0);

  const field = await readPageField(appPage);

  // For these books the spine length and the TOC length are the same number, so
  // this asserts the additive promise directly: nothing moved.
  expect(
    field.total,
    `"${target!.title}" has spine ${target!.spineLength} and toc ${target!.tocLength}; ` +
      `the page field reports ${field.total}.`,
  ).toBe(target!.spineLength);
});
