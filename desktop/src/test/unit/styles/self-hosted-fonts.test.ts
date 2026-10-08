import { describe, expect, it } from 'vitest';
import { existsSync, readFileSync } from 'node:fs';
import { join } from 'node:path';

/**
 * Guard (odd/desktop-prod-csp-fixes U3): fonts are bundled via @fontsource,
 * never fetched from Google Fonts — the prod CSP blocks fonts.googleapis.com
 * in style-src/font-src, so any remote <link> silently falls back to system
 * fonts. index.html must carry zero remote font references and styles.css
 * must import the exact weights of the old Google URL (Manrope 400/500/600/
 * 700/800 + Newsreader Variable with the opsz axis).
 */
const MANROPE_WEIGHTS = [400, 500, 600, 700, 800];

describe('self-hosted fonts guard', () => {
  it('index.html references no remote font host', () => {
    const html = readFileSync(join(__dirname, '../../../../index.html'), 'utf8');
    expect(html).not.toContain('fonts.googleapis.com');
    expect(html).not.toContain('fonts.gstatic.com');
  });

  it('styles.css imports the exact Manrope weights plus Newsreader Variable', () => {
    const css = readFileSync(join(__dirname, '../../../styles.css'), 'utf8');
    for (const weight of MANROPE_WEIGHTS) {
      expect(css).toContain(`@fontsource/manrope/${weight}.css`);
    }
    expect(css).toContain('@fontsource-variable/newsreader/');
    expect(css).not.toContain('fonts.googleapis.com');
  });

  it('every imported fontsource css file exists on disk', () => {
    const css = readFileSync(join(__dirname, '../../../styles.css'), 'utf8');
    const imports = [...css.matchAll(/@import '(@[^']+\.css)'/g)].map((match) => match[1]);
    expect(imports.length).toBeGreaterThan(0);
    for (const specifier of imports) {
      const [scope, ...rest] = specifier.split('/');
      const diskPath = join(__dirname, '../../../../../node_modules', scope, ...rest);
      expect(existsSync(diskPath), specifier).toBe(true);
    }
  });

  it('font tokens resolve to the bundled family names', () => {
    const tokens = readFileSync(join(__dirname, '../../../lib/shared/styles/tokens.css'), 'utf8');
    expect(tokens).toContain("'Manrope'");
    // @fontsource-variable/newsreader registers 'Newsreader Variable'.
    expect(tokens).toContain("'Newsreader Variable'");
  });
});
