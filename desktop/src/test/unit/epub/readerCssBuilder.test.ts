/**
 * `readerCssBuilder` owns the reader-override stylesheet (U2 of
 * `epub-css-followups`). Two things are proved here:
 *
 *  1. **The extraction is a move.** `buildReaderOverrideCss` is compared,
 *     byte for byte, against the output the pre-extraction builder produced.
 *     The frozen fixture is the matrix captured from the original source by
 *     `scripts/u1-capture-css-fixture.mts` (`scripts/u1-css-fixture.json`,
 *     99 option combinations). That script is the *same* instrument used during
 *     the refactor; keeping it in the repo means any future edit that changes
 *     the stylesheet has to be an explicit, reviewed update, never an accident.
 *
 *  2. **The readium-css adoption (U3) is present and structured.** Rules ported
 *     from ReadiumCSS-html5patch / ReadiumCSS-base are asserted by their
 *     resolved declarations.
 *
 * HONESTY — what this file cannot prove: jsdom has no layout engine, so nothing
 * here shows a rule *renders*. It asserts the stylesheet text. Whether the rules
 * take effect in a real chapter is measured against the running app in
 * `e2e/tauri/reader.spec.ts` (CDP), which reads computed styles inside the
 * chapter iframe. Both are required; neither substitutes for the other.
 *
 * The fixture deliberately stores full CSS plus a SHA-256 per case rather than
 * only the hash: a hash alone fails without saying *what* changed.
 */
import { describe, expect, it } from 'vitest';
import { createHash } from 'node:crypto';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import {
  buildReaderOverrideCss,
  getThemeBgColor,
  getThemeStyles,
  type ReaderCssOptions,
} from '$lib/features/reader/viewer-epub/readerCssBuilder';
import { buildReaderOverrideCss as viaRenderModule } from '$lib/features/reader/viewer-epub/useEpubRender.svelte';

type Capture = {
  buildReaderOverrideCss: Record<string, { css: string; sha256: string }>;
  getThemeStyles: Record<string, string>;
  getThemeBgColor: Record<string, string>;
};

const fixture = JSON.parse(
  readFileSync(join(process.cwd(), 'scripts/u1-css-fixture.json'), 'utf8'),
) as Capture;

/**
 * The same matrix `scripts/u1-capture-css-fixture.mts` uses to build the
 * fixture. It is duplicated rather than imported because the script is a `.mts`
 * one-shot run through bun, not part of the vitest graph.
 */
function cases(): Array<{ name: string; opts: ReaderCssOptions }> {
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
  const out: Array<{ name: string; opts: ReaderCssOptions }> = [];
  for (const themeMode of ['paper', 'sepia', 'night', 'oled']) {
    for (const textAlign of ['left', 'center', 'right', 'justify']) {
      for (const hyphenation of [false, true]) {
        for (const paragraphSpacing of [0, 1, 2]) {
          out.push({
            name: `${themeMode}-${textAlign}-hy${hyphenation ? 1 : 0}-ps${paragraphSpacing}`,
            opts: { ...base, themeMode, textAlign, hyphenation, paragraphSpacing },
          });
        }
      }
    }
  }
  out.push({ name: 'zoom-300-rgb', opts: { ...base, zoomLevel: 300 } });
  out.push({
    name: 'custom-font-rtl-letterSpacing',
    opts: { ...base, fontFamily: 'Literata', direction: 'rtl', letterSpacing: 3, fontSize: 120 },
  });
  out.push({
    name: 'asymmetric-margins-unknown-theme',
    opts: {
      ...base,
      themeMode: 'does-not-exist',
      margins: { top: 1, bottom: 4, left: 0, right: 6 },
    },
  });
  return out;
}

const sha256 = (text: string): string => createHash('sha256').update(text, 'utf8').digest('hex');

/**
 * Parses a stylesheet with a real CSS engine (jsdom's) and returns the style
 * rules, so a test can assert the PARSED structure rather than the text.
 *
 * This exists because the two can disagree: a comment containing `*` `/`
 * terminates early and the parser drops the rule after it, while the emitted
 * string still reads correctly. A text-only assertion cannot see that.
 */
function parseCssRules(css: string): CSSStyleRule[] {
  const style = document.createElement('style');
  style.textContent = css;
  document.head.appendChild(style);
  const rules: CSSStyleRule[] = [];
  const sheet = style.sheet;
  if (sheet) {
    for (const rule of Array.from(sheet.cssRules)) {
      rules.push(rule as CSSStyleRule);
    }
  }
  style.remove();
  return rules;
}

describe('readerCssBuilder — extraction parity (U2)', () => {
  describe('the builder reproduces the frozen pre-extraction output byte for byte', () => {
    const all = cases();

    it('covers the whole branch matrix (99 cases)', () => {
      // Guards the guard: an empty or truncated fixture would make the loop
      // below vacuously green.
      expect(all).toHaveLength(99);
      expect(Object.keys(fixture.buildReaderOverrideCss)).toHaveLength(99);
    });

    it.each(all)('$name', ({ name, opts }) => {
      const expected = fixture.buildReaderOverrideCss[name];
      expect(expected, `case "${name}" missing from the fixture`).toBeDefined();

      const actual = buildReaderOverrideCss(opts);

      // The hash first: on failure it names the case without dumping 4kB of CSS.
      expect(sha256(actual), `case "${name}" changed (sha256)`).toBe(expected.sha256);
      // Then the full text, so a diff shows the offending line.
      expect(actual, `case "${name}" changed (text)`).toBe(expected.css);
    });
  });

  it('theme tables are unchanged', () => {
    for (const [theme, css] of Object.entries(fixture.getThemeStyles)) {
      expect(getThemeStyles(theme), `getThemeStyles(${theme})`).toBe(css);
    }
    for (const [theme, bg] of Object.entries(fixture.getThemeBgColor)) {
      expect(getThemeBgColor(theme), `getThemeBgColor(${theme})`).toBe(bg);
    }
  });

  it('useEpubRender re-exports the same function (no second copy to drift)', () => {
    // The barrel and `useEpubZoomTheme` import through `useEpubRender`; if the
    // re-export ever pointed at a stale copy, this catches it.
    const opts = cases()[0].opts;
    expect(viaRenderModule(opts)).toBe(buildReaderOverrideCss(opts));
  });
});

describe('readerCssBuilder — readium-css adoption (U3)', () => {
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
  const css = buildReaderOverrideCss(base);

  const block = (match: RegExp): string => {
    const found = css.match(match)?.[0] ?? '';
    expect(found, `no block matched ${match}`).not.toBe('');
    return found;
  };

  it('R1a: dt/hr/caption are fragmented (ReadiumCSS-html5patch)', () => {
    // Scope to the U3 block: the S1 groups earlier in the file also set
    // break-after on h1..h6, and a loose regex would match those instead.
    const adopted = css.slice(css.indexOf('/* R1:'));
    const after = adopted.match(/body h2[^]*?\{[^}]*break-after: avoid[^}]*\}/)?.[0] ?? '';
    expect(after, 'no R1a break-after block found').not.toBe('');
    expect(after).toContain('break-after: avoid !important');
    for (const sel of ['h2', 'h6', 'dt', 'hr', 'caption']) expect(after).toContain(sel);
    // h1 is deliberately absent: the CDP spec asserts it separately.
    expect(after).not.toMatch(/body h1,/);
    // And the S1 block must still carry h1, so the removal is scoped.
    expect(css).toMatch(/body h1, body h2, body h3[^]*?break-after: avoid !important/);

    const dtInside = adopted.match(/body dt\s*\{[^}]*break-inside: avoid[^}]*\}/)?.[0] ?? '';
    expect(dtInside).toContain('break-inside: avoid !important');
  });

  it('every rule in the U3 block actually parses (no comment terminates early)', () => {
    // A REAL bug this class of check exists to catch. An R1 comment contained
    // the two characters `*` `/` (writing out the `--RS__`/`--USER__` variable
    // names), which closed the comment early: the CSS *text* still looked right,
    // so a text assertion passed while the parser silently swallowed the rule
    // that followed. The CDP spec caught it; this asserts the PARSED structure,
    // so the same mistake cannot get through again.
    const parsed = parseCssRules(css);

    const breakAfterSelectors = parsed
      .filter((r) => r.style.getPropertyValue('break-after'))
      .map((r) => r.selectorText);
    expect(
      breakAfterSelectors.some((s) => s.includes('body dt') && s.includes('body hr')),
      `the R1a break-after rule did not survive parsing; break-after selectors seen: ${breakAfterSelectors.join(' | ')}`,
    ).toBe(true);

    const breakInsideSelectors = parsed
      .filter((r) => r.style.getPropertyValue('break-inside'))
      .map((r) => r.selectorText);
    expect(
      breakInsideSelectors.some((s) => s.includes('body dt')),
      'the dt break-inside rule was dropped',
    ).toBe(true);

    // No comment content may leak into the stylesheet as a parsed rule.
    for (const rule of parsed) {
      expect(
        rule.selectorText,
        `stray text parsed as a selector: ${rule.selectorText}`,
      ).not.toContain('--');
    }
  });

  it('R1b widening lives in the S2 opt-out, not in the R block', () => {
    // The dt/address/center widening is applied to the EXISTING S2 hyphenation
    // opt-out list (one rule for the "must not hyphenate" set), so it lands
    // before `/* R1:` in the emitted order. Asserted here so a future move of
    // that rule is a deliberate change, not a silent one.
    const optOut = css.match(/body code[^]*?\{[^}]*hyphens: none[^}]*\}/)?.[0] ?? '';
    expect(optOut).toContain('hyphens: none !important');
    for (const sel of ['dt', 'address', 'center']) expect(optOut).toContain(sel);
    expect(css.indexOf('body dt, body address, body center')).toBeLessThan(css.indexOf('/* R1:'));
  });

  it('R2: OTF numeric variants, with no !important', () => {
    const b = block(/body\s*\{\s*[^}]*font-variant-numeric:\s*oldstyle-nums proportional-nums/);
    // No !important: a publisher's own numeric variant on body still wins.
    expect(b).not.toContain('font-variant-numeric: oldstyle-nums proportional-nums !important');

    const table = block(/body table\s*\{[^}]*font-variant-numeric[^}]*\}/);
    expect(table).toContain('lining-nums tabular-nums');

    const headings = block(
      /body h1,[^]*?\{[^}]*font-variant-numeric:\s*lining-nums proportional-nums/,
    );
    expect(headings).toContain('lining-nums proportional-nums');
  });

  it('R3: CJK emphasis is upright', () => {
    expect(css).toMatch(/body :lang\(ja\) :is\(em, i, cite, dfn\)/);
    expect(css).toMatch(/body :lang\(ko\) :is\(em, i, cite, dfn\)\s*\{[^}]*font-style: normal/);
  });

  it('R4: the adopted rules carry no !important unless they must beat the publisher', () => {
    // Assert on DECLARATIONS, not prose: the R2 comment mentions `!important`
    // in a sentence, and grepping the raw text for the word would fail on the
    // comment while proving nothing about a single rule.
    const stripComments = (text: string): string => text.replace(/\/\*[^]*?\*\//g, '');
    const adopted = stripComments(css.slice(css.indexOf('/* R1:')));

    // Only the fragmentation re-statements (R1a) and the widened hyphenation
    // opt-out (R1b) may carry it; R2 and R3 must not.
    const r2r3 = adopted.slice(adopted.indexOf('font-variant-numeric'));
    expect(r2r3).not.toContain('!important');

    // And the must-win group added by U3 does carry it.
    expect(adopted).toContain('break-after: avoid !important');
    expect(adopted).toContain('break-inside: avoid !important');
  });

  it('adopted rules introduce no colour (themes stay structural-safe)', () => {
    const adopted = css.slice(css.indexOf('/* R1:')).replace(/\/\*[^]*?\*\//g, '');
    expect(adopted).not.toMatch(/#[0-9a-fA-F]{3,6}/);
  });

  it('does not hard-code any user-controlled property', () => {
    // Each of these is a reader setting; a literal in the U3 block would be a
    // regression that silently overrides the control. Comments are stripped so
    // a prose mention is not mistaken for a declaration.
    const adopted = css.slice(css.indexOf('/* R1:')).replace(/\/\*[^]*?\*\//g, '');
    for (const prop of [
      'font-size:',
      'line-height:',
      'letter-spacing:',
      'text-align:',
      'direction:',
      'hyphens: auto',
      'hyphens: none',
      'margin-top:',
      'margin-bottom:',
      'margin-left:',
      'margin-right:',
      'padding:',
      'font-family:',
    ]) {
      expect(adopted, `U3 block must not set ${prop}`).not.toContain(prop);
    }
    // `hyphens: none` IS re-stated by R1b, but inside the S2 group earlier in
    // the file; that exception is asserted by the R1b test above, not here.
  });
});
