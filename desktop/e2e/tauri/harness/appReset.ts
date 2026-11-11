/**
 * Leaves the live app in a KNOWN-CLEAN state before and after each CDP spec.
 *
 * ## Why this module exists
 *
 * The `tauri-cdp` specs all attach to **one long-lived window** — the harness
 * never reloads it between specs, because the app is the developer's and the
 * suite deliberately does not restart it. That means every spec inherits
 * whatever the previous one left behind, and what gets left behind is a floating
 * menu.
 *
 * Concretely, three surfaces are `fixed` over the reader and intercept the next
 * click:
 *
 *   - `.selection-toolbar` (`fixed z-50`, over the top bar),
 *   - `[data-testid="highlight-context-menu"]` (which additionally renders a
 *     `fixed inset-0 z-[99]` backdrop),
 *   - the portalled popovers both of those open (`.selection-toolbar
 *     [role="listbox"]`, marked `[data-floating-menu]`).
 *
 * Measured in this suite: a click aimed at the reader's own chrome behind one of
 * them "retried 566 times instead of failing", and a menu left up by a previous
 * run made the *next* run block on a control a human had to reach on screen —
 * which is exactly the report this module answers.
 *
 * ## Why not a Playwright fixture (yet)
 *
 * The clean thing long-term is a `resetApp`/`cleanApp` auto-fixture in
 * `connectApp.ts`. That is deliberately NOT done here: `appPage`'s teardown
 * closes the CDP attachment, and an auto-fixture ordered *after* it would run
 * against a detached page — an endless source of "works until it does not".
 * Wiring it correctly means rewiring the `appPage` fixture's `finally`, which is
 * the one piece of this harness a bad edit breaks for every spec at once.
 * So the helpers are exported as plain functions a spec calls explicitly, and
 * the fixture change is left as a separate, reviewable step.
 *
 * ## Two rules, both load-bearing
 *
 * 1. **Bounded.** Every wait here has a deadline. A leftover menu must never
 *    turn into an unbounded wait for UI a human would have to resolve, which is
 *    the defect this module exists to remove.
 * 2. **Best-effort.** It never throws for "the menu would not close". It
 *    reports, and the caller decides. A reset that fails loudly turns a stale
 *    menu into a red test with the wrong cause; a reset that fails silently is
 *    worse. Hence {@link DismissResult}, which says what it saw.
 */
import type { Page } from '@playwright/test';

/**
 * Every surface that counts as "a floating menu is up", as a selector list.
 *
 * Kept in sync with `FLOATING_MENU_SELECTOR` in
 * `src/lib/features/reader/chrome/floatingMenu.ts` (which marks each surface
 * with `data-floating-menu="true"`), plus the selection toolbar's own selector.
 * The `[data-floating-menu]` member is what catches surfaces the other two miss
 * — today the colour picker and tag popover — so a surface added later is
 * covered by that marker without an edit here.
 */
export const FLOATING_MENU_SELECTORS = [
  '.selection-toolbar',
  '[data-testid="highlight-context-menu"]',
  '[data-floating-menu]',
] as const;

/** One selector list for `locator()` calls that only need a count. */
const FLOATING_MENU_SELECTOR = FLOATING_MENU_SELECTORS.join(', ');

/** What a dismissal attempt saw. `closed` is a fact, not a hope. */
export type DismissResult = {
  /** How many floating-menu elements were on screen when the reset started. */
  seen: number;
  /** How many are left. 0 is the only clean value. */
  remaining: number;
  /** True when the app recorded a dismissal on `window.__toolbarDismissals`. */
  dismissed: boolean;
};

/**
 * How many floating menus are on screen, counted in the parent document (where
 * all of them render — the selection toolbar and the highlight menu both live in
 * the app document, not in the chapter frame).
 */
export async function floatingMenuCount(page: Page): Promise<number> {
  return page.locator(FLOATING_MENU_SELECTOR).count();
}

/**
 * Reads and clears `window.__toolbarDismissals`, the ring
 * `ReaderWorkspace.dismissToolbar` pushes its reason onto.
 *
 * `readerTrace.installTrace` replaces that array with a forwarding ring, so this
 * reads whichever copy is currently installed. The clear is what makes a
 * dismissal *attributable to this reset* rather than to whatever ran before it.
 */
export async function takeDismissals(page: Page): Promise<string[]> {
  return page.evaluate(() => {
    const w = window as unknown as { __toolbarDismissals?: string[] };
    const reasons = [...(w.__toolbarDismissals ?? [])];
    if (w.__toolbarDismissals) w.__toolbarDismissals.length = 0;
    return reasons;
  });
}

/**
 * Closes any open floating menu, collapses any live text selection, and returns
 * what it saw. Safe to call when nothing is open (the common case): it then
 * costs one count and returns immediately.
 *
 * ## Why Escape, and not a click on a neutral point
 *
 * Escape is the reader's **own** dismissal path for both menus —
 * `ReaderWorkspace`'s `keydown` handler closes the highlight menu on it, and the
 * selection toolbar follows through `onselectionclear` (the viewer's Escape path
 * calls `clearIframeSelection()`, which posts the clear that dismisses it). That
 * is the gesture the specs in this suite already use
 * (`readerToolbarInteraction`'s "Escape did not close the menu" case,
 * `readerOneMenu`, `readerSelection`).
 *
 * A synthetic click on a neutral point is the *wrong* tool here even though the
 * workspace has a capture-phase `pointerdown` dismissal, for two reasons:
 *
 *   - `elementFromPoint` lands on whatever is topmost, and with a menu open that
 *     is often the menu itself (or the `fixed inset-0` backdrop it renders), so
 *     the press reads as a press INSIDE a floating menu and is deliberately
 *     ignored — the exact guard `floatingMenu.ts` documents.
 *   - A `MouseEvent` dispatched from `page.evaluate` does not produce the
 *     browser's own follow-up `click`, so it leaves the app in a state no real
 *     gesture creates.
 *
 * The frame's selection is collapsed separately and directly, because the parent
 * cannot reach into it otherwise: the chapter is a same-origin `srcdoc` frame, so
 * `iframe.contentDocument.getSelection()` is where a stale selection lives, and a
 * selection left up re-raises the toolbar.
 *
 * The last resort is a press on the document body at a coordinate OUTSIDE any
 * menu, which the workspace's capture-phase guard does treat as "outside". It is
 * bounded and tried once, not in a loop.
 */
export async function dismissFloatingMenus(
  page: Page,
  options: { timeoutMs?: number } = {},
): Promise<DismissResult> {
  const timeoutMs = options.timeoutMs ?? 2_000;
  const deadline = Date.now() + timeoutMs;

  const seen = await floatingMenuCount(page).catch(() => 0);

  // Escape, bounded. One press is what the app expects; the retry window is for
  // a menu that is mid-transition, not for a menu that refuses to close.
  if (seen > 0) {
    await page.keyboard.press('Escape').catch(() => undefined);
    while (Date.now() < deadline) {
      if ((await floatingMenuCount(page).catch(() => 0)) === 0) break;
      await page.waitForTimeout(120);
    }
  }

  // Collapse any live selection, in both documents, unconditionally: a selection
  // with no menu yet is the state that re-raises one on the next gesture.
  await page
    .evaluate(() => {
      window.getSelection()?.removeAllRanges();
      for (const el of Array.from(document.querySelectorAll('iframe'))) {
        try {
          (el as HTMLIFrameElement).contentDocument?.getSelection()?.removeAllRanges();
        } catch {
          // Cross-origin or detached frame: nothing this reset can reach.
        }
      }
    })
    .catch(() => undefined);

  // Last resort, once, bounded: a real press on the body at a point outside
  // every menu box. Only attempted while something is still up.
  if ((await floatingMenuCount(page).catch(() => 0)) > 0) {
    const point = await page
      .evaluate((selector) => {
        const boxes = Array.from(document.querySelectorAll(selector)).map((el) =>
          el.getBoundingClientRect(),
        );
        const inside = (x: number, y: number): boolean =>
          boxes.some((b) => x >= b.left && x <= b.right && y >= b.top && y <= b.bottom);
        // A grid of candidates rather than one guess: the menus are not
        // guaranteed to be anywhere in particular, and every candidate is cheap.
        for (let y = 8; y < window.innerHeight; y += 64) {
          for (let x = 8; x < window.innerWidth; x += 64) {
            if (!inside(x, y)) return { x, y };
          }
        }
        return null;
      }, FLOATING_MENU_SELECTOR)
      .catch(() => null);

    if (point) {
      await page.mouse.click(point.x, point.y).catch(() => undefined);
      const until = Date.now() + timeoutMs;
      while (Date.now() < until) {
        if ((await floatingMenuCount(page).catch(() => 0)) === 0) break;
        await page.waitForTimeout(120);
      }
    }
  }

  const reasons = await takeDismissals(page).catch(() => [] as string[]);
  const remaining = await floatingMenuCount(page).catch(() => 0);

  return { seen, remaining, dismissed: reasons.length > 0 };
}

/**
 * Closes any open floating menu and clears any selection, without waiting for
 * more than {@link dismissFloatingMenus}'s own bounded budget. Never throws.
 */
export async function resetApp(page: Page): Promise<DismissResult> {
  try {
    return await dismissFloatingMenus(page);
  } catch {
    // Best-effort by contract: a reset that cannot run must not fail the spec it
    // was meant to prepare, because the failure would carry the wrong cause.
    return { seen: 0, remaining: -1, dismissed: false };
  }
}

/**
 * The teardown half. Identical to {@link resetApp} today, and kept as its own
 * name because the two are called at different points and a reader should be
 * able to tell which one guards the NEXT run. It never throws: a spec that
 * passed must not be turned red by its own cleanup.
 */
export async function cleanApp(page: Page): Promise<DismissResult> {
  return resetApp(page);
}

/**
 * `test.afterEach`-shaped guard. Registers a teardown that leaves no menu open,
 * so the next spec in the same worker starts clean.
 *
 * `test.afterEach` is not referenced here — this module takes the function in,
 * which keeps it a plain helper with no dependency on how the spec imports
 * `test`. Call it from the spec's top level:
 *
 * ```ts
 * import { test } from './harness/connectApp';
 * import { registerCleanup } from './harness/appReset';
 * registerCleanup(test);
 * ```
 */
export function registerCleanup(testApi: {
  afterEach: (fn: (fixtures: { appPage: Page }) => Promise<void>) => void;
}): void {
  testApi.afterEach(async ({ appPage }) => {
    await cleanApp(appPage);
  });
}
