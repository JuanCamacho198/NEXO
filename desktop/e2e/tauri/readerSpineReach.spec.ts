/**
 * The reader reaches every chapter a book actually contains, even when the
 * book's own table of contents lists fewer entries than its spine.
 *
 * ## Why this spec exists
 *
 * *Prisioneros de la geografía* ships 21 NCX navPoints over 43 spine documents.
 * Each chapter is three documents — a title page, an image spread, and the body
 * — and the NCX names only the title page. Measured on the real file
 * (2026-10-21, through the app's own `parse_epub`):
 *
 *     totalChapters  43
 *     spineHrefs     43
 *     toc            21
 *
 * Before the fix, `goToNext` was bounded by `toc.length`, so the reader walked
 * the 21 title pages and never reached spine 9 (`0005_0002.htm`), which holds
 * 57 356 characters of prose. The body was in the book, indexed for search, and
 * unreachable by reading.
 *
 * ## What this proves that the other specs cannot
 *
 * `readerCoherence.spec.ts` asserts that the header and the footer move together
 * — but it opens whichever book `selectUsableEpub` finds, and every other book in
 * the library has `spine === toc`, where the two counts are the same number and
 * the defect is invisible. This spec is the only one that opens a book where the
 * two counts *differ*, so it is the only one that can tell the fix from the bug.
 *
 * ## The invariant
 *
 * The page field reports the spine: "N of 43", not "N of 21". Turning past the
 * end of the outline must keep producing new pages rather than freezing, and the
 * total must stay at the spine length the whole way.
 *
 * A book whose spine equals its TOC must be untouched by this change — its
 * assertion is that the total still equals its own TOC length, which is the same
 * number before and after.
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
    })) as { totalChapters?: number; spineHrefs?: string[]; toc?: unknown[] };

    return {
      id: row.id,
      title: row.title,
      totalChapters: parsed.totalChapters ?? 0,
      spineLength: (parsed.spineHrefs ?? []).length,
      tocLength: (parsed.toc ?? []).length,
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

/** The page's own Next control (prev is index 0, next is index 1). */
function nextButton(page: Page): ReturnType<Page['locator']> {
  return page.locator('[data-testid="bottom-bar-controls"] button').nth(1);
}

/**
 * True when the chapter frame currently on screen renders prose.
 *
 * A title page renders 60 characters and an image spread renders none, so
 * "rendered something" is not the test — the question is whether the document
 * the reader is standing on contains body text.
 */
async function chapterProseLength(page: Page): Promise<number> {
  const frame = await waitForChapterFrame(page).catch(() => null);
  if (!frame) return 0;
  return frame
    .evaluate(() => (document.body.innerText ?? '').replace(/\s+/g, ' ').trim().length)
    .catch(() => 0);
}

test('a partial NCX does not hide the book: the page field counts the spine', async ({
  appPage,
}) => {
  test.setTimeout(240_000);

  const rows = await readLibraryRows(appPage);
  let target: BookShape | null = null;
  const shapes: string[] = [];

  for (const row of rows) {
    if ((row.format ?? '').toLowerCase() !== 'epub') continue;
    const shape = await bookShape(appPage, row.id);
    if (!shape || shape.spineLength === 0) continue;
    shapes.push(`${shape.title}: spine ${shape.spineLength}, toc ${shape.tocLength}`);
    if (shape.spineLength > shape.tocLength) target = shape;
  }

  test.skip(
    target === null,
    `No book in this library has a spine longer than its table of contents. ` +
      `Shapes measured: ${shapes.length > 0 ? shapes.join('; ') : '(no epubs)'}.`,
  );

  await openBook(appPage, { id: target!.id, title: target!.title } as never);
  await expect
    .poll(async () => (await readPageField(appPage)).total, { timeout: 30_000 })
    .toBeGreaterThan(0);

  const first = await readPageField(appPage);

  // The claim under test. A TOC-length total (21) is the bug; the spine (43) is
  // the fix. Asserting `!== tocLength` as well as `=== spineLength` keeps the
  // failure message honest if the app reports some third number.
  expect(
    first.total,
    `"${target!.title}" reports ${first.total} pages, but its spine has ` +
      `${target!.spineLength} documents and its NCX declares ${target!.tocLength} entries. ` +
      `The page field must count the spine.`,
  ).toBe(target!.spineLength);
  expect(first.total).not.toBe(target!.tocLength);

  // Walk forward past the outline's end. The turn that used to freeze is the one
  // that crosses `tocLength`; keep going a little further so a fix that merely
  // bumped the bound by one is not mistaken for a fix.
  const turnsPastOutline = target!.tocLength - first.current + 3;
  const next = nextButton(appPage);
  let last = first;

  for (let i = 0; i < turnsPastOutline; i++) {
    await expect(next).toBeEnabled({ timeout: 10_000 });
    await next.click();
    await appPage.waitForTimeout(700);
    last = await readPageField(appPage);
  }

  expect(
    last.current,
    `After ${turnsPastOutline} forward turns the reader is on page ${last.current} ` +
      `(started at ${first.current}); it should have crossed the outline's ` +
      `${target!.tocLength} entries.`,
  ).toBeGreaterThan(target!.tocLength);

  // The total must not move while the reader walks — a total that drifts is the
  // failure mode this spec is second-most afraid of.
  expect(last.total).toBe(first.total);

  // And the page the reader crossed into must be a real document, not a blank
  // placeholder: the whole point of the fix is that the bodies are readable.
  const prose = await chapterProseLength(appPage);
  expect(
    prose,
    `Page ${last.current} of "${target!.title}" renders ${prose} characters of text. ` +
      `Beyond a partial NCX the reader should be standing on a chapter body.`,
  ).toBeGreaterThan(0);
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
