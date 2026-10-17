/**
 * U1 — the U2 extraction's parity instrument.
 *
 * The extraction of `readerCssBuilder.ts` had to be a MOVE: same bytes, same
 * order, same `!important` placement. This script is how that was proved, and it
 * stays in the repo so the same guarantee can be re-checked after any future
 * edit to the stylesheet.
 *
 *   bun run scripts/u1-capture-css-fixture.mts capture   # freeze current output
 *   bun run scripts/u1-capture-css-fixture.mts verify    # diff against the freeze
 *
 * `verify` is the gate: it re-runs the real builder across the whole branch
 * matrix and fails on a single byte of difference. The snapshot it reads is
 * asserted by `src/test/unit/epub/readerCssBuilder.test.ts`, so the stylesheet
 * is under test in CI too, not only when someone remembers to run this.
 *
 * It runs the real source through vite's SSR transform rather than importing it
 * directly because the module graph reaches `.svelte.ts` files (which use
 * `$state`/`$effect`), and plain `node`/`tsx` cannot parse those.
 *
 * ## The U3 caveat, stated honestly
 *
 * The snapshot currently in the repo was captured AFTER U3 adopted the
 * readium-css rules — it is the post-U3 golden, not the pre-U2 one. The
 * pre-U2 → post-U2 move was proved with a snapshot of the original source,
 * confirmed byte-identical, and then legitimately replaced once U3 began
 * editing the stylesheet on purpose. `verify` today therefore guards "U3 did
 * not change anything since it was frozen", not "the extraction was a move";
 * the latter is a fact about the refactor history, recorded here and in the
 * feature document, not something this script can re-derive.
 */
import { createHash } from 'node:crypto';
import { existsSync, mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const scriptDir = dirname(fileURLToPath(import.meta.url));
const projectRoot = join(scriptDir, '..');
const fixturePath = join(scriptDir, 'u1-css-fixture.json');

/**
 * The module under test. `readerCssBuilder.ts` is a plain `.ts` module, so it
 * loads through vite SSR with no `.svelte.ts` in the way; the re-export from
 * `useEpubRender.svelte.ts` is checked separately by the unit test.
 */
const MODULE_PATH = '/src/lib/features/reader/viewer-epub/readerCssBuilder.ts';

type Capture = {
  buildReaderOverrideCss: Record<string, { css: string; sha256: string }>;
  getThemeStyles: Record<string, string>;
  getThemeBgColor: Record<string, string>;
};

/**
 * The matrix that has to survive the move. It exercises the branching inside
 * `buildReaderOverrideCss`: the justify/hyphens paths, both paragraph-spacing
 * branches (the indent reset is conditional), the four themes and a font pick.
 */
function cases(): Array<{ name: string; opts: Record<string, unknown> }> {
  const base = {
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
  const out: Array<{ name: string; opts: Record<string, unknown> }> = [];
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
  // Edge combinations the matrix above cannot reach.
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

function sha256(text: string): string {
  return createHash('sha256').update(text, 'utf8').digest('hex');
}

async function loadModule(): Promise<{
  buildReaderOverrideCss: (opts: Record<string, unknown>) => string;
  getThemeStyles: (theme: string) => string;
  getThemeBgColor: (theme: string) => string;
}> {
  const { createServer } = await import('vite');
  const server = await createServer({
    root: projectRoot,
    logLevel: 'error',
    server: { middlewareMode: true },
    appType: 'custom',
  });
  try {
    const mod = await server.ssrLoadModule(MODULE_PATH);
    return mod as never;
  } finally {
    await server.close();
  }
}

function capture(mod: Awaited<ReturnType<typeof loadModule>>): Capture {
  const css: Capture['buildReaderOverrideCss'] = {};
  for (const testCase of cases()) {
    const built = mod.buildReaderOverrideCss(testCase.opts);
    css[testCase.name] = { css: built, sha256: sha256(built) };
  }
  const themes = ['paper', 'sepia', 'night', 'oled', 'unknown'];
  const getThemeStyles: Record<string, string> = {};
  const getThemeBgColor: Record<string, string> = {};
  for (const theme of themes) {
    getThemeStyles[theme] = mod.getThemeStyles(theme);
    getThemeBgColor[theme] = mod.getThemeBgColor(theme);
  }
  return { buildReaderOverrideCss: css, getThemeStyles, getThemeBgColor };
}

const mode = process.argv[2] ?? 'verify';
const mod = await loadModule();
const current = capture(mod);

if (mode === 'capture') {
  mkdirSync(dirname(fixturePath), { recursive: true });
  writeFileSync(fixturePath, `${JSON.stringify(current, null, 2)}\n`, 'utf8');
  const count = Object.keys(current.buildReaderOverrideCss).length;
  console.log(`U1 capture: ${count} buildReaderOverrideCss cases + theme tables -> ${fixturePath}`);
  process.exit(0);
}

if (!existsSync(fixturePath)) {
  console.error(`No fixture at ${fixturePath}. Run "capture" first.`);
  process.exit(2);
}

const fixture = JSON.parse(readFileSync(fixturePath, 'utf8')) as Capture;
const differences: string[] = [];

const names = new Set([
  ...Object.keys(fixture.buildReaderOverrideCss),
  ...Object.keys(current.buildReaderOverrideCss),
]);
for (const name of names) {
  const before = fixture.buildReaderOverrideCss[name];
  const after = current.buildReaderOverrideCss[name];
  if (!before) {
    differences.push(`${name}: only in current (new case)`);
    continue;
  }
  if (!after) {
    differences.push(`${name}: missing from current`);
    continue;
  }
  if (before.sha256 !== after.sha256) {
    differences.push(`${name}: sha ${before.sha256} -> ${after.sha256}`);
    const beforeLines = before.css.split('\n');
    const afterLines = after.css.split('\n');
    const max = Math.max(beforeLines.length, afterLines.length);
    for (let i = 0; i < max; i++) {
      if (beforeLines[i] !== afterLines[i]) {
        differences.push(`  line ${i + 1}:\n    before: ${JSON.stringify(beforeLines[i])}\n    after:  ${JSON.stringify(afterLines[i])}`);
      }
    }
  }
}

for (const theme of new Set([
  ...Object.keys(fixture.getThemeStyles),
  ...Object.keys(current.getThemeStyles),
])) {
  if (fixture.getThemeStyles[theme] !== current.getThemeStyles[theme]) {
    differences.push(`getThemeStyles(${theme}): differs`);
  }
  if (fixture.getThemeBgColor[theme] !== current.getThemeBgColor[theme]) {
    differences.push(
      `getThemeBgColor(${theme}): ${fixture.getThemeBgColor[theme]} -> ${current.getThemeBgColor[theme]}`,
    );
  }
}

const total = Object.keys(current.buildReaderOverrideCss).length;
if (differences.length === 0) {
  console.log(`U1 verify: BYTE-IDENTICAL across ${total} buildReaderOverrideCss cases + theme tables.`);
  process.exit(0);
}

console.error(`U1 verify: ${differences.length} DIFFERENCE(S) across ${total} cases:`);
for (const diff of differences) console.error(`- ${diff}`);
process.exit(1);
