/**
 * Three reader-side decisions about chapter images, kept out of the three large
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
 *  2. **Zoom destination.** The reader has one zoom value (`epub.fontSize`).
 *     On prose it scales type; on an image-only document there is no type, so
 *     it must scale the plates instead. {@link imageZoomScale} is that rule.
 *
 *  3. **Clickability.** Whether a click landed on an image worth enlarging.
 *
 * All are pure functions over a document (and a number) so the rules are
 * testable in jsdom: no layout engine is involved anywhere in this module.
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

/**
 * How much an image-only page's plates grow for a given zoom value, as a
 * multiplier of `1` (no change).
 *
 * The reader owns exactly ONE zoom number, `epub.fontSize`, clamped to 50..300
 * by `clampZoomPercent`. On a text page that number scales type. On a document
 * that is only images there is no type, so the number has to reach the pictures
 * or the control is dead — measured live on the two-map spread in
 * *Prisioneros de la geografía* (spine 8): at 100%, 125% and 150% every image
 * stayed 504x754 / 501x754 while `body` font-size went 16px -> 20px -> 24px.
 *
 * It is derived here rather than stored beside `epub.fontSize` on purpose. A
 * second persisted value could disagree with the first (the header, the wheel,
 * the keys and the menu all write `epub.fontSize`), and the reader would then
 * have two competing "current zooms". The derivation makes disagreement
 * unrepresentable.
 *
 * The clamp lives with the value that is persisted (`clampZoomPercent`); this
 * function only translates it. A non-finite or non-positive input yields `1`, so
 * a broken settings row cannot collapse a plate to zero width.
 */
export function imageZoomScale(zoomPercent: number): number {
  if (!Number.isFinite(zoomPercent) || zoomPercent <= 0) return 1;
  return zoomPercent / 100;
}

/**
 * How wide a plate renders, as a `%` of the image's OWN natural width, or
 * `null` for "leave it at its natural size".
 *
 * `null` is exactly 100%: the default view must render a plate exactly as it did
 * before zoom reached the pictures (measured: `width: 100%` in the stylesheet
 * stretched the 504px maps to 1123px, because that percentage is of the
 * container).
 *
 * ## Why the stylesheet cannot state this as a percentage
 *
 * A stylesheet `width: N%` resolves against the image's CONTAINING BLOCK, not
 * against the image. On the measured spread (*Prisioneros de la geografía*,
 * spine 8) each plate sits in a publisher `div` 972px wide inside a 1100px body,
 * and the images are 504px natural, so a percentage is off by 972/504 — and by a
 * different factor in every book, since each book wraps its plates its own way.
 * Measured live on this very fix:
 *
 *     width: 75%  -> 729px rendered  (the reader asked for 378px: LARGER than natural)
 *     width: 125% -> 1215px rendered (the reader asked for 630px)
 *
 * A percentage `max-width` cannot rescue it: it is an upper bound, so
 * `min(75%, 200%)` resolves to `200%` and the plate stays at 504px (measured).
 * Growth has to be a stated `width`; shrink has to be a stated `width`; and the
 * only length that means "this plate" rather than "this container" is the live
 * image's own `naturalWidth`.
 *
 * So this function answers the DECISION (what multiple of its own pixels) and
 * `useEpubRender` applies it as a pixel width per image, in the chapter it
 * already rewrites on every zoom change. Keeping the rule here keeps it a pure,
 * testable function rather than a stray constant in the render pipeline.
 *
 * A non-finite or non-positive input yields `null` (no scaling), so a broken
 * settings row cannot collapse a plate to zero width.
 */
export function imageZoomPercentOfNatural(zoomPercent: number): number | null {
  if (!Number.isFinite(zoomPercent) || zoomPercent <= 0) return null;
  return zoomPercent === 100 ? null : zoomPercent;
}

/**
 * Root marker recording the zoom a chapter document was last sized for.
 *
 * `document.documentElement` carries it so the pass is provable in the DOM: a
 * value of `100` means "sized to natural, no inline width written", any other
 * number is the multiple applied. It is also what makes the teardown scoped —
 * see {@link clearImagePlateZoomFromDocument}.
 */
export const IMAGE_PLATE_ZOOM_ATTR = 'data-nexo-plate-zoom';

/** Marks an element whose inline `width` this pipeline wrote. */
const PLATE_WIDTH_MARKER = 'data-nexo-plate-zoom-width';

/** Holds the publisher's own inline `width` while ours is in effect. */
const PLATE_ORIGIN_WIDTH_ATTR = 'data-nexo-plate-origin-width';

/** Holds the publisher's own inline `width` priority alongside it. */
const PLATE_ORIGIN_PRIORITY_ATTR = 'data-nexo-plate-origin-priority';

/**
 * The width an SVG plate contributes when it has no intrinsic pixel size.
 *
 * `<svg>` elements (as opposed to SVG files loaded through `<img>`) expose no
 * `naturalWidth`, and an inline book chart legitimately has none. Reading the
 * author's own `width` attribute — or the rendered box before anything was
 * written — is the only honest natural size available. Returns 0 when neither
 * exists, which {@link applyImagePlateZoomToDocument} treats as "not measurable
 * yet, leave it alone".
 */
function naturalPlateWidth(plate: HTMLElement): number {
  const img = plate as HTMLImageElement;
  if (typeof img.naturalWidth === 'number' && img.naturalWidth > 0) return img.naturalWidth;
  const attr = Number.parseInt(plate.getAttribute('width') ?? '', 10);
  if (Number.isFinite(attr) && attr > 0) return attr;
  return 0;
}

/**
 * Writes the reader's zoom onto an image-only chapter's plates, as a pixel width
 * per image, and returns true when the document was treated as a plate page.
 *
 * `zoomPercent` is the reader's zoom in percent (`75`, `125`), i.e.
 * `fontSize * zoomLevel / 100` — the same two numbers the prose path folds into
 * its `font-size`. At exactly 100 no width is written at all: the plate keeps its
 * natural size, which is the render an image-only page had before zoom reached
 * the pictures.
 *
 * A document with prose is left untouched and has any previous width removed, so
 * a chapter that gains text (the pipeline re-renders a chapter as its assets
 * settle) cannot keep a plate sizing it no longer earned.
 */
export function applyImagePlateZoomToDocument(doc: Document | null, zoomPercent: number): boolean {
  if (!doc?.documentElement) return false;
  if (!isImageOnlyDocument(doc)) {
    clearImagePlateZoomFromDocument(doc);
    return false;
  }
  const percent = imageZoomPercentOfNatural(zoomPercent);
  // The marker goes on BEFORE the per-image loop: it records that this pass ran
  // for this document, so a later teardown knows the inline widths are ours.
  doc.documentElement.setAttribute(IMAGE_PLATE_ZOOM_ATTR, String(percent ?? 100));
  for (const plate of doc.querySelectorAll<HTMLElement>('img, svg')) {
    if (percent === null) {
      clearPlateWidth(plate);
      continue;
    }
    const natural = naturalPlateWidth(plate);
    // Not measurable yet (an image mid-load): leave it alone. The chapter's own
    // load path re-runs this pass once the pixels exist, rather than freezing a
    // 0px-wide plate into the document.
    if (natural <= 0) continue;
    writePlateWidth(plate, `${Math.round((natural * percent) / 100)}px`);
  }
  return true;
}

/**
 * Writes our pixel width and remembers what was there before.
 *
 * A publisher may set its own inline `width`; clobbering it would leave the book
 * permanently mis-sized after the reader returns to 100%, because the teardown
 * would have nothing to restore. The previous value is parked in data
 * attributes on the first write and put back on the teardown, so `100%` really
 * is the book's own layout again rather than "no width at all".
 */
function writePlateWidth(plate: HTMLElement, width: string): void {
  if (plate.getAttribute(PLATE_WIDTH_MARKER) !== '1') {
    const previous = plate.style.getPropertyValue('width');
    const priority = plate.style.getPropertyPriority('width');
    if (previous) plate.setAttribute(PLATE_ORIGIN_WIDTH_ATTR, previous);
    if (priority) plate.setAttribute(PLATE_ORIGIN_PRIORITY_ATTR, priority);
    plate.setAttribute(PLATE_WIDTH_MARKER, '1');
  }
  plate.style.setProperty('width', width, 'important');
}

/**
 * Removes the per-image widths this pipeline wrote.
 *
 * Scoped by marker rather than "clear every image": a publisher's own inline
 * width must survive, and a plain `style.removeProperty('width')` on every image
 * would silently delete it.
 */
export function clearImagePlateZoomFromDocument(doc: Document | null): void {
  const root = doc?.documentElement;
  if (!root?.hasAttribute(IMAGE_PLATE_ZOOM_ATTR)) return;
  root.removeAttribute(IMAGE_PLATE_ZOOM_ATTR);
  for (const plate of doc!.querySelectorAll<HTMLElement>('img, svg')) clearPlateWidth(plate);
}

function clearPlateWidth(plate: HTMLElement): void {
  if (plate.getAttribute(PLATE_WIDTH_MARKER) !== '1') return;
  const previous = plate.getAttribute(PLATE_ORIGIN_WIDTH_ATTR);
  const priority = plate.getAttribute(PLATE_ORIGIN_PRIORITY_ATTR);
  if (previous) plate.style.setProperty('width', previous, priority ?? '');
  else plate.style.removeProperty('width');
  plate.removeAttribute(PLATE_WIDTH_MARKER);
  plate.removeAttribute(PLATE_ORIGIN_WIDTH_ATTR);
  plate.removeAttribute(PLATE_ORIGIN_PRIORITY_ATTR);
}
