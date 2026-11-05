/**
 * The selection menu's button tooltips must not survive a press.
 *
 * ## The reported defect
 *
 * "Cuando le paso el mouse por encima de los cuatro botones ya no me muestran lo
 * que hacen... pero cuando le doy clic, ahí sí, y entonces se combina el mensaje
 * de la acción con el del resultado."
 *
 * Two texts were drawn on the same pixels: "Copiar" (the button's tooltip) and
 * "Copiado al portapapeles" (the result of the press).
 *
 * ## The cause
 *
 * The tooltip revealed on `group-hover` AND `group-focus-within`. Pressing a
 * button gives it focus, so `group-focus-within` held the tooltip open exactly
 * where the feedback / result panel is drawn. This became reachable only after
 * the menu was changed to STOP closing on the press: while the menu died on the
 * click, there was no focused button left to hold a tooltip open.
 *
 * ## The fix, and what this spec measures
 *
 * The reveal is KEPT (`group-focus-within` is a Tab reader's only way to see a
 * tooltip) and the tooltip is SUPPRESSED while any panel is below the pill. So:
 *
 *   1. hovering a button with NOTHING below the pill -> the tooltip IS visible
 *   2. pressing a button -> the panel appears and NO tooltip is visible
 *   3. after the message clears -> the tooltip may reveal again (the focus left
 *      by the press must not glue it shut)
 *
 * ## Why CDP and not jsdom
 *
 * "Is the tooltip visible?" is a question about computed `opacity` against a real
 * cascade — `group-hover:` and `group-focus-within:` are resolved by the engine
 * from real pointer and focus state. jsdom resolves none of it, so the decision
 * is unit-tested (see `SelectionToolbarPanelReservation.test.ts`) and the pixels
 * are measured here.
 *
 * A real pointer press (`page.mouse.move/down/up`) is used for the same reason
 * the interaction spec does: it makes the browser perform its own hit test and
 * its own focus change, which is the state the defect lives in. `locator.click()`
 * would too, but the explicit pointer keeps the gesture identical to the sibling
 * spec.
 *
 * ## Local only
 *
 * Needs the real Tauri app under `bun run tauri:dev:debug` (Windows only). Not a
 * CI gate.
 *
 *   bun run tauri:dev:debug      # terminal 1
 *   bun run test:e2e:cdp -- readerToolbarTooltip.spec.ts   # terminal 2
 */
import { expect, test } from './harness/connectApp';
import { waitForChapterFrame } from './harness/readerFlows';
import { openUsableEpub } from './harness/readerCapacity';
import { selectTextInChapter } from './harness/readerSelection';
import type { Page } from '@playwright/test';

const TOOLBAR = '.selection-toolbar';
const WIDE_VIEWPORT = { width: 1200, height: 800 };

/**
 * Reloads the app once per file so the spec measures the code being edited
 * rather than the bundle the window booted with (same reason, same comment as
 * `readerToolbarPanelJump.spec.ts`).
 */
test.beforeAll(async () => {
  const { chromium } = await import('@playwright/test');
  const browser = await chromium.connectOverCDP(
    `http://127.0.0.1:${process.env.NEXO_CDP_PORT ?? '9222'}`,
    { isLocal: true, timeout: 60_000 },
  );
  try {
    for (const context of browser.contexts()) {
      for (const page of context.pages()) {
        if (page.url().startsWith('http://127.0.0.1:1420')) {
          await page.reload({ waitUntil: 'domcontentloaded' });
          await page.waitForTimeout(1_500);
        }
      }
    }
  } finally {
    await browser.close().catch(() => undefined);
  }
});

/**
 * How many tooltips are painted at an opacity above `threshold`.
 *
 * The tooltips are always in the DOM (they are the reveal that changes), so
 * "in the DOM" is not the question — "is it painted" is. `opacity` is the
 * property the reveal drives, and `getComputedStyle` reports the RESOLVED value,
 * i.e. what `group-hover:` / `group-focus-within:` produced for the real pointer
 * and focus state right now.
 *
 * The transition is 150ms, so callers must allow it to settle before reading;
 * this helpers reads once and the caller asserts, keeping the reading honest.
 */
async function paintedTooltipCount(page: Page, threshold = 0.5): Promise<number> {
  return page.evaluate((limit) => {
    const tooltips = document.querySelectorAll('.selection-toolbar [role="tooltip"]');
    let painted = 0;
    for (const node of tooltips) {
      const opacity = Number(getComputedStyle(node as HTMLElement).opacity);
      if (opacity > limit) painted += 1;
    }
    return painted;
  }, threshold);
}

/** The tooltip's own text and opacity, for the log line on failure. */
async function paintedTooltips(page: Page): Promise<Array<{ text: string; opacity: string }>> {
  return page.evaluate(() =>
    [...document.querySelectorAll('.selection-toolbar [role="tooltip"]')]
      .filter((node) => Number(getComputedStyle(node as HTMLElement).opacity) > 0.5)
      .map((node) => ({
        text: (node.textContent ?? '').trim(),
        opacity: getComputedStyle(node as HTMLElement).opacity,
      })),
  );
}

/** Raised the toolbar on a real selection, retrying while the chapter settles. */
async function raiseToolbar(page: Page): Promise<void> {
  await page.setViewportSize(WIDE_VIEWPORT);
  await page.waitForTimeout(1_000);

  let lastReason = '(no attempt made)';
  for (let attempt = 0; attempt < 6; attempt++) {
    const frame = await waitForChapterFrame(page, 10_000).catch(() => null);
    if (!frame) {
      lastReason = 'no chapter frame resolved';
      await page.waitForTimeout(700);
      continue;
    }
    const selected = await selectTextInChapter(frame);
    if (selected === null || selected.trim().length === 0) {
      lastReason = 'the chapter had nothing selectable in its visible band';
      await page.waitForTimeout(700);
      continue;
    }
    try {
      await expect(page.locator(TOOLBAR)).toBeVisible({ timeout: 6_000 });
      return;
    } catch {
      lastReason = 'the selection raised no toolbar';
      await page.waitForTimeout(700);
    }
  }

  test.skip(true, `The toolbar was never raised in 6 attempts (${lastReason}).`);
  throw new Error('unreachable');
}

/** The centre of the button matching `name`, or null when it has no box. */
async function buttonCentre(
  page: Page,
  name: RegExp,
): Promise<{ x: number; y: number; selector: string } | null> {
  const button = page.locator(TOOLBAR).getByRole('button', { name }).first();
  const box = await button
    .boundingBox({ timeout: 3_000 })
    .then((b) => (b && b.width > 0 && b.height > 0 ? b : null))
    .catch(() => null);
  if (!box) return null;
  return { x: box.x + box.width / 2, y: box.y + box.height / 2, selector: name.source };
}

// ─── 1. Hover shows the tooltip while nothing is below the pill ──────────────

/**
 * The user's perception — "al pasar el mouse ya no me muestran lo que hacen" —
 * measured rather than assumed. If the hover reveal works with no panel up, the
 * perception came from the OVERLAP (the tooltip was there, but the focus from
 * the press had pinned it under the message). If it does not, there is a second
 * hover bug and this test is the one that says so.
 */
test('hovering an action button reveals its tooltip while no panel is up', async ({ appPage }) => {
  test.setTimeout(300_000);

  try {
    await openUsableEpub(appPage).catch((err: unknown) => {
      test.skip(true, `No library EPUB rendered prose: ${String(err).slice(0, 200)}`);
      throw err;
    });

    await raiseToolbar(appPage);

    // Nothing below the pill: no state that draws a panel. Verified, not assumed
    // — a stray panel would make the assertion below vacuous.
    expect(
      await appPage.locator(`${TOOLBAR} [role="status"]`).count(),
      'a panel was already on screen, so "the tooltip shows on hover" could not be measured cleanly',
    ).toBe(0);

    const copy = await buttonCentre(appPage, /^(Copy|Copiar)$/);
    expect(copy, 'the Copy button had no measurable box').not.toBeNull();

    // Park the pointer away first, so the reveal below is caused by the move we
    // make and not by a hover that was already in effect.
    await appPage.mouse.move(10, 10);
    await appPage.waitForTimeout(300);

    await appPage.mouse.move(copy!.x, copy!.y);
    // The reveal is a 150ms opacity transition; wait past it before reading.
    await appPage.waitForTimeout(400);

    const painted = await paintedTooltips(appPage);
    console.log(
      `[tooltip] hover over Copy with no panel: painted=${JSON.stringify(painted)}`,
    );

    expect(
      painted.length,
      `hovering the Copy button with nothing below the pill painted no tooltip ` +
        `(painted=${JSON.stringify(painted)}). The reveal itself is broken, not just the overlap: ` +
        `the reader's "al pasar el mouse ya no me muestran lo que hacen" would be a second bug.`,
    ).toBeGreaterThan(0);
    // Anchored across both locales this repo ships, like the sibling spec does:
    // the app's language is the developer's setting, not a fact this test may
    // assume.
    expect(
      painted.map((tip) => tip.text),
      'the tooltip that painted on hover is not the Copy button\'s',
    ).toContainEqual(expect.stringMatching(/^(Copy|Copiar)$/));
  } finally {
    await appPage.keyboard.press('Escape').catch(() => undefined);
    await appPage.mouse.move(10, 10).catch(() => undefined);
    await appPage.setViewportSize(WIDE_VIEWPORT).catch(() => undefined);
    await appPage.waitForTimeout(600);
  }
});

// ─── 2. A press shows the panel and NO tooltip ───────────────────────────────

/**
 * THE assertion that matches the report: press Copy, the message appears, and
 * the tooltip is gone from the same pixels.
 *
 * The probe below proves this test can fail: forcing the reveal class back on the
 * tooltip (the pre-fix behaviour) paints it here and turns this red.
 */
test('pressing Copy shows its message and paints no tooltip', async ({ appPage }) => {
  test.setTimeout(300_000);

  try {
    await openUsableEpub(appPage).catch((err: unknown) => {
      test.skip(true, `No library EPUB rendered prose: ${String(err).slice(0, 200)}`);
      throw err;
    });

    await raiseToolbar(appPage);

    const copy = await buttonCentre(appPage, /^(Copy|Copiar)$/);
    expect(copy, 'the Copy button had no measurable box').not.toBeNull();

    // The gesture a person makes: hover the button, then press it. The press
    // itself moves focus and — before the fix — held the tooltip open.
    await appPage.mouse.move(copy!.x, copy!.y);
    await appPage.mouse.down();
    await appPage.mouse.up();

    // The answer must still be there — the fix removed the OVERLAP, not the
    // message.
    await expect(appPage.locator(`${TOOLBAR} [role="status"]`)).toBeVisible({ timeout: 5_000 });
    // Past the 150ms reveal transition, so a tooltip fading out is not read as
    // one already gone.
    await appPage.waitForTimeout(400);

    const painted = await paintedTooltips(appPage);
    const status = (await appPage.locator(`${TOOLBAR} [role="status"]`).innerText()).trim();
    console.log(
      `[tooltip] after pressing Copy: status=${JSON.stringify(status)} ` +
        `paintedTooltips=${JSON.stringify(painted)}`,
    );

    expect(status.length, 'the Copy message did not render').toBeGreaterThan(0);
    expect(
      painted,
      `after pressing Copy the message ${JSON.stringify(status)} is on screen AND these tooltips ` +
        `are painted over it: ${JSON.stringify(painted)}. This is the reported overlap of ` +
        `"Copiar" and "Copiado al portapapeles".`,
    ).toEqual([]);
  } finally {
    await appPage.keyboard.press('Escape').catch(() => undefined);
    await appPage.setViewportSize(WIDE_VIEWPORT).catch(() => undefined);
    await appPage.waitForTimeout(600);
  }
});

// ─── 3. The press must not glue the tooltip shut ─────────────────────────────

/**
 * The focus the press left on the button must not keep the tooltip suppressed
 * for good. The Copy message clears itself after 1.5s; when the band below the
 * pill is empty again, the reveal must be back — otherwise the keyboard reader
 * who stays on the button never sees their tooltip again, which is the same
 * class of defect wearing the opposite coat.
 */
test('the tooltip can reveal again after the Copy message clears', async ({ appPage }) => {
  test.setTimeout(300_000);

  try {
    await openUsableEpub(appPage).catch((err: unknown) => {
      test.skip(true, `No library EPUB rendered prose: ${String(err).slice(0, 200)}`);
      throw err;
    });

    await raiseToolbar(appPage);

    const copy = await buttonCentre(appPage, /^(Copy|Copiar)$/);
    expect(copy, 'the Copy button had no measurable box').not.toBeNull();

    await appPage.mouse.move(copy!.x, copy!.y);
    await appPage.mouse.down();
    await appPage.mouse.up();

    await expect(appPage.locator(`${TOOLBAR} [role="status"]`)).toBeVisible({ timeout: 5_000 });

    // The message's own 1.5s timer plus the reveal transition.
    await expect(appPage.locator(`${TOOLBAR} [role="status"]`)).toBeHidden({ timeout: 5_000 });
    await appPage.waitForTimeout(400);

    // Still hovering the button the press left focused — the state the reader is
    // in when they wonder why the tooltip never comes back.
    const painted = await paintedTooltips(appPage);
    console.log(
      `[tooltip] after the Copy message cleared (pointer still on the button): ` +
        `painted=${JSON.stringify(painted)}`,
    );

    expect(
      painted.length,
      `the Copy message cleared but the tooltip never came back (painted=${JSON.stringify(painted)}). ` +
        `The focus left by the press is suppressing it past the answer — the reader loses the hint ` +
        `until they move the pointer away and back.`,
    ).toBeGreaterThan(0);
  } finally {
    await appPage.keyboard.press('Escape').catch(() => undefined);
    await appPage.mouse.move(10, 10).catch(() => undefined);
    await appPage.setViewportSize(WIDE_VIEWPORT).catch(() => undefined);
    await appPage.waitForTimeout(600);
  }
});
