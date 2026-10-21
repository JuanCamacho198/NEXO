/**
 * Two reader-side decisions about chapter images, kept out of the three large
 * reader modules on purpose:
 *
 *  1. **Layout class.** A document made only of images must escape the prose
 *     measure. `readerCssBuilder` centres `body` at `min(65ch, 100%)`, and
 *     `max-width: 100%` on an image therefore resolves against that column, not
 *     the page. Measured on *Prisioneros de la geografía*, `OPS/0005_0001.htm`:
 *     0 characters of text, two `<img>` of roughly 500x750 source pixels laid
 *     out side by side at ~280 px each, so every place name rendered ~6 px tall.
 *     The images get the page; the text keeps its measure.
 *
 *  2. **Clickability.** Whether a click landed on an image worth enlarging.
 *
 * Both are pure functions over a document so the rules are testable in jsdom
 * (no layout engine needed for (2), and (1) is decided from the DOM shape).
 */

/**
 * Natural width, in CSS pixels, below which an image is treated as an ornament
 * rather than content.
 *
 * Why 240: the widest remotely plausible decorative image is a chapter-drop or
 * section ornament — epubcheck does not constrain it, but publishers cap those
 * at one or two text lines, which is under 100 CSS px even at a 200% reader
 * font size. A map, plate or photograph that a reader would ever want to
 * enlarge is authored at a real pixel width: the maps in this book are 500 px
 * wide, and a full-page scan is 1200 px or more. 240 sits in the empty gap
 * between "sits in the sentence" and "carries detail", so it separates the two
 * without a judgement call. It is a floor on CONTENT, not a gate on layout: a
 * small ornament still renders, it just does not open a viewer.
 */
export const CONTENT_IMAGE_MIN_WIDTH = 240;

/** Tags whose text content is not chapter prose (see `hasProseText`). */
const NON_PROSE_TAGS = new Set(['SCRIPT', 'STYLE', 'NOSCRIPT', 'TEMPLATE', 'HEAD', 'TITLE']);

/**
 * Prose length at which a document counts as having text. A lone caption or a
 * page number is not a body of prose; 40 characters is below one short line of
 * a 65ch measure and far below any real paragraph, so a caption never pulls an
 * image-only plate back into the text column.
 */
const PROSE_MIN_CHARS = 40;

/**
 * True when the document renders one or more images and effectively no text.
 *
 * Decided from the DOM rather than from `innerText` because `buildChapterSrcdoc`
 * assembles the srcdoc before layout: at that moment nothing is measurable, and
 * a stylesheet written after a round-trip through the renderer would reflow the
 * chapter the reader is already looking at.
 *
 * `innerText` is deliberately not used: it depends on layout, and this function
 * runs inside `DOMParser` output that is not yet in a document with a viewport.
 */
export function isImageOnlyDocument(doc: Document | null): boolean {
  if (!doc?.body) return false;
  const images = doc.body.querySelectorAll('img, svg image, svg, object[type^="image/"]');
  if (images.length === 0) return false;
  return !hasProseText(doc);
}

/**
 * True when the body holds meaningful text outside the injected script tags.
 *
 * The injected bridge/selection scripts live in `body` and would otherwise
 * count as text, which is what makes a naive `textContent.length > 0` test
 * useless here.
 */
export function hasProseText(doc: Document): boolean {
  if (!doc.body) return false;
  return proseLength(doc.body) >= PROSE_MIN_CHARS;
}

/**
 * The `<img>` a click landed on, if that image is content worth enlarging.
 *
 * Returns null when the click must keep today's behaviour:
 *
 *  - not an image (any other element);
 *  - inside a link — following a link must never be hijacked by the viewer, and
 *    many books wrap a full-page plate in `<a href="...">`;
 *  - narrower than {@link CONTENT_IMAGE_MIN_WIDTH}. An image still loading has
 *    `naturalWidth === 0`; it is treated as an ornament so a click during load
 *    cannot open a 0x0 viewer.
 *
 * The tag check is a string comparison and NOT `instanceof HTMLImageElement`.
 * The click listener lives in the parent document but receives events whose
 * target was created by the chapter iframe's own `DOMParser`, i.e. an object
 * from another realm whose `HTMLImageElement` is a DIFFERENT constructor.
 * Measured live: `target instanceof HTMLImageElement` was false for a real map,
 * so every click fell through to the reader's paging handler and the viewer
 * never opened.
 */
export function contentImageFromEvent(event: MouseEvent): HTMLImageElement | null {
  const target = event.target;
  if (!target || (target as Element).nodeName?.toLowerCase() !== 'img') return null;
  const img = target as HTMLImageElement;
  if (img.closest('a')) return null;
  // `currentSrc` keeps the SVG-degenerate case honest: an `<img>` with no
  // resolvable source also reports naturalWidth 0 and must not qualify.
  if (!(img.naturalWidth >= CONTENT_IMAGE_MIN_WIDTH)) return null;
  return img;
}

/**
 * True when the click is the tail of a text selection rather than a click on
 * the picture.
 *
 * Two cases, both measured as real: dragging a selection across a paragraph
 * that ends next to a figure fires a click on the figure when the button is
 * released there, and a double-click on a word inside a caption selects it and
 * fires a click too. The selection lives in the CHAPTER document, whose window
 * is reachable from the event's view, so it is read from there rather than from
 * the parent (the parent's selection is always empty).
 */
export function isSelectionClick(event: MouseEvent): boolean {
  const view = event.view as Window | null;
  const selection = view?.getSelection?.() ?? null;
  if (!selection) return false;
  // A non-collapsed selection is a drag or a double-click that left text
  // selected; the click that follows it belongs to the text, not the picture.
  return !selection.isCollapsed;
}

/**
 * Images that must stack instead of sharing a row.
 *
 * A sibling image alone and wider than the prose measure cannot sit beside
 * another one without both shrinking; that is exactly the two-map spread. The
 * marker is an attribute rather than `:has()` because the rule has to hold in
 * the chapter document regardless of the engine's `:has()` support, and because
 * the publisher stylesheet routinely sets `float` on a plate, which no
 * descendent-only selector can undo.
 */
export const STACKED_IMAGE_ATTR = 'data-nexo-image-stack';

/**
 * Marks a single image that fills the page on its own: it is the only image in
 * its parent AND its parent holds no other content. Those may grow past the
 * prose measure in every layout mode; images sharing a parent never do, because
 * a row of two inside a text column is still a row of two in the column.
 */
export const SOLO_IMAGE_ATTR = 'data-nexo-image-solo';

/**
 * Stars images for the stylesheet:
 *
 *  - {@link SOLO_IMAGE_ATTR} on an image that is alone in its parent and whose
 *    parent carries no text — a plate or a cover, not an inline ornament;
 *  - {@link STACKED_IMAGE_ATTR} on images that must not share a row.
 *
 * Mutates the parsed document in place, before the stylesheet is written, so
 * the exported srcdoc is already deterministic (no script runs, no reflow).
 */
export function markChapterImages(doc: Document): void {
  if (!doc.body) return;
  const images = Array.from(doc.body.querySelectorAll('img'));
  for (const img of images) {
    if (isSoloPlate(img)) img.setAttribute(SOLO_IMAGE_ATTR, '');
  }

  // The two-map spread in the measured book is:
  //   <div class="image"><img/></div> <p class="white1"> </p> <div class="image"><img/></div>
  // — two images in two DIFFERENT parents separated by a whitespace paragraph.
  // Sibling-only detection (and `img + img`) measured as a no-op there, so the
  // grouping is done on the nearest common ancestor: images that share a block
  // of content are one row and must stack, whatever wrappers sit between them.
  const groups = new Map<Element, HTMLImageElement[]>();
  for (const img of images) {
    const container = imageRowContainer(img, doc.body);
    const bucket = groups.get(container);
    if (bucket) bucket.push(img);
    else groups.set(container, [img]);
  }
  for (const bucket of groups.values()) {
    if (bucket.length < 2) continue;
    for (const img of bucket) img.setAttribute(STACKED_IMAGE_ATTR, '');
  }
}

/**
 * The block that owns an image's row: the nearest ancestor that holds more
 * content than this one image, stopping at `body`.
 *
 * Walking up from `<div><img/></div>` finds the wrapper first (one image, so it
 * is promoted past) and lands on the chapter's `<div class="body">`, which holds
 * both wrappers — that is the row the reader sees.
 */
function imageRowContainer(img: HTMLImageElement, body: HTMLElement): HTMLElement {
  let node: HTMLElement | null = img.parentElement;
  while (node && node !== body) {
    if (node.querySelectorAll('img').length > 1) return node;
    // A block that carries real text ends the search: an image beside words is
    // a figure in the flow, not a member of a plate row.
    if (proseLength(node) >= PROSE_MIN_CHARS) return node;
    node = node.parentElement;
  }
  return body;
}

/**
 * An image that occupies its parent alone and has no sibling content, i.e. a
 * page plate rather than an ornament inside a sentence.
 *
 * Uses children rather than `:only-child` because `<p><img/></p>` is a plate in
 * one of the two common conventions and `<img/><figcaption/>` is not.
 */
function isSoloPlate(img: HTMLImageElement): boolean {
  const parent = img.parentElement;
  if (!parent || parent === img.ownerDocument.body) return false;
  if (parent.querySelectorAll('img').length > 1) return false;
  return proseLength(parent) === 0;
}

/** Collapsed text length of an element, ignoring whitespace and scripts. */
function proseLength(root: Element): number {
  let chars = 0;
  const doc = root.ownerDocument;
  if (!doc) return 0;
  const walker = doc.createTreeWalker(root, NodeFilter.SHOW_TEXT);
  for (let node = walker.nextNode(); node; node = walker.nextNode()) {
    const parent = node.parentElement;
    if (!parent) continue;
    if (NON_PROSE_TAGS.has(parent.tagName)) continue;
    if (parent.closest('script, style, noscript, template')) continue;
    chars += (node.nodeValue ?? '').replace(/\s+/g, '').length;
    if (chars >= PROSE_MIN_CHARS) return chars;
  }
  return chars;
}
