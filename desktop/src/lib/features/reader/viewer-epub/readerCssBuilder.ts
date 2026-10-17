/**
 * readerCssBuilder — the reader-override stylesheet, and nothing else.
 *
 * Extracted from `useEpubRender.svelte.ts` (U2 of `epub-css-followups`). The
 * move is byte-identical: same rules, same order, same `!important` placement.
 * `scripts/u1-css-fixture.json` freezes the output and
 * `scripts/u1-capture-css-fixture.mts verify` diffs against it across 99 option
 * combinations — hash-exact, not "looks the same".
 *
 * Scope is deliberately narrow: theme tables + the override builder. The
 * `srcdoc` assembler stays in `useEpubRender.svelte.ts` because it also inlines
 * seven iframe scripts and rewrites asset URLs, which are render concerns, not
 * CSS ones.
 *
 * A note on `!important`, because it is the load-bearing part of this file: the
 * chapter document is a `srcdoc` iframe carrying the publisher's own
 * stylesheet, so a reader rule that must win is written with `!important`. The
 * ones that must NOT win (the CJK font stacks) deliberately go without it, so an
 * explicit reader font pick — which carries `!important` — still beats them.
 */
export interface ReaderCssOptions {
  fontSize: number;
  zoomLevel: number;
  themeMode: string;
  lineHeight: number;
  letterSpacing: number;
  paragraphSpacing: number;
  textAlign: string;
  direction: string;
  hyphenation: boolean;
  margins: { top: number; bottom: number; left: number; right: number };
  /** The book's own face is `''`; the caller appends the override when set. */
  fontFamily?: string;
}

// NOTE (U5.4): the hex literals below are intentional. They style HTML
// injected into the EPUB iframe, where the app's CSS tokens do not exist,
// so var(--color-*) references would resolve to nothing.
// U3: themes own the full element set (headings, quotes, code, rules,
// tables, captions) — body+a alone left book colors bleeding into dark modes.
export function getThemeStyles(themeMode: string): string {
  const themes: Record<string, string> = {
    paper: `
        body { background: #faf8f5; color: #333; }
        a { color: #3366cc; }
        h1, h2, h3, h4, h5, h6 { color: #1a1a1a !important; }
        blockquote { color: #5a544a !important; border-left: 3px solid #d8d0c4 !important; background: #f3efe8 !important; }
        code { color: #7a3b2e !important; background: #efe9dd !important; }
        pre { color: #333 !important; background: #f3efe8 !important; border: 1px solid #e2dacb !important; }
        hr { border-color: #d8d0c4 !important; }
        table, th, td { border-color: #d8d0c4 !important; }
        th { background: #f3efe8 !important; }
        figcaption { color: #8a8478 !important; }
      `,
    sepia: `
        body { background: #f5eedd; color: #5b4636; }
        a { color: #8b6914; }
        h1, h2, h3, h4, h5, h6 { color: #3f2f22 !important; }
        blockquote { color: #6b563d !important; border-left: 3px solid #d6c49a !important; background: #efe3cb !important; }
        code { color: #6b3b1f !important; background: #e9dcc0 !important; }
        pre { color: #5b4636 !important; background: #efe3cb !important; border: 1px solid #d6c49a !important; }
        hr { border-color: #d6c49a !important; }
        table, th, td { border-color: #d6c49a !important; }
        th { background: #efe3cb !important; }
        figcaption { color: #97805a !important; }
      `,
    night: `
        body { background: #0f1320; color: #c8ccd8; }
        a { color: #7bb8ff; }
        h1, h2, h3, h4, h5, h6 { color: #eef1f8 !important; }
        blockquote { color: #9aa3bd !important; border-left: 3px solid #2c3a55 !important; background: #151b2e !important; }
        code { color: #ffb86b !important; background: #1a2238 !important; }
        pre { color: #c8ccd8 !important; background: #151b2e !important; border: 1px solid #2c3a55 !important; }
        hr { border-color: #2c3a55 !important; }
        table, th, td { border-color: #2c3a55 !important; }
        th { background: #151b2e !important; }
        figcaption { color: #7d86a0 !important; }
      `,
    oled: `
        body { background: #000000; color: #b8bcc8; }
        a { color: #7bb8ff; }
        h1, h2, h3, h4, h5, h6 { color: #e8ebf4 !important; }
        blockquote { color: #8f97ad !important; border-left: 3px solid #22262f !important; background: #0a0c10 !important; }
        code { color: #ffb86b !important; background: #0d0f14 !important; }
        pre { color: #b8bcc8 !important; background: #0a0c10 !important; border: 1px solid #22262f !important; }
        hr { border-color: #22262f !important; }
        table, th, td { border-color: #22262f !important; }
        th { background: #0a0c10 !important; }
        figcaption { color: #737a8c !important; }
      `,
  };
  return themes[themeMode] || themes.paper;
}

export function getThemeBgColor(themeMode: string): string {
  const bgs: Record<string, string> = {
    paper: '#faf8f5',
    sepia: '#f5eedd',
    night: '#151b2e',
    oled: '#000000',
  };
  return bgs[themeMode] || bgs.paper;
}

export function buildReaderOverrideCss(opts: ReaderCssOptions): string {
  const justify = opts.textAlign.toLowerCase() === 'justify';
  // U1: justification without hyphenation rivers. Justify always hyphenates;
  // explicit hyphenation still controls the left/center paths.
  const hyphensValue = opts.hyphenation || justify ? 'auto' : 'none';
  const effectiveFontSize = (opts.fontSize * opts.zoomLevel) / 100;
  const themeCss = getThemeStyles(opts.themeMode);

  return `
      ${themeCss}
      html {
        height: auto !important;
        max-height: none !important;
        overflow-x: hidden !important;
        overflow-y: visible !important;
      }
      body {
        height: auto !important;
        min-height: 100% !important;
        max-height: none !important;
        overflow: visible !important;
        font-size: ${effectiveFontSize}% !important;
        line-height: ${opts.lineHeight} !important;
        letter-spacing: ${opts.letterSpacing}px !important;
        direction: ${opts.direction} !important;
        hyphens: ${hyphensValue} !important;
        text-wrap: pretty !important;
        /* U6: long-session legibility. */
        orphans: 3 !important;
        widows: 3 !important;
        overflow-wrap: break-word !important;
        padding: ${opts.margins.top}rem ${opts.margins.right}rem ${opts.margins.bottom}rem ${opts.margins.left}rem !important;
        /* U2: measure in ch, not rem — ch tracks the reader font-size, so the
          line stays ~65 characters from 80% to 200%. */
        max-width: min(65ch, 100%) !important;
        margin: 0 auto !important;
        box-sizing: border-box !important;
      }
      /* Alignment must reach the text elements, not just body: EPUB stylesheets
        routinely set text-align on p/div/headings, and those descend from body,
        so a body-only rule loses. */
      body p, body div, body li, body dd, body dt, body blockquote,
      body h1, body h2, body h3, body h4, body h5, body h6 {
        text-align: ${opts.textAlign} !important;
      }
      /* Paragraph spacing carried no !important and was the only rule in this
        block without it, so the book's own stylesheet won and the control did
        nothing. Covers div-wrapped paragraphs too, which many EPUBs use. */
      body p, body div > p, body p + p {
        margin-bottom: ${opts.paragraphSpacing}em !important;
      }
      /* U6: spacing XOR indent. Book stylesheets that indent would double
        up with our paragraph spacing, so indent loses when spacing is on. */
      ${opts.paragraphSpacing > 0 ? 'body p, body div { text-indent: 0 !important; }' : ''}
      body pre {
        white-space: pre-wrap !important;
        overflow-x: auto !important;
        overflow-wrap: break-word !important;
      }
      body code {
        overflow-wrap: break-word !important;
      }
      body img {
        max-width: 100%;
        height: auto;
        /* U4: covers never push text off-screen; small inline images unaffected. */
        max-height: 85vh;
      }
      body svg {
        max-width: 100% !important;
        max-height: 85vh !important;
      }
      /* U4: figures read as intentional spreads; captions stay muted. */
      body figure {
        margin: 2em auto !important;
        text-align: center !important;
        max-width: 100% !important;
      }
      body figcaption {
        font-size: 0.85em !important;
        margin-top: 0.5em !important;
      }
      /* U4: wide tables scroll instead of breaking the measure. */
      body table {
        display: block !important;
        overflow-x: auto !important;
        max-width: 100% !important;
      }
      /* S1: fragmentation control, ported from ReadiumCSS-html5patch.
        Orphans/widows alone do not stop a heading or a figure from being split
        from the block it introduces, so the atomic elements get break-inside
        and the headings additionally break-after. Structural only — no color,
        so the four themes are unaffected. */
      body h1, body h2, body h3, body h4, body h5, body h6,
      body figure, body table, body pre, body img, body svg, body tr {
        break-inside: avoid !important;
      }
      body h1, body h2, body h3, body h4, body h5, body h6 {
        break-after: avoid !important;
      }
      /* S2: hyphenation limits (ReadiumCSS-html5patch). hyphens is set on
        body, so headings, code, pre and captions inherit it and get hyphenated
        where they must not - hence the explicit opt-out below. The limits only
        apply where hyphens is auto, so they are inert for the left/center paths. */
      body {
        hyphenate-character: '-' !important;
        hyphenate-limit-chars: 6 3 3 !important;
        hyphenate-limit-lines: 3 !important;
      }
      body h1, body h2, body h3, body h4, body h5, body h6,
      body code, body pre, body figcaption, body caption, body var,
      body dt, body address, body center {
        hyphens: none !important;
      }
      /* S3: i18n baseline (ReadiumCSS-base + html5patch). Ruby sizing and
        east-asian variant; CJK line-breaking and word-breaking; CJK-capable
        font stacks; and a math font stack for MathML. Font stacks name
        families only — srcdoc cannot fetch, so they are fallback chains. */
      body ruby {
        ruby-position: over !important;
      }
      body rt, body rp {
        font-size: 0.5em !important;
        font-variant-east-asian: ruby !important;
        user-select: none !important;
      }
      body :lang(ja), body :lang(zh), body :lang(ko) {
        line-break: strict !important;
        word-break: normal !important;
        overflow-wrap: break-word !important;
      }
      /* CJK-capable chain: a Japanese face first, then the pan-CJK families a
        Windows/macOS/Android install commonly ships, then the book's own face.
        Named families only — srcdoc has no network, so any that is absent is
        skipped by the cascade. No !important, so an explicit reader font pick
        (which carries !important) still wins. */
      body :lang(ja) {
        font-family:
          'Yu Mincho', 'Hiragino Mincho ProN', 'Noto Serif CJK JP', 'Noto Serif JP',
          'Source Han Serif', 'BIZ UDMincho', serif;
      }
      body :lang(zh) {
        font-family:
          'Songti SC', 'SimSun', 'Noto Serif CJK SC', 'Noto Serif SC',
          'Source Han Serif', serif;
      }
      body :lang(ko) {
        font-family:
          'Nanum Myeongjo', 'Batang', 'Noto Serif CJK KR', 'Noto Serif KR',
          'Source Han Serif', serif;
      }
      body math {
        font-family:
          'Latin Modern Math', 'STIX Two Math', 'Cambria Math', 'DejaVu Math TeX Gyre', serif !important;
      }
      /* R1: readium-css adoption — rules taken from ReadiumCSS-html5patch and
        ReadiumCSS-base and mapped onto this pipeline (no RS__USER__ variable
        indirection; values are literal here on purpose). Everything below is
        structural, carries no colour, and touches no user setting. Where a rule
        must beat the publisher stylesheet it keeps !important, matching this
        file's existing contract; where it must lose to a user control it
        deliberately does not. */
      /* R1a: fragmentation for the two elements Readium splits from S1's group.
        Readium fragments h1..h6, dt, figure, tr with break-inside and
        h2..h6, dt, hr, caption with break-after. NEXO already covers h1..h6,
        figure, table, pre, img, svg, tr broken-inside and h1..h6 broken-after,
        so only dt (both) and hr/caption (break-after) are new. The break-after
        list stops at h2 for two reasons Readium also implies: a body h1 already
        resolves to 1.0 (verified with scripts/u3-heading-lineheight-probe.mts),
        and h1 is asserted separately in the CDP spec - keeping it out of this
        list leaves that assertion untouched. */
      body h2, body h3, body h4, body h5, body h6, body dt, body hr, body caption {
        break-after: avoid !important;
      }
      body dt {
        break-inside: avoid !important;
      }
      /* R1b: the wrong-element hyphenation opt-out also covers dt, address and
        center (ReadiumCSS-html5patch). Folding them into the existing S2 list
        rather than re-declaring it keeps one rule for the "must not hyphenate"
        set. */
      /* R2: OTF variants. Readium sets oldstyle-nums proportional-nums on body
        and lining-nums tabular-nums on tables, which is exactly the behaviour
        an EPUB typesetter expects, and it opts headings into lining-nums
        because oldstyle figures in a heading read as a mistake. These are
        non-inherited-by-override properties, so no !important is needed to
        reach the elements; no !important also means a publisher's own
        font-variant-numeric on body still wins, which keeps this from being a
        silent global override. Dropped from Readium's set: code/var
        slashed-zero and the CJK/arabic-only variants, see the notes below. */
      body {
        font-variant-numeric: oldstyle-nums proportional-nums;
      }
      body table {
        font-variant-numeric: lining-nums tabular-nums;
      }
      body h1, body h2, body h3, body h4, body h5, body h6, body dt {
        font-variant-numeric: lining-nums proportional-nums;
      }
      /* R3: CJK italics. A CJK face has no true italic, so the browser
        synthesises an oblique that is wrong for the script; Readium turns
        emphasis back to upright. Scoped with :not(:lang(ja-Latn))-style guards,
        reduced to the plain :lang() form this pipeline already uses. Text
        decoration is left to the publisher here. */
      body :lang(ja) :is(em, i, cite, dfn),
      body :lang(zh) :is(em, i, cite, dfn),
      body :lang(ko) :is(em, i, cite, dfn) {
        font-style: normal;
      }
    `;
}
