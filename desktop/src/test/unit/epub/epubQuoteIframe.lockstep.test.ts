import { describe, expect, it } from 'vitest';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { JSDOM } from 'jsdom';
import { IFRAME_QUOTE_SCRIPT } from '$lib/features/reader/viewer-epub/epubQuoteIframe';
import { MAX_QUOTE_LENGTH } from '$lib/shared/dictionary/dictionaryKey';

// ---------------------------------------------------------------------------
// REQ-DRE-005 lockstep: the fragment that runs inside the EPUB iframe must
// implement the paragraph heuristic exactly as the design specifies it, and
// `useEpubRender` must actually inject it. The fragment is a plain-JS string,
// so it is evaluated with `new Function` against real jsdom documents — the
// same code path the iframe executes, not a re-implementation.
// ---------------------------------------------------------------------------

interface QuoteWindow extends Window {
  __epubQuote?: {
    extract: (range: Range, doc: Document) => { quote: string | null };
  };
}

function setupQuote(html: string): { win: QuoteWindow; doc: Document } {
  const dom = new JSDOM(html, { runScripts: 'outside-only', url: 'http://localhost/' });
  const win = dom.window as unknown as QuoteWindow;
  new Function('window', IFRAME_QUOTE_SCRIPT)(win);
  return { win, doc: dom.window.document };
}

/** First text node whose value contains `needle`. */
function findText(doc: Document, needle: string): Text {
  const walker = doc.createTreeWalker(doc.body, 0x4 /* SHOW_TEXT */);
  let node: Node | null = walker.nextNode();
  while (node) {
    if ((node.nodeValue ?? '').includes(needle)) return node as Text;
    node = walker.nextNode();
  }
  throw new Error(`fixture text not found: ${needle}`);
}

function rangeOver(doc: Document, start: Text, startOffset: number, end: Text, endOffset: number) {
  const range = doc.createRange();
  range.setStart(start, startOffset);
  range.setEnd(end, endOffset);
  return range;
}

const PAGE = (body: string) =>
  `<!DOCTYPE html><html><head><meta charset="utf-8"></head><body>${body}</body></html>`;

describe('epubQuoteIframe lockstep (REQ-DRE-005)', () => {
  it('evaluates to window.__epubQuote and is idempotent', () => {
    const { win } = setupQuote(PAGE('<p style="display: block">text</p>'));
    expect(typeof win.__epubQuote?.extract).toBe('function');
    const first = win.__epubQuote;
    new Function('window', IFRAME_QUOTE_SCRIPT)(win);
    expect(win.__epubQuote).toBe(first);
    expect(IFRAME_QUOTE_SCRIPT).toContain('window.__epubQuote');
  });

  it('returns the containing <p> for a selection with nested inline markup', () => {
    const { win, doc } = setupQuote(
      PAGE('<p style="display: block">Hello <em>brave</em> <strong>world</strong>!</p>'),
    );
    const range = rangeOver(doc, findText(doc, 'Hello'), 0, findText(doc, 'world'), 5);
    const result = win.__epubQuote!.extract(range, doc);
    expect(result.quote).toBe('Hello brave world!');
    // The block element is never returned or plumbed.
    expect(Object.keys(result)).toEqual(['quote']);
  });

  it('returns the <li> for a selection inside a list item', () => {
    const { win, doc } = setupQuote(
      PAGE('<ul><li style="display: list-item">Item <b>one</b></li></ul>'),
    );
    const range = rangeOver(doc, findText(doc, 'Item'), 0, findText(doc, 'one'), 3);
    expect(win.__epubQuote!.extract(range, doc).quote).toBe('Item one');
  });

  it('returns the <blockquote> for a selection inside a quote', () => {
    const { win, doc } = setupQuote(
      PAGE('<blockquote style="display: block">Quoted <i>line</i></blockquote>'),
    );
    const range = rangeOver(doc, findText(doc, 'Quoted'), 0, findText(doc, 'line'), 4);
    expect(win.__epubQuote!.extract(range, doc).quote).toBe('Quoted line');
  });

  it('returns the <td> for a selection inside a table cell', () => {
    const { win, doc } = setupQuote(
      PAGE(
        '<table><tbody><tr><td style="display: table-cell">Cell <span>text</span></td></tr></tbody></table>',
      ),
    );
    const range = rangeOver(doc, findText(doc, 'Cell'), 0, findText(doc, 'text'), 4);
    expect(win.__epubQuote!.extract(range, doc).quote).toBe('Cell text');
  });

  it('captures only the START paragraph when the selection spans two paragraphs', () => {
    const { win, doc } = setupQuote(
      PAGE(
        '<div style="display: block">' +
          '<p style="display: block">Alpha <em>one</em> end</p>' +
          '<p style="display: block">Beta two</p>' +
          '</div>',
      ),
    );
    const range = rangeOver(doc, findText(doc, 'Alpha'), 0, findText(doc, 'Beta'), 4);
    expect(win.__epubQuote!.extract(range, doc).quote).toBe('Alpha one end');
  });

  it('returns quote: null when no block ancestor exists before the body', () => {
    const { win, doc } = setupQuote(PAGE('<span style="display: inline">bare text</span>'));
    const result = win.__epubQuote!.extract(
      rangeOver(doc, findText(doc, 'bare'), 0, findText(doc, 'bare'), 4),
      doc,
    );
    expect(result.quote).toBeNull();
    expect(Object.keys(result)).toEqual(['quote']);
  });

  it('collapses runs of whitespace and trims the paragraph', () => {
    const { win, doc } = setupQuote(PAGE('<p style="display: block">  hello \n   world  </p>'));
    const node = findText(doc, 'hello');
    expect(win.__epubQuote!.extract(rangeOver(doc, node, 0, node, node.length), doc).quote).toBe(
      'hello world',
    );
  });

  it('truncates an oversized paragraph at the cap and appends an ellipsis', () => {
    const long = 'a'.repeat(MAX_QUOTE_LENGTH + 500);
    const { win, doc } = setupQuote(PAGE(`<p style="display: block">${long}</p>`));
    const node = findText(doc, 'aaaa');
    const quote = win.__epubQuote!.extract(rangeOver(doc, node, 0, node, node.length), doc).quote!;
    expect(MAX_QUOTE_LENGTH).toBe(2000);
    expect(quote.length).toBe(MAX_QUOTE_LENGTH + 1);
    expect(quote.slice(0, MAX_QUOTE_LENGTH)).toBe('a'.repeat(MAX_QUOTE_LENGTH));
    expect(quote.endsWith('\u2026')).toBe(true);
  });

  it('useEpubRender injects the fragment and carries the chapter title into the payload', () => {
    const renderSource = readFileSync(
      join(process.cwd(), 'src/lib/features/reader/viewer-epub/useEpubRender.svelte.ts'),
      'utf8',
    );
    // The selection script was extracted to its own module (the viewer was far
    // over the 300-line budget); the payload assertions follow it there. What
    // stays here is that the viewer actually injects both scripts, and that the
    // chapter title reaches the payload the selection script builds.
    const selectionSource = readFileSync(
      join(process.cwd(), 'src/lib/features/reader/viewer-epub/epubSelectionScript.ts'),
      'utf8',
    );
    expect(renderSource).toContain('IFRAME_QUOTE_SCRIPT');
    expect(renderSource).toContain('buildChapterSelectionScript');
    expect(selectionSource).toContain('window.__epubQuote');
    expect(selectionSource).toContain('__epubQuote.extract(range, document)');
    expect(selectionSource).toContain('CHAPTER_TITLE');
    expect(selectionSource).toContain('chapterTitle: CHAPTER_TITLE');
    // The CFI bridge stays untouched: its lockstep twin is not imported here.
    expect(renderSource).toContain('IFRAME_CFI_BRIDGE_SCRIPT');
  });
});
