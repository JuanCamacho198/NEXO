/**
 * The injected chapter selection bridge (`epubSelectionScript`).
 *
 * This script is *generated as a string* and evaluated by the browser, so
 * nothing in the frontend type system checks it. The tests below are the only
 * place the string is inspected without booting the app, and they cover the
 * two properties a silent break would hide: it must parse, and it must carry
 * the chapter metadata the parent gates every message on.
 *
 * The behavioural cases (case 1–5 of the selection contract) live in
 * `e2e/tauri/readerSelection.spec.ts`, which drives the real app.
 */
import { describe, expect, it } from 'vitest';
import { buildChapterSelectionScript } from '$lib/features/reader/viewer-epub/epubSelectionScript';

const build = (over: Partial<Parameters<typeof buildChapterSelectionScript>[0]> = {}) =>
  buildChapterSelectionScript({
    chapterHref: 'OEBPS/Text/c01.xhtml',
    chapterIndex: 9,
    chapterTitle: 'Chapter One',
    ...over,
  });

describe('buildChapterSelectionScript', () => {
  it('parses as JavaScript when the browser evaluates it', () => {
    expect(() => new Function(build())).not.toThrow();
  });

  it('parses for a chapter with no title and quoting characters in the href', () => {
    const script = build({
      chapterHref: `OEBPS/We'ird/"path".xhtml`,
      chapterTitle: null,
    });
    expect(() => new Function(script)).not.toThrow();
    expect(script).toContain('var CHAPTER_TITLE = null;');
  });

  it('interpolates the chapter href, index and title (the parent gates on all three)', () => {
    const script = build({ chapterIndex: 4, chapterTitle: 'Prólogo' });
    // JSON.stringify emits double-quoted literals, which is also what makes a
    // title containing a quote safe inside the generated string.
    expect(script).toContain('var CHAPTER_HREF = "OEBPS/Text/c01.xhtml";');
    expect(script).toContain('var CHAPTER_INDEX = 4;');
    expect(script).toContain('var CHAPTER_TITLE = "Prólogo";');
  });

  it('emits every message type the bridge dispatches on', () => {
    const script = build();
    for (const type of ['epub-selection', 'epub-highlight-click', 'epub-pagination-key']) {
      expect(script).toContain(`'${type}'`);
    }
  });

  it('names a reason on every clear, so a dismissal is attributable in the log', () => {
    const script = build();
    for (const reason of [
      'inside-click',
      'scroll',
      'mouseup-collapsed',
      'selection-collapsed',
      'iframe-blur',
      'escape',
      'pagehide',
    ]) {
      expect(script).toContain(`'${reason}'`);
    }
  });

  it('registers exactly one mouseup listener (a release must not emit twice from two handlers)', () => {
    const occurrences = build().match(/document\.addEventListener\('mouseup'/g) ?? [];
    expect(occurrences).toHaveLength(1);
  });

  it('tags the immediate selection form so the parent can collapse it against the debounced one', () => {
    expect(build()).toContain('fast: true');
  });

  it('suppresses collapse-driven clears while a drag is in flight', () => {
    const script = build();
    expect(script).toContain('beginDrag');
    expect(script).toContain('endDrag');
    // The three listeners that can fire mid-drag must all consult the state.
    expect(script).toContain("if (dragState === 'dragging') return;");
    // ...and a timeout must end the drag in case no mouseup ever arrives.
    expect(script).toContain('DRAG_IDLE_MS');
  });

  /**
   * The blur handler must distinguish "focus moved into the parent's chrome"
   * from "focus left the reader".
   *
   * The toolbar lives in the PARENT document, so pressing one of its buttons
   * blurs the chapter frame. Treating that blur as "the selection is over"
   * dismissed the toolbar between the `pointerdown` and the `click`, and the
   * click landed on a button that no longer existed — "every button needs two
   * clicks". The guard is `document.hasFocus()`: only a blur that takes the
   * focus out of the window clears, and a blur into the parent leaves
   * `hasFocus()` true.
   */
  it('does not treat a blur into the parent chrome as the end of the selection', () => {
    const script = build();
    expect(script).toContain('document.hasFocus()');
    // The guard has to sit in the blur handler, before the clear it gates.
    const blurStart = script.indexOf("window.addEventListener('blur'");
    expect(blurStart, 'the blur listener is missing').toBeGreaterThan(-1);
    const blurBody = script.slice(blurStart, blurStart + 600);
    expect(blurBody).toContain('document.hasFocus()');
    expect(blurBody.indexOf('document.hasFocus()')).toBeLessThan(
      blurBody.indexOf("postClear('iframe-blur')"),
    );
  });

  it('emits every other dismissal path unconditionally, so nothing legitimate is lost', () => {
    // The blur guard must not have narrowed the paths that DO know the selection
    // ended: a click inside the chapter, a scroll, Escape and teardown each still
    // post their clear from this frame.
    const script = build();
    for (const reason of ['inside-click', 'scroll', 'escape', 'pagehide']) {
      expect(script, `the ${reason} clear went missing`).toContain(`'${reason}'`);
    }
  });
});
