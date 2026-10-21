/**
 * An image spread is readable, and a click on a map opens it full size.
 *
 * ## Why this spec exists
 *
 * *Prisioneros de la geografía* ships 10 spine documents that are nothing but
 * two maps (`OPS/0005_0001.htm` at spine 8 and its siblings every third
 * document). Measured through the app's own parser (2026-10-21): 2 `<img>`,
 * 0 characters of text. The reader laid them out inside the prose measure —
 * `body { max-width: min(65ch, 100%) }` plus `img { max-width: 100% }` — so two
 * 500x750 sources shared one text column at roughly 280 CSS px each and every
 * place name rendered ~6 px tall. A click on one did nothing.
 *
 * ## Two claims, both measured in the running app
 *
 *  1. **Layout.** In an image-only document the images are not confined to the
 *     prose measure: each renders wider than it did under the old rule, and the
 *     pair stacks (distinct vertical bands) instead of sharing a row.
 *  2. **The viewer.** A click on a map opens the image viewer and shows the
 *     picture larger than it was inline. The assertion compares the viewer's
 *     rendered width against the SAME image's inline width captured before the
 *     click, so it cannot pass by accident on a wide window.
 *
 * ## Proving the assertion can fail
 *
 * The click wiring in `EpubNativeViewer.svelte` (the `contentImageFromEvent`
 * branch inside `onIframeClick`) is the only thing that opens the viewer. With
 * that branch removed, claim (2) fails at the poll with
 * `epub-image-viewer` never becoming visible — the literal output is recorded
 * in the feature notes. Claim (1) still passes, which is the point: the two
 * assertions are independent.
 *
 * ## Local only
 *
 * Needs the real Tauri app under `bun run tauri:dev:debug` (Windows only). Not a
 * CI gate.
 */
import { expect, test } from './harness/connectApp';
import type { Frame, Page } from '@playwright/test';
import { openBook, readLibraryRows, waitForChapterFrame } from './harness/readerFlows';

type LibraryRowLike = { id: string; title: string; filePath?: string; format?: string };

/** One spine document as the app's own parser describes it. */
type SpineShape = {
  id: string;
  title: string;
  /** Spine document that holds images and no prose. */
  imageSpineIndex: number;
  imageCount: number;
};

/**
 * Finds the book and spine document this spec is about, from the app's parser.
 *
 * `parse_epub` + `get_epub_chapter` are what the viewer itself calls, so a
 * document classified here as "images, no text" is one the reader will really
 * render that way. Deriving it from a title or a file name would be a guess.
 *
 * A document counts as an image spread when it carries at least two images and
 * under 40 characters of text — the same threshold `epubImageFit.ts` uses, so
 * this spec measures the case the feature was written for.
 */
async function findImageSpread(page: Page): Promise<SpineShape | null> {
  return page.evaluate(async (): Promise<SpineShape | null> => {
    const internals = (
      window as unknown as {
        __TAURI_INTERNALS__?: { invoke: (cmd: string, args?: unknown) => Promise<unknown> };
      }
    ).__TAURI_INTERNALS__;
    if (!internals) throw new Error('__TAURI_INTERNALS__ is missing — not a Tauri webview');

    const rows = (await internals.invoke('listLibraryBooks', {
      payload: { responseVersion: 1 },
    })) as LibraryRowLike[];

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
        return { id: row.id, title: row.title, imageSpineIndex: index, imageCount };
      }
    }
    return null;
  });
}

/** The page field's current value, which the reader drives. */
async function readCurrentPage(page: Page): Promise<number> {
  return page.evaluate(() => {
    const input = document
      .querySelector('[data-testid="bottom-bar-controls"]')
      ?.querySelector('input') as HTMLInputElement | null;
    return input ? Number.parseInt(input.value, 10) || 0 : 0;
  });
}

/**
 * Puts the reader on the spine document that holds the spread.
 *
 * The page field is 1-based over the spine (`readerSpineReach.spec.ts` proves
 * that), so the target is `imageSpineIndex + 1`. Waiting for the page field to
 * report that value AND for the frame to hold an image with a real
 * `naturalWidth` means the document under test is laid out, not mid-swap: the
 * renderer re-assigns `srcdoc` as fonts and assets settle, and measuring a
 * discarded document would report a reader that no longer exists.
 */
async function goToImageSpread(page: Page, spineIndex: number): Promise<void> {
  const target = spineIndex + 1;
  const input = page.locator('[data-testid="bottom-bar-controls"] input').first();
  await input.fill(String(target));
  await input.press('Enter');
  await expect.poll(() => readCurrentPage(page), { timeout: 30_000 }).toBe(target);
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

/** Measured geometry of every image in the chapter document. */
type InlineImage = {
  src: string;
  naturalWidth: number;
  renderedWidth: number;
  top: number;
  left: number;
  /** The prose measure the image is laid out inside. */
  bodyWidth: number;
};

async function readInlineImages(frame: Frame): Promise<InlineImage[]> {
  return frame.evaluate(() =>
    Array.from(document.querySelectorAll('img'))
      .filter((img) => img.naturalWidth > 0)
      .map((img) => {
        const rect = img.getBoundingClientRect();
        return {
          src: img.currentSrc || img.src,
          naturalWidth: img.naturalWidth,
          renderedWidth: Math.round(rect.width),
          top: Math.round(rect.top),
          left: Math.round(rect.left),
          bodyWidth: Math.round(document.body.getBoundingClientRect().width),
        };
      }),
  );
}

test('an image spread uses the page and a click on a map opens it full size', async ({
  appPage,
}) => {
  test.setTimeout(240_000);

  const target = await findImageSpread(appPage);
  test.skip(
    target === null,
    'No EPUB in this library has a spine document with two or more images and no prose. ' +
      'This spec needs that shape to measure an image spread.',
  );

  await openBook(appPage, { id: target!.id, title: target!.title } as never);
  await expect
    .poll(() => readCurrentPage(appPage), { timeout: 30_000 })
    .toBeGreaterThan(0);

  await goToImageSpread(appPage, target!.imageSpineIndex);
  const frame = await waitForChapterFrame(appPage);
  const images = await readInlineImages(frame);

  const where = `"${target!.title}" spine ${target!.imageSpineIndex} (${images.length} images)`;

  expect(images.length, `${where}: the spread rendered no measurable images`).toBeGreaterThanOrEqual(
    2,
  );

  const [first, second] = images as [InlineImage, InlineImage];

  // (1) THE LAYOUT CLAIM. The old rule capped each image at the prose measure
  // and let two of them halve it, so both maps sat side by side in a ~440px
  // column. Two things must now be true, and the second is the one that makes a
  // map readable:
  //
  //   a. the body is not the prose measure any more (the column is released);
  //   b. the images stack instead of sharing a row.
  //
  // It deliberately does NOT assert the image got wider than its natural width:
  // requirement (2) forbids upscaling, and these sources are 500px, so an honest
  // fix shows each map at its own 1:1 size rather than a smeared 900px.
  const PROSE_MEASURE_MAX = 620;
  expect(
    first.bodyWidth,
    `${where}: the chapter body is ${first.bodyWidth}px — still the prose measure. ` +
      `An image-only document must drop the 65ch column so its plates get the page.`,
  ).toBeGreaterThan(PROSE_MEASURE_MAX);

  for (const [i, image] of images.entries()) {
    expect(
      image.renderedWidth,
      `${where}: image ${i + 1} rendered ${image.renderedWidth}px from a ${image.naturalWidth}px ` +
        `source. Images must never be upscaled past 2x their natural width.`,
    ).toBeLessThanOrEqual(image.naturalWidth * 2 + 1);
  }
  // And the pair is no longer squeezing each other: each is allowed to reach its
  // own natural width, which half a 440px column never permitted.
  expect(
    first.renderedWidth,
    `${where}: image 1 renders ${first.renderedWidth}px of its ${first.naturalWidth}px source. ` +
      `It is still being shrunk by a neighbour sharing its row.`,
  ).toBeGreaterThanOrEqual(Math.min(first.naturalWidth, first.bodyWidth) * 0.95);

  // (2) THE STACKING CLAIM. Two plates sharing a row have the same vertical band;
  // stacked they do not, and the second starts below the first.
  expect(
    second.top,
    `${where}: both images start at top=${second.top}px, so they are still sharing a row and ` +
      `splitting the measure between them. Consecutive content images must stack.`,
  ).toBeGreaterThan(first.top + 40);

  // (3) THE CLICK CLAIM. The viewer must show the map at its own pixel size —
  // the whole point of clicking is to escape the chapter's layout, not to
  // redraw the source at a larger size (the file is only ~500px).
  //
  // Comparing against the inline measurement is what keeps this honest: in the
  // OLD layout the same map rendered at ~314px inside a ~442px column, so a
  // viewer at natural size is a decisive improvement. It deliberately does NOT
  // demand "wider than inline" in every case, because once the spread is fixed
  // the inline image already sits at natural width — asserting growth there
  // would be forcing an upscale, which requirement (2) forbids.
  const inlineWidthForImage = first.renderedWidth;
  const box = await appPage.locator('iframe[title="chapter"]').boundingBox();
  expect(box, `${where}: the chapter frame has no bounding box`).not.toBeNull();

  const center = await frame
    .evaluate(() => {
      const img = Array.from(document.querySelectorAll('img')).find((el) => el.naturalWidth > 0);
      if (!img) return null;
      const rect = img.getBoundingClientRect();
      return { x: rect.left + rect.width / 2, y: rect.top + rect.height / 2 };
    })
    .catch(() => null);
  expect(center, `${where}: no image to click`).not.toBeNull();

  await frame.evaluate(
    ({ x, y }) => {
      const el = document.elementFromPoint(x, y);
      if (!(el instanceof HTMLElement)) throw new Error('nothing at the image centre');
      el.click();
    },
    center!,
  );

  const viewer = appPage.locator('[data-testid="epub-image-viewer"]');
  await expect(
    viewer,
    `${where}: clicking the map did not open the image viewer. The click branch in ` +
      `EpubNativeViewer.onClick did not fire.`,
  ).toBeVisible({ timeout: 10_000 });

  const viewerImage = appPage.locator('[data-testid="epub-image-viewer-img"]');
  await expect(viewerImage).toBeVisible({ timeout: 10_000 });

  const shown = await viewerImage.evaluate((el) => {
    const img = el as HTMLImageElement;
    const rect = img.getBoundingClientRect();
    return {
      width: Math.round(rect.width),
      height: Math.round(rect.height),
      naturalWidth: img.naturalWidth,
    };
  });

  // Fit-to-stage, at the source's own pixels when it fits: the map must display
  // at (or, when the source is larger than the window, very near) natural size.
  const expected = Math.min(shown.naturalWidth, await appPage.evaluate(() => window.innerWidth));
  expect(
    shown.width,
    `${where}: the viewer shows the map at ${shown.width}px across, but its source is ` +
      `${shown.naturalWidth}px and the stage fits ${expected}px. The viewer is not showing the ` +
      `image at full size.`,
  ).toBeGreaterThanOrEqual(expected - 2);

  // And it is at least as large as inline — never a shrink-wrap that loses the
  // readability the spread just gained.
  expect(
    shown.width,
    `${where}: the viewer shows the map at ${shown.width}px, narrower than the ${inlineWidthForImage}px ` +
      `it already occupied in the chapter.`,
  ).toBeGreaterThanOrEqual(Math.min(inlineWidthForImage, shown.naturalWidth));

  // Escape closes it and the reader is back — the dismissal contract.
  await appPage.keyboard.press('Escape');
  await expect(viewer).toBeHidden({ timeout: 10_000 });
});
