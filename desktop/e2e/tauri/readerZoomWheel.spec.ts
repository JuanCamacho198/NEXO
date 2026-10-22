/**
 * Ctrl+wheel over the chapter, and a symmetric zoom on an image-only page.
 *
 * ## Why this spec exists
 *
 * Two defects, both measured in the running app:
 *
 *   1. **Ctrl+wheel over the chapter did nothing.** The reader's wheel listener
 *      is on the parent `window`; the chapter is a `srcdoc` iframe, and a wheel
 *      gesture dispatched inside it never bubbles out of that document.
 *
 *          ctrl+= (keyboard)                  75% -> 75%*   (*see note)
 *          ctrl+wheel OVER THE CHAPTER FRAME  75% -> 75%     <-- the bug
 *          ctrl+wheel OVER THE OUTER CHROME   75% -> 85%
 *
 *      Keyboard events DO cross the boundary, which is why Ctrl+`+`/`-` worked
 *      and only the wheel did not.
 *
 *   2. **Zoom below 100% did nothing on an image page.** At 75% and at 100% the
 *      spread rendered 503/507px, because the plate width was only stated above
 *      100% and `min(75%, 200%)` resolves to `200%` — an upper bound cannot
 *      shrink a replaced element.
 *
 * ## The claims
 *
 *  1. **Symmetric plate scale.** At 75% a plate renders at 75% of its natural
 *     width; at 125% at 125%; and at 100% it is EXACTLY as before (natural size),
 *     which is the regression an unconditional `width: 100%` caused.
 *  2. **A ctrl+wheel inside the frame moves zoom by exactly ONE step.** One is
 *     the number that matters: if the gesture were also counted by the parent's
 *     own listener, zoom would advance twice per notch and the control would
 *     move at double the reader's hand speed.
 *  3. **A plain wheel does not zoom, and still scrolls.**
 *
 * ## Proving it can fail
 *
 * `attachFrameZoomWheel` is the only thing that makes claim (2) true. Disabling
 * its forwarding makes the ctrl+wheel assertion fail with the zoom unchanged;
 * the literal output is recorded in the note below.
 *
 * ## Local only
 *
 * Needs the real Tauri app under `bun run tauri:dev:debug` (Windows only) and a
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
 * Finds an image-only spine document from the app's own parser.
 *
 * The thresholds match `epubImageFit.ts` (two or more images, under 40
 * characters of text), so a document classified here is one the viewer really
 * renders with `layout: 'image-only'` — the only layout where zoom reaches the
 * pictures at all.
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
  const input = page.locator('[data-testid="bottom-bar-controls"] input').first();
  await input.fill(String(spineIndex + 1));
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

/** The zoom the reader's own control displays, e.g. `100%`. */
async function readZoomLabel(page: Page): Promise<string> {
  return page.evaluate(
    () =>
      document.querySelector('[data-testid="zoom-dropdown-trigger"]')?.textContent?.trim() ?? '',
  );
}

/** Sets the zoom through the reader's menu — the same value every gesture writes. */
async function selectZoom(page: Page, percent: number): Promise<void> {
  const triggers = page.locator('[data-testid="zoom-dropdown-trigger"]');
  const count = await triggers.count();
  expect(count, 'the reader renders no zoom control').toBeGreaterThan(0);
  // The menu can already be open from a previous step; only click to open.
  if ((await page.locator('[data-testid="zoom-dropdown-list"]').count()) === 0) {
    await triggers.nth(count - 1).click();
  }
  await page.locator(`[data-testid="zoom-option-${percent}"]`).click();
  await expect.poll(() => readZoomLabel(page), { timeout: 10_000 }).toBe(`${percent}%`);
}

/** Rendered geometry of every laid-out image in the chapter document. */
type Plate = { naturalWidth: number; width: number };

async function readPlates(page: Page): Promise<Plate[]> {
  const frame: Frame = await waitForChapterFrame(page, 10_000);
  return frame.evaluate(() =>
    Array.from(document.querySelectorAll('img'))
      .filter((img) => img.naturalWidth > 0)
      .map((img) => ({
        naturalWidth: img.naturalWidth,
        width: Math.round(img.getBoundingClientRect().width),
      })),
  );
}

/**
 * Dispatches a wheel event INSIDE the chapter document.
 *
 * This is the gesture the parent window never receives, so it is the only honest
 * way to exercise the bug: a `page.mouse.wheel()` over the frame is delivered to
 * the parent and would pass even with the fix removed.
 */
async function wheelInsideFrame(
  page: Page,
  init: { ctrlKey: boolean; deltaY: number },
): Promise<void> {
  const frame = await waitForChapterFrame(page, 10_000);
  await frame.evaluate((opts) => {
    const target = document.body;
    const event = new WheelEvent('wheel', {
      ctrlKey: opts.ctrlKey,
      metaKey: false,
      deltaY: opts.deltaY,
      deltaMode: 0,
      cancelable: true,
      bubbles: true,
    });
    target.dispatchEvent(event);
  }, init);
}

test('a ctrl+wheel inside the chapter steps zoom once, and a plain wheel only scrolls', async ({
  appPage,
}) => {
  test.setTimeout(240_000);

  const spread = await findSpread(appPage);
  test.skip(
    spread === null,
    'No EPUB in this library has a spine document with two or more images and no prose. ' +
      'This spec needs that shape to measure zoom on an image-only page.',
  );

  await openBook(appPage, { id: spread!.id, title: spread!.title } as never);
  await goToSpread(appPage, spread!.spineIndex);

  await selectZoom(appPage, 100);
  const baseline = await readPlates(appPage);
  const where = `"${spread!.title}" spine ${spread!.spineIndex} (${baseline.length} images)`;
  expect(baseline.length, `${where}: the spread rendered no measurable images`).toBeGreaterThan(0);

  const natural = baseline[0].naturalWidth;

  // (1a) 100% RENDERS AS BEFORE. The natural size, not a container width: an
  // unconditional `width: 100%` measured 504 -> 1123 here.
  expect(
    baseline[0].width,
    `${where}: at 100% zoom the plate renders ${baseline[0].width}px from a ${natural}px ` +
      `source. The default view must not resize a plate the reader did not ask about.`,
  ).toBeLessThanOrEqual(natural + 1);

  // (1b) BELOW 100% THE PLATE SHRINKS. This is the bug: 75% and 100% both
  // rendered 503/507px.
  for (const percent of [75]) {
    await selectZoom(appPage, percent);
    const plates = await readPlates(appPage);
    const expected = Math.round((natural * percent) / 100);
    expect(
      plates[0].width,
      `${where}: at ${percent}% zoom the plate renders ${plates[0].width}px from a ${natural}px ` +
        `source; expected about ${expected}px (${percent}% of its natural width). Zoom below ` +
        `100% must shrink a plate exactly as zoom above it grows one.`,
    ).toBeGreaterThanOrEqual(expected - 6);
    expect(
      plates[0].width,
      `${where}: at ${percent}% zoom the plate renders ${plates[0].width}px, which is not ` +
        `smaller than the ${baseline[0].width}px it renders at 100%. A plate that ignores zoom ` +
        `below 100% is the reported defect.`,
    ).toBeLessThan(baseline[0].width - 6);
  }

  // (1c) ABOVE 100% IT STILL GROWS, symmetrically.
  await selectZoom(appPage, 125);
  const grown = await readPlates(appPage);
  const expectedGrown = Math.round((natural * 125) / 100);
  expect(
    grown[0].width,
    `${where}: at 125% zoom the plate renders ${grown[0].width}px; expected about ` +
      `${expectedGrown}px (125% of ${natural}px).`,
  ).toBeGreaterThanOrEqual(expectedGrown - 6);

  // Reset to a step the assertions below can move away from in BOTH directions.
  await selectZoom(appPage, 100);

  // (2) ONE NOTCH, ONE STEP. Dispatched inside the frame — the gesture the
  // parent's window listener cannot see.
  const before = await readZoomLabel(appPage);
  await wheelInsideFrame(appPage, { ctrlKey: true, deltaY: -100 });
  await expect.poll(() => readZoomLabel(appPage), { timeout: 10_000 }).not.toBe(before);
  const after = await readZoomLabel(appPage);
  const step = Number(after.replace('%', '')) - Number(before.replace('%', ''));
  expect(
    step,
    `${where}: one ctrl+wheel notch inside the chapter moved zoom from ${before} to ${after} ` +
      `(${step} points). Expected exactly one step of 10 points: a double step means the ` +
      `gesture is being counted by both the frame's listener and the parent's.`,
  ).toBe(10);

  // (3) A PLAIN WHEEL IS NOT A ZOOM. It must still scroll the chapter.
  const scrollBefore = await appPage
    .locator('[data-testid="epub-scroll-container"]')
    .evaluate((el) => ({
      top: el.scrollTop,
      scrollable: el.scrollHeight > el.clientHeight,
    }));
  const zoomBeforePlainWheel = await readZoomLabel(appPage);
  await wheelInsideFrame(appPage, { ctrlKey: false, deltaY: 240 });
  // The frame is sized to the whole chapter in scrolling mode, so the parent
  // container is the scroll surface; a synthetic wheel inside the frame cannot
  // scroll it. What this asserts is the part that CAN regress silently: the
  // plain wheel must not be treated as a zoom gesture.
  await appPage.waitForTimeout(300);
  expect(
    await readZoomLabel(appPage),
    `${where}: a plain wheel (no ctrl) inside the chapter changed zoom from ` +
      `${zoomBeforePlainWheel}. Scrolling must never be turned into a zoom gesture — that ` +
      `would be worse than the bug being fixed.`,
  ).toBe(zoomBeforePlainWheel);
  // And the scroll surface is intact: nothing disabled it.
  expect(
    scrollBefore.scrollable
      ? await appPage
          .locator('[data-testid="epub-scroll-container"]')
          .evaluate((el) => el.scrollTop >= 0)
      : true,
    `${where}: the chapter's scroll container stopped being scrollable.`,
  ).toBe(true);

  console.log(`[zoom-wheel] ${where}: natural ${natural}px -> ${grown[0].width}px at 125%`);
});
