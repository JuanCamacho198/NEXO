import { describe, expect, it } from 'vitest';
import { IMAGE_PAIR_ATTR, markChapterImages } from '$lib/features/reader/viewer-epub/epubImageFit';
import {
  buildReaderOverrideCss,
  type ReaderCssOptions,
} from '$lib/features/reader/viewer-epub/readerCssBuilder';

function parse(html: string): Document {
  return new DOMParser().parseFromString(`<html><body>${html}</body></html>`, 'text/html');
}

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

describe('a two-image map spread', () => {
  it('marks the block holding the pair, as in the measured book', () => {
    const doc = parse(
      '<div class="body"><div class="image"><img src="a.jpg"/></div><p class="white1"> </p><div class="image"><img src="b.jpg"/></div></div>',
    );
    markChapterImages(doc);
    expect(doc.querySelector('.body')?.hasAttribute(IMAGE_PAIR_ATTR)).toBe(true);
  });

  it('never makes body the row, and leaves three images stacked', () => {
    const atBody = parse('<img src="a.jpg"/><img src="b.jpg"/>');
    markChapterImages(atBody);
    expect(atBody.body.hasAttribute(IMAGE_PAIR_ATTR)).toBe(false);

    const three = parse('<div><img src="a.jpg"/><img src="b.jpg"/><img src="c.jpg"/></div>');
    markChapterImages(three);
    expect(three.querySelector(`[${IMAGE_PAIR_ATTR}]`)).toBeNull();
  });

  it('sits side by side only on a paginated plate, never in scrolling mode', () => {
    const plate = buildReaderOverrideCss({
      ...base,
      layout: 'image-only',
      pagination: { width: 1120, height: 571 },
    });
    expect(plate).toContain(`[${IMAGE_PAIR_ATTR}] {`);
    expect(plate).toContain('flex-direction: row !important;');
    // No block margin on a full-height plate: it is what left a page blank.
    expect(plate).toMatch(/img\[data-nexo-image-stack\] \{\s*margin-block: 0 !important;/);

    expect(buildReaderOverrideCss({ ...base, layout: 'image-only' })).not.toContain(
      IMAGE_PAIR_ATTR,
    );
    expect(
      buildReaderOverrideCss({ ...base, pagination: { width: 1120, height: 571 } }),
    ).not.toContain(IMAGE_PAIR_ATTR);
  });
});
