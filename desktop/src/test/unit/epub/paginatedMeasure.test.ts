import { describe, expect, it } from 'vitest';
import {
  buildReaderOverrideCss,
  type ReaderCssOptions,
} from '$lib/features/reader/viewer-epub/readerCssBuilder';

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
  margins: { top: 2.5, bottom: 2.5, left: 2, right: 3 },
};

const MEASURE = /padding-left: max\(2rem, calc\(\(1120px - 65ch\) \/ 2\)\) !important;/;

describe('paginated mode keeps the prose measure', () => {
  it('pads a wide page so a line stays ~65 characters, never below the margins', () => {
    const css = buildReaderOverrideCss({ ...base, pagination: { width: 1120, height: 583 } });
    expect(css).toMatch(MEASURE);
    expect(css).toContain('padding-right: max(3rem, calc((1120px - 65ch) / 2)) !important;');
    // Emitted after the pagination block, so it wins over its body rule.
    expect(css.lastIndexOf('padding-left: max(')).toBeGreaterThan(css.indexOf('column-width'));
  });

  it('leaves scrolling mode and image documents alone', () => {
    expect(buildReaderOverrideCss(base)).not.toMatch(/65ch\) \/ 2/);
    const plate = buildReaderOverrideCss({
      ...base,
      layout: 'image-only',
      pagination: { width: 1120, height: 583 },
    });
    expect(plate).not.toMatch(/65ch\) \/ 2/);
  });
});
