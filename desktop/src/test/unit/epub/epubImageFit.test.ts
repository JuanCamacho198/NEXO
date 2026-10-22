/**
 * Image readability in the EPUB reader.
 *
 * Four behaviours, each of which was a measured defect on the same book
 * (*Prisioneros de la geografía*, `OPS/0005_0001.htm`: two 500x750 maps, 0
 * characters of text, ~280px each, place names ~6px tall):
 *
 *  1. an image-only document escapes the prose measure;
 *  2. an image is never upscaled past a sane multiple of its own pixels;
 *  3. consecutive content images stack;
 *  4. a click on a content image opens the viewer, but a link, a selection or an
 *     ornament keeps its old behaviour.
 *
 * jsdom has no layout engine, so (1)-(3) assert the stylesheet and the markers
 * it keys on, not rendered pixels. The rendered half is measured against the
 * running app in `e2e/tauri/readerImageSpread.spec.ts`.
 */
import { describe, expect, it } from 'vitest';
import {
  buildReaderOverrideCss,
  type ReaderCssOptions,
} from '$lib/features/reader/viewer-epub/readerCssBuilder';
import {
  CONTENT_IMAGE_MIN_WIDTH,
  IMAGE_PLATE_ZOOM_ATTR,
  SOLO_IMAGE_ATTR,
  STACKED_IMAGE_ATTR,
  applyImagePlateZoomToDocument,
  clearImagePlateZoomFromDocument,
  contentImageFromEvent,
  hasProseText,
  imageZoomPercentOfNatural,
  imageZoomScale,
  isImageOnlyDocument,
  isSelectionClick,
  markChapterImages,
} from '$lib/features/reader/viewer-epub/epubImageFit';

const base: ReaderCssOptions = {
  fontSize: 100,
  zoomLevel: 100,
  themeMode: 'paper',
  lineHeight: 1.8,
  letterSpacing: 0,
  paragraphSpacing: 1,
  textAlign: 'left',
  direction: 'ltr',
  hyphenation: false,
  margins: { top: 2.5, bottom: 2.5, left: 2, right: 2 },
};

/** Parse HTML into a document the way `buildChapterSrcdoc` does. */
function parse(html: string): Document {
  return new DOMParser().parseFromString(`<html><body>${html}</body></html>`, 'text/html');
}

describe('image-only documents get the whole page', () => {
  it('treats a document of plates with no text as image-only', () => {
    const doc = parse('<p><img src="map1.png"/></p><p><img src="map2.png"/></p>');
    expect(hasProseText(doc)).toBe(false);
    expect(isImageOnlyDocument(doc)).toBe(true);
  });

  it('does not count the injected bridge scripts as prose', () => {
    // The real srcdoc carries seven <script> tags in <body>; a naive
    // textContent length test would call every chapter a text chapter.
    const doc = parse(
      '<p><img src="map.png"/></p><script>window.x = "a long string of script text here";</script>',
    );
    expect(isImageOnlyDocument(doc)).toBe(true);
  });

  it('keeps a chapter with a real paragraph in the prose measure', () => {
    const doc = parse(
      '<img src="plate.png"/><p>This is a chapter with enough prose to be a real body of text.</p>',
    );
    expect(isImageOnlyDocument(doc)).toBe(false);
  });

  it('does not treat a lone caption as prose (the plate stays image-only)', () => {
    const doc = parse('<figure><img src="plate.png"/><figcaption>Fig. 1</figcaption></figure>');
    expect(isImageOnlyDocument(doc)).toBe(true);
  });

  it('a document with no images is never image-only', () => {
    expect(isImageOnlyDocument(parse('<p>Only words, no pictures at all.</p>'))).toBe(false);
  });

  it('the default (prose) stylesheet carries no image-only override', () => {
    const css = buildReaderOverrideCss(base);
    // The marker is the released body measure; prose must keep its column.
    expect(css).not.toContain('max-width: none !important');
  });

  it('image-only releases the body measure and the viewport-height cap', () => {
    const css = buildReaderOverrideCss({ ...base, layout: 'image-only' });
    const bodyMaxWidth = css.match(/max-width: none !important/);
    expect(bodyMaxWidth, 'image-only must drop the 65ch measure').toBeTruthy();
    // Without this the plates stay clipped to a letterbox in scroll mode.
    expect(css).toMatch(/body img, body svg \{[^}]*max-height: none !important/);
  });

  it('image-only keeps the height cap off the PROSE path only', () => {
    // The cover rule is load-bearing: a 85vh cap is what stops a cover in a
    // text chapter from pushing the prose off-screen. It must survive there.
    const prose = buildReaderOverrideCss(base);
    expect(prose).toMatch(/body img \{[^}]*max-height: 85vh/);
    const imageOnly = buildReaderOverrideCss({ ...base, layout: 'image-only' });
    const proseBlock = imageOnly.match(/body img \{[^}]*\}/)?.[0] ?? '';
    expect(proseBlock, 'the base cover rule must be unchanged').toContain('max-height: 85vh');
  });
});

describe('an image is never upscaled past a sane multiple of its pixels', () => {
  it('caps the image width at 200% rather than an unbounded 100% of the column', () => {
    const css = buildReaderOverrideCss(base);
    const block = css.match(/body img \{[^}]*\}/)?.[0] ?? '';
    expect(block).toContain('max-width: min(100%, 200%)');
    // The old rule is what resolved against the prose measure and halved each map.
    expect(block).not.toContain('max-width: 100%;');
  });

  it('leaves the intrinsic ratio alone', () => {
    const block = buildReaderOverrideCss(base).match(/body img \{[^}]*\}/)?.[0] ?? '';
    expect(block).toContain('width: auto');
    expect(block).toContain('height: auto');
  });
});

describe('zoom reaches the plates on an image-only page', () => {
  /** An image-only chapter: two plates inside publisher wrappers, no prose. */
  function spreadDoc(naturalWidth = 504): Document {
    const doc = parse(
      `<div class="image"><img id="a" src="a.png"/></div>` +
        `<div class="image"><img id="b" src="b.png"/></div>`,
    );
    markChapterImages(doc);
    // jsdom never loads images, so the natural width the browser would report is
    // stubbed: it is the ONLY length the pixel rule is derived from.
    for (const img of doc.querySelectorAll('img')) {
      Object.defineProperty(img, 'naturalWidth', { value: naturalWidth, configurable: true });
    }
    return doc;
  }

  it('scale is monotonic across every zoom stop the control offers', () => {
    // The same stops `ZOOM_OPTIONS` exposes in the reader's menu.
    const stops = [50, 75, 90, 100, 110, 125, 150, 175, 200, 250, 300];
    let previous = -1;
    for (const stop of stops) {
      const grown = imageZoomScale(stop);
      expect(grown, `zoom ${stop}% did not grow the plate`).toBeGreaterThanOrEqual(previous);
      previous = grown;
    }
    // Exactly 100% states no scale at all: the natural-size render must stay
    // reachable, and it is the one value that must never write a width.
    expect(imageZoomPercentOfNatural(100)).toBeNull();
    // Below and above 100% both scale: a percentage ceiling can never SHRINK a
    // plate, so 75% has no other way to render smaller.
    expect(imageZoomPercentOfNatural(75)).toBe(75);
    expect(imageZoomPercentOfNatural(110)).toBe(110);
  });

  it('scales the plate down below 100% as well as up above it', () => {
    // The measured bug this pins: at 75% and 100% every plate stayed 503/507px.
    const doc = spreadDoc(504);
    const img = doc.getElementById('a')!;

    applyImagePlateZoomToDocument(doc, 75);
    expect(img.style.getPropertyValue('width')).toBe('378px');
    expect(doc.documentElement.getAttribute(IMAGE_PLATE_ZOOM_ATTR)).toBe('75');

    applyImagePlateZoomToDocument(doc, 100);
    expect(
      img.style.getPropertyValue('width'),
      'at 100% the inline width must be REMOVED, not set to the natural size — ' +
        'the default render is the one an image-only page had before zoom existed',
    ).toBe('');
    expect(doc.documentElement.getAttribute(IMAGE_PLATE_ZOOM_ATTR)).toBe('100');

    applyImagePlateZoomToDocument(doc, 125);
    expect(img.style.getPropertyValue('width')).toBe('630px');
    applyImagePlateZoomToDocument(doc, 300);
    expect(img.style.getPropertyValue('width')).toBe('1512px');
  });

  it('sizes from the plate own pixels, not from the container', () => {
    // Measured live, the bug this replaced: a stylesheet `width: 75%` rendered
    // 729px for a 504px plate, because the percentage resolves against the
    // publisher's 972px wrapper. The stated width must be natural * zoom even
    // when the wrapper is unrelated to the image's size.
    const doc = spreadDoc(1200);
    applyImagePlateZoomToDocument(doc, 75);
    expect(doc.getElementById('a')!.style.getPropertyValue('width')).toBe('900px');
  });

  it('leaves a prose chapter alone and clears a stale plate size', () => {
    const doc = spreadDoc(504);
    const img = doc.getElementById('a')!;
    applyImagePlateZoomToDocument(doc, 150);
    expect(img.style.getPropertyValue('width')).toBe('756px');
    // The pipeline re-renders a chapter as its assets settle, and the document
    // on screen is the authority: once it has prose it must stop being sized as
    // a plate.
    doc.body.appendChild(doc.createTextNode('A'.repeat(80)));
    applyImagePlateZoomToDocument(doc, 150);
    expect(img.style.getPropertyValue('width')).toBe('');
    expect(doc.documentElement.hasAttribute(IMAGE_PLATE_ZOOM_ATTR)).toBe(false);
  });

  it('never overwrites a publisher inline width it did not write', () => {
    const doc = spreadDoc(504);
    const img = doc.getElementById('a')!;
    img.style.setProperty('width', '420px');
    applyImagePlateZoomToDocument(doc, 50);
    // Ours wins while the zoom is applied…
    expect(img.style.getPropertyValue('width')).toBe('252px');
    clearImagePlateZoomFromDocument(doc);
    // …and the book's own value comes back, because only our marker is cleared.
    expect(img.style.getPropertyValue('width')).toBe('420px');
  });

  it('skips a plate that has not loaded yet instead of freezing it at 0px', () => {
    const doc = spreadDoc(0);
    applyImagePlateZoomToDocument(doc, 75);
    expect(doc.getElementById('a')!.style.getPropertyValue('width')).toBe('');
    // The root marker still records the attempt, so a load path knows to redo it.
    expect(doc.documentElement.getAttribute(IMAGE_PLATE_ZOOM_ATTR)).toBe('75');
  });

  it('emits a ceiling release rather than a percentage width', () => {
    const imageOnly = buildReaderOverrideCss({ ...base, layout: 'image-only', zoomLevel: 150 });
    // The stylesheet must NOT state a plate width: a percentage there is a
    // percentage of the publisher's wrapper (measured 729px for a 504px plate).
    expect(imageOnly).not.toMatch(/body img, body svg \{[^}]*width: \d+% !important/);
    // It releases every percentage bound that would clamp the pixel width.
    expect(imageOnly).toMatch(/body img, body svg \{[^}]*max-width: none !important/);
    expect(imageOnly).toMatch(/body img\[data-nexo-image-solo\][^}]*max-width: none !important/);
    // Prose is untouched at any zoom — its images keep width:auto.
    const prose = buildReaderOverrideCss({ ...base, zoomLevel: 150 });
    expect(prose).toContain('body img {');
    expect(prose).not.toContain('max-width: none !important;\n        max-height: none');
  });

  it('keeps a zoomed plate horizontally reachable instead of clipping it', () => {
    // The chapter root sets overflow-x: hidden for the prose measure, which
    // would clip a plate wider than the viewport to its left edge.
    const imageOnly = buildReaderOverrideCss({ ...base, layout: 'image-only', zoomLevel: 300 });
    expect(imageOnly).toMatch(/html \{[^}]*overflow-x: auto !important/);
    // Prose keeps the hidden overflow it has always had.
    const proseHtml = buildReaderOverrideCss(base).match(/html \{[^}]*\}/)?.[0] ?? '';
    expect(proseHtml).toContain('overflow-x: hidden');
  });

  it('a book with no images never receives the image-only rules at all', () => {
    const prose = buildReaderOverrideCss({ ...base, zoomLevel: 300 });
    expect(prose).not.toContain(IMAGE_PLATE_ZOOM_ATTR);
    expect(prose).not.toMatch(/html \{[^}]*overflow-x: auto/);
    // And the prose path carries nothing a plate page adds.
    expect(prose).not.toContain('max-height: none !important;\n        height: auto !important;');
  });
});

describe('consecutive content images stack', () => {
  it('marks both members of a pair of images sharing a parent', () => {
    const doc = parse('<p><img src="a.png"/><img src="b.png"/></p>');
    markChapterImages(doc);
    const imgs = Array.from(doc.querySelectorAll('img'));
    expect(imgs).toHaveLength(2);
    for (const img of imgs) expect(img.hasAttribute(STACKED_IMAGE_ATTR)).toBe(true);
  });

  it('a lone image is not marked for stacking', () => {
    const doc = parse('<p><img src="a.png"/></p>');
    markChapterImages(doc);
    expect(doc.querySelector('img')!.hasAttribute(STACKED_IMAGE_ATTR)).toBe(false);
  });

  it('a document with no images is untouched', () => {
    const doc = parse('<p>Words only.</p>');
    const before = doc.body.innerHTML;
    markChapterImages(doc);
    expect(doc.body.innerHTML).toBe(before);
  });

  it('the stacked rule breaks the row and undoes a publisher float', () => {
    const css = buildReaderOverrideCss(base);
    const block = css.match(/body img\[data-nexo-image-stack\] \{[^}]*\}/)?.[0] ?? '';
    expect(block, 'no stacked-image rule emitted').not.toBe('');
    expect(block).toContain('display: block');
    expect(block).toContain('float: none !important');
  });

  it('stars a solo plate so it can outgrow the measure in a text chapter', () => {
    const doc = parse('<figure><img src="plate.png"/></figure>');
    markChapterImages(doc);
    expect(doc.querySelector('img')!.hasAttribute(SOLO_IMAGE_ATTR)).toBe(true);
  });

  it('marks the real book markup: two wrapped images split by a spacer paragraph', () => {
    // Measured markup from *Prisioneros de la geografía*, OPS/0005_0001.htm.
    // Sibling-only detection is a no-op here, which is why the grouping walks
    // to the nearest common block container.
    const doc = parse(
      '<div class="body">' +
        '<div class="image"><img src="image_extract1_1.jpg"/></div>' +
        '<p class="white1"> </p>' +
        '<div class="image"><img src="image_extract1_2.jpg"/></div>' +
        '</div>',
    );
    markChapterImages(doc);
    const imgs = Array.from(doc.querySelectorAll('img'));
    for (const img of imgs) {
      expect(img.hasAttribute(STACKED_IMAGE_ATTR), 'both maps must stack').toBe(true);
      expect(img.hasAttribute(SOLO_IMAGE_ATTR), 'each map is alone in its wrapper').toBe(true);
    }
  });

  it('does not star an image that shares its block with text', () => {
    const doc = parse('<p><img src="inline.png"/> next to words</p>');
    markChapterImages(doc);
    expect(doc.querySelector('img')!.hasAttribute(SOLO_IMAGE_ATTR)).toBe(false);
  });
});

describe('which image a click may enlarge', () => {
  /** A click event whose target is `target`, as the chapter frame would fire it. */
  function clickOn(target: Element): MouseEvent {
    const event = new MouseEvent('click', { bubbles: true, cancelable: true });
    Object.defineProperty(event, 'target', { value: target });
    return event;
  }

  /** jsdom does not implement naturalWidth; set it the way a loaded image has it. */
  function imageWithNaturalWidth(doc: Document, width: number, parent?: string): HTMLImageElement {
    const img = doc.createElement('img');
    if (parent) {
      const wrapper = doc.createElement(parent);
      wrapper.appendChild(img);
      doc.body.appendChild(wrapper);
      if (parent === 'a') wrapper.setAttribute('href', 'chapter2.xhtml');
    } else {
      doc.body.appendChild(img);
    }
    Object.defineProperty(img, 'naturalWidth', { value: width, configurable: true });
    return img;
  }

  it('accepts a content-sized image', () => {
    const doc = parse('');
    const img = imageWithNaturalWidth(doc, 500);
    expect(contentImageFromEvent(clickOn(img))).toBe(img);
  });

  it('rejects an ornament below the content threshold', () => {
    const doc = parse('');
    const img = imageWithNaturalWidth(doc, CONTENT_IMAGE_MIN_WIDTH - 1);
    expect(contentImageFromEvent(clickOn(img))).toBeNull();
  });

  it('accepts an image exactly at the threshold', () => {
    const doc = parse('');
    const img = imageWithNaturalWidth(doc, CONTENT_IMAGE_MIN_WIDTH);
    expect(contentImageFromEvent(clickOn(img))).toBe(img);
  });

  it('rejects an image still loading (naturalWidth 0)', () => {
    const doc = parse('');
    const img = imageWithNaturalWidth(doc, 0);
    expect(contentImageFromEvent(clickOn(img))).toBeNull();
  });

  it('never hijacks a linked image: the link still follows', () => {
    const doc = parse('');
    const img = imageWithNaturalWidth(doc, 1200, 'a');
    expect(contentImageFromEvent(clickOn(img))).toBeNull();
  });

  it('ignores a click that is not on an image', () => {
    const doc = parse('<p id="para">text</p>');
    expect(contentImageFromEvent(clickOn(doc.getElementById('para')!))).toBeNull();
  });

  it('recognises an image from ANOTHER realm (the real chapter iframe)', () => {
    // The regression this guards: the parent's listener receives events whose
    // target was parsed by the chapter iframe, whose HTMLImageElement is a
    // different constructor. `instanceof HTMLImageElement` was false for a real
    // map, so every click fell through to the paging handler.
    //
    // jsdom gives every document the same realm, so a duplicate document cannot
    // reproduce it. The minimal shape that does is a node-like object whose
    // prototype chain never reaches this realm's `HTMLImageElement` — which is
    // exactly what `instanceof` tests and what `nodeName` does not.
    const foreign = Object.create({
      nodeName: 'IMG',
      naturalWidth: 504,
      currentSrc: 'asset://map.jpg',
      src: 'asset://map.jpg',
      alt: '',
      closest: () => null,
    });
    expect(foreign instanceof HTMLImageElement, 'the fixture must be cross-realm').toBe(false);

    const event = new MouseEvent('click', { cancelable: true });
    Object.defineProperty(event, 'target', { value: foreign });
    expect(contentImageFromEvent(event)).toBe(foreign);
  });

  it('rejects a click that is part of an active selection', () => {
    // The selection lives in the chapter document's own window, which this
    // module cannot reach from the parent — so the event's `view` is used. jsdom
    // gives a detached document no working selection API, so the window contract
    // the function actually reads is provided explicitly.
    const view = { getSelection: () => ({ isCollapsed: false }) } as unknown as Window;
    const event = new MouseEvent('click', { cancelable: true });
    Object.defineProperty(event, 'view', { value: view });
    expect(isSelectionClick(event)).toBe(true);
  });

  it('a collapsed selection is an ordinary click', () => {
    const view = { getSelection: () => ({ isCollapsed: true }) } as unknown as Window;
    const event = new MouseEvent('click', { cancelable: true });
    Object.defineProperty(event, 'view', { value: view });
    expect(isSelectionClick(event)).toBe(false);
  });
});

describe('a book with no images renders exactly as before', () => {
  it('a text-only chapter gets no image markers and the prose stylesheet', () => {
    const doc = parse(
      '<h1>Chapter</h1><p>Enough prose to be a real body of chapter text here.</p>',
    );
    const before = doc.body.innerHTML;
    markChapterImages(doc);
    expect(doc.body.innerHTML).toBe(before);
    expect(isImageOnlyDocument(doc)).toBe(false);
    // And the stylesheet it would get is byte-identical to the frozen default.
    expect(buildReaderOverrideCss({ ...base, layout: 'prose' })).toBe(buildReaderOverrideCss(base));
  });
});
