import { describe, expect, it } from 'vitest';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { contrastRatio, readThemeColors, relativeLuminance } from '$lib/shared/styles/contrast';

/**
 * The chrome bars paint a dedicated plane (`--color-reader-chrome`) instead of
 * the app background. These assertions read `tokens.css` from disk and resolve
 * every pair from the ACTUAL token values — only the WCAG floors are fixed
 * here, never the ratios. Changing a token is what makes this suite fail.
 *
 * Honest boundary: jsdom does not apply stylesheets, so this suite cannot prove
 * that `bg-(--color-reader-chrome)` resolves to a painted colour. It proves the
 * token-to-ratio half; the element-to-token half is a class-string assertion
 * (ReaderChromeAccessibility.test.ts); the composition of the two is a manual
 * checkpoint in the running app.
 */
const css = readFileSync(join(__dirname, '../../../lib/shared/styles/tokens.css'), 'utf8');
const dark = readThemeColors(css, 'dark');
const light = readThemeColors(css, 'light');

const AA_TEXT = 4.5;

describe('contrast helpers', () => {
  it('computes relative luminance per WCAG 2.1', () => {
    expect(relativeLuminance('#000000')).toBeCloseTo(0, 5);
    expect(relativeLuminance('#ffffff')).toBeCloseTo(1, 5);
  });

  it('returns the WCAG reference ratios', () => {
    expect(contrastRatio('#000000', '#ffffff')).toBeCloseTo(21, 2);
    expect(contrastRatio('#ffffff', '#ffffff')).toBeCloseTo(1, 5);
    expect(contrastRatio('#ffffff', '#000000')).toBeCloseTo(21, 2);
  });

  it('accepts shorthand hex and a missing hash', () => {
    expect(contrastRatio('#fff', '000')).toBeCloseTo(21, 2);
  });

  it('throws on a value it cannot parse', () => {
    expect(() => relativeLuminance('rgba(0, 0, 0, 0.5)')).toThrow();
  });
});

describe('readThemeColors', () => {
  it('reads the dark and light blocks separately', () => {
    expect(dark['--color-reader-chrome']).toBe('#0f1b2e');
    expect(light['--color-reader-chrome']).toBe('#f5f8fc');
    expect(dark['--color-background']).toBe('#08111f');
    expect(light['--color-background']).toBe('#eef2f7');
  });

  it('does not leak values across the theme blocks', () => {
    expect(dark['--color-text-auxiliary']).toBe('#94a3b8');
    expect(light['--color-text-auxiliary']).toBe('#6b7d9a');
  });
});

describe('reader chrome planes', () => {
  it('separates the dark chrome from the app background', () => {
    expect(
      contrastRatio(dark['--color-reader-chrome'], dark['--color-background']),
    ).toBeGreaterThan(1.0);
  });

  it('separates the dark chrome from the sidebar rail', () => {
    expect(contrastRatio(dark['--color-reader-chrome'], '#0b1626')).toBeGreaterThan(1.0);
  });

  it('clears AA for dark chrome foreground text', () => {
    expect(
      contrastRatio(dark['--color-text-inverse'], dark['--color-reader-chrome']),
    ).toBeGreaterThanOrEqual(AA_TEXT);
    expect(
      contrastRatio(dark['--color-text-auxiliary'], dark['--color-reader-chrome']),
    ).toBeGreaterThanOrEqual(AA_TEXT);
  });

  it('clears AA for the light chrome labels', () => {
    expect(
      contrastRatio(light['--color-text-muted'], light['--color-reader-chrome']),
    ).toBeGreaterThanOrEqual(AA_TEXT);
  });

  it('separates the light chrome from the app background', () => {
    expect(
      contrastRatio(light['--color-reader-chrome'], light['--color-background']),
    ).toBeGreaterThan(1.0);
  });
});

describe('light chrome auxiliary-text guard', () => {
  /**
   * NEGATIVE GUARD — intent, not dead code.
   *
   * `--color-text-auxiliary` (#6b7d9a) is BELOW the AA floor on the light
   * chrome plane. That is why the chrome bars were switched to
   * `--color-text-muted` (#4a6888). This assertion exists so that a future
   * revert of that fix — or a silent retune of the light auxiliary token back
   * down — fails LOUDLY here instead of shipping a regression.
   *
   * Do not "repair" this assertion by lowering the floor or swapping the pair:
   * the failing contrast is the point.
   */
  it('fails AA — the reason the chrome labels use --color-text-muted', () => {
    const ratio = contrastRatio(light['--color-text-auxiliary'], light['--color-reader-chrome']);
    expect(ratio).toBeLessThan(AA_TEXT);
  });
});
