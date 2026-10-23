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
import type { PageBox } from '$lib/features/reader/viewer-epub/readerPagination';
import { SOLO_IMAGE_ATTR, STACKED_IMAGE_ATTR } from '$lib/features/reader/viewer-epub/epubImageFit';

export type { PageBox };
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
  /**
   * Paginated mode. When set, the chapter is laid out as columns instead of a
   * single tall scroll. The values are the measured page box in CSS pixels, so
   * the caller must re-render the stylesheet when the box changes.
   *
   * Absent (or `null`) is scrolling mode, and its output is byte-identical to
   * the pre-pagination builder — the scroll path must not move.
   */
  pagination?: PaginationCssOptions | null;
  /**
   * The chapter document holds images and no prose (see `epubImageFit.ts`).
   *
   * `image-only` drops the prose measure and the viewport-height cap so the
   * plates get the page. `plate` is the same release of the measure for a
   * document that is mostly text but carries a full-page figure; the cap stays,
   * because there the image shares a scroll with text.
   *
   * Absent means `prose`, whose output is byte-identical to the pre-image-fit
   * builder — a book with no images must render exactly as before.
   */
  layout?: 'prose' | 'image-only' | 'plate';
}

/** The measured page box, in CSS pixels, that a column must occupy. */
export type PaginationCssOptions = PageBox;

/**
 * Pagination stylesheet.
 *
 * The model is CSS multicolumn on the chapter's root: `column-width` equal to
 * the page box, `column-gap: 0`, and `column-fill: auto` so columns pack in
 * source order rather than being balanced into a shorter stack. The root then
 * becomes the horizontally overflowing column container, exactly one page per
 * step.
 *
 * Two deliberate choices, both measured against the real app on 2026-10-17:
 *
 *  1. **Pixels, not viewport units.** `100vh`/`100vw` are useless here. The
 *     chapter frame is a `srcdoc` iframe that the viewer sizes to its content,
 *     so inside the frame `100vh` resolves to the *chapter's* height (measured:
 *     `window.innerHeight` 25292 for a 25292px chapter) and a single giant
 *     column is produced instead of pages. The page box is therefore measured in
 *     the parent and passed in as pixels.
 *  2. **The height is bounded on both the root and the body.** An unbounded
 *     column container is one column however many columns are requested. With
 *     the box pinned to the page height, the same chapter measured
 *     `scrollWidth` 28600 against `clientWidth` 1100 — exactly 26 pages.
 *
 * `overflow-x: auto` + `overflow-y: hidden` moves the reader's paging axis to
 * the inline axis while keeping the container scrollable, so the existing
 * `scroll` listener on the chapter still fires and still drives location
 * emission; only the axis it reads changes.
 */
export function buildPaginationCss(opts: PaginationCssOptions): string {
  const w = Math.max(1, Math.round(opts.width));
  const h = Math.max(1, Math.round(opts.height));
  return `
      html {
        height: ${h}px !important;
        max-height: ${h}px !important;
        overflow-x: auto !important;
        overflow-y: hidden !important;
        column-width: ${w}px !important;
        column-gap: 0 !important;
        column-fill: auto !important;
        /* A page turn is a single offset assignment and must land exactly one
           page box away. Native smooth scrolling animates toward the target and
           the browser keeps adjusting after the assignment, which measured as
           turns landing on non-multiples of the page width (offsets 2795 and
           14870 against a 1110px box). Pinning the behaviour to auto keeps the
           step exact, and it is also what makes a turn feel instant rather than
           animated. */
        scroll-behavior: auto !important;
      }
      body {
        height: ${h}px !important;
        max-height: ${h}px !important;
        min-height: 0 !important;
        /* Scrolling mode centres the measure with max-width + margin; a column
           must fill the page box instead, or the text would be a narrow ribbon
           inside a wide column. */
        max-width: none !important;
        margin: 0 !important;
        overflow: visible !important;
        box-sizing: border-box !important;
      }
      /* The scrollbar itself must not consume a page's width: with a visible
         vertical scrollbar a page would measure narrower than the column and
         the page step would drift out of phase. */
      html {
        scrollbar-width: none;
      }
      html::-webkit-scrollbar {
        display: none;
      }
    `;
}

/**
 * The block height a paginated page has for its content, as a CSS length.
 *
 * One expression, shared by every rule that has to fit inside a page: `max()`
 * keeps it valid on a box shorter than the body's own padding (a very small
 * window), where a plain `calc` would resolve negative and the browser would
 * drop the declaration — which is the clipping these rules exist to prevent.
 * Stated in rem, the unit the padding is written in, so a book that changes the
 * root font-size still resolves to the same box.
 */
function paginatedContentCeiling(opts: ReaderCssOptions): string | null {
  const pagination = opts.pagination;
  if (!pagination) return null;
  const height = Math.max(1, Math.round(pagination.height));
  const top = Number.isFinite(opts.margins.top) ? Math.max(0, opts.margins.top) : 0;
  const bottom = Number.isFinite(opts.margins.bottom) ? Math.max(0, opts.margins.bottom) : 0;
  // The plate case gets the whole page because `buildImageOnlyCss` zeroes the
  // body's vertical padding; the prose case has to subtract what body reserves.
  return opts.layout === 'image-only' || (top === 0 && bottom === 0)
    ? `${height}px`
    : `max(1px, calc(${height}px - ${top}rem - ${bottom}rem))`;
}

/**
 * A ceiling on media height, for paginated mode only.
 *
 * ## The defect this answers
 *
 * In paginated mode the chapter is a stack of columns of exactly the page box's
 * height, and the root's vertical overflow is `hidden` (see
 * `buildPaginationCss`). A block that cannot be fragmented — an `<img>` is one,
 * it carries `break-inside: avoid` — and that is TALLER than the box therefore
 * overflows the column and is simply cut off: there is no vertical scroll in
 * this mode to reach the rest of it.
 *
 * Measured on *Prisioneros de la geografía* at a 1110x583 box: the two-map
 * spread of every chapter holds 507x754 and 505x754 plates, so the root reported
 * `scrollHeight` 754 against a 583 box — the bottom 171px of both maps were
 * unreachable — and the closing page's five plates pushed its last line 20px
 * below the box. 14 of the book's 43 documents clipped content this way.
 *
 * The release that causes it is deliberate: `buildImageOnlyCss` sets
 * `max-height: none` so a plate can be zoomed past the viewport, which is right
 * in scrolling mode (the chapter scrolls) and wrong in a column (it does not).
 * So this block restores a cap, sized to the page, only when pagination is on.
 *
 * `max-height` alone is enough: with `height: auto` the browser preserves the
 * aspect ratio and shrinks the inline size to match (measured: 507x754 became
 * 392x583, and the root's `scrollHeight` became the box's 583).
 */
function buildPaginatedMediaCeiling(opts: ReaderCssOptions): string {
  const ceiling = paginatedContentCeiling(opts);
  if (ceiling === null) return '';
  // A capped plate still sat in a line box, and the leading below its baseline
  // pushed the wrapper 8px past the page (measured on this book's cover: the
  // image landed at exactly 583 and its `div` measured 591). A plate is a block
  // of its own here — the image-only layout exists precisely because the
  // document is plates and no prose — so it stacks instead of sitting on a line.
  const plateDisplay = opts.layout === 'image-only' ? 'display: block !important;' : '';
  return `
      body img, body svg {
        max-height: ${ceiling} !important;
        ${plateDisplay}
      }
    `;
}

/**
 * The page-scrolls-within-itself fallback for a table that cannot fit, in
 * paginated mode only.
 *
 * ## The defect this answers
 *
 * A table has no line-breaking: its inline size is the width of its widest
 * cell content, and `break-inside: avoid` (S1, above) keeps its rows on one
 * column. Two publisher pages of *Prisioneros de la geografía* are a single
 * table each — spine 2 (`portadilla.htm`, 1203px wide) and spine 42
 * (`tematica_01.htm`, 977px) — inside a 583px page box, and each is taller
 * than the page as well. The generic `body table { display: block;
 * overflow-x: auto }` rule already gives the inline axis a scroller, but with
 * no height bound the block simply grows to its full height (620px) and the
 * column clips the rest: `html { overflow-y: hidden }` leaves the reader no
 * way to reach it.
 *
 * Bounding the block to the page turns the table into its own scroller, so
 * "that page scrolls" instead of being cut. The bound is deliberately the same
 * expression the media ceiling uses: a table that FITS never reaches it, so
 * no ordinary page gains a scrollbar, and the column geometry that pagination
 * depends on is untouched — the block still occupies one column.
 *
 * Scaling the page down was rejected: it shrinks the text the reader was
 * given, and these pages are the publisher's fixed-size artwork.
 */
function buildPaginatedTableFallback(opts: ReaderCssOptions): string {
  const ceiling = paginatedContentCeiling(opts);
  if (ceiling === null) return '';
  return `
      body table {
        max-height: ${ceiling} !important;
        overflow: auto !important;
        /* A table that scrolls must LOOK like it can. These are publisher
           colours, not app tokens: this sheet is injected into the chapter
           iframe, where the app's palette does not exist. A mid grey reads on
           both the light and the dark reading themes. */
        scrollbar-width: thin !important;
        scrollbar-color: rgba(128, 128, 128, 0.45) transparent !important;
      }
    `;
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

/**
 * The reader's scrollbar thumb, per reading theme.
 *
 * The reading theme is NOT the app theme: a reader on `night` with the app in
 * light mode would otherwise get the app's dark-blue thumb painted on a
 * near-black surface and lose the only continuous position indicator the
 * scrolling mode has. The values are written as `rgba` because the parent
 * stylesheet paints them onto the reader's own surface with
 * `scrollbar-color`, where a token reference would resolve against the app's
 * palette instead. Opacity keeps the thumb quiet enough to read as chrome.
 */
export function getThemeScrollbarColor(themeMode: string): string {
  const thumbs: Record<string, string> = {
    paper: 'rgba(51, 51, 51, 0.35)',
    sepia: 'rgba(91, 70, 54, 0.4)',
    night: 'rgba(200, 204, 216, 0.35)',
    oled: 'rgba(184, 188, 200, 0.35)',
  };
  return thumbs[themeMode] || thumbs.paper;
}

export function buildReaderOverrideCss(opts: ReaderCssOptions): string {
  const justify = opts.textAlign.toLowerCase() === 'justify';
  // U1: justification without hyphenation rivers. Justify always hyphenates;
  // explicit hyphenation still controls the left/center paths.
  const hyphensValue = opts.hyphenation || justify ? 'auto' : 'none';
  const effectiveFontSize = (opts.fontSize * opts.zoomLevel) / 100;
  const themeCss = getThemeStyles(opts.themeMode);
  // Pagination wins over the scroll rules below by being emitted last: the
  // scroll path sets `height:auto`, `overflow-y:visible` and a centred
  // `max-width`, and every one of those must lose to the column box.
  const paginationCss = opts.pagination ? buildPaginationCss(opts.pagination) : '';
  // Emitted LAST so it wins the cascade over both the scroll and the pagination
  // blocks above: an image-only document is a plate either way, and both blocks
  // centre `body` at the prose measure.
  const imageOnlyCss = opts.layout === 'image-only' ? buildImageOnlyCss() : '';
  // After `imageOnlyCss` on purpose: that block releases the viewport-height cap
  // (`max-height: none`) so a plate can be zoomed, which in a column would let a
  // plate grow taller than the page it is printed on. See the function.
  const paginatedMediaCss = buildPaginatedMediaCeiling(opts);
  // Last of the paginated blocks: it has to win over the generic `body table`
  // rule above (`overflow-x: auto` with no height bound), which is what left a
  // table taller than the page with its bottom unreachable.
  const paginatedTableCss = buildPaginatedTableFallback(opts);

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
        /* Never upscaled past a sane multiple of the file's own pixels. The old
           rule had max-width: 100% alone, which a browser resolves against the
           PROSE measure (min(65ch, 100%)) — so an image was never wider than
           the text column. 200% is the high-density headroom: at DPR 2 a 500px
           source is meant to print 250 CSS px, and 2x lets it fill a page
           without turning a 500px source into a 1400px smear. max-height keeps
           covers from pushing text off-screen. */
        max-width: min(100%, 200%);
        width: auto;
        height: auto;
        max-height: 85vh;
      }
      body svg {
        max-width: 100% !important;
        max-height: 85vh !important;
      }
      /* An image-only document (no prose, only plates) must not be laid out
         inside the prose measure: Prisioneros de la geografía's
         OPS/0005_0001.htm holds two 500x750 maps that rendered at ~280px each,
         which made every place name ~6px tall. Such a chapter drops the column
         and unreserves the viewport-height cap, because 85vh of a scroll
         container clipped a plate that should be read top to bottom. */
      ${imageOnlyCss}
      /* A plate alone in its block grows past the measure in every layout mode.
         Images sharing a parent never do — a row of two inside a text column is
         still a row of two. Colour is untouched, so the four themes hold. */
      body img[${SOLO_IMAGE_ATTR}] {
        max-width: min(100%, 200%);
        height: auto;
      }
      /* Consecutive content images stack instead of splitting the measure
         between them (the two-map spread again). The images may sit in
         different parents, so no single container can own the layout — hence a
         per-image display:block rather than a flex column. */
      body img[${STACKED_IMAGE_ATTR}] {
        display: block;
        max-width: min(100%, 200%);
        height: auto;
        margin-inline: auto;
        margin-block: 1em;
        float: none !important;
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
${paginationCss}${imageOnlyCss}${paginatedMediaCss}${paginatedTableCss}    `;
}

/**
 * The image-only override: last in the cascade, so it beats both the scroll and
 * the pagination body rules.
 *
 * `max-width: none` on `body` is the whole layout fix — `body img { max-width:
 * min(100%, 200%) }` resolves its percentage against the body's own width, and
 * it is the centred `min(65ch, 100%)` body that halved each map. Releasing the
 * column releases the images with it.
 *
 * `max-height: none` only for this layout. The 85vh cap exists so a cover in a
 * text chapter cannot push the prose off-screen; in a document that is ALL
 * image there is no prose to push, and the cap was clipping a plate to a
 * letterbox the reader could not scroll past.
 *
 * ## The zoom is NOT a percentage here, and this rule says so
 *
 * A plate's zoom is a multiple of the image's own `naturalWidth`, which no
 * stylesheet percentage can express: `width: 75%` is 75% of the publisher's
 * wrapper (measured 729px for a 504px plate the reader had asked to see at
 * 378px). `useEpubRender` therefore writes a pixel width per image, and
 * {@link buildImagePlateZoomCss} emits the rules that let it win — this block
 * deliberately states no `width` at all, only the ceiling release.
 */
function buildImageOnlyCss(): string {
  return `
      body {
        max-width: none !important;
        padding-top: 0 !important;
        padding-bottom: 0 !important;
      }
      /* A zoomed plate grows past the viewport. The chapter root hides
         overflow-x for the prose path (the measure is centred and must never
         produce a sideways scrollbar), so a wide plate would be CLIPPED to the
         viewport and the reader could reach only its left edge — trading an
         unreadably small plate for an unreachable large one. Releasing it here
         makes the chapter document itself scroll sideways: measured at 300%
         zoom, scrollLeft reaches 2182 of a 3433px document, so the right edge of
         a 3369px plate really comes into view. Only this layout is affected, and
         at 100% zoom the content fits, so no scrollbar appears. */
      html {
        overflow-x: auto !important;
      }
      ${buildImagePlateZoomCss()}
      body img[${STACKED_IMAGE_ATTR}] {
        display: block;
        margin-inline: auto;
        margin-block: 0.5em;
        float: none !important;
      }
    `;
}

/**
 * The rules that let a per-image pixel `width` be the plate's zoom.
 *
 * The generic block above (`body img { max-width: min(100%, 200%) }`) is a
 * CEILING, and on an image-only page the ceiling is the wrapper width — so it
 * would clamp a plate the reader zoomed IN, and could only ever `max-width` a
 * plate they zoomed OUT, which an upper bound cannot do (measured: `min(75%,
 * 200%)` resolves to 200%, plate unchanged at 504px). Both problems disappear
 * once the width is stated in the plate's own pixels, which is what
 * `useEpubRender` does with `imageZoomPercentOfNatural`, so this block removes
 * every percentage bound that could fight it and leaves height flowing from the
 * stated width.
 *
 * `max-width: none` is what makes an upscale reachable past the wrapper; without
 * it a 300% plate is silently cut back to the publisher's column, which is the
 * original "the zoom control is dead" symptom in a different disguise.
 */
export function buildImagePlateZoomCss(): string {
  return `
      body img, body svg {
        max-width: none !important;
        max-height: none !important;
        height: auto !important;
      }
      /* The solo/stacked markers only carry layout. Their \`!important\` width
         ceiling would otherwise win over the inline pixel width and clamp a
         zoomed plate, so they are re-stated here without one. */
      body img[${SOLO_IMAGE_ATTR}], body img[${STACKED_IMAGE_ATTR}] {
        max-width: none !important;
        height: auto !important;
      }
    `;
}
