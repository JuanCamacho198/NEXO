/**
 * Self-hosted reader faces, re-declared for the chapter iframe.
 *
 * The reader renders each chapter inside its own `srcdoc` document, which does
 * not inherit the app's `@font-face` set — so a `font-family: "Literata"` there
 * resolves to nothing and the browser silently falls back to serif. That is
 * exactly why picking a face used to "look like it changes but never change".
 *
 * The files live in `public/fonts/` and are served from the app origin, so the
 * iframe can load them offline. They are NOT imported through Vite's `?url`:
 * that rewrites to `asset.localhost/@fs/<abs path>`, and the Tauri asset
 * protocol is scoped to `$APPDATA` only, so every font came back 403. Serving
 * from `public/` keeps them under `'self'`, identical in dev and production.
 *
 * The families here must stay in sync with `FONT_FAMILY_PRESETS`;
 * `readerFontFaces.test.ts` guards that coupling.
 */

/** One `@font-face` block: the family name, its file and the weight it covers. */
type ReaderFontFace = {
  family: string;
  file: string;
  weight: string;
};

const READER_FONT_FACES: readonly ReaderFontFace[] = [
  { family: 'Newsreader', file: 'newsreader-latin-wght-normal.woff2', weight: '200 800' },
  { family: 'Literata', file: 'literata-latin-wght-normal.woff2', weight: '200 900' },
  { family: 'Manrope', file: 'manrope-latin-400-normal.woff2', weight: '400' },
  { family: 'Manrope', file: 'manrope-latin-500-normal.woff2', weight: '500' },
  { family: 'Manrope', file: 'manrope-latin-600-normal.woff2', weight: '600' },
  { family: 'Manrope', file: 'manrope-latin-700-normal.woff2', weight: '700' },
  {
    family: 'Atkinson Hyperlegible',
    file: 'atkinson-hyperlegible-latin-400-normal.woff2',
    weight: '400',
  },
  {
    family: 'Atkinson Hyperlegible',
    file: 'atkinson-hyperlegible-latin-700-normal.woff2',
    weight: '700',
  },
];

/** Public path prefix every face is served from, relative to the app origin. */
export const READER_FONT_PUBLIC_DIR = './fonts';

/**
 * Absolute URL for a public asset, resolved against the app origin.
 *
 * The chapter iframe is `srcdoc`, so a relative `./fonts/x.woff2` there would
 * resolve against `about:srcdoc` and fail. The faces need the real origin.
 */
function fontUrl(file: string): string {
  const base = typeof window === 'undefined' ? 'http://localhost/' : window.location.href;
  return new URL(`${READER_FONT_PUBLIC_DIR}/${file}`, base).href;
}

/**
 * `@font-face` CSS for the chapter iframe.
 *
 * `font-display: swap` keeps the first paint immediate instead of blocking the
 * chapter on a font fetch.
 */
export function buildReaderFontFaceCss(): string {
  return READER_FONT_FACES.map(
    (face) => `
      @font-face {
        font-family: '${face.family}';
        font-style: normal;
        font-weight: ${face.weight};
        font-display: swap;
        src: url('${fontUrl(face.file)}') format('woff2');
      }`,
  ).join('\n');
}

/** Family names that actually have a face injected into the iframe. */
export const SELF_HOSTED_READER_FAMILIES: readonly string[] = [
  ...new Set(READER_FONT_FACES.map((f) => f.family)),
];
