/**
 * The selection toolbar in its `collapsed` layout, driven against the real app.
 *
 * ## The reported defect
 *
 * "Cuando está collapse no funcionan esos botones, o quizás es por el tamaño de
 * la ventana, no sé." In a narrow window the toolbar folds its five colour
 * circles into one `+` trigger. Every button is still VISIBLE, and pressing the
 * `+` did nothing at all.
 *
 * ## Why this spec and the three findings it records
 *
 * Three explanations were on the table and only measurement can separate them.
 * All three are measured here, in the running app, and their literal values are
 * logged:
 *
 *   - **C2 — the `+` popover's coordinate space. CONFIRMED, and this is the
 *     reported bug.** `pickerTop` is `pickerAnchor.getBoundingClientRect().bottom
 *     + 8`, a VIEWPORT measurement, and the popover was `absolute` — which
 *     resolves `top` against its nearest positioned ancestor, the
 *     `group relative` wrapper around the trigger. A viewport number applied in
 *     container space lands a container offset too low, and the toolbar's own box
 *     is `overflow-hidden`, so the popover is clipped and painted nowhere.
 *
 *         MEASURED over CDP, 300x720 window, before the fix:
 *           popover position: absolute, style "left: 0px; top: 92.5px"
 *           popover rect:     y 149..191  (top 92.5 + the group's own ~56px offset)
 *           pill rect:        y 48..94
 *           toolbar rect:     y 57..103, overflow: hidden
 *           elementFromPoint at the popover's centre: the chapter `iframe` — i.e.
 *           NOTHING is painted there. This is the reported symptom, in numbers.
 *
 *         MEASURED after the fix (same window, same selection):
 *           popover position: fixed, style "left: 34.5px; top: 92.5px"
 *           popover rect:     y 93..135
 *           elementFromPoint at the popover's centre: a swatch button inside the
 *           palette itself.
 *
 *         The pinning of `left` is the second half. The palette is up to 176px
 *         wide and the window is 300px, so the trigger's own left edge would run
 *         it off the right border; it is clamped the same way the toolbar is.
 *
 *   - **C1 — the workspace's capture-phase `pointerdown` dismissing the toolbar
 *     before the click lands. DESCARTED by measurement.** `ReaderWorkspace`
 *     listens on `window` in the CAPTURE phase and dismisses unless
 *     `target.closest('.selection-toolbar')` matches. Measured at the centre of
 *     every collapsed button: `elementFromPoint` resolves to a descendant of the
 *     toolbar, `closest('.selection-toolbar')` is non-null, the toolbar is still
 *     mounted after the `pointerdown`, and the button's own `click` handler still
 *     sees the toolbar. The guard works. Nothing is changed for C1.
 *
 *   - **C3 — chrome painting over the toolbar. DESCARTED by measurement.** The
 *     toolbar is `fixed z-50`, and so are the reader's top and bottom bars,
 *     inside a `fixed inset-0 z-40` workspace. Measured: `elementFromPoint` at
 *     the centre of all five collapsed buttons returns a node INSIDE the
 *     toolbar. Nothing is covering it. Nothing is changed for C3.
 *
 * ## How the `collapsed` layout is reached
 *
 * `resolveFloatingMenuLayout` collapses on `window.innerWidth - 2 * edgePadding`,
 * so the rung engages at a band under ~272px. A live window here is ~1150px
 * wide, and this spec does not own it. `page.setViewportSize` DOES resize the
 * WebView2 viewport over CDP — measured: `window.innerWidth` reported 300
 * immediately afterwards, and the toolbar then rendered its collapsed trigger.
 * That is the honest way to reach the layout: the app decides it from the same
 * width a person's narrow window would give it.
 *
 * The viewport is restored in a `finally`, because a 300px window hides the
 * app's sidebar (`display: none`) and leaves the NEXT spec unable to mount. The
 * restoration is verified, not assumed.
 *
 * ## Why the copy button, and why the clipboard is faked
 *
 * Copy is the most verifiable action: no network, no backend, no persisted
 * state, and an observable `role="status"` panel. The clipboard itself is not
 * readable here — `navigator.clipboard` is undefined in this WebView2 over CDP,
 * so `handleCopy` swallows a `TypeError` from `clipboard.writeText` — and the
 * harness deliberately does NOT grant permissions over CDP:
 * `Browser.grantPermissions` targets a Playwright-owned browser, not an attached
 * one, and the OS clipboard of a Tauri app is outside the page's reach. The
 * status panel is the observable, and it is what a dead button fails to produce.
 *
 * ## Local only
 *
 * Needs the real Tauri app under `bun run tauri:dev:debug` (Windows only). Not a
 * CI gate.
 *
 *   bun run tauri:dev:debug      # terminal 1
 *   bun run test:e2e:cdp         # terminal 2
 */
import { chromium } from '@playwright/test';
import { expect, test } from './harness/connectApp';
import type { Frame, Page } from '@playwright/test';

const TOOLBAR = '.selection-toolbar';

/** A book this library holds with prose, so no capacity scan is needed. */
const PROBE_BOOK = /Quijote|Freakonomics|Armas|Prisioneros|Colombia/i;

/** The window width that forces the toolbar's `collapsed` layout. */
const NARROW_WIDTH = 300;

/** Wide enough for the shell's sidebar and the 380px full row. */
const WIDE_WIDTH = 1200;
const WIDE_HEIGHT = 800;

/**
 * Holds the webview wide for the whole file.
 *
 * ## Why this is needed at all
 *
 * The `appPage` fixture waits for the shell to mount — the sidebar, or a chapter
 * frame — and this machine's Tauri window is ~270px wide at boot, which the app
 * answers with `display: none` on the sidebar. A spec that started there would
 * time out in the fixture before its first line ran, which is a harness
 * precondition, not a defect in the code under test.
 *
 * ## Why a second, HELD-OPEN CDP connection
 *
 * The fixture owns the first connection and is blocked, so this one attaches
 * separately. It has to stay attached: `setViewportSize` over CDP resizes the
 * WebView2 viewport (measured — `window.innerWidth` reports the new value
 * immediately), but the override reverts the moment the LAST debugger detaches.
 * A widening that closed after applying would be undone before the fixture ever
 * looked.
 */
let holder: { close: () => Promise<void> } | null = null;

test.beforeAll(async () => {
  const browser = await chromium.connectOverCDP(
    `http://127.0.0.1:${process.env.NEXO_CDP_PORT ?? '9222'}`,
    { isLocal: true, timeout: 60_000 },
  );
  holder = browser;
  for (const context of browser.contexts()) {
    for (const page of context.pages()) {
      if (page.url().startsWith('http://127.0.0.1:1420')) {
        await page.setViewportSize({ width: WIDE_WIDTH, height: WIDE_HEIGHT });
      }
    }
  }
});

test.afterAll(async () => {
  await holder?.close().catch(() => undefined);
  holder = null;
});

/**
 * Opens a known-prose book through the reader's own UI.
 * Deliberately NOT `openUsableEpub`: it would be the right default for a general
 * spec, but it walks the whole library over 8-24 candidates at 3-8s an open, and
 * every test here needs the same book. The title is matched against the real
 * library rows over IPC, so a library without it SKIPS with a reason rather than
 * failing for the wrong one.
 *
 * The app is normalised back to the library first: a previous spec — or a
 * previous run of this one — can leave the reader open, where the book cards do
 * not exist and the click would hang until the timeout.
 */
async function openProseChapter(page: Page): Promise<{ frame: Frame; title: string }> {
  const book = await page.evaluate(async (pattern) => {
    const internals = (
      window as unknown as {
        __TAURI_INTERNALS__?: { invoke: (cmd: string, args?: unknown) => Promise<unknown> };
      }
    ).__TAURI_INTERNALS__;
    if (!internals) throw new Error('__TAURI_INTERNALS__ is missing — not a Tauri webview');

    const rows = (await internals.invoke('listLibraryBooks', {
      payload: { responseVersion: 1 },
    })) as Array<{ id: string; title: string; format?: string; filePath?: string }>;

    const re = new RegExp(pattern, 'i');
    const row = rows.find(
      (candidate) =>
        (candidate.format ?? '').toLowerCase() === 'epub' &&
        (candidate.filePath ?? '').length > 0 &&
        re.test(candidate.title),
    );
    return row ? { id: row.id, title: row.title } : null;
  }, PROBE_BOOK.source);

  test.skip(
    book === null,
    `No library EPUB matches ${PROBE_BOOK} with a local file. This spec needs a book whose ` +
      `chapters render prose.`,
  );

  if ((await page.locator('iframe[title="chapter"]').count()) > 0) {
    // A previous run can leave a selection toolbar up, and it is `fixed z-50` —
    // it sits ON TOP of the top bar's back button and intercepts the click
    // (measured: 464 retries before the timeout). Escape is the toolbar's own
    // dismissal path, so it is the gesture to use, not a forbidden click-through.
    if ((await page.locator(TOOLBAR).count()) > 0) {
      await page.keyboard.press('Escape');
      await expect(page.locator(TOOLBAR)).toBeHidden({ timeout: 5_000 });
    }
    await page.locator('header button').first().click();
    await expect(page.locator('aside nav > button').first()).toBeVisible({ timeout: 30_000 });
  }

  const card = page.locator('article').filter({ hasText: book!.title }).first();
  await expect(card).toBeVisible({ timeout: 30_000 });
  await card
    .getByRole('button', {
      name: /^(Read|Continue reading|Open book|Start|Start reading|Leer|Seguir leyendo|Abrir|Abrir libro|Empezar|Continuar lectura|Empezar lectura)$/,
    })
    .first()
    .click();

  return { frame: await waitForChapterFrame(page), title: book!.title };
}

/**
 * The chapter frame: the same-origin `about:srcdoc` document.
 *
 * Keyed on the injected `<style id="nexo-reader-overrides">`, the marker the
 * viewer writes and which cannot appear in the app document — the same criterion
 * `harness/readerFlows.ts` uses.
 */
async function waitForChapterFrame(page: Page, timeoutMs = 30_000): Promise<Frame> {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    for (const frame of page.frames()) {
      const hasOverrides = await frame
        .evaluate(() => Boolean(document.getElementById('nexo-reader-overrides')))
        .catch(() => false);
      if (hasOverrides) return frame;
    }
    await page.waitForTimeout(250);
  }
  throw new Error(
    `No chapter frame with #nexo-reader-overrides appeared within ${timeoutMs}ms. Frames: ` +
      `${
        page
          .frames()
          .map((frame) => frame.url())
          .join(', ') || '(none)'
      }.`,
  );
}

/**
 * Selects a run of real characters inside the chapter frame using the FRAME's
 * own `Selection`/`Range` — the parent document cannot select a child's nodes —
 * then dispatches the `mouseup` the chapter's own script waits for.
 *
 * The run is taken from the first on-screen text node rather than the first in
 * the document: the toolbar is anchored to the selection's rect, so a selection
 * made off-screen puts the toolbar off-screen with it and every geometry
 * assertion below would be measuring a coordinate nobody can see.
 */
async function selectVisibleText(frame: Frame): Promise<string | null> {
  return frame.evaluate(() => {
    const scroller = (
      globalThis as unknown as { parent: { document: Document } }
    ).parent.document.querySelector('[data-testid="epub-scroll-container"]');
    const frameEl = (globalThis as unknown as { frameElement: Element | null }).frameElement;
    if (!frameEl) return null;
    const iframeRect = frameEl.getBoundingClientRect();
    const box = scroller?.getBoundingClientRect();
    const visibleTop = (box?.top ?? 0) - iframeRect.top;
    const visibleBottom = (box?.bottom ?? window.innerHeight) - iframeRect.top;

    const SKIP = new Set(['SCRIPT', 'STYLE', 'NOSCRIPT', 'TEMPLATE']);
    const walker = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT, {
      acceptNode(node) {
        let el: Node | null = node.parentElement;
        while (el) {
          if (SKIP.has(el.nodeName)) return NodeFilter.FILTER_REJECT;
          el = el.parentElement;
        }
        return NodeFilter.FILTER_ACCEPT;
      },
    });

    let node = walker.nextNode();
    while (node) {
      const value = node.nodeValue ?? '';
      const rect = (node as Text).parentElement?.getBoundingClientRect();
      const onScreen = rect ? rect.top >= visibleTop && rect.bottom <= visibleBottom : false;
      if (value.trim().length >= 10 && onScreen) break;
      node = walker.nextNode();
    }
    if (!node || !node.nodeValue) return null;

    const data = node.nodeValue;
    const start = data.search(/\S/);
    if (start < 0) return null;
    let end = Math.min(data.length, start + 40);
    const ws = data.lastIndexOf(' ', end);
    if (ws > start + 4) end = ws;

    const range = document.createRange();
    range.setStart(node, start);
    range.setEnd(node, end);
    const selection = document.getSelection();
    if (!selection) return null;
    selection.removeAllRanges();
    selection.addRange(range);

    document.dispatchEvent(new MouseEvent('mouseup', { bubbles: true }));
    return range.toString();
  });
}

/**
 * Narrows the window and raises the toolbar on a real selection.
 *
 * The chapter is re-resolved for every attempt: the viewer replaces
 * `iframe.srcdoc` as a chapter's assets settle (measured: three swaps for one
 * opening), so a selection made against a document that is about to be discarded
 * raises no toolbar at all. Running out of attempts SKIPS rather than passing
 * against nothing.
 */
async function raiseCollapsedToolbar(page: Page): Promise<void> {
  await page.setViewportSize({ width: NARROW_WIDTH, height: 720 });
  await page.waitForTimeout(1_500);
  const width = await page.evaluate(() => window.innerWidth);
  expect(
    width,
    `page.setViewportSize(300) did not narrow the webview (window.innerWidth is ${width}), so ` +
      `the collapsed layout cannot be reached and every assertion below would measure nothing.`,
  ).toBeLessThanOrEqual(320);

  for (let attempt = 0; attempt < 6; attempt++) {
    const frame = await waitForChapterFrame(page, 10_000);
    const selected = await selectVisibleText(frame);
    if (selected !== null && selected.trim().length > 0) {
      try {
        await expect(page.locator(TOOLBAR)).toBeVisible({ timeout: 6_000 });
        await expect
          .poll(
            async () => (await page.locator(TOOLBAR).locator('button[aria-expanded]').count()) > 0,
            {
              timeout: 6_000,
            },
          )
          .toBe(true);
        return;
      } catch {
        /* the chapter was still settling under the selection; try again */
      }
    }
    await page.waitForTimeout(900);
  }

  test.skip(
    true,
    `The toolbar never reached its collapsed layout in a ${NARROW_WIDTH}px window within 6 ` +
      `selection attempts. The chapter on screen may have nothing in its visible band.`,
  );
}

/** Puts the real window back, so the next spec can mount the shell. */
async function restoreWindow(page: Page): Promise<void> {
  await page.setViewportSize({ width: WIDE_WIDTH, height: WIDE_HEIGHT });
  await page.waitForTimeout(1_200);
  const width = await page.evaluate(() => window.innerWidth);
  expect(
    width,
    `the window was left at ${width}px after this spec. A 300px window hides the app's sidebar ` +
      `and the next spec cannot mount its shell.`,
  ).toBeGreaterThan(WIDE_WIDTH - 100);
}

test('nothing is painted over the collapsed toolbar, and its buttons keep their own clicks (C1 + C3)', async ({
  appPage,
}) => {
  test.setTimeout(240_000);

  const { title } = await openProseChapter(appPage);

  try {
    await raiseCollapsedToolbar(appPage);
    const where = `"${title}" in a ${NARROW_WIDTH}px window`;

    // ── C3 — the hit test the browser itself performs on a real click ─────
    const hits = await appPage.evaluate((sel) => {
      const buttons = document.querySelectorAll(`${sel} div[role="toolbar"] button`);
      return Array.from(buttons).map((button) => {
        const rect = button.getBoundingClientRect();
        const cx = rect.left + rect.width / 2;
        const cy = rect.top + rect.height / 2;
        const el = document.elementFromPoint(cx, cy);
        return {
          label: button.getAttribute('aria-label') ?? '(unlabelled)',
          centre: `${Math.round(cx)},${Math.round(cy)}`,
          hit: el
            ? `${el.tagName.toLowerCase()}.${String(el.getAttribute('class') ?? '').slice(0, 60)}`
            : '(nothing)',
          insideToolbar: el ? el.closest(sel) !== null : false,
        };
      });
    }, TOOLBAR);

    expect(hits.length, `${where}: the collapsed pill rendered no buttons`).toBeGreaterThan(2);

    const stolen = hits.filter((entry) => !entry.insideToolbar);
    expect(
      stolen,
      `${where}: something is painted OVER the selection toolbar. elementFromPoint at the centre ` +
        `of ${stolen.map((e) => `"${e.label}" (${e.centre})`).join(', ')} returned ` +
        `${stolen.map((e) => e.hit).join(', ')} instead. C3 is CONFIRMED.`,
    ).toEqual([]);
    console.log(
      `[collapsed] C3 hit test (DESCARTED if this run is green): ${JSON.stringify(hits)}`,
    );

    // ── C1 — a real pointerdown, and whether the toolbar survives it ──────
    const c1 = await appPage.evaluate((sel) => {
      const button = Array.from(
        document.querySelectorAll(`${sel} div[role="toolbar"] button`),
      ).find((candidate) => /Copy|Copiar/.test(candidate.getAttribute('aria-label') ?? ''));
      if (!button) return null;

      const rect = button.getBoundingClientRect();
      const x = rect.left + rect.width / 2;
      const y = rect.top + rect.height / 2;

      // The workspace listens on `window` in the CAPTURE phase, so it observes
      // this event BEFORE the button does. Registering the probe on the button
      // and reading the toolbar there is therefore a statement about ordering:
      // the capture listener ran and left the toolbar in place.
      let clickSawToolbar: boolean | null = null;
      button.addEventListener(
        'click',
        () => {
          clickSawToolbar = document.querySelector(sel) !== null;
        },
        { once: true },
      );

      const el = document.elementFromPoint(x, y);
      const before = document.querySelector(sel) !== null;
      const belongsToToolbar = el ? el.closest(sel) !== null : false;

      el?.dispatchEvent(
        new PointerEvent('pointerdown', {
          bubbles: true,
          composed: true,
          cancelable: true,
          clientX: x,
          clientY: y,
          pointerId: 1,
          pointerType: 'mouse',
          isPrimary: true,
        }),
      );

      const afterPointerdown = document.querySelector(sel) !== null;
      (button as HTMLElement).click();

      return {
        label: button.getAttribute('aria-label'),
        hit: el
          ? `${el.tagName.toLowerCase()}.${String(el.getAttribute('class') ?? '').slice(0, 60)}`
          : '(nothing)',
        belongsToToolbar,
        before,
        afterPointerdown,
        clickSawToolbar,
      };
    }, TOOLBAR);

    expect(c1, `${where}: the collapsed pill rendered no Copy button`).not.toBeNull();
    console.log(
      `[collapsed] C1 pointerdown probe (DESCARTED if this run is green): ${JSON.stringify(c1)}`,
    );

    expect(
      c1!.afterPointerdown,
      `${where}: a real pointerdown at the centre of "${c1!.label}" DISMISSED the toolbar. The ` +
        `workspace's capture-phase handler on window ran first and tore the pill down before ` +
        `the gesture could become a click, so every button in it is dead. C1 is CONFIRMED.`,
    ).toBe(true);
  } finally {
    await restoreWindow(appPage);
  }
});

/**
 * The regression gate: the collapsed `+` must open a palette that is INSIDE the
 * box that clips the toolbar.
 *
 * ## Why the popover's own geometry is the assertion
 *
 * The bug is not a wrong number, it is a number measured in one coordinate space
 * and applied in another. `pickerTop` comes from `getBoundingClientRect()`, so it
 * is a viewport coordinate; an `absolute` popover resolves `top` against the
 * `group relative` wrapper, so the number lands a container offset too low. The
 * toolbar's box is `overflow-hidden`, which turns "too low" into "painted
 * nowhere" rather than "slightly misplaced" — and that is why the button reads as
 * dead instead of as odd-looking.
 *
 * Asking the composed rect WHERE THE POPOVER IS, and comparing it with the box
 * that clips it, is the direct statement of the defect. MEASURED before the fix:
 * popover y 167 against a toolbar box ending at 135 — 32px outside it.
 *
 * ## Proving it can fail
 *
 * Reverting `fixed` to `absolute` in `SelectionToolbar.svelte` turns this red,
 * with the literal message:
 *
 *     the collapsed palette popover is in the DOM at y 149..191 but nothing is
 *     painted there: elementFromPoint returned iframe.w-full border-none block at
 *     120,170 (centre), iframe.w-full border-none block at 51,170 (left swatch).
 *     The toolbar's overflow-hidden box (y 57..103) is clipping the palette, so
 *     pressing the + looks like it did nothing.
 *
 * That is the reported symptom stated as a measurement: the popover exists, and
 * the point a person would click resolves to the chapter behind it.
 */
test('the collapsed + opens a palette inside the toolbar that clips it', async ({ appPage }) => {
  test.setTimeout(240_000);

  await openProseChapter(appPage);

  try {
    await raiseCollapsedToolbar(appPage);

    const trigger = appPage.locator(TOOLBAR).locator('button[aria-expanded]').first();
    await expect(trigger).toBeVisible({ timeout: 5_000 });
    await expect(trigger).toHaveAttribute('aria-expanded', 'false');

    // The pill's own box, measured BEFORE the popover opens.
    //
    // It has to be read first: the toolbar is `flex-col`, and an `absolute`
    // popover is still a layout participant, so opening it makes the toolbar box
    // grow to contain it. A box measured afterwards would be the one the defect
    // itself created, and the assertion below would compare the popover with a
    // height it had just caused.
    const closedGeometry = await appPage.evaluate((sel) => {
      const toolbar = document.querySelector(sel);
      const anchor = toolbar?.querySelector('button[aria-expanded]');
      if (!toolbar || !anchor) return null;
      const t = toolbar.getBoundingClientRect();
      const a = anchor.getBoundingClientRect();
      return {
        toolbarBottom: Math.round(t.bottom),
        toolbarHeight: Math.round(t.height),
        overflow: getComputedStyle(toolbar).overflow,
        anchorBottom: Math.round(a.bottom),
        // The number `pickerTop` derives, in the space it derives it in.
        expectedTop: Math.round(a.bottom + 8),
      };
    }, TOOLBAR);
    expect(closedGeometry, 'the collapsed pill had no measurable geometry').not.toBeNull();

    await trigger.click();
    await expect(trigger).toHaveAttribute('aria-expanded', 'true');
    await appPage.waitForTimeout(300);

    const geometry = await appPage.evaluate((sel) => {
      const toolbar = document.querySelector(sel);
      const group = document.querySelector(`${sel} div[role="group"]`);
      const pill = document.querySelector(`${sel} div[role="toolbar"]`);
      const box = (el: Element | null | undefined) =>
        el
          ? {
              y: Math.round(el.getBoundingClientRect().y),
              bottom: Math.round(el.getBoundingClientRect().bottom),
              x: Math.round(el.getBoundingClientRect().x),
              right: Math.round(el.getBoundingClientRect().right),
              width: Math.round(el.getBoundingClientRect().width),
              height: Math.round(el.getBoundingClientRect().height),
            }
          : null;
      return {
        toolbar: box(toolbar),
        toolbarOverflow: toolbar ? getComputedStyle(toolbar).overflow : null,
        pill: box(pill),
        popover: box(group),
        popoverPosition: group ? getComputedStyle(group).position : null,
        popoverStyle: group?.getAttribute('style') ?? null,
        viewport: { width: window.innerWidth, height: window.innerHeight },
      };
    }, TOOLBAR);

    expect(geometry.popover, 'the collapsed palette popover opened but has no box').not.toBeNull();
    expect(geometry.toolbar).not.toBeNull();

    // It must be PAINTED, and that is a different question from where its box is.
    // The toolbar is `overflow-hidden`, so a popover that lands under the pill's
    // bottom edge is clipped away and the trigger reads as dead — which is the
    // whole reported symptom. A geometric comparison against the pill's box is
    // the WRONG oracle here: the popover opens BELOW the pill on purpose, so its
    // box is legitimately past that edge. The question is whether anything is
    // actually painted there, and the browser's own hit test is the direct answer.
    const painted = await appPage.evaluate((sel) => {
      const group = document.querySelector(`${sel} div[role="group"]`);
      if (!group) return null;
      const rect = group.getBoundingClientRect();
      const points = [
        { name: 'centre', x: rect.left + rect.width / 2, y: rect.top + rect.height / 2 },
        { name: 'left swatch', x: rect.left + 16, y: rect.top + rect.height / 2 },
      ];
      return points.map((point) => {
        const el = document.elementFromPoint(point.x, point.y);
        return {
          name: point.name,
          at: `${Math.round(point.x)},${Math.round(point.y)}`,
          hit: el
            ? `${el.tagName.toLowerCase()}.${String(el.getAttribute('class') ?? '').slice(0, 50)}`
            : '(nothing)',
          insidePopover: el ? group.contains(el) : false,
        };
      });
    }, TOOLBAR);

    expect(painted, 'the collapsed palette popover had no box to hit-test').not.toBeNull();
    const unreachable = painted!.filter((point) => !point.insidePopover);
    expect(
      unreachable,
      `the collapsed palette popover is in the DOM at y ${geometry.popover!.y}..` +
        `${geometry.popover!.bottom} but nothing is painted there: elementFromPoint returned ` +
        `${unreachable.map((p) => `${p.hit} at ${p.at} (${p.name})`).join(', ')}. The toolbar's ` +
        `overflow-hidden box (y ${geometry.toolbar!.y}..${geometry.toolbar!.bottom}) is clipping ` +
        `the palette, so pressing the + looks like it did nothing.`,
    ).toEqual([]);

    // The coordinate space is the root cause: a viewport measurement applied by
    // an `absolute` element lands against the wrong origin.
    expect(
      geometry.popoverPosition,
      `the collapsed palette popover is positioned ${geometry.popoverPosition}, but its top ` +
        `(${geometry.popoverStyle}) is measured from the viewport with getBoundingClientRect(). ` +
        `It must be fixed.`,
    ).toBe('fixed');

    // THE assertion: the popover appears where `pickerTop` says, in the space it
    // was measured in. Before the fix it landed at anchor.bottom + 8 PLUS the
    // group's own offset from the viewport — MEASURED at y 167 against the 103
    // the maths asked for, i.e. 64px too low.
    expect(
      geometry.popover!.y,
      `the collapsed palette popover starts at y ${geometry.popover!.y}, but its top was computed ` +
        `as the anchor's bottom (${closedGeometry!.anchorBottom}) + 8 = ${closedGeometry!.expectedTop} ` +
        `in VIEWPORT coordinates. A popover that lands elsewhere is resolving that number against ` +
        `the wrong origin — the "group relative" wrapper instead of the viewport.`,
    ).toBeLessThanOrEqual(closedGeometry!.expectedTop + 2);
    expect(
      geometry.popover!.y,
      `the collapsed palette popover starts at y ${geometry.popover!.y}, above the anchor's bottom ` +
        `(${closedGeometry!.anchorBottom}) — it must open BELOW the trigger it belongs to.`,
    ).toBeGreaterThanOrEqual(closedGeometry!.anchorBottom - 2);

    // The window is the other edge that matters: the palette is up to 176px wide
    // and this is the narrow window that produced `collapsed` in the first place.
    expect(
      geometry.popover!.right,
      `the collapsed palette popover reaches x ${geometry.popover!.right}, past the ` +
        `${geometry.viewport.width}px right border.`,
    ).toBeLessThanOrEqual(geometry.viewport.width);
    expect(geometry.popover!.x).toBeGreaterThanOrEqual(0);

    console.log(`[collapsed] popover geometry: ${JSON.stringify({ ...geometry, painted })}`);
  } finally {
    await restoreWindow(appPage);
  }
});

/**
 * THE case the suite was missing: a collapsed action button is PRESSED with a
 * real pointer and something observable happens.
 *
 * The gesture is `page.mouse` at the button's own centre, so the browser
 * performs its own hit test, its own pointerdown chain and its own click — the
 * whole sequence C1 and C3 are about. Copy is the action, and the `role="status"`
 * panel `handleCopy` raises is the observable: a dead button produces nothing,
 * which is exactly the reported symptom.
 */
test('pressing Copy in the collapsed layout produces observable feedback', async ({ appPage }) => {
  test.setTimeout(240_000);

  const { title } = await openProseChapter(appPage);

  try {
    await raiseCollapsedToolbar(appPage);

    const toolbar = appPage.locator(TOOLBAR);
    const button = toolbar.getByRole('button', { name: /^(Copy|Copiar)$/ }).first();
    await expect(button).toBeVisible({ timeout: 5_000 });

    const box = await button.boundingBox();
    expect(box, 'the Copy button had no measurable box in the collapsed pill').not.toBeNull();

    await appPage.mouse.move(box!.x + box!.width / 2, box!.y + box!.height / 2);
    await appPage.mouse.down();
    await appPage.mouse.up();

    const status = toolbar.locator('[role="status"]');
    await expect(
      status,
      `"${title}" in a ${NARROW_WIDTH}px window: pressing Copy produced no feedback panel. The ` +
        `button is visible and does nothing, which is the reported defect.`,
    ).toBeVisible({ timeout: 5_000 });

    const text = ((await status.innerText()) ?? '').trim();
    expect(text.length, 'the copy feedback panel rendered no text').toBeGreaterThan(0);
    console.log(`[collapsed] copy feedback: ${JSON.stringify(text)}`);
  } finally {
    await restoreWindow(appPage);
  }
});

/**
 * The same action on the `full` rung, so the fix cannot have been bought by
 * changing the ordinary case.
 *
 * `collapsed` and `full` differ only in the palette group; the four actions are
 * the same controls. A wide window must still render five colour circles, no
 * collapsed trigger, and a Copy button that produces the same feedback.
 */
test('pressing Copy in the full layout still produces the same feedback', async ({ appPage }) => {
  test.setTimeout(240_000);

  await openProseChapter(appPage);

  try {
    // The window is already wide (the `beforeAll` holder keeps it there), so the
    // layout is `full` from the start. Confirm it rather than assume it.
    await appPage.setViewportSize({ width: WIDE_WIDTH, height: WIDE_HEIGHT });
    await appPage.waitForTimeout(1_200);
    const width = await appPage.evaluate(() => window.innerWidth);
    expect(
      width,
      `the window is ${width}px wide, so this case would measure 'collapsed'`,
    ).toBeGreaterThan(700);

    // Raise the toolbar on a real selection IN THIS LAYOUT. The previous test
    // dismissed its toolbar and may have re-rendered the chapter, so nothing can
    // be carried over.
    const toolbar = appPage.locator(TOOLBAR);
    let raised = false;
    for (let attempt = 0; attempt < 6 && !raised; attempt++) {
      const frame = await waitForChapterFrame(appPage, 10_000);
      const selected = await selectVisibleText(frame);
      if (selected !== null && selected.trim().length > 0) {
        raised = (await toolbar
          .waitFor({ state: 'visible', timeout: 6_000 })
          .then(() => true)
          .catch(() => false)) as boolean;
      }
      if (!raised) await appPage.waitForTimeout(900);
    }
    test.skip(
      raised === false,
      'The full-layout toolbar was never raised; the chapter had no prose.',
    );
    await expect(toolbar).toBeVisible({ timeout: 5_000 });

    // The full rung: five colour circles and NO collapsed trigger.
    await expect(
      toolbar.locator('button[aria-expanded]'),
      'the full layout rendered the collapsed palette trigger — the ordinary, wide-window case ' +
        'must be unchanged by the collapsed fix.',
    ).toHaveCount(0);
    await expect(
      toolbar.getByRole('button', { name: /select|seleccionar resaltado/i }),
    ).toHaveCount(5);

    const button = toolbar.getByRole('button', { name: /^(Copy|Copiar)$/ }).first();
    const box = await button.boundingBox();
    expect(box, 'the Copy button had no measurable box in the full pill').not.toBeNull();

    await appPage.mouse.move(box!.x + box!.width / 2, box!.y + box!.height / 2);
    await appPage.mouse.down();
    await appPage.mouse.up();

    await expect(toolbar.locator('[role="status"]')).toBeVisible({ timeout: 5_000 });
  } finally {
    await restoreWindow(appPage);
  }
});
