/**
 * Selection geometry and gestures inside the chapter frame.
 *
 * ## Why this is its own module
 *
 * Every function here crosses the same awkward boundary: the chapter lives in a
 * same-origin `about:srcdoc` iframe that is sized to the **whole chapter**
 * (measured: 27 382 px for one *Don Quijote* chapter), so the frame's own
 * `innerHeight` is its content height and its rect coordinates are frame
 * -relative. The parent's viewport is something else entirely. Getting that
 * translation wrong is not a detail — it is what made a spec select text that
 * was 3 225 px above the viewport, place the toolbar at the selection's
 * off-screen rect, and then fail to click a swatch with "element is outside of
 * the viewport" (measured, and the reason this module exists).
 *
 * Keeping the translation in one place means one definition of "on screen" for
 * every caller, instead of one per spec.
 *
 * ## What it does not do
 *
 * It selects, it clicks, it reads. It never asserts: "there was nothing to
 * select" is a precondition the caller decides about, at the point where that
 * missing precondition is meaningful.
 */
import type { Frame, Page } from '@playwright/test';

/**
 * The chapter's visible band, in the frame's own coordinates.
 *
 * ## Why this is not the parent's `innerHeight`
 *
 * A run at frame-relative `top: 399` is on screen only while the parent's
 * scroller sits at an offset that brings it there. Comparing that 399 against the
 * parent's `innerHeight` (767) says "on screen" for a run the parent is showing
 * at viewport `-3225`, which is exactly the mismatch measured before this
 * module existed.
 *
 * So the band is derived from real geometry: the scroller's viewport box,
 * translated into frame coordinates through the frame element's own rect. A
 * caller tests a text node's rect against *this*, and "on screen" means what a
 * person would see.
 *
 * Returns `null` when the geometry cannot be read (the frame element is not
 * attached), which callers treat as "cannot establish" rather than "visible".
 */
export async function chapterVisibleBand(
  frame: Frame,
): Promise<{ top: number; bottom: number } | null> {
  return frame
    .evaluate(() => {
      const parentWindow = (globalThis as unknown as { parent: Window }).parent;
      const parentDoc = (globalThis as unknown as { parent: { document: Document } }).parent
        .document;
      const frameEl = (globalThis as unknown as { frameElement: Element | null }).frameElement;
      if (!frameEl) return null;
      const iframeRect = frameEl.getBoundingClientRect();

      // The element that actually scrolls the chapter in scrolling mode. Its own
      // box is the person's viewport, which is shorter than `innerHeight`
      // because the reader renders chrome above and below it.
      const scroller = parentDoc.querySelector('[data-testid="epub-scroll-container"]');
      const box = scroller
        ? scroller.getBoundingClientRect()
        : { top: 0, bottom: parentWindow.innerHeight };

      // A point at frame-relative y is visible when `iframeRect.top + y` falls
      // inside [box.top, box.bottom]; solve for y.
      return { top: box.top - iframeRect.top, bottom: box.bottom - iframeRect.top };
    })
    .catch(() => null);
}

/**
 * Selects a run of real characters inside the chapter frame using the
 * **frame's own** `Selection`/`Range` API — the parent document has a different
 * `document` and cannot select nodes the chapter owns.
 *
 * The range starts at the first non-whitespace text node **that is on screen**
 * and spans up to `maxChars` characters, stopping at a whitespace boundary so
 * the selection is a readable run rather than a mid-word cut. Returns the
 * selected string, or null when the document has no on-screen text node.
 *
 * The on-screen requirement is not cosmetic: the toolbar is anchored to the
 * selection's rect, so selecting an off-screen run puts the toolbar off-screen
 * with it, where `toBeVisible()` fails — or a swatch cannot be clicked — for a
 * reason that has nothing to do with the behaviour under test.
 *
 * The mouse is NOT driven. A real drag would need the iframe scrolled to real
 * page coordinates, which tests the scroll position more than the toolbar. The
 * chapter's own script reads `window.getSelection()` on `mouseup`, so that event
 * is synthesised after the range is set. Same boundary, same handlers.
 */
export async function selectTextInChapter(
  frame: Frame,
  options: { maxChars?: number; minChars?: number } = {},
): Promise<string | null> {
  const band = await chapterVisibleBand(frame);
  if (!band) return null;
  const maxChars = options.maxChars ?? 60;
  const minChars = options.minChars ?? 3;

  const text = await frame.evaluate(
    ([limit, floor, visible]) => {
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
        const onScreen = rect ? rect.top >= visible.top && rect.bottom <= visible.bottom : false;
        if (value.trim().length >= floor && onScreen) break;
        node = walker.nextNode();
      }
      if (!node || !node.nodeValue) return null;

      const data = node.nodeValue;
      const start = data.search(/\S/);
      if (start < 0) return null;

      let end = Math.min(data.length, start + limit);
      const ws = data.lastIndexOf(' ', end);
      if (ws > start + 4) end = ws;

      const range = document.createRange();
      range.setStart(node, start);
      range.setEnd(node, end);

      const selection = document.getSelection();
      if (!selection) return null;
      selection.removeAllRanges();
      selection.addRange(range);

      return range.toString();
    },
    [maxChars, minChars, band] as const,
  );

  if (text === null) return null;

  // The gesture the chapter's script waits for: it debounces `mouseup` before
  // posting `epub-selection`, so the caller polls rather than reading once.
  await frame.evaluate(() => {
    document.dispatchEvent(new MouseEvent('mouseup', { bubbles: true }));
  });

  return text;
}

/**
 * Selects a run at the END of the widest line in the chapter's visible band, so
 * the selection's right edge is as close to the right border as the chapter
 * allows.
 *
 * ## Why {@link selectTextInChapter} is not enough
 *
 * That helper selects the FIRST on-screen text node, which is the start of a
 * paragraph — measured on *Don Quijote*, that puts the selection's right edge
 * around 40% of the window width, where the toolbar is nowhere near an edge and
 * a broken clamp still lands inside the viewport. `readerSelection.spec.ts`
 * carries a containment assertion that passed BEFORE the clamp was fixed for
 * exactly this reason: it was measuring the easy case. This helper exists so the
 * assertion has something to fail on.
 *
 * ## Why the candidate must be visible on BOTH axes
 *
 * The reader lays a chapter out as horizontal PAGES: measured on *Don Quijote*,
 * `documentElement.scrollWidth` is 40 622px while the frame's `innerWidth` is
 * 1 069, so the columns sit at x = 32, 1101, 2170 … and everything past the
 * first column is off screen. {@link chapterVisibleBand} constrains the VERTICAL
 * axis only (that is the axis the toolbar's flip cares about), so a candidate
 * chosen on "largest right" without a horizontal test lands in a column tens of
 * thousands of pixels away — measured: a selection with `lineRight` 40 584
 * against a 1 149px window, i.e. an off-screen anchor whose toolbar placement
 * says nothing about the edge this helper exists to probe. Hence the horizontal
 * test below.
 *
 * ## How the line is chosen
 *
 * The chapter's paragraphs are laid out as normal text, so a `Range` per text
 * node gives each node's client rects — one per wrapped line. The rect with the
 * largest `right` among the VISIBLE nodes is the right-most line end on screen,
 * and the selection is made over the last few characters of that node. The
 * frame's own `Selection` is used, as everywhere in this harness: a Range cannot
 * span two documents, and the parent reports nothing about a child frame's
 * selection.
 *
 * Returns null when nothing in the band has a line to select, which is the
 * honest "this chapter cannot pose the question" answer — the caller skips on it
 * rather than asserting against easy geometry.
 */
export async function selectTextAtRightEdge(
  frame: Frame,
  options: { chars?: number } = {},
): Promise<{ text: string; lineRight: number; reach: number } | null> {
  const band = await chapterVisibleBand(frame);
  if (!band) return null;
  const chars = options.chars ?? 6;

  const result = await frame.evaluate(
    ([charCount, visible]) => {
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

      const frameEl = (globalThis as unknown as { frameElement: Element | null }).frameElement;
      const frameRect = frameEl?.getBoundingClientRect() ?? { left: 0, top: 0 };
      const viewportWidth = (globalThis as unknown as { parent: Window }).parent.innerWidth;
      // The frame's own box is the widget the reader sees, so a node is on
      // screen only when it falls inside BOTH of its axes. `innerWidth` is not
      // the bound for the horizontal test: the document is 40 622px wide and
      // `innerWidth` reports only the visible column.
      const visibleLeft = -frameRect.left;
      const visibleRight = visibleLeft + window.innerWidth;

      // The right-most line end among every VISIBLE text node, with the node that
      // owns it. `selectNodeContents` yields one rect per wrapped line, so this
      // measures the actual layout rather than guessing from character counts.
      let best: { node: Text; right: number; top: number; bottom: number } | null = null;

      let node = walker.nextNode();
      while (node) {
        const value = node.nodeValue ?? '';
        const nodeRect = (node as Text).parentElement?.getBoundingClientRect();
        const onScreen =
          nodeRect &&
          nodeRect.top >= visible.top &&
          nodeRect.bottom <= visible.bottom &&
          nodeRect.left >= visibleLeft &&
          nodeRect.right <= visibleRight;
        if (onScreen && value.trim().length >= charCount + 2) {
          const range = document.createRange();
          range.selectNodeContents(node);
          const rects = Array.from(range.getClientRects()).filter((r) => r.width > 1);
          const last = rects.reduce<(DOMRect | null)>(
            (acc, r) => (acc === null || r.right > acc.right ? r : acc),
            null,
          );
          if (last && (best === null || last.right > best.right)) {
            best = { node: node as Text, right: last.right, top: last.top, bottom: last.bottom };
          }
        }
        node = walker.nextNode();
      }

      if (!best) return null;

      // The END characters of the right-most LINE, not of the node's string.
      //
      // Those are different, and conflating them is what made an earlier version
      // of this helper report a `lineRight` of 1 037 while the run it had actually
      // selected sat at window x 72–154: the paragraph is many wrapped lines, so
      // `data.slice(-6)` is the end of the paragraph, while the rect belongs to
      // the widest line. The run is located by walking the node's characters from
      // the end until one lands on that line, which needs no assumption about
      // word boundaries or line length.
      const data = best.node.nodeValue ?? '';

      /** A one-character Range carries its own rect, so a character's line is
       * the vertical span of its rect. */
      const rectAt = (index: number): DOMRect | null => {
        const probe = document.createRange();
        probe.setStart(best!.node, index);
        probe.setEnd(best!.node, Math.min(data.length, index + 1));
        const rect = probe.getBoundingClientRect();
        return rect.width === 0 && rect.height === 0 ? null : rect;
      };

      // The target line's vertical span, from the rect this node was chosen by.
      const lineTop = best.top;
      const lineBottom = best.bottom;

      let end = -1;
      for (let i = data.length - 1; i >= 0; i--) {
        const rect = rectAt(i);
        if (!rect) continue;
        // On the target line: the spans overlap rather than merely touching.
        if (rect.top < lineBottom - 1 && rect.bottom > lineTop + 1) {
          end = i + 1;
          break;
        }
      }
      if (end < 0) return null;

      // Walk back to the start of the word, or `charCount` characters.
      let start = end - 1;
      let taken = 1;
      while (start > 0 && taken < charCount && !/\s/.test(data[start - 1])) {
        start -= 1;
        taken += 1;
      }
      if (start >= end) start = Math.max(0, end - 1);

      const range = document.createRange();
      range.setStart(best.node, start);
      range.setEnd(best.node, end);

      const selection = document.getSelection();
      if (!selection) return null;
      selection.removeAllRanges();
      selection.addRange(range);

      // The run's OWN right edge, measured from the range that was just created —
      // not the line's. The containment assertion is about where the selection
      // put the toolbar, so the number the spec gates on has to be the run.
      const runRect = range.getBoundingClientRect();
      const lineRight = frameRect.left + runRect.right;

      return { text: range.toString(), lineRight, reach: viewportWidth - lineRight };
    },
    [chars, band] as const,
  );

  if (!result) return null;

  // The gesture the chapter's script waits for, exactly as `selectTextInChapter`
  // dispatches it: it debounces `mouseup` before posting `epub-selection`.
  await frame.evaluate(() => {
    document.dispatchEvent(new MouseEvent('mouseup', { bubbles: true }));
  });

  return result;
}

/** The frame's own current selection text (the parent cannot see it). */
export async function readFrameSelection(frame: Frame): Promise<string> {
  return frame.evaluate(() => (document.getSelection()?.toString() ?? '').trim()).catch(() => '');
}

/**
 * Scrolls the reader so the chapter's own prose is inside the visible band.
 *
 * ## Why a page turn can leave nothing to select
 *
 * A turn commits the new chapter at the offset its CFI restore names, and
 * measured on this machine that can be the **bottom** of the chapter: for one
 * *Don Quijote* chapter, `scrollTop` 26875 with `scrollHeight - clientHeight`
 * also 26875. The visible band then begins at the last text node and contains
 * none of it, so there is nothing on screen to select and no selection to anchor
 * a toolbar to.
 *
 * This brings the first text node of the chapter into view — what a person does
 * when a page opens below the text — and leaves the container there. It does not
 * assert; if the chapter has no text at all there is nothing to do and the caller
 * decides.
 */
export async function scrollToChapterProse(frame: Frame, page: Page): Promise<void> {
  const offset = await frame
    .evaluate(() => {
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
      const frameRect = (
        globalThis as unknown as { frameElement: Element | null }
      ).frameElement?.getBoundingClientRect() ?? { top: 0 };
      let node = walker.nextNode();
      while (node) {
        if ((node.nodeValue ?? '').trim().length >= 3) {
          const rect = (node as Text).parentElement?.getBoundingClientRect();
          if (rect) return Math.max(0, Math.round(frameRect.top + rect.top));
        }
        node = walker.nextNode();
      }
      return null;
    })
    .catch(() => null);

  if (offset === null) return;

  await page
    .locator('[data-testid="epub-scroll-container"]')
    .evaluate((el: HTMLElement, target: number) => {
      el.scrollTop = target - 40;
      el.dispatchEvent(new Event('scroll', { bubbles: true }));
    }, offset);
  await page.waitForTimeout(600);
}

/**
 * Clicks inside the chapter without selecting anything, the way a reader clicks
 * to move the caret.
 *
 * Composed at the frame level (down, up, collapse, click) rather than driven with
 * `page.mouse`, because a real click needs real page coordinates inside a frame
 * that `openBook` did not scroll into view. The frame's own handlers read
 * `mousedown` / `click`, which is exactly this sequence — the same technique
 * {@link selectTextInChapter} uses for `mouseup`.
 */
export async function clickInsideChapter(frame: Frame): Promise<void> {
  await frame.evaluate(() => {
    document.dispatchEvent(new MouseEvent('mousedown', { bubbles: true }));
    document.dispatchEvent(new MouseEvent('mouseup', { bubbles: true }));
    document.getSelection()?.removeAllRanges();
    document.dispatchEvent(new MouseEvent('click', { bubbles: true }));
  });
}

/**
 * How many characters of on-screen, non-script prose the chapter offers.
 *
 * Mirrors {@link selectTextInChapter}'s definition of a candidate — same band,
 * same skipped subtrees — so "there is something to select" and "the select
 * produced something" cannot disagree about what counts.
 */
export async function onScreenProseLength(frame: Frame): Promise<number> {
  const band = await chapterVisibleBand(frame);
  if (!band) return 0;

  return frame.evaluate((visible) => {
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
    let best = 0;
    let node = walker.nextNode();
    while (node) {
      const trimmed = (node.nodeValue ?? '').trim();
      const rect = (node as Text).parentElement?.getBoundingClientRect();
      const onScreen = rect ? rect.top >= visible.top && rect.bottom <= visible.bottom : false;
      if (onScreen && trimmed.length > best) best = trimmed.length;
      node = walker.nextNode();
    }
    return best;
  }, band);
}
