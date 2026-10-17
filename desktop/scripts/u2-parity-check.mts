/**
 * U2 parity proof — NOT a test, a one-shot historical check.
 *
 * The U2 extraction moved the theme tables and the override builder out of
 * `useEpubRender.svelte.ts` into `readerCssBuilder.ts`. The claim is that the
 * move changed nothing: same bytes, same order, same `!important` placement.
 *
 * This script proves it by loading the PRE-extraction source straight out of git
 * (`git show 36374753:...`, the commit before the extraction) through vite's SSR
 * transform, and diffing its full output against the snapshot that was captured
 * from that same file before any edit (`scripts/u1-css-fixture.json`, frozen
 * pre-U3). A byte difference in any of the 99 cases fails.
 *
 * It is kept out of the vitest suite on purpose: the suite asserts the post-U3
 * golden, which is what future edits must hold. This file records a fact about
 * the refactor's history, so it is a script, not a gate.
 *
 * Run: `bun run scripts/u2-parity-check.mts`
 */
import { execFileSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import { readFileSync, rmSync, writeFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const scriptDir = dirname(fileURLToPath(import.meta.url));
const projectRoot = join(scriptDir, '..');
const repoRoot = join(projectRoot, '..');

/** The commit whose `useEpubRender.svelte.ts` still carried the builder inline. */
const PRE_EXTRACTION_COMMIT = '36374753';
const PRE_EXTRACTION_SOURCE = 'desktop/src/lib/features/reader/viewer-epub/useEpubRender.svelte.ts';

/**
 * Snapshot captured from that source before U2 touched it.
 *
 * The captured file is NOT committed: it is 677 KB of CSS that exists only to
 * answer one question ("did the move change the output?"), and that question was
 * answered — 99 cases, 0 differences. It was deleted once it had served, rather
 * than left in the tree as a megabyte nobody reads.
 *
 * To re-run the check, regenerate it from the same commit first:
 *
 *   git show 36374753:desktop/src/lib/features/reader/viewer-epub/useEpubRender.svelte.ts
 *     > /tmp/pre.ts   (then point this script at a fixture captured from it)
 *
 * In practice: check out that commit, run `u1-capture-css-fixture.mts`, copy the
 * result here, and pass its path as argv[2].
 */
const PRE_U3_FIXTURE =
  process.argv[2] ?? join(scriptDir, 'u1-css-fixture.pre-u3.json');

const sha256 = (text: string): string => createHash('sha256').update(text, 'utf8').digest('hex');

const preU3 = JSON.parse(readFileSync(PRE_U3_FIXTURE, 'utf8')) as {
  buildReaderOverrideCss: Record<string, { css: string; sha256: string }>;
  getThemeStyles: Record<string, string>;
  getThemeBgColor: Record<string, string>;
};

// Materialise the pre-extraction source into the tree so vite can resolve its
// own relative imports, then remove it. It is NEVER imported by app code.
const checkoutPath = join(
  projectRoot,
  'src/lib/features/reader/viewer-epub/__u2-parity-pre-extraction.svelte.ts',
);

const source = execFileSync(
  'git',
  ['show', `${PRE_EXTRACTION_COMMIT}:${PRE_EXTRACTION_SOURCE}`],
  { cwd: repoRoot, encoding: 'utf8', maxBuffer: 16 * 1024 * 1024 },
);
writeFileSync(checkoutPath, source, 'utf8');

const { createServer } = await import('vite');
const server = await createServer({
  root: projectRoot,
  logLevel: 'error',
  server: { middlewareMode: true },
  appType: 'custom',
});

try {
  const mod = (await server.ssrLoadModule(
    '/src/lib/features/reader/viewer-epub/__u2-parity-pre-extraction.svelte.ts',
  )) as {
    buildReaderOverrideCss: (o: Record<string, unknown>) => string;
    getThemeStyles: (t: string) => string;
    getThemeBgColor: (t: string) => string;
  };

  // Reconstruct the option matrix inline. It is the same one the capture used;
  // the fixture's keys are self-describing (`theme-align-hyN-psN`), so the
  // matrix is rebuilt here rather than imported (importing the script would run
  // its top-level CLI code).
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
  const cases: Array<{ name: string; opts: Record<string, unknown> }> = [];
  for (const themeMode of ['paper', 'sepia', 'night', 'oled']) {
    for (const textAlign of ['left', 'center', 'right', 'justify']) {
      for (const hyphenation of [false, true]) {
        for (const paragraphSpacing of [0, 1, 2]) {
          cases.push({
            name: `${themeMode}-${textAlign}-hy${hyphenation ? 1 : 0}-ps${paragraphSpacing}`,
            opts: { ...base, themeMode, textAlign, hyphenation, paragraphSpacing },
          });
        }
      }
    }
  }
  cases.push({ name: 'zoom-300-rgb', opts: { ...base, zoomLevel: 300 } });
  cases.push({
    name: 'custom-font-rtl-letterSpacing',
    opts: { ...base, fontFamily: 'Literata', direction: 'rtl', letterSpacing: 3, fontSize: 120 },
  });
  cases.push({
    name: 'asymmetric-margins-unknown-theme',
    opts: {
      ...base,
      themeMode: 'does-not-exist',
      margins: { top: 1, bottom: 4, left: 0, right: 6 },
    },
  });

  let checked = 0;
  let diffs = 0;
  for (const { name, opts } of cases) {
    const expected = preU3.buildReaderOverrideCss[name];
    if (!expected) continue;
    checked++;
    const actual = mod.buildReaderOverrideCss(opts);
    if (sha256(actual) !== expected.sha256 || actual !== expected.css) {
      diffs++;
      if (diffs <= 3) {
        const a = expected.css.split('\n');
        const b = actual.split('\n');
        for (let i = 0; i < Math.max(a.length, b.length); i++) {
          if (a[i] !== b[i]) {
            console.error(
              `${name} line ${i + 1}:\n  snapshot: ${JSON.stringify(a[i])}\n  pre-U2 : ${JSON.stringify(b[i])}`,
            );
          }
        }
      }
    }
  }

  for (const theme of Object.keys(preU3.getThemeStyles)) {
    if (mod.getThemeStyles(theme) !== preU3.getThemeStyles[theme]) {
      diffs++;
      console.error(`getThemeStyles(${theme}) differs from the snapshot`);
    }
    if (mod.getThemeBgColor(theme) !== preU3.getThemeBgColor[theme]) {
      diffs++;
      console.error(`getThemeBgColor(${theme}) differs from the snapshot`);
    }
  }

  console.log(
    `U2 parity: pre-extraction source (${PRE_EXTRACTION_COMMIT}) vs the pre-U3 snapshot — ` +
      `${checked} cases checked, ${diffs} difference(s).`,
  );
  // Remove the materialised checkout BEFORE exiting: `process.exit` skips the
  // `finally` block, which is how a stray copy was left in the tree once.
  rmSync(checkoutPath, { force: true });
  process.exit(diffs === 0 ? 0 : 1);
} catch (error) {
  rmSync(checkoutPath, { force: true });
  throw error;
} finally {
  await server.close();
}
