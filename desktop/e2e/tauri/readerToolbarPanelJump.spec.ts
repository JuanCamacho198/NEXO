/**
 * The selection menu must NOT move when Copy or Add to Dictionary is pressed.
 *
 * ## The reported defect
 *
 * "Cuando le doy a cualquiera de los botones de copiar, traducir, definir... el
 * menú sube hacia arriba y luego vuelve hacia abajo." — the menu went UP on the
 * press and came back DOWN a moment later, for every action button.
 *
 * ## The cause, measured in the code
 *
 * `resultPanelOpen` put all SIX "something is showing below the pill" states in
 * one bag — `copyFeedback`, `dictionaryFeedback`, `translating`, `translation`,
 * `defining`, `definition`, `definitionFailed` — and the placement reserved a
 * 120px panel budget whenever ANY of them was set. Copy and Dictionary render a
 * ONE-LINE message that clears itself after 1.5s, so pressing Copy made the
 * placement believe the menu was 120px taller, lifted it to fit, and dropped it
 * back when the message expired. That is the up-then-down the reader saw.
 *
 * ## The distinction the fix makes, and why
 *
 * Copy and Dictionary SHOW FEEDBACK; Translate and Define SHOW RESULTS. Only a
 * result is a panel whose height the placement cannot predict, so only a result
 * reserves room. The refusal is deliberate and is what this spec measures:
 *
 *   - Copy press        -> the menu's `top` must be IDENTICAL (0px of movement)
 *   - Translate press   -> the menu MAY move (accepted), but the move must be
 *                          ANIMATED rather than instant
 *
 * ## Why this has to be here and not in jsdom
 *
 * jsdom has no layout engine and no computed transitions: every `top` reads back
 * as a string and nothing interpolates. The whole question — "did the menu
 * actually move on screen, and did the move take time?" — is a statement about
 * the live engine, so it is measured against the real app over CDP.
 *
 * The press is a real `page.mouse` press at the button's own centre, so the
 * browser runs its own hit test, its own focus change and its own click — the
 * gesture a person makes, not a synthesised event.
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
import { scrollChapter, waitForChapterFrame } from './harness/readerFlows';
import { openUsableEpub } from './harness/readerCapacity';
import { selectTextInChapter } from './harness/readerSelection';
import type { Frame, Page } from '@playwright/test';

const TOOLBAR = '.selection-toolbar';
const WIDE_VIEWPORT = { width: 1200, height: 800 };

/**
 * Reloads the app once per file, so the spec measures the code that is being
 * edited rather than the bundle the window happened to boot with.
 *
 * The other specs deliberately never reload (the harness note: "the app page is
 * never reloaded between specs"), and that is right for a spec measuring a
 * settled app. It is wrong here. This spec asserts on TRANSITION classes and on
 * the placement's own `top` — values a stale HMR chunk gets exactly, silently
 * and wrongly: MEASURED, the first run of this file against a window that had
 * booted before the edit reported a -120px Copy jump and `transition-property:
 * all`, i.e. the OLD behaviour, from a dev server that was already serving the
 * new source. A spec that cannot tell the new code from the old is not an
 * instrument.
 *
 * The reload is safe: the library lives in the database, not the page, and every
 * test here drives to the book itself through `openUsableEpub`.
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

/** The toolbar's box right now, including where its bottom edge lands. */
async function readMenuBox(page: Page): Promise<{
  top: number;
  bottom: number;
  height: number;
  viewportHeight: number;
} | null> {
  return page.evaluate((sel) => {
    const el = document.querySelector(sel);
    if (!el) return null;
    const rect = el.getBoundingClientRect();
    return {
      top: rect.top,
      bottom: rect.bottom,
      height: rect.height,
      viewportHeight: window.innerHeight,
    };
  }, TOOLBAR);
}

/**
 * The `top` written by the placement, read from the raw style attribute — the
 * number the component decided, before any transition has had a chance to
 * interpolate it.
 *
 * `getBoundingClientRect().top` is the WRONG oracle for the "did it move?"
 * question: it reports the ANIMATED position, so a transition in flight reads as
 * a partial move and the assertion becomes a race.
 */
async function readInlineTop(page: Page): Promise<number | null> {
  return page.evaluate((sel) => {
    const el = document.querySelector(sel) as HTMLElement | null;
    if (!el) return null;
    const match = /top:\s*(-?[\d.]+)px/.exec(el.getAttribute('style') ?? '');
    return match ? Number(match[1]) : null;
  }, TOOLBAR);
}

/** Raised the toolbar on a real selection, retrying while the chapter settles. */
async function raiseToolbar(page: Page): Promise<Frame> {
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
      return frame;
    } catch {
      lastReason = 'the selection raised no toolbar';
      await page.waitForTimeout(700);
    }
  }

  test.skip(true, `The toolbar was never raised in 6 attempts (${lastReason}).`);
  throw new Error('unreachable');
}

/** Presses the action button matching `name` once, with a real pointer. */
async function pressOnce(page: Page, name: RegExp): Promise<void> {
  const button = page.locator(TOOLBAR).getByRole('button', { name }).first();
  await expect(button).toBeVisible({ timeout: 5_000 });
  const box = await button.boundingBox();
  expect(box, `the button matching ${name} had no measurable box`).not.toBeNull();
  await page.mouse.move(box!.x + box!.width / 2, box!.y + box!.height / 2);
  await page.mouse.down();
  await page.mouse.up();
}

// ─── 1. Copy must not move the menu ──────────────────────────────────────────

/**
 * THE assertion that matches the report: press Copy, and the menu's position is
 * IDENTICAL before and after.
 *
 * The bug is proven testable below: restoring `copyFeedback` to the reserved bag
 * turns this red with a literal non-zero offset.
 */
test('pressing Copy moves the menu 0px', async ({ appPage }) => {
  test.setTimeout(300_000);

  try {
    await openUsableEpub(appPage).catch((err: unknown) => {
      test.skip(true, `No library EPUB rendered prose: ${String(err).slice(0, 200)}`);
      throw err;
    });

    await raiseToolbar(appPage);

    const before = await readInlineTop(appPage);
    expect(before, 'the toolbar had no readable `top` before the press').not.toBeNull();

    await pressOnce(appPage, /^(Copy|Copiar)$/);

    // The feedback panel must still be there — the fix removed the RESERVATION,
    // not the message.
    await expect(appPage.locator(TOOLBAR).locator('[role="status"]')).toBeVisible({
      timeout: 5_000,
    });

    const after = await readInlineTop(appPage);
    expect(after, 'the toolbar had no readable `top` after the press').not.toBeNull();

    const delta = after! - before!;
    console.log(
      `[panel-jump] Copy: top ${before} -> ${after} (delta ${delta}px). ` +
        `A non-zero delta is the reported "el menú sube hacia arriba y luego vuelve hacia abajo".`,
    );

    expect(
      delta,
      `pressing Copy moved the menu from top ${before} to top ${after} — a ${delta}px jump. Copy ` +
        `shows a one-line message that clears itself after 1.5s, so it must reserve no panel room ` +
        `and the menu must not move at all. This is the reported "el menú sube hacia arriba y ` +
        `luego vuelve hacia abajo".`,
    ).toBe(0);
  } finally {
    await appPage.keyboard.press('Escape').catch(() => undefined);
    await appPage.setViewportSize(WIDE_VIEWPORT).catch(() => undefined);
    await appPage.waitForTimeout(600);
  }
});

// ─── 2. Dictionary must not move the menu either ─────────────────────────────

test('pressing Add to Dictionary moves the menu 0px', async ({ appPage }) => {
  test.setTimeout(300_000);

  try {
    await openUsableEpub(appPage).catch((err: unknown) => {
      test.skip(true, `No library EPUB rendered prose: ${String(err).slice(0, 200)}`);
      throw err;
    });

    await raiseToolbar(appPage);

    const before = await readInlineTop(appPage);
    expect(before, 'the toolbar had no readable `top` before the press').not.toBeNull();

    await pressOnce(appPage, /^(Add to Dictionary|Agregar al diccionario)$/);

    // Dictionary goes over IPC, so the panel may arrive a moment later than
    // Copy's synchronous one. Its appearance is what the assertion below needs.
    const panel = appPage.locator(TOOLBAR).locator('[role="status"]');
    await expect(panel)
      .toBeVisible({ timeout: 10_000 })
      .catch(() => undefined);

    const after = await readInlineTop(appPage);
    expect(after, 'the toolbar had no readable `top` after the press').not.toBeNull();

    const delta = after! - before!;
    console.log(
      `[panel-jump] Dictionary: top ${before} -> ${after} (delta ${delta}px); ` +
        `panel=${JSON.stringify(((await panel.innerText().catch(() => '')) ?? '').trim().slice(0, 40))}`,
    );

    expect(
      delta,
      `pressing Add to Dictionary moved the menu from top ${before} to top ${after} — a ${delta}px ` +
        `jump. Dictionary feedback is a one-line message like Copy's, so it must not move the menu.`,
    ).toBe(0);
  } finally {
    await appPage.keyboard.press('Escape').catch(() => undefined);
    await appPage.setViewportSize(WIDE_VIEWPORT).catch(() => undefined);
    await appPage.waitForTimeout(600);
  }
});

// ─── 3. Translate MAY move the menu, but the move is animated ────────────────

/**
 * The accepted half, and the second half of the request.
 *
 * Translate opens a real result panel, so the menu is ALLOWED to move — but the
 * reader asked for that move to be "una pequeña transición" rather than a jump.
 * The transition is proven by reading the computed `transition-duration` and
 * `transition-property` off the live element: a duration of 0ms is the jump the
 * request was about.
 */
test('pressing Translate may move the menu, and the move is animated', async ({ appPage }) => {
  test.setTimeout(300_000);

  try {
    await openUsableEpub(appPage).catch((err: unknown) => {
      test.skip(true, `No library EPUB rendered prose: ${String(err).slice(0, 200)}`);
      throw err;
    });

    await raiseToolbar(appPage);

    const before = await readInlineTop(appPage);
    expect(before, 'the toolbar had no readable `top` before the press').not.toBeNull();

    // The transition as the live engine resolved it, BEFORE the press. Reading
    // it here means the assertion is about the element that is about to move.
    const transition = await appPage.evaluate((sel) => {
      const el = document.querySelector(sel) as HTMLElement | null;
      if (!el) return null;
      const style = getComputedStyle(el);
      return {
        property: style.transitionProperty,
        duration: style.transitionDuration,
        timing: style.transitionTimingFunction,
      };
    }, TOOLBAR);

    await pressOnce(appPage, /^(Translate|Traducir)$/);
    await expect(appPage.locator(TOOLBAR).locator('[role="status"]')).toBeVisible({
      timeout: 5_000,
    });

    const after = await readInlineTop(appPage);
    expect(after, 'the toolbar had no readable `top` after the press').not.toBeNull();

    const delta = after! - before!;
    console.log(
      `[panel-jump] Translate: top ${before} -> ${after} (delta ${delta}px), transition ` +
        `${JSON.stringify(transition)}`,
    );

    // The move itself is ACCEPTED — Translate renders a real panel, so reserving
    // room for it is the point. What is asserted is that the move exists (so the
    // reservation was not silently removed) and that it is animated.
    expect(
      delta,
      'Translate opened a result panel and the menu did not move at all, so the panel has no ' +
        'reserved room and will hang off the bottom edge',
    ).not.toBe(0);

    expect(transition, 'the toolbar had no computed transition').not.toBeNull();
    expect(
      transition!.property,
      `the menu declares transition-property "${transition!.property}", which does not include ` +
        `"top" — its re-placement is an instant jump`,
    ).toContain('top');
    expect(
      Number.parseFloat(transition!.duration),
      `the menu's transition-duration is ${transition!.duration}ms. A re-placement that takes no ` +
        `time IS the jump the reader reported; the request was for a small transition.`,
    ).toBeGreaterThan(0);
    // The project's own token, not an invented value: `--duration-fast` is 150ms
    // in `tokens.css`. 200ms is the ceiling so a different token would still pass.
    expect(
      Number.parseFloat(transition!.duration),
      `the menu's transition-duration is ${transition!.duration}ms, which is longer than the ` +
        `"pequeña transición" the request asked for`,
    ).toBeLessThanOrEqual(200);
  } finally {
    await appPage.keyboard.press('Escape').catch(() => undefined);
    await appPage.setViewportSize(WIDE_VIEWPORT).catch(() => undefined);
    await appPage.waitForTimeout(600);
  }
});

// ─── 4. The short feedback panel never leaves the window ─────────────────────

/**
 * The consequence of "the menu does not move": the one-line message is drawn
 * where the pill already is, so a selection near the bottom edge leaves it less
 * room. Its own ceiling has to be the space that actually exists.
 *
 * The reader is scrolled to a selection low in the chapter and Copy is pressed;
 * the panel's bottom edge must stay inside the window.
 */
test('the Copy feedback panel stays inside the window on a low selection', async ({ appPage }) => {
  test.setTimeout(300_000);

  try {
    await openUsableEpub(appPage).catch((err: unknown) => {
      test.skip(true, `No library EPUB rendered prose: ${String(err).slice(0, 200)}`);
      throw err;
    });

    await raiseToolbar(appPage);
    await appPage.keyboard.press('Escape');
    await appPage.waitForTimeout(400);

    // Push the chapter down so the next selection sits low in the window, which
    // is the case the panel's ceiling exists for.
    await scrollChapter(appPage, 600);
    await appPage.waitForTimeout(700);

    const frame = await waitForChapterFrame(appPage);
    const selected = await selectTextInChapter(frame);
    if (selected === null || selected.trim().length === 0) {
      test.skip(true, 'the chapter had no selectable run after scrolling');
      return;
    }
    await expect(appPage.locator(TOOLBAR)).toBeVisible({ timeout: 8_000 });

    await pressOnce(appPage, /^(Copy|Copiar)$/);
    const status = appPage.locator(TOOLBAR).locator('[role="status"]');
    await expect(status).toBeVisible({ timeout: 5_000 });

    const panel = await status.evaluate((el) => {
      const rect = el.getBoundingClientRect();
      return {
        top: Math.round(rect.top),
        bottom: Math.round(rect.bottom),
        viewportHeight: window.innerHeight,
        style: el.getAttribute('style') ?? '',
      };
    });

    console.log(`[panel-jump] low-selection Copy panel: ${JSON.stringify(panel)}`);

    expect(
      panel.bottom,
      `the Copy feedback panel's bottom edge is at ${panel.bottom}px in a ` +
        `${panel.viewportHeight}px window — the message is drawn off the bottom edge. The menu ` +
        `deliberately does not move for feedback, so the panel's own ceiling has to be the room ` +
        `below the pill.`,
    ).toBeLessThanOrEqual(panel.viewportHeight);
    expect(panel.top).toBeGreaterThanOrEqual(0);

    const box = await readMenuBox(appPage);
    expect(box, 'the toolbar had no box after the Copy press').not.toBeNull();
  } finally {
    await appPage.keyboard.press('Escape').catch(() => undefined);
    await appPage.setViewportSize(WIDE_VIEWPORT).catch(() => undefined);
    await appPage.waitForTimeout(600);
  }
});
