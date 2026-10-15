/**
 * Copy the reader faces from @fontsource into `public/fonts/`.
 *
 * Why a copy instead of a Vite import: the chapter iframe is a `srcdoc`
 * document. Importing a font with `?url` rewrites it to
 * `asset.localhost/@fs/<abs path>`, and Tauri's asset protocol is scoped to
 * `$APPDATA` only, so every face came back 403. Files under `public/` are
 * served from the app origin and work the same in dev and production.
 *
 * Run after any `@fontsource` version bump so the served files stay in step
 * with the packages:  bun run sync:reader-fonts
 */
import { copyFileSync, existsSync, mkdirSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const here = dirname(fileURLToPath(import.meta.url));
const repoRoot = resolve(here, '../..');
const publicFonts = resolve(here, '../public/fonts');

/** [package path under node_modules, file name] for every served face. */
const FACES = [
  ['@fontsource-variable/newsreader/files/newsreader-latin-wght-normal.woff2'],
  ['@fontsource-variable/literata/files/literata-latin-wght-normal.woff2'],
  ['@fontsource/manrope/files/manrope-latin-400-normal.woff2'],
  ['@fontsource/manrope/files/manrope-latin-500-normal.woff2'],
  ['@fontsource/manrope/files/manrope-latin-600-normal.woff2'],
  ['@fontsource/manrope/files/manrope-latin-700-normal.woff2'],
  ['@fontsource/atkinson-hyperlegible/files/atkinson-hyperlegible-latin-400-normal.woff2'],
  ['@fontsource/atkinson-hyperlegible/files/atkinson-hyperlegible-latin-700-normal.woff2'],
];

mkdirSync(publicFonts, { recursive: true });

let copied = 0;
for (const [rel] of FACES) {
  // Bun hoists to the workspace root, so the package may not sit under
  // desktop/node_modules even though the dependency is declared there.
  const candidates = [
    resolve(here, '../node_modules', rel),
    resolve(repoRoot, 'node_modules', rel),
  ];
  const source = candidates.find((p) => existsSync(p));
  if (!source) {
    console.error(`missing source font: ${rel}`);
    process.exitCode = 1;
    continue;
  }
  copyFileSync(source, resolve(publicFonts, rel.split('/').pop()));
  copied += 1;
}

console.log(`reader fonts synced: ${copied}/${FACES.length}`);
