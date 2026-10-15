/**
 * Self-hosted reader faces, re-declared for the chapter iframe.
 *
 * The reader renders each chapter inside its own `srcdoc` document, which does
 * not inherit the app's `@font-face` set — so a `font-family: "Literata"` there
 * resolves to nothing and the browser silently falls back to serif. That is
 * exactly why picking a face used to "look like it changes but never change".
 *
 * Vite rewrites each `?url` import to the built asset URL (hashed, served
 * locally by Tauri), so the iframe can load the real file offline. The families
 * here must stay in sync with `FONT_FAMILY_PRESETS`; `readerFonts.test.ts`
 * guards that coupling.
 */
import atkinson400 from '@fontsource/atkinson-hyperlegible/files/atkinson-hyperlegible-latin-400-normal.woff2?url';
import atkinson700 from '@fontsource/atkinson-hyperlegible/files/atkinson-hyperlegible-latin-700-normal.woff2?url';
import literata from '@fontsource-variable/literata/files/literata-latin-wght-normal.woff2?url';
import manrope400 from '@fontsource/manrope/files/manrope-latin-400-normal.woff2?url';
import manrope500 from '@fontsource/manrope/files/manrope-latin-500-normal.woff2?url';
import manrope600 from '@fontsource/manrope/files/manrope-latin-600-normal.woff2?url';
import manrope700 from '@fontsource/manrope/files/manrope-latin-700-normal.woff2?url';
import newsreader from '@fontsource-variable/newsreader/files/newsreader-latin-wght-normal.woff2?url';

/** One `@font-face` block: the family name and the asset URL Vite produced. */
type ReaderFontFace = {
  family: string;
  url: string;
  weight: string;
};

const READER_FONT_FACES: readonly ReaderFontFace[] = [
  { family: 'Newsreader', url: newsreader, weight: '200 800' },
  { family: 'Literata', url: literata, weight: '200 900' },
  { family: 'Manrope', url: manrope400, weight: '400' },
  { family: 'Manrope', url: manrope500, weight: '500' },
  { family: 'Manrope', url: manrope600, weight: '600' },
  { family: 'Manrope', url: manrope700, weight: '700' },
  { family: 'Atkinson Hyperlegible', url: atkinson400, weight: '400' },
  { family: 'Atkinson Hyperlegible', url: atkinson700, weight: '700' },
];

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
        src: url('${face.url}') format('woff2');
      }`,
  ).join('\n');
}

/** Family names that actually have a face injected into the iframe. */
export const SELF_HOSTED_READER_FAMILIES: readonly string[] = [
  ...new Set(READER_FONT_FACES.map((f) => f.family)),
];
