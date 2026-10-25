import { describe, expect, it, vi } from 'vitest';
import { JSDOM } from 'jsdom';
import { IFRAME_HIGHLIGHT_OVERLAY_SCRIPT } from '$lib/features/reader/viewer-epub/epubHighlightOverlayIframe';
import { IFRAME_CFI_BRIDGE_SCRIPT } from '$lib/features/reader/viewer-epub/cfiBridgeIframe';

// ────────────────────────────────────────────────────────────────────
// PR 2 full test suite for the CSS Custom Highlight API overlay.
//
// PR 1 rewrote the overlay to registration-based rendering via
// CSS.highlights + ::highlight(). jsdom does NOT implement the CSS
// Custom Highlight API (no `CSS.highlights`, no `Highlight`, and no
// `document.caretRangeFromPoint`), so this suite installs a faithful
// mock BEFORE evaluating the overlay script:
//   - `Highlight` mock capturing `.ranges` (added via `.add()`).
//   - `HighlightRegistry` mock (Map-like: set/delete/clear/get).
//   - a `document.caretRangeFromPoint` stub for hit-test boundary tests.
// The REAL cfiBridge iframe script is also evaluated so CFIs resolve to
// genuine jsdom Ranges (real comparePoint/collapsed/toString/text), which
// is far closer to production than hand-built fake ranges.
// ────────────────────────────────────────────────────────────────────

const FIXTURE_HTML = `<!DOCTYPE html>
<html>
  <head><meta charset="utf-8"><title>Chapter</title></head>
  <body>
    <p>Primer parrafo. Contiene algo de texto para resaltar.</p>
    <p>Segundo parrafo. Tambien tiene contenido relevante.</p>
    <p>Tercer parrafo. Y este completa la seleccion.</p>
  </body>
</html>`;

const CHAPTER_HREF = 'OEBPS/Text/ch1.xhtml';

interface HighlightLike {
  ranges: Range[];
  add(range: Range): void;
}

class HighlightRegistryMock {
  private store = new Map<string, HighlightLike>();
  set(name: string, hl: HighlightLike): void {
    this.store.set(name, hl);
  }
  delete(name: string): boolean {
    return this.store.delete(name);
  }
  clear(): void {
    this.store.clear();
  }
  get(name: string): HighlightLike | undefined {
    return this.store.get(name);
  }
  names(): string[] {
    return [...this.store.keys()];
  }
  rangesFor(name: string): Range[] {
    return this.store.get(name)?.ranges ?? [];
  }
  entries(): Array<[string, HighlightLike]> {
    return [...this.store.entries()];
  }
}

interface OverlayWindow extends Window {
  __epubHighlightOverlay?: {
    render: (
      highlights: Array<{
        id: string;
        color: string;
        pageNumber: number;
        cfi: string | null;
        text?: string | null;
      }>,
      chapterHref: string,
      currentChapterIndex: number,
    ) => void;
    hitTest: (x: number, y: number) => { id: string; color: string; text: string } | null;
    rangeOverlapsHighlight: (range: Range) => boolean;
  };
  __cfiBridge?: {
    setSpine: (hrefs: string[]) => void;
    cfiToRange: (cfi: string, chapterHref: string, doc: Document) => Range | null;
    rangeToCFI: (range: Range, chapterHref: string, doc: Document) => string | null;
  };
  Highlight?: new () => HighlightLike;
  CSS?: { highlights: HighlightRegistryMock };
  // jsdom Window exposes `eval`; lib.dom.d.ts Window does not.
  eval: (code: string) => unknown;
}

interface OverlayHarness {
  win: OverlayWindow;
  doc: Document;
  registry: HighlightRegistryMock;
  /** postMessages captured through the stubbed `window.parent`. */
  messages: Array<Record<string, unknown>>;
}

function setupOverlay(opts: { cssHighlights?: boolean; parentSpy?: boolean } = {}): OverlayHarness {
  const dom = new JSDOM(FIXTURE_HTML, {
    runScripts: 'outside-only',
    url: 'http://localhost/',
  });
  const doc = dom.window.document;
  const win = dom.window as unknown as OverlayWindow;

  // Install the CSS Custom Highlight API mock unless the test wants the
  // feature-detect path (absent API).
  const registry = new HighlightRegistryMock();
  if (opts.cssHighlights !== false) {
    class HighlightMock {
      ranges: Range[] = [];
      add(range: Range): void {
        this.ranges.push(range);
      }
    }
    win.Highlight = HighlightMock;
    win.CSS = { highlights: registry };
  }

  // Optional parent postMessage spy to observe epub-hl-failed payloads.
  const messages: Array<Record<string, unknown>> = [];
  if (opts.parentSpy !== false) {
    Object.defineProperty(win, 'parent', {
      value: { postMessage: (msg: unknown) => messages.push(msg as Record<string, unknown>) },
      configurable: true,
    });
  }

  // Install the real CFI bridge so CFIs resolve to genuine jsdom ranges.
  win.eval(IFRAME_CFI_BRIDGE_SCRIPT);
  win.__cfiBridge!.setSpine([CHAPTER_HREF]);

  // Install the overlay.
  win.eval(IFRAME_HIGHLIGHT_OVERLAY_SCRIPT);

  return { win, doc, registry, messages };
}

/** Select the first occurrence of `text` and return its Range. */
function selectText(doc: Document, text: string): Range | null {
  const walker = doc.createTreeWalker(doc.body, 0x4 /* SHOW_TEXT */);
  let node: Node | null = walker.nextNode();
  while (node) {
    const value = node.nodeValue ?? '';
    const idx = value.indexOf(text);
    if (idx >= 0) {
      const range = doc.createRange();
      range.setStart(node, idx);
      range.setEnd(node, idx + text.length);
      return range;
    }
    node = walker.nextNode();
  }
  return null;
}

/** Compute a resolvable CFI for the first occurrence of `text`. */
function cfiFor(win: OverlayWindow, doc: Document, text: string): string {
  const range = selectText(doc, text);
  if (!range) throw new Error(`fixture text not found: ${text}`);
  const cfi = win.__cfiBridge!.rangeToCFI(range, CHAPTER_HREF, doc);
  if (!cfi) throw new Error(`could not compute CFI for ${text}`);
  return cfi;
}

function hl(id: string, color: string, cfi: string, pageNumber = 0) {
  return { id, color, pageNumber, cfi };
}

// ────────────────────────────────────────────────────────────────────
// Smoke surface (kept from PR 1 — mount surface, idempotent eval,
// feature-detect no-op).
// ────────────────────────────────────────────────────────────────────
describe('epubHighlightOverlay — overlay surface (smoke)', () => {
  it('mounts render/hitTest/rangeOverlapsHighlight', () => {
    const { win } = setupOverlay();
    expect(win.__epubHighlightOverlay).toBeTruthy();
    expect(typeof win.__epubHighlightOverlay!.render).toBe('function');
    expect(typeof win.__epubHighlightOverlay!.hitTest).toBe('function');
    expect(typeof win.__epubHighlightOverlay!.rangeOverlapsHighlight).toBe('function');
  });

  it('is idempotent across repeated evaluations', () => {
    const { win } = setupOverlay();
    const first = win.__epubHighlightOverlay;
    win.eval(IFRAME_HIGHLIGHT_OVERLAY_SCRIPT);
    expect(win.__epubHighlightOverlay).toBe(first);
  });

  it('feature-detect: warns and renders nothing when CSS.highlights is absent', () => {
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => {});
    try {
      const { win, doc, registry } = setupOverlay({ cssHighlights: false });
      const before = doc.documentElement.outerHTML;
      win.__epubHighlightOverlay!.render(
        [hl('hl-1', '#FACC15', cfiFor(win, doc, 'Segundo'))],
        CHAPTER_HREF,
        0,
      );
      expect(registry.names().length).toBe(0);
      expect(doc.querySelectorAll('.epub-hl').length).toBe(0);
      expect(doc.documentElement.outerHTML).toBe(before);
      expect(warn).toHaveBeenCalled();
    } finally {
      warn.mockRestore();
    }
  });
});

// ────────────────────────────────────────────────────────────────────
// T12 — registry semantics (REQ-RENDERING)
// ────────────────────────────────────────────────────────────────────
describe('epubHighlightOverlay — registry semantics (T12)', () => {
  it('render registers ranges grouped under the correct color names', () => {
    const { win, doc, registry } = setupOverlay();
    const yellowCfi = cfiFor(win, doc, 'Primer parrafo');
    const yellowCfi2 = cfiFor(win, doc, 'Contiene algo');
    const blueCfi = cfiFor(win, doc, 'Segundo parrafo');

    win.__epubHighlightOverlay!.render(
      [
        hl('a', '#FACC15', yellowCfi), // canonical yellow
        hl('b', '#FACC15', yellowCfi2), // canonical yellow
        hl('c', '#3B82F6', blueCfi), // canonical blue
      ],
      CHAPTER_HREF,
      0,
    );

    expect(registry.names().sort()).toEqual(['epub-hl-blue', 'epub-hl-yellow']);
    expect(registry.rangesFor('epub-hl-yellow').length).toBe(2);
    expect(registry.rangesFor('epub-hl-blue').length).toBe(1);
    // The ranges hold the real selected text (the substring matched by
    // the fixture selector, not the full paragraph).
    const yellowTexts = registry.rangesFor('epub-hl-yellow').map((r) => r.toString().trim());
    expect(yellowTexts).toEqual(expect.arrayContaining(['Primer parrafo', 'Contiene algo']));
    expect(registry.rangesFor('epub-hl-blue')[0].toString()).toBe('Segundo parrafo');
  });

  it('render only registers highlights matching the current pageNumber', () => {
    const { win, doc, registry } = setupOverlay();
    const cfi = cfiFor(win, doc, 'Segundo');
    win.__epubHighlightOverlay!.render(
      [
        hl('a', '#FACC15', cfi, 0), // current chapter
        hl('b', '#4ADE80', cfi, 1), // other chapter → ignored
      ],
      CHAPTER_HREF,
      0,
    );
    expect(registry.names().sort()).toEqual(['epub-hl-yellow']);
  });

  it('idempotent re-render: second render with an extra highlight keeps both', () => {
    const { win, doc, registry } = setupOverlay();
    const cfiA = cfiFor(win, doc, 'Primer parrafo');
    const cfiB = cfiFor(win, doc, 'Segundo parrafo');

    win.__epubHighlightOverlay!.render([hl('a', '#FACC15', cfiA)], CHAPTER_HREF, 0);
    win.__epubHighlightOverlay!.render(
      [hl('a', '#FACC15', cfiA), hl('b', '#4ADE80', cfiB)],
      CHAPTER_HREF,
      0,
    );

    expect(registry.names().sort()).toEqual(['epub-hl-green', 'epub-hl-yellow']);
    expect(registry.rangesFor('epub-hl-yellow').length).toBe(1);
    expect(registry.rangesFor('epub-hl-green').length).toBe(1);
  });

  it('delete: re-render without a highlight removes its range; others remain', () => {
    const { win, doc, registry } = setupOverlay();
    const cfiA = cfiFor(win, doc, 'Primer parrafo');
    const cfiB = cfiFor(win, doc, 'Segundo parrafo');

    win.__epubHighlightOverlay!.render(
      [hl('a', '#FACC15', cfiA), hl('b', '#4ADE80', cfiB)],
      CHAPTER_HREF,
      0,
    );
    expect(registry.rangesFor('epub-hl-green').length).toBe(1);

    win.__epubHighlightOverlay!.render([hl('a', '#FACC15', cfiA)], CHAPTER_HREF, 0);

    expect(registry.names().sort()).toEqual(['epub-hl-yellow']);
    expect(registry.rangesFor('epub-hl-yellow').length).toBe(1);
    expect(registry.rangesFor('epub-hl-green').length).toBe(0);
  });

  it('recolor: re-render moves the range to the new color Highlight', () => {
    const { win, doc, registry } = setupOverlay();
    const cfi = cfiFor(win, doc, 'Primer parrafo');

    win.__epubHighlightOverlay!.render([hl('a', '#FACC15', cfi)], CHAPTER_HREF, 0);
    expect(registry.names().sort()).toEqual(['epub-hl-yellow']);

    win.__epubHighlightOverlay!.render([hl('a', '#3B82F6', cfi)], CHAPTER_HREF, 0);

    expect(registry.names().sort()).toEqual(['epub-hl-blue']);
    expect(registry.rangesFor('epub-hl-blue').length).toBe(1);
    expect(registry.rangesFor('epub-hl-yellow').length).toBe(0);
  });

  it('collapsed range is skipped with a warn; other highlights still register', () => {
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => {});
    try {
      const { win, doc, registry } = setupOverlay();
      const goodCfi = cfiFor(win, doc, 'Segundo');
      // A zero-length selection collapses to a single point → CFI with
      // start == end terminuses resolves to a collapsed range.
      const collapsedRange = doc.createRange();
      const p = doc.querySelector('p')!;
      const tn = p.firstChild!;
      collapsedRange.setStart(tn, 2);
      collapsedRange.setEnd(tn, 2);
      const collapsedCfi = win.__cfiBridge!.rangeToCFI(collapsedRange, CHAPTER_HREF, doc)!;

      win.__epubHighlightOverlay!.render(
        [hl('collapsed', '#FACC15', collapsedCfi), hl('good', '#4ADE80', goodCfi)],
        CHAPTER_HREF,
        0,
      );

      // No throw; collapsed skipped; the good one still registers.
      expect(registry.names().sort()).toEqual(['epub-hl-green']);
      expect(registry.rangesFor('epub-hl-green').length).toBe(1);
      expect(warn).toHaveBeenCalled();
    } finally {
      warn.mockRestore();
    }
  });
});

// ────────────────────────────────────────────────────────────────────
// cfi-unresolved failure (REQ-OBSERVABILITY reason + REQ-REMOVAL
// no-repair-path at runtime). The wrap/A+B repair machinery is gone:
// an unresolvable CFI must post epub-hl-failed cfi-unresolved, register
// nothing, and NEVER post epub-hl-repair.
// ────────────────────────────────────────────────────────────────────
describe('epubHighlightOverlay — cfi-unresolved failure (REQ-OBSERVABILITY / REQ-REMOVAL)', () => {
  it('posts epub-hl-failed cfi-unresolved, registers no range and never posts epub-hl-repair', () => {
    const { win, doc, registry, messages } = setupOverlay();
    const goodCfi = cfiFor(win, doc, 'Segundo');

    // 'epubcfi(/6/1!/999/999,/1:0,/1:0)' points at spine index 1 while
    // the registered spine is [CHAPTER_HREF] (index 0), so cfiToRange
    // rejects it with a spine mismatch → null → cfi-unresolved.
    win.__epubHighlightOverlay!.render(
      [hl('bogus', '#FACC15', 'epubcfi(/6/1!/999/999,/1:0,/1:0)'), hl('good', '#4ADE80', goodCfi)],
      CHAPTER_HREF,
      0,
    );

    // The unresolvable highlight is skipped: no range under its color.
    expect(registry.names().sort()).toEqual(['epub-hl-green']);
    expect(registry.rangesFor('epub-hl-yellow').length).toBe(0);
    expect(registry.rangesFor('epub-hl-green').length).toBe(1);
    // REQ-OBSERVABILITY: exact failure payload with the cfi-unresolved
    // reason (REQ-REMOVAL: the repair path is gone, so this is the only
    // failure signal the parent can receive).
    expect(messages).toContainEqual(
      expect.objectContaining({
        type: 'epub-hl-failed',
        id: 'bogus',
        reason: 'cfi-unresolved',
        pageNumber: 0,
      }),
    );
    // REQ-REMOVAL runtime pin: no epub-hl-repair message ever posts.
    expect(messages.some((m) => m.type === 'epub-hl-repair')).toBe(false);
  });
});

// ────────────────────────────────────────────────────────────────────
// T14 — colorMap (REQ-OBSERVABILITY, D2)
// ────────────────────────────────────────────────────────────────────
describe('epubHighlightOverlay — colorMap (T14)', () => {
  it('maps a legacy hex to the nearest canonical color and posts epub-hl-failed unknown-color', () => {
    const { win, doc, registry, messages } = setupOverlay();
    const cfi = cfiFor(win, doc, 'Primer parrafo');

    // #F9A8D4 (a pink) is nearest to canonical red (Euclidean d² = 30836
    // vs orange 38909 — purple no longer exists in the palette).
    win.__epubHighlightOverlay!.render([hl('pink', '#F9A8D4', cfi)], CHAPTER_HREF, 0);

    expect(registry.names().sort()).toEqual(['epub-hl-red']);
    expect(registry.rangesFor('epub-hl-red').length).toBe(1);
    expect(messages).toContainEqual(
      expect.objectContaining({
        type: 'epub-hl-failed',
        id: 'pink',
        reason: 'unknown-color',
        pageNumber: 0,
      }),
    );
  });

  it.each([
    ['#FBDC6B', 'epub-hl-yellow'],
    ['#86EFAC', 'epub-hl-green'],
    ['#93C5FD', 'epub-hl-blue'],
    ['#C4B5FD', 'epub-hl-blue'],
    ['#FDBA74', 'epub-hl-yellow'],
  ])('legacy hex %s maps to the nearest canonical %s', (hex, expectedName) => {
    const { win, doc, registry } = setupOverlay();
    const cfi = cfiFor(win, doc, 'Primer parrafo');
    win.__epubHighlightOverlay!.render([hl('h', hex, cfi)], CHAPTER_HREF, 0);
    expect(registry.names().sort()).toEqual([expectedName]);
  });

  it('tie-breaks exact Euclidean equidistance to the first canonical entry (blue before red)', () => {
    // #95639D is exactly equidistant (d² = 16982) between blue and red;
    // the strict '<' tie-break resolves to blue because blue precedes red
    // in the canonical list. Verified: yellow 39722, green 21595,
    // orange 28481 are all farther.
    const { win, doc, registry } = setupOverlay();
    const cfi = cfiFor(win, doc, 'Primer parrafo');
    win.__epubHighlightOverlay!.render([hl('tie', '#95639D', cfi)], CHAPTER_HREF, 0);
    expect(registry.names().sort()).toEqual(['epub-hl-blue']);
  });

  it('a canonical hex maps to itself without an unknown-color failure', () => {
    const { win, doc, registry, messages } = setupOverlay();
    const cfi = cfiFor(win, doc, 'Primer parrafo');
    win.__epubHighlightOverlay!.render([hl('c', '#FACC15', cfi)], CHAPTER_HREF, 0);
    expect(registry.names().sort()).toEqual(['epub-hl-yellow']);
    expect(messages.some((m) => m.type === 'epub-hl-failed')).toBe(false);
  });

  it('invalid color posts epub-hl-failed invalid-color and skips the range', () => {
    const { win, doc, registry, messages } = setupOverlay();
    const goodCfi = cfiFor(win, doc, 'Segundo');
    win.__epubHighlightOverlay!.render(
      [hl('bad', 'not-a-color', goodCfi), hl('ok', '#4ADE80', goodCfi)],
      CHAPTER_HREF,
      0,
    );
    expect(registry.names().sort()).toEqual(['epub-hl-green']);
    expect(messages).toContainEqual(
      expect.objectContaining({ type: 'epub-hl-failed', id: 'bad', reason: 'invalid-color' }),
    );
  });
});

// ────────────────────────────────────────────────────────────────────
// T13 — hit-test boundaries (REQ-CLICK, D1)
// ────────────────────────────────────────────────────────────────────
describe('epubHighlightOverlay — hit-test boundaries (T13)', () => {
  interface HitTestHarness {
    win: OverlayWindow;
    range: Range;
    /** set the caret that document.caretRangeFromPoint will return. */
    setCaret: (c: { startContainer: Node; startOffset: number } | null) => void;
  }

  function setupHitTest(text: string): HitTestHarness {
    const { win, doc } = setupOverlay();
    const range = selectText(doc, text)!;
    const cfi = win.__cfiBridge!.rangeToCFI(range, CHAPTER_HREF, doc)!;
    win.__epubHighlightOverlay!.render([hl('hit', '#FACC15', cfi)], CHAPTER_HREF, 0);

    // Stub document.caretRangeFromPoint to return the preset caret.
    let caret: { startContainer: Node; startOffset: number } | null = null;
    const stub = (() => {
      const fn = () => caret;
      fn.__set = (c: { startContainer: Node; startOffset: number } | null) => {
        caret = c;
      };
      return fn;
    })() as unknown as Document['caretRangeFromPoint'] & { __set: (c: unknown) => void };
    Object.defineProperty(doc, 'caretRangeFromPoint', { value: stub, configurable: true });

    return { win, range, setCaret: (c) => stub.__set(c) };
  }

  it('first character of the highlight resolves (start boundary inclusive)', () => {
    const { win, range, setCaret } = setupHitTest('Segundo');
    setCaret({ startContainer: range.startContainer, startOffset: range.startOffset });

    const hit = win.__epubHighlightOverlay!.hitTest(10, 10);
    expect(hit).not.toBeNull();
    expect(hit!.id).toBe('hit');
  });

  it('last character of the highlight resolves', () => {
    const { win, range, setCaret } = setupHitTest('Segundo');
    setCaret({ startContainer: range.endContainer, startOffset: range.endOffset - 1 });

    const hit = win.__epubHighlightOverlay!.hitTest(10, 10);
    expect(hit).not.toBeNull();
    expect(hit!.id).toBe('hit');
    expect(hit!.text).toBe('Segundo');
  });

  it('exact end boundary resolves (D1: end boundary inclusive)', () => {
    const { win, range, setCaret } = setupHitTest('Segundo');
    setCaret({ startContainer: range.endContainer, startOffset: range.endOffset });

    const hit = win.__epubHighlightOverlay!.hitTest(10, 10);
    expect(hit).not.toBeNull();
    expect(hit!.id).toBe('hit');
  });

  it('adjacent plain text just past the end boundary misses', () => {
    const { win, range, setCaret } = setupHitTest('Segundo');
    // One char past the end boundary within the same text node.
    setCaret({ startContainer: range.endContainer, startOffset: range.endOffset + 1 });

    expect(win.__epubHighlightOverlay!.hitTest(10, 10)).toBeNull();
  });

  it('plain text before the highlight misses', () => {
    // "Contiene algo" begins mid-text-node (offset > 0) inside the first
    // paragraph, so a caret one char before it is a valid in-text position.
    const { win, range, setCaret } = setupHitTest('Contiene algo');
    expect(range.startOffset).toBeGreaterThan(0);
    setCaret({ startContainer: range.startContainer, startOffset: range.startOffset - 1 });

    expect(win.__epubHighlightOverlay!.hitTest(10, 10)).toBeNull();
  });

  it('hit-testing over multiple highlights resolves the correct one by id', () => {
    const { win, doc } = setupOverlay();
    const rangeA = selectText(doc, 'Primer parrafo')!;
    const rangeB = selectText(doc, 'Segundo parrafo')!;
    win.__epubHighlightOverlay!.render(
      [
        hl('a', '#FACC15', win.__cfiBridge!.rangeToCFI(rangeA, CHAPTER_HREF, doc)!),
        hl('b', '#4ADE80', win.__cfiBridge!.rangeToCFI(rangeB, CHAPTER_HREF, doc)!),
      ],
      CHAPTER_HREF,
      0,
    );

    let caret: { startContainer: Node; startOffset: number } | null = null;
    const stub = (() => {
      const fn = () => caret;
      fn.__set = (c: unknown) => {
        caret = c as typeof caret;
      };
      return fn;
    })() as unknown as Document['caretRangeFromPoint'] & { __set: (c: unknown) => void };
    Object.defineProperty(doc, 'caretRangeFromPoint', { value: stub, configurable: true });
    stub.__set({ startContainer: rangeB.startContainer, startOffset: rangeB.startOffset });

    const hit = win.__epubHighlightOverlay!.hitTest(5, 5);
    expect(hit).not.toBeNull();
    expect(hit!.id).toBe('b');
  });

  it('returns null when there is no caret at the point (outside text)', () => {
    const { win, setCaret } = setupHitTest('Segundo');
    setCaret(null);
    expect(win.__epubHighlightOverlay!.hitTest(10, 10)).toBeNull();
  });
});

// ────────────────────────────────────────────────────────────────────
// Timing race: the parent's highlight $effect can call render() BEFORE
// the iframe's injected cfiBridge script has executed (the srcdoc
// scripts run when the document finishes parsing, which races the
// parent's reactive effect). At that moment __cfiBridge is undefined and
// every CFI would "fail to resolve" — a false-negative epub-hl-failed
// cfi-unresolved that made highlights appear only after the iframe
// onload re-render ("sometimes works, sometimes not"). The overlay must
// defer the render until the bridge mounts and then auto re-run it.
// ────────────────────────────────────────────────────────────────────
describe('epubHighlightOverlay — deferred render when bridge not ready', () => {
  it('does not post cfi-unresolved when the bridge is not mounted yet', async () => {
    const dom = new JSDOM(FIXTURE_HTML, { runScripts: 'outside-only', url: 'http://localhost/' });
    const doc = dom.window.document;
    const win = dom.window as unknown as OverlayWindow;

    // CSS.highlights mock (same as setupOverlay).
    const registry = new HighlightRegistryMock();
    class HighlightMock {
      ranges: Range[] = [];
      add(range: Range): void {
        this.ranges.push(range);
      }
    }
    win.Highlight = HighlightMock;
    win.CSS = { highlights: registry };

    // Capture postMessages via the stubbed parent.
    const messages: Array<Record<string, unknown>> = [];
    Object.defineProperty(win, 'parent', {
      value: { postMessage: (msg: unknown) => messages.push(msg as Record<string, unknown>) },
      configurable: true,
    });

    // Generate a genuine resolvable CFI by mounting the bridge briefly.
    win.eval(IFRAME_CFI_BRIDGE_SCRIPT);
    win.__cfiBridge!.setSpine([CHAPTER_HREF]);
    const range = selectText(doc, 'Primer parrafo')!;
    const validCfi = win.__cfiBridge!.rangeToCFI(range, CHAPTER_HREF, doc)!;
    expect(validCfi).toBeTruthy();

    // Now remove the bridge to simulate the race: the parent effect runs
    // before the bridge script has executed.
    delete win.__cfiBridge;

    // Install ONLY the overlay (bridge is absent now).
    win.eval(IFRAME_HIGHLIGHT_OVERLAY_SCRIPT);

    // render() while the bridge is missing.
    win.__epubHighlightOverlay!.render(
      [{ id: 'race-hl', color: '#FACC15', pageNumber: 0, cfi: validCfi }],
      CHAPTER_HREF,
      0,
    );

    // MUST NOT post a false cfi-unresolved while the bridge is missing.
    const failures = messages.filter((m) => m.type === 'epub-hl-failed');
    expect(failures.length).toBe(0);

    // Now the bridge mounts (as it would when the srcdoc finishes
    // parsing): install it and let the deferred poll flush.
    win.eval(IFRAME_CFI_BRIDGE_SCRIPT);
    win.__cfiBridge!.setSpine([CHAPTER_HREF]);

    // Wait for the 20ms deferred-render poll.
    await new Promise((r) => setTimeout(r, 60));

    // The highlight must now be registered (bridge mounted → deferred
    // render flushed) and still no cfi-unresolved failure.
    expect(registry.names()).toContain('epub-hl-yellow');
    const after = messages.filter((m) => m.type === 'epub-hl-failed');
    expect(after.length).toBe(0);
  });

  it('still posts cfi-unresolved for a genuinely unresolvable CFI once the bridge is ready', async () => {
    const dom = new JSDOM(FIXTURE_HTML, { runScripts: 'outside-only', url: 'http://localhost/' });
    const win = dom.window as unknown as OverlayWindow;

    const registry = new HighlightRegistryMock();
    class HighlightMock {
      ranges: Range[] = [];
      add(range: Range): void {
        this.ranges.push(range);
      }
    }
    win.Highlight = HighlightMock;
    win.CSS = { highlights: registry };

    const messages: Array<Record<string, unknown>> = [];
    Object.defineProperty(win, 'parent', {
      value: { postMessage: (msg: unknown) => messages.push(msg as Record<string, unknown>) },
      configurable: true,
    });

    // Install the bridge FIRST (simulates the onload path where the
    // bridge is already mounted) then the overlay.
    win.eval(IFRAME_CFI_BRIDGE_SCRIPT);
    win.__cfiBridge!.setSpine([CHAPTER_HREF]);
    win.eval(IFRAME_HIGHLIGHT_OVERLAY_SCRIPT);

    // A bogus CFI that the bridge cannot resolve.
    win.__epubHighlightOverlay!.render(
      [{ id: 'bad-hl', color: '#FACC15', pageNumber: 0, cfi: 'epubcfi(/6/1!/999/999,/1:0,/1:0)' }],
      CHAPTER_HREF,
      0,
    );

    const failures = messages.filter((m) => m.type === 'epub-hl-failed');
    expect(failures.length).toBe(1);
    expect(failures[0].reason).toBe('cfi-unresolved');
    expect(registry.names()).not.toContain('epub-hl-yellow');
  });
});

// ────────────────────────────────────────────────────────────────────
// Text-anchor placement fallback: Android-created highlights can store
// a non-epubcfi locator (e.g. "readium:OEBPS/Text/cap1.xhtml") with a
// pageNumber normalized away from the spine index, so neither the
// spine gate nor CFI resolution applies. When the highlight carries its
// source text, the overlay must place it by that anchor, report
// `epub-hl-placed` to the parent, and skip only when no usable text
// anchor exists.
// ────────────────────────────────────────────────────────────────────
describe('epubHighlightOverlay — text-anchor placement fallback', () => {
  it('places a page-mismatched highlight by its stored text and posts epub-hl-placed', () => {
    const { win, registry, messages } = setupOverlay();

    win.__epubHighlightOverlay!.render(
      [
        {
          id: 'android-hl',
          color: '#FACC15',
          pageNumber: 1,
          cfi: null,
          text: 'Contiene algo de texto para resaltar.',
        },
      ],
      CHAPTER_HREF,
      0,
    );

    // Registered like a CFI-resolved highlight (yellow group, exact text).
    expect(registry.names()).toEqual(['epub-hl-yellow']);
    expect(registry.rangesFor('epub-hl-yellow').length).toBe(1);
    expect(registry.rangesFor('epub-hl-yellow')[0].toString().trim()).toBe(
      'Contiene algo de texto para resaltar.',
    );
    // The parent learns where the highlight actually landed so it can
    // persist the derived spine page.
    expect(messages).toContainEqual(
      expect.objectContaining({
        type: 'epub-hl-placed',
        id: 'android-hl',
        pageNumber: 0,
      }),
    );
  });

  it('still skips page-mismatched highlights whose text anchor is too short', () => {
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => {});
    try {
      const { win, registry, messages } = setupOverlay();
      win.__epubHighlightOverlay!.render(
        [{ id: 'tiny-hl', color: '#FACC15', pageNumber: 5, cfi: null, text: 'ab' }],
        CHAPTER_HREF,
        0,
      );
      expect(registry.names().length).toBe(0);
      expect(messages.some((m) => m.type === 'epub-hl-placed')).toBe(false);
    } finally {
      warn.mockRestore();
    }
  });

  it('falls back to the stored text when a page-matched locator is not an epubcfi and fails to resolve', () => {
    const { win, registry, messages } = setupOverlay();
    win.__epubHighlightOverlay!.render(
      [
        {
          id: 'readium-hl',
          color: '#4ADE80',
          pageNumber: 0,
          cfi: 'readium:OEBPS/Text/cap1.xhtml',
          text: 'Tercer parrafo. Y este completa la seleccion.',
        },
      ],
      CHAPTER_HREF,
      0,
    );

    // Placed by text instead of posting epub-hl-failed.
    expect(registry.names()).toEqual(['epub-hl-green']);
    expect(messages.some((m) => m.type === 'epub-hl-failed')).toBe(false);
    expect(messages).toContainEqual(
      expect.objectContaining({
        type: 'epub-hl-placed',
        id: 'readium-hl',
        pageNumber: 0,
      }),
    );
  });
});

// ────────────────────────────────────────────────────────────────────
// The text-anchor fallback indexes the chapter ONCE per render.
//
// It used to rebuild the whole chapter's per-character normalized map for
// every candidate highlight, so a chapter with none of its own highlights
// still paid one full pass per highlight: measured live on *Prisioneros de la
// geografía*, 48 821 characters costs 96.2 ms per pass and that book stores 7
// highlights, so one render burned ~673 ms rebuilding the same string. The
// tree walk is the first thing that pass does, so counting walks counts passes.
// ────────────────────────────────────────────────────────────────────
describe('epubHighlightOverlay — text-anchor index is built once per render', () => {
  /** Highlights that belong to ANOTHER chapter, so each one needs the fallback. */
  function foreignTextHighlights(count: number) {
    return Array.from({ length: count }, (_, i) => ({
      id: `txt-${i}`,
      color: '#FACC15',
      pageNumber: 9,
      cfi: null,
      text: `a needle that is not in this chapter ${i}`,
    }));
  }

  it('walks the chapter once for N highlights that need the text fallback', () => {
    const { win, doc } = setupOverlay();
    const walk = vi.spyOn(doc, 'createTreeWalker');
    try {
      win.__epubHighlightOverlay!.render(foreignTextHighlights(5), CHAPTER_HREF, 0);
      expect(walk).toHaveBeenCalledTimes(1);
    } finally {
      walk.mockRestore();
    }
  });

  it('never walks the chapter when there is nothing to place by text', () => {
    const { win, doc } = setupOverlay();
    const walk = vi.spyOn(doc, 'createTreeWalker');
    try {
      win.__epubHighlightOverlay!.render([], CHAPTER_HREF, 0);
      expect(walk).not.toHaveBeenCalled();
    } finally {
      walk.mockRestore();
    }
  });

  it('still places a highlight by its stored text, one walk for all of them', () => {
    const { win, doc, registry } = setupOverlay();
    const walk = vi.spyOn(doc, 'createTreeWalker');
    try {
      win.__epubHighlightOverlay!.render(
        [
          {
            id: 'android-hl',
            color: '#FACC15',
            pageNumber: 9,
            cfi: 'readium:OEBPS/Text/cap1.xhtml',
            text: 'Segundo parrafo',
          },
          ...foreignTextHighlights(4),
        ],
        CHAPTER_HREF,
        0,
      );
      expect(registry.rangesFor('epub-hl-yellow').map((r) => r.toString())).toContain(
        'Segundo parrafo',
      );
      expect(walk).toHaveBeenCalledTimes(1);
    } finally {
      walk.mockRestore();
    }
  });
});
