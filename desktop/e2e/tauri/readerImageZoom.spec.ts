/**
 * Zoom works on an image-only page: it scales the plates.
 *
 * ## Why this spec exists
 *
 * Every zoom entry point (Ctrl+wheel, Ctrl+`+`/`-`, the zoom menu, the header)
 * already wrote `epub.fontSize`, and the stylesheet scaled TEXT from it. On the
 * two-map spread there is no text, so the number changed and the pictures did
 * not move. Measured in this running app before the fix, zoom 100/125/150%:
 *
 *     img1 504x754  img2 501x754     (identical at all three)
 *     body font-size 16px -> 20px -> 24px
 *
 * The reader photographed exactly that: two maps and a zoom control that
 * appeared inert.
 *
 * ## The claims
 *
 *  1. **Zoom scales the plate.** Raising zoom raises the RENDERED width of the
 *     spread's images, across every menu stop, monotonically.
 *  2. **100% is unchanged.** The default view still renders each plate at its
 *     natural size — the fix must not upscale a plate the reader did not ask
 *     for. (This is the regression an unconditional `width: 100%` produced:
 *     measured, it stretched the 504px maps to 1123px at 100%.)
 *  3. **A zoomed plate stays reachable.** When a plate is wider than the
 *     viewport the reader can scroll to its right edge. Clipping would trade an
 *     unreadable state for an unreachable one.
 *  4. **The zoom value is not captured at render time.** It is re-read on every
 *     stylesheet rebuild, which is what makes the second and later control
 *     touches work (the `refreshReaderStyles` trap).
 *
 * ## Proving it can fail
 *
 * Reverting the wiring — passing no zoom to `buildImageOnlyCss`, so the rules
 * emit `width: auto` at every level — reproduces the original defect and claim
 * (1) fails. The literal output is recorded in the feature notes.
 *
 * ## Local only
 *
 * Needs the real Tauri app under `bun run tauri:dev:debug` (Windows only), and a
 * library holding an image-only spine document. Not a CI gate.
 */
import { expect, test } from './harness/connectApp';
import type { Frame, Page } from '@playwright/test';
import { openBook, waitForChapterFrame } from './harness/readerFlows';

type Spread = {
  id: string;
  title: string;
  spineIndex: number;
  imageCount: number;
};

/**
 * Finds the book and spine document under test from the app's own parser.
 *
 * `parse_epub` + `get_epub_chapter` are what the viewer itself calls, so a
 * document classified here as "images, no prose" is one the reader really
 * renders with `layout: 'image-only'`. The thresholds match `epubImageFit.ts`
 * (two or more images, under 40 characters of text), so this spec measures the
 * case the rule was written for.
 */
async function findSpread(page: Page): Promise<Spread | null> {
  return page.evaluate(async (): Promise<Spread | null> => {
    const internals = (
      window as unknown as {
        __TAURI_INTERNALS__?: { invoke: (cmd: string, args?: unknown) => Promise<unknown> };
      }
    ).__TAURI_INTERNALS__;
    if (!internals) throw new Error('__TAURI_INTERNALS__ is missing — not a Tauri webview');

    const rows = (await internals.invoke('listLibraryBooks', {
      payload: { responseVersion: 1 },
    })) as Array<{ id: string; title: string; format?: string; filePath?: string }>;

    for (const row of rows) {
      if ((row.format ?? '').toLowerCase() !== 'epub') continue;
      if (!row.filePath) continue;

      const parsed = (await internals.invoke('parse_epub', {
        filePath: row.filePath,
        bookId: row.id,
      })) as { spineHrefs?: string[]; resourcesPath?: string };
      const spine = parsed.spineHrefs ?? [];

      for (let index = 0; index < spine.length; index++) {
        const chapter = (await internals
          .invoke('get_epub_chapter', {
            filePath: row.filePath,
            bookId: row.id,
            resourcesPath: parsed.resourcesPath,
            chapterIndex: index,
          })
          .catch(() => null)) as { html?: string } | null;
        const html = chapter?.html ?? '';
        const imageCount = (html.match(/<img\b/gi) ?? []).length;
        if (imageCount < 2) continue;
        const text = html
          .replace(/<script[^]*?<\/script>/gi, '')
          .replace(/<style[^]*?<\/style>/gi, '')
          .replace(/<[^>]*>/g, '')
          .replace(/\s+/g, '');
        if (text.length >= 40) continue;
        return { id: row.id, title: row.title, spineIndex: index, imageCount };
      }
    }
    return null;
  });
}

/** Puts the reader on the spread's spine document and waits for it to lay out. */
async function goToSpread(page: Page, spineIndex: number): Promise<void> {
  const target = spineIndex + 1;
  const input = page.locator('[data-testid="bottom-bar-controls"] input').first();
  await input.fill(String(target));
  await input.press('Enter');
  await expect
    .poll(
      async () => {
        const frame = await waitForChapterFrame(page, 2_000).catch(() => null);
        if (!frame) return 0;
        return frame
          .evaluate(
            () =>
              Array.from(document.querySelectorAll('img')).filter((img) => img.naturalWidth > 0)
                .length,
          )
          .catch(() => 0);
      },
      { timeout: 30_000 },
    )
    .toBeGreaterThanOrEqual(2);
}

/**
 * Drives zoom through the reader's own control — the bottom-bar menu, which
 * routes to `handleHeaderFontSizeChange` and writes `epub.fontSize`. That is the
 * single value Ctrl+wheel and Ctrl+`+`/`-` step one increment at a time, so a
 * menu selection and a wheel tick reach the same decision.
 */
async function selectZoom(page: Page, percent: number): Promise<void> {
  const triggers = page.locator('[data-testid="zoom-dropdown-trigger"]');
  const count = await triggers.count();
  expect(count, 'the reader renders no zoom control').toBeGreaterThan(0);
  await triggers.nth(count - 1).click();
  await page.locator(`[data-testid="zoom-option-${percent}"]`).click();
  // The stylesheet is rebuilt by an effect; give it a frame to settle.
  await expect
    .poll(
      () =>
        page.evaluate(
          () =>
            document.querySelector('[data-testid="zoom-dropdown-trigger"]')?.textContent?.trim() ??
            '',
        ),
      { timeout: 10_000 },
    )
    .toBe(`${percent}%`);
}

/** Measured geometry of every laid-out image in the chapter document. */
type PlateBox = {
  naturalWidth: number;
  width: number;
  height: number;
  /** Where the image's right edge sits once the document is scrolled fully right. */
  rightAtMaxScroll: number;
  docScrollLeft: number;
  docScrollWidth: number;
  docClientWidth: number;
};

async function readPlates(page: Page): Promise<PlateBox[]> {
  // A settings change can re-assign `srcdoc`, which detaches the old frame.
  const frame: Frame = await waitForChapterFrame(page, 10_000);
  return frame.evaluate(() =>
    Array.from(document.querySelectorAll('img'))
      .filter((img) => img.naturalWidth > 0)
      .map((img) => {
        const rect = img.getBoundingClientRect();
        const docEl = document.documentElement;
        // Ask for the far right and measure what the reader can actually see.
        docEl.scrollLeft = 1_000_000;
        const scrolled = img.getBoundingClientRect();
        const box = {
          naturalWidth: img.naturalWidth,
          width: Math.round(rect.width),
          height: Math.round(rect.height),
          rightAtMaxScroll: Math.round(scrolled.right),
          docScrollLeft: docEl.scrollLeft,
          docScrollWidth: docEl.scrollWidth,
          docClientWidth: docEl.clientWidth,
        };
        docEl.scrollLeft = 0;
        return box;
      }),
  );
}

test('zoom scales the plates on an image-only page', async ({ appPage }) => {
  test.setTimeout(240_000);

  const spread = await findSpread(appPage);
  test.skip(
    spread === null,
    'No EPUB in this library has a spine document with two or more images and no prose. ' +
      'This spec needs that shape to measure zoom on an image-only page.',
  );

  await openBook(appPage, { id: spread!.id, title: spread!.title } as never);
  await goToSpread(appPage, spread!.spineIndex);

  // Reset to the reader's default zoom so the baseline is the one a fresh
  // reader sees, whatever a previous spec left in the settings row.
  await selectZoom(appPage, 100);
  const baseline = await readPlates(appPage);
  const where = `"${spread!.title}" spine ${spread!.spineIndex} (${baseline.length} images)`;

  expect(
    baseline.length,
    `${where}: the spread rendered no measurable images`,
  ).toBeGreaterThanOrEqual(2);

  // (2) THE DEFAULT CLAIM. At 100% a plate keeps its natural size. This is the
  // regression an unconditional `width: 100%` caused: both maps went 504 -> 1123.
  for (const [i, plate] of baseline.entries()) {
    expect(
      plate.width,
      `${where}: image ${i + 1} renders ${plate.width}px from a ${plate.naturalWidth}px source ` +
        `at 100% zoom. The default view must not upscale a plate the reader did not ask for.`,
    ).toBeLessThanOrEqual(plate.naturalWidth + 1);
  }

  // (1) THE ZOOM CLAIM. Width must rise with the number, at every stop.
  let previousWidth = baseline[0].width;
  const growth: string[] = [`100% ${baseline[0].width}px`];
  for (const percent of [125, 150, 200, 300]) {
    await selectZoom(appPage, percent);
    const plates = await readPlates(appPage);
    expect(
      plates.length,
      `${where}: the spread lost its images at ${percent}%`,
    ).toBeGreaterThanOrEqual(2);
    const width = plates[0].width;
    expect(
      width,
      `${where}: at ${percent}% zoom image 1 renders ${width}px — it did not grow from ${previousWidth}px. ` +
        `On an image-only page the zoom value must scale the plates; there is no text for it to scale.`,
    ).toBeGreaterThan(previousWidth);
    growth.push(`${percent}% ${width}px`);
    previousWidth = width;
  }

  // (2b) THE ASPECT CLAIM. Scaling is uniform: the plate is never stretched.
  const finalPlates = await readPlates(appPage);
  const first = finalPlates[0];
  const baselineAspect = baseline[0].width / baseline[0].height;
  const finalAspect = first.width / first.height;
  expect(
    Math.abs(finalAspect - baselineAspect),
    `${where}: zoom changed the aspect ratio from ${baselineAspect.toFixed(3)} to ` +
      `${finalAspect.toFixed(3)}. Zoom must scale a plate uniformly.`,
  ).toBeLessThan(0.02);

  // (3) THE REACH CLAIM. At 300% the plate is wider than the viewport; the
  // reader must be able to bring its right edge into view, not just its left.
  expect(
    first.docScrollWidth,
    `${where}: at 300% the chapter reports scrollWidth ${first.docScrollWidth} against ` +
      `clientWidth ${first.docClientWidth} — the document itself cannot scroll sideways, so a ` +
      `plate wider than the viewport is clipped to its left edge.`,
  ).toBeGreaterThan(first.docClientWidth);
  expect(
    first.docScrollLeft,
    `${where}: scrolling the chapter fully right left scrollLeft at 0, so a zoomed plate is ` +
      `unreachable horizontally.`,
  ).toBeGreaterThan(0);
  expect(
    first.rightAtMaxScroll,
    `${where}: after scrolling fully right the plate's right edge is at ` +
      `${first.rightAtMaxScroll}px, outside the ${first.docClientWidth}px viewport.`,
  ).toBeLessThanOrEqual(first.docClientWidth + 2);

  console.log(`[zoom-spread] ${where}: ${growth.join(' -> ')}`);

  // (4) THE LIVE-VALUE CLAIM, measured rather than argued: step back down and
  // the plate must shrink. A zoom captured at render time would hold at its
  // first value after the first control touch (the `refreshReaderStyles` trap).
  await selectZoom(appPage, 125);
  const shrunk = await readPlates(appPage);
  expect(
    shrunk[0].width,
    `${where}: back at 125% zoom image 1 still renders ${shrunk[0].width}px, the same as at ` +
      `300%. The zoom value is being captured at render time instead of re-read when the ` +
      `stylesheet is rebuilt.`,
  ).toBeLessThan(previousWidth);
});
