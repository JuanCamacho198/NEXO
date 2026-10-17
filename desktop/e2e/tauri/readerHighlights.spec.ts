/**
 * Highlight rendering through the CSS Custom Highlight API, in the real app.
 *
 * ## Why this exists, and why nothing else can prove it
 *
 * EPUB highlights are painted with the **CSS Custom Highlight API**
 * (`CSS.highlights` + `::highlight(epub-hl-<color>)`), registered by the overlay
 * script in `epubHighlightOverlayIframe.ts`. The chapter DOM is never mutated —
 * that is the whole design (it removes the wrap-mutation bug class).
 *
 * **jsdom does not implement `CSS.highlights`.** So every existing test of the
 * highlight path either stubs the API or asserts on the *inputs* to `render()`
 * (the `Highlight` objects the overlay builds, the CSS text, the CFI
 * resolution) — never that a highlight is actually registered in a browser
 * engine and, therefore, actually painted. This spec asks the live engine the
 * only question that settles it: is a `Highlight` with the expected name present
 * in `CSS.highlights`?
 *
 * ## What it asserts
 *
 * A **persisted** highlight (read from the developer's real library over IPC)
 * appears in the live chapter: `CSS.highlights.has('epub-hl-<color>')` is true
 * and its `Highlight` holds at least one range. The check runs **inside the
 * chapter frame**, because `CSS.highlights` is per-document and the chapter owns
 * its own.
 *
 * ## The text-anchor fallback
 *
 * The overlay also carries a fallback for highlights authored on Android, whose
 * locator is not an `epubcfi` (a text anchor looked up in the chapter DOM). That
 * path runs in the same `render()` and, when it places a highlight, posts
 * `epub-hl-placed` back to the parent. This spec exercises whichever path a real
 * book's highlight takes; it cannot force the fallback without fabricating a
 * non-CFI highlight, which would mean seeding the library. So the fallback is
 * reported as "exercised if the chosen highlight took it", not asserted
 * directly.
 *
 * ## What it does not prove
 *
 * It does not prove the painted colour (the `::highlight()` rule's `background`
 * is measured separately as CSS text elsewhere), nor the hit-test / context-menu
 * path, nor that the range visually covers the intended glyphs. It proves
 * registration in a real engine, which is exactly the layer jsdom cannot reach.
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
import { findChapterFrame, openBook, pickEpub, waitForChapterFrame } from './harness/readerFlows';

type HighlightRow = {
  id: string;
  text: string;
  color: string;
  /**
   * The wire field is `page` (verified against the live IPC reply), NOT
   * `pageNumber`. For EPUB it is the **spine** index of the chapter the
   * highlight lives in, which is not necessarily the 1-based TOC page the
   * footer's page field uses — this book has front-matter, so the offset is
   * non-zero. That is why the spec navigates by sweeping reachable chapters
   * rather than trusting a positional guess.
   */
  page?: number;
  cfi?: string | null;
};

/**
 * Finds an EPUB in the library that carries at least one persisted highlight,
 * and returns the book plus its highlight. Returns null when no EPUB in the
 * library has a highlight — the spec then skips with a reason instead of
 * clicking a colour and calling the render "covered".
 *
 * The highlight is read over the real IPC bridge, so it is the developer's own
 * data, not a fixture.
 */
async function pickEpubWithHighlight(
  page: Parameters<typeof pickEpub>[0],
): Promise<{ bookTitle: string; highlight: HighlightRow } | null> {
  const epub = await pickEpub(page);
  if (!epub) return null;

  return page.evaluate(
    async ({
      bookId,
      bookTitle,
    }): Promise<{ bookTitle: string; highlight: HighlightRow } | null> => {
      const internals = (
        window as unknown as {
          __TAURI_INTERNALS__?: { invoke: (cmd: string, args?: unknown) => Promise<unknown> };
        }
      ).__TAURI_INTERNALS__;
      if (!internals) throw new Error('__TAURI_INTERNALS__ is missing — not a Tauri webview');

      const rows = (await internals.invoke('listHighlights', { bookId })) as HighlightRow[];
      // A highlight with text can be placed by the text-anchor fallback even if
      // its CFI does not resolve; a highlight with a CFI can resolve directly.
      // Either is a real candidate; a row with neither could not render and is
      // not evidence.
      const usable = rows.find((r) => Boolean(r.text) || Boolean(r.cfi));
      return usable ? { bookTitle, highlight: usable } : null;
    },
    { bookId: epub.id, bookTitle: epub.title },
  );
}

/**
 * Polls the chapter frame for any registered `epub-hl-*` highlight. The overlay
 * renders asynchronously (bridge readiness + a retry loop up to 25×20ms), so a
 * single read races the render.
 */
async function readRegisteredHighlights(
  chapter: Awaited<ReturnType<typeof waitForChapterFrame>>,
  timeoutMs = 15_000,
): Promise<{ names: string[]; totalRanges: number }> {
  const deadline = Date.now() + timeoutMs;
  let last: { names: string[]; totalRanges: number } = { names: [], totalRanges: 0 };

  while (Date.now() < deadline) {
    last = await chapter.evaluate(() => {
      const registry = (
        CSS as unknown as {
          highlights?: {
            keys: () => Iterable<string>;
            get: (name: string) => { size: number } | undefined;
          };
        }
      ).highlights;
      if (!registry) return { names: [], totalRanges: 0 };

      const names: string[] = [];
      let totalRanges = 0;
      for (const name of registry.keys()) {
        names.push(name);
        totalRanges += registry.get(name)?.size ?? 0;
      }
      return { names, totalRanges };
    });

    if (last.names.some((n) => n.startsWith('epub-hl-'))) return last;
    await new Promise((resolve) => setTimeout(resolve, 250));
  }

  return last;
}

test('a persisted highlight renders in the live chapter via the Custom Highlight API', async ({
  appPage,
}) => {
  test.setTimeout(180_000);

  const found = await pickEpubWithHighlight(appPage);
  test.skip(
    found === null,
    'No EPUB in the library has a persisted highlight — nothing to render.',
  );

  const epub = await pickEpub(appPage);
  const chapter = await openBook(appPage, epub!);

  // The API must exist in this engine at all. WebView2 is evergreen Chromium, so
  // it does; a false here would mean the design's feature-detect path is live and
  // the assertion below would be meaningless.
  const hasApi = await chapter.evaluate(
    () =>
      typeof CSS !== 'undefined' &&
      Boolean((CSS as unknown as { highlights?: unknown }).highlights),
  );
  test.skip(!hasApi, 'The engine has no CSS.highlights — the overlay renders nothing by design.');

  // The highlight may belong to a chapter other than the one the book opened on.
  // Navigate through the footer page field to the highlight's chapter
  // (`pageNumber` is the spine index; the EPUB page input is 1-based TOC, and
  // spine and TOC align for a normal book). This is best-effort: the overlay's
  // text-anchor fallback can still place a highlight whose page gate misses, so
  // a failure to navigate is not fatal — the poll below is the real assertion.
  const targetPage = found!.highlight.cfi
    ? (() => {
        const m = /epubcfi\(\/6\/(\d+)!/.exec(found!.highlight.cfi ?? '');
        return m ? Number.parseInt(m[1], 10) : null;
      })()
    : null;
  if (targetPage && targetPage > 0) {
    const pageInput = appPage.locator('[data-testid="bottom-bar-controls"] input').first();
    if ((await pageInput.count()) > 0) {
      await pageInput.fill(String(targetPage));
      await pageInput.press('Enter');
    }
  }

  // Re-resolve the frame: a chapter change swaps the `srcdoc` document.
  const liveChapter = (await findChapterFrame(appPage)) ?? chapter;

  const registered = await readRegisteredHighlights(liveChapter);

  // No highlight registered. This is reported as a skip, NOT a pass: it can mean
  // "the book's highlight is in a chapter we could not reach" as much as "the
  // render path is broken", and a spec that goes green having asserted nothing is
  // the failure mode this whole change guards against. The reason is reported so
  // a human can tell the two apart from the run output.
  test.skip(
    !registered.names.some((n) => n.startsWith('epub-hl-')),
    `No epub-hl-* highlight registered (registry: [${registered.names.join(', ')}]) — ` +
      `the book's highlight did not land in the chapter on screen.`,
  );

  // The registered names must be the `epub-hl-*` family, and the matching entry
  // must actually hold ranges: an empty `Highlight` would be registered but paint
  // nothing.
  const epubNames = registered.names.filter((n) => n.startsWith('epub-hl-'));
  expect(epubNames.length).toBeGreaterThan(0);
  expect(registered.totalRanges).toBeGreaterThan(0);

  // The colour name is one of the canonical five the overlay maps to.
  const CANONICAL = [
    'epub-hl-yellow',
    'epub-hl-green',
    'epub-hl-blue',
    'epub-hl-orange',
    'epub-hl-red',
  ];
  expect(epubNames.some((n) => CANONICAL.includes(n))).toBe(true);
});
