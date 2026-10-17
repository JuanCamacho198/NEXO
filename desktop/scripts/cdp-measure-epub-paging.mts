/**
 * Measure EPUB paging cost against the live app — the before/after instrument
 * for `epub-paging-performance`.
 *
 * ## Why a script and not a CDP spec
 *
 * A spec in `e2e/tauri/**` runs inside `test:e2e:cdp`, which is a gate. This
 * measurement is **wall-clock timing on a live window**: the numbers depend on
 * the machine, the book and what else is running, so an assertion built on them
 * would be a timer-flake, not a guarantee. The committed instrument therefore
 * prints the table the baseline in the feature doc was recorded with, the same
 * way `scripts/cdp-measure-readium.mts` prints what `reader.spec.ts` asserts. A
 * human (or the next agent) runs it before and after a change and compares; it
 * never turns a stopwatch into a green check.
 *
 * ## What it measures
 *
 * The baseline in `odd/tasks/epub-paging-performance.md`:
 *
 * ```
 * step  0..9 : 380, 384, 386, 396, 497, 589 ms   page 1 → 1     (ten presses)
 * step 10    : 589 ms                            page 1 → 11    (one press, ten chapters)
 * step 11    : 388 ms                            page 11 → 11
 * ```
 *
 * Twelve Next presses, ~350 ms apart, recording the wall time each press takes
 * to settle and the page field before and after it. The three things to watch:
 *
 *   1. **Cost must be flat, not climbing.** Rising milliseconds across a burst
 *      mean the presses are queueing backend work instead of coalescing it.
 *   2. **One press, one chapter.** A press that moves zero chapters and a later
 *      press that moves ten are the same defect seen twice.
 *   3. **The page field tracks the destination.** `N → N` during a burst means
 *      the field lags the reader.
 *
 * ## Running it
 *
 * Needs the real app with the debug port open (Windows only). It opens a real
 * EPUB from the developer's library itself, resets to chapter 1, then bursts —
 * so one command produces a comparable run:
 *
 *   bun run tauri:dev:debug                       # terminal 1
 *   bun run scripts/cdp-measure-epub-paging.mts   # terminal 2
 *
 * `NEXO_CDP_PORT` overrides the endpoint, matching `tauri:dev:debug`.
 */
import { chromium } from '@playwright/test';
import { openBook, pickEpub } from '../e2e/tauri/harness/readerFlows';

/** Presses in the burst — the baseline used twelve. */
const PRESSES = 12;
/** Space between presses, ms — the baseline used ~350. */
const INTERVAL_MS = 350;
/** How long a press is given to commit before the after-reading, ms. */
const SETTLE_MS = 900;

type Step = {
  step: number;
  ms: number;
  pageBefore: number;
  pageAfter: number;
};

const browser = await chromium.connectOverCDP(
  `http://127.0.0.1:${process.env.NEXO_CDP_PORT ?? '9222'}`,
  { isLocal: true },
);

try {
  let page = null;
  for (const context of browser.contexts()) {
    for (const candidate of context.pages()) {
      if (candidate.url().startsWith('http://127.0.0.1:1420')) page = candidate;
    }
  }
  if (!page) throw new Error('app page not found — is the app running under tauri:dev:debug?');

  // Open a real EPUB so the probe is one command from a cold app. The library
  // row comes from the real Rust `listLibraryBooks`; the same helper the CDP
  // specs use, so the entry path is the one they exercise.
  const epub = await pickEpub(page);
  if (!epub) throw new Error('no EPUB in the library — cannot measure paging');
  await openBook(page, epub);

  /** Reads the page field in the bottom bar, and the Next button's enabled state. */
  const readPage = async (): Promise<number> =>
    page.evaluate(() => {
      const controls = document.querySelector('[data-testid="bottom-bar-controls"]');
      const input = controls?.querySelector('input') as HTMLInputElement | null;
      return input ? Number.parseInt(input.value, 10) || 0 : 0;
    });

  const nextButton = page.locator('[data-testid="bottom-bar-controls"] button').nth(1);
  await nextButton.waitFor({ state: 'visible', timeout: 10_000 });

  // Reset to chapter 1 so every run starts from the same place — the baseline
  // was recorded from page 1, and a burst that starts mid-book is not
  // comparable to one that starts at the front. Typing 1 into the page field
  // and committing it drives the same `handleGoToPage` path the user does.
  const pageInput = page.locator('[data-testid="bottom-bar-controls"] input').first();
  await pageInput.fill('1');
  // `onchange` on a text input fires on blur, so commit by leaving the field.
  await pageInput.blur();
  await page.waitForTimeout(800);

  const steps: Step[] = [];
  for (let step = 0; step < PRESSES; step++) {
    const pageBefore = await readPage();
    const startedAt = Date.now();

    // A real user gesture: keyboard focus in the reader, then ArrowRight. The
    // bottom-bar Next button is the same navigation path, but the reported
    // symptom is the *arrow* one, so the probe presses what the user presses.
    await page.keyboard.press('ArrowRight');

    // Wait until the destination is committed (the page field reaches the
    // chapter this press asked for) or the budget runs out. Measuring to the
    // commit, not to a fixed sleep, is what exposes a backlog: if ten presses
    // queue ten backend reads, each press's commit time climbs.
    const deadline = Date.now() + SETTLE_MS;
    let pageAfter = pageBefore;
    while (Date.now() < deadline) {
      pageAfter = await readPage();
      if (pageAfter > pageBefore) break;
      await page.waitForTimeout(20);
    }
    const ms = Date.now() - startedAt;
    if (pageAfter === pageBefore) pageAfter = await readPage();
    steps.push({ step, ms, pageBefore, pageAfter });

    await page.waitForTimeout(Math.max(0, INTERVAL_MS - ms));
  }

  const format = (value: number): string => String(value).padStart(3, ' ');
  console.log('');
  console.log('— discrete presses (one ArrowRight per step) —');
  console.log('step   ms   page');
  for (const s of steps) {
    console.log(
      `${String(s.step).padStart(4)}  ${format(s.ms)}  ${s.pageBefore} -> ${s.pageAfter}`,
    );
  }

  const positiveSteps = steps.filter((s) => s.pageAfter > s.pageBefore);
  const stalls = steps.filter((s) => s.pageAfter === s.pageBefore);
  const maxJump = positiveSteps.reduce((max, s) => Math.max(max, s.pageAfter - s.pageBefore), 0);
  const msValues = steps.map((s) => s.ms);

  console.log('');
  console.log(
    JSON.stringify(
      {
        presses: PRESSES,
        intervalMs: INTERVAL_MS,
        ms: { min: Math.min(...msValues), max: Math.max(...msValues), series: msValues },
        pages: {
          start: steps[0]?.pageBefore ?? null,
          end: steps.at(-1)?.pageAfter ?? null,
          stalls: stalls.length,
          moves: positiveSteps.length,
          maxJump,
        },
      },
      null,
      2,
    ),
  );

  // ── Held key ────────────────────────────────────────────────────────────
  //
  // The symptom the feature doc names is a *held* arrow. Playwright does NOT
  // simulate OS auto-repeat: every `keyboard.press` is a fresh event with
  // `repeat: false`. So the only way to exercise the repeat filter is to
  // dispatch the events the OS would emit — a `keydown` with `repeat: true` per
  // repeat — onto the window the reader listens on. The filter lives in
  // `resolveReaderArrowIntent`, so a repeat must resolve to nothing.
  //
  // With the filter, a hold advances the reader by one chapter (the initial
  // non-repeat keydown). Without it, it advances by one chapter per repeat.
  await pageInput.fill('1');
  await pageInput.blur();
  await page.waitForTimeout(1_200);
  const holdStart = await readPage();

  const REPEAT_COUNT = 30;
  const holdElapsed = await page.evaluate((count) => {
    const startedAt = performance.now();
    const fire = (repeat: boolean): void => {
      window.dispatchEvent(
        new KeyboardEvent('keydown', {
          key: 'ArrowRight',
          repeat,
          bubbles: true,
          cancelable: true,
        }),
      );
    };
    // The initial press (repeat: false) is the deliberate move.
    fire(false);
    // Then the auto-repeat stream the OS would send while the key is held.
    for (let i = 0; i < count; i++) fire(true);
    return performance.now() - startedAt;
  }, REPEAT_COUNT);

  await page.waitForTimeout(1_500);
  const holdEnd = await readPage();

  console.log('');
  console.log('— held key (synthetic auto-repeat) —');
  console.log(`${REPEAT_COUNT} repeat events in ${holdElapsed.toFixed(1)} ms`);
  console.log(`page ${holdStart} -> ${holdEnd} (moved ${holdEnd - holdStart})`);
  console.log(
    JSON.stringify({
      held: {
        repeatEvents: REPEAT_COUNT,
        start: holdStart,
        end: holdEnd,
        moved: holdEnd - holdStart,
      },
    }),
  );
} finally {
  await browser.close();
}
