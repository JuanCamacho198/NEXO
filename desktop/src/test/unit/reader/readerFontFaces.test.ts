import { describe, expect, it } from 'vitest';
import {
  buildReaderFontFaceCss,
  SELF_HOSTED_READER_FAMILIES,
} from '$lib/features/reader/viewer-epub/readerFontFaces';
import { FONT_FAMILY_PRESETS } from '$lib/features/reader/chrome/readerTextPresets';

/**
 * Guards the coupling that made face picks a no-op for so long: the panel may
 * only offer a self-hosted family if a matching `@font-face` actually reaches
 * the chapter iframe. Without this, "Literata" is a string the iframe cannot
 * resolve and the browser falls back to serif without telling anyone.
 */
describe('readerFontFaces — the offered faces must exist in the iframe', () => {
  it('declares a face for every self-hosted preset', () => {
    const offered = FONT_FAMILY_PRESETS.filter((p) => p.selfHosted).map((p) => p.value);
    for (const family of offered) {
      expect(SELF_HOSTED_READER_FAMILIES).toContain(family);
    }
  });

  it('does not declare faces nothing offers', () => {
    const offered = new Set(FONT_FAMILY_PRESETS.map((p) => p.value));
    for (const family of SELF_HOSTED_READER_FAMILIES) {
      expect(offered.has(family)).toBe(true);
    }
  });

  it('emits an @font-face block per family, with font-display swap', () => {
    const css = buildReaderFontFaceCss();
    for (const family of SELF_HOSTED_READER_FAMILIES) {
      expect(css).toContain(`@font-face`);
      expect(css).toContain(`font-family: '${family}'`);
    }
    // One block per family declaration, never a bare family name.
    const blocks = css.match(/@font-face/g) ?? [];
    expect(blocks.length).toBeGreaterThanOrEqual(SELF_HOSTED_READER_FAMILIES.length);
    expect(css).toContain('font-display: swap');
  });

  it('hands each face a resolved url string and a woff2 format', () => {
    const css = buildReaderFontFaceCss();
    // Vitest resolves a `?url` import to a path rather than the hashed build
    // asset, so this asserts the shape (a url() with a .woff2 and an explicit
    // format) instead of a build-specific path. `bun run build` is what proves
    // the hashed asset exists; this guards the declaration itself.
    const urls = css.match(/url\('([^']+)'\)/g) ?? [];
    expect(urls.length).toBeGreaterThanOrEqual(SELF_HOSTED_READER_FAMILIES.length);
    for (const url of urls) {
      expect(url).not.toContain('undefined');
      expect(url).toContain('.woff2');
    }
    expect(css).toContain("format('woff2')");
  });
});
