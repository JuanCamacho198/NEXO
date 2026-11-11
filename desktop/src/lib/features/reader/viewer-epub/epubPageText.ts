/**
 * Plain text of the chapter the reader is on, for language detection.
 *
 * ## Why the chapter HTML and not the live document
 *
 * `collectVisibleTextNodes(doc)` reads the rendered chapter, but that document
 * only exists after the iframe's `load` fires, and the render pipeline can
 * already have moved on by then (the epoch guard discards stale commits). The
 * HTML arriving from `get_epub_chapter` exists at the exact moment the render
 * commits, so the text is written once, in the same guarded block that sets the
 * srcdoc, and can never describe a chapter the reader has left.
 *
 * ## Why the result is capped
 *
 * The consumer needs a few hundred words to detect a language; a fresh string of
 * a whole chapter (measured: 4638 words in *Don Quijote*) would be a large
 * reactive value copied on every chapter change for no added signal. The
 * detector's own window table hits 99% at 100 words and does not improve at 300,
 * so the cap is set well above the useful window and far below a chapter.
 */

/**
 * Characters kept. A word averages about 5-6 characters in Latin prose, so this
 * holds roughly 800 words — several times the 300-word window the detector
 * measurably stops improving at, and a small fraction of a chapter.
 */
export const PAGE_TEXT_MAX_CHARS = 5000;

/** Tags whose text is not chapter prose. */
const NON_PROSE_SELECTOR = 'script, style, noscript, template, head, title';

/**
 * Collapse whitespace and drop non-prose text from a chapter document element.
 *
 * Pure over a parsed document so the rule is testable without a browser. The
 * walk stops as soon as the cap is reached: a chapter is parsed once, but the
 * string is built eagerly up to the cap and no further.
 */
function collapseText(root: Element | null, maxChars: number): string {
  if (!root) return '';
  const doc = root.ownerDocument;
  if (!doc) return '';
  const walker = doc.createTreeWalker(root, NodeFilter.SHOW_TEXT);
  const parts: string[] = [];
  let total = 0;
  for (let node = walker.nextNode(); node; node = walker.nextNode()) {
    const textNode = node as Text;
    if (textNode.parentElement?.closest(NON_PROSE_SELECTOR)) continue;
    const text = (textNode.nodeValue ?? '').replace(/\s+/g, ' ').trim();
    if (!text) continue;
    parts.push(text);
    total += text.length + 1;
    if (total >= maxChars) break;
  }
  const joined = parts.join(' ').trim();
  return joined.length > maxChars ? joined.slice(0, maxChars) : joined;
}

/**
 * Extract the readable text of a chapter from its XHTML/HTML source.
 *
 * Returns `''` for a document with no prose (a full-page plate, a cover), which
 * is honest: there is no signal to offer the detector.
 */
export function extractPageText(html: string, maxChars = PAGE_TEXT_MAX_CHARS): string {
  if (!html.trim()) return '';
  const doc = new DOMParser().parseFromString(html, 'text/html');
  return collapseText(doc.body ?? doc.documentElement, maxChars);
}
