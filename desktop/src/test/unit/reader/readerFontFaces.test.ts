import { existsSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';
import {
  buildReaderFontFaceCss,
  SELF_HOSTED_READER_FAMILIES,
} from '$lib/features/reader/viewer-epub/readerFontFaces';
import { FONT_FAMILY_PRESETS } from '$lib/features/reader/chrome/readerTextPresets';

/**
 * Guards the coupling that made face picks a no-op for so long: the panel may
 * only offer a self-hosted family if a matching `@font-face` actually reaches
 * the chapter iframe.
 *
 * The first version of this table imported the files through Vite's `?url`,
 * which resolves to `asset.localhost/@fs/<abs path>`. Tauri's asset protocol is
 * scoped to `$APPDATA`, so every font came back 403 and the pick still did
 * nothing — the bug this file exists to prevent. The files must therefore live
 * in `public/`, and this test asserts they are really there.
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
      expect(css).toContain('@font-face');
      expect(css).toContain(`font-family: '${family}'`);
    }
    const blocks = css.match(/@font-face/g) ?? [];
    expect(blocks.length).toBeGreaterThanOrEqual(SELF_HOSTED_READER_FAMILIES.length);
    expect(css).toContain('font-display: swap');
  });

  it('serves every face from the app origin, never through the asset protocol', () => {
    const css = buildReaderFontFaceCss();
    // An `@fs/` path or an absolute Windows path is exactly what Tauri 403s.
    expect(css).not.toContain('@fs');
    expect(css).not.toContain('asset.localhost');
    expect(css).not.toContain('node_modules');
    expect(css).not.toContain(':\\\\');
    const urls = css.match(/url\('([^']+)'\)/g) ?? [];
    expect(urls.length).toBeGreaterThanOrEqual(SELF_HOSTED_READER_FAMILIES.length);
    for (const url of urls) {
      // Absolute, so the `srcdoc` iframe resolves against the app origin and
      // not against `about:srcdoc`.
      expect(url).toMatch(/url\('https?:\/\/[^']*\/fonts\/[^']+\.woff2'\)/);
    }
  });

  it('has every declared file present in public/fonts', () => {
    const publicFonts = resolve(process.cwd(), 'public', 'fonts');
    const css = buildReaderFontFaceCss();
    const files = [...css.matchAll(/\/fonts\/([^'"]+\.woff2)/g)].map((m) => m[1]);
    expect(files.length).toBeGreaterThan(0);
    for (const file of files) {
      expect(existsSync(resolve(publicFonts, file!))).toBe(true);
    }
  });
});
