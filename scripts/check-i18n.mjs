#!/usr/bin/env bun
/**
 * Untranslated-string guard.
 *
 * Fails when a user-visible string in an Astro app is hardcoded instead of
 * coming from the i18n dictionaries, and when those dictionaries drift apart.
 *
 * Usage: bun scripts/check-i18n.mjs <project-dir>
 *   e.g. bun scripts/check-i18n.mjs nexo-web   (run from the repo root)
 *        bun scripts/check-i18n.mjs .          (run with the app as cwd)
 *
 * The project directory is resolved against the current working directory
 * first, then against the repo root, so both invocation styles work.
 *
 * It parses every `.astro` file under `<project>/src` with the real Astro compiler and
 * walks the AST. A regex scanner cannot tell an expression from literal markup
 * (multi-line `${...}` and class attributes produce both false positives and
 * missed strings); the AST excludes code, comments, class attributes and
 * script/style payloads by construction.
 *
 * Findings are O(1)-deterministic: sorted by file, then line, then kind.
 */
import { existsSync } from 'node:fs';
import { readdir, readFile } from 'node:fs/promises';
import { createRequire } from 'node:module';
import path from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

const repoRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');

// Attributes whose literal text reaches the user (screen reader, tooltip,
// image fallback). Anything not listed here is structural (href, class, id, …)
// and is deliberately ignored.
const VISIBLE_ATTRS = new Set([
  'alt',
  'aria-label',
  'aria-placeholder',
  'aria-description',
  'placeholder',
  'title',
]);

// Allowlist by VALUE, never by line: these are the same in every language, so
// they are correctly literal. `{{ }}` values are dictionary interpolation
// templates and are allowed by the regex below.
const ALLOWLIST = new Set([
  'ES',
  'EN',
  'Next',
  'Page',
  'Addons',
  '·',
  '—',
  '–',
  '/',
  '|',
  '%',
  '•',
  '‹',
  '›',
  '&nbsp;',
  '+',
  '-',
  '→',
]);

const TWO_LETTERS = /[A-Za-z]{2,}/;
const INTERPOLATION = /\{\{[\s\S]*?\}\}/;

// Astro attribute `kind` values that carry a literal string. `expression`
// (`{...}`), `spread` (`{...props}`) and `shorthand` are code, not copy.
const LITERAL_KINDS = new Set(['quoted', 'unquoted', 'empty']);

const DICTIONARY_PAIRS = [
  { es: 'i18n/es.ts', en: 'i18n/en.ts' },
  { es: 'i18n/ui.es.ts', en: 'i18n/ui.en.ts' },
];

function resolveProjectDir(arg) {
  if (!arg) {
    console.error('Usage: bun scripts/check-i18n.mjs <project-dir>');
    process.exit(2);
  }
  if (path.isAbsolute(arg)) return arg;
  const fromCwd = path.resolve(process.cwd(), arg);
  if (existsSync(fromCwd)) return fromCwd;
  return path.resolve(repoRoot, arg);
}

function toRepoRelative(absPath) {
  return path.relative(repoRoot, absPath).split(path.sep).join('/');
}

async function collectAstroFiles(dir) {
  const entries = await readdir(dir, { withFileTypes: true });
  const files = [];
  for (const entry of entries) {
    const full = path.join(dir, entry.name);
    if (entry.isDirectory()) {
      files.push(...(await collectAstroFiles(full)));
    } else if (entry.isFile() && entry.name.endsWith('.astro')) {
      files.push(full);
    }
  }
  return files;
}

const normalize = (value) => String(value ?? '').trim().replace(/\s+/g, ' ');

const isAllowed = (value) => ALLOWLIST.has(value) || INTERPOLATION.test(value);

const isCandidate = (value) => TWO_LETTERS.test(value) && !isAllowed(value);

const startLine = (node) => node?.position?.start?.line ?? 0;

function walk(node, file, findings) {
  if (!node || typeof node !== 'object') return;

  switch (node.type) {
    case 'frontmatter':
    case 'comment':
    case 'expression':
      // Frontmatter and `${...}` expressions are code; comments never render.
      return;
    case 'text': {
      const value = normalize(node.value);
      if (value && isCandidate(value)) {
        findings.push({ file, line: startLine(node), kind: 'text', literal: value });
      }
      return;
    }
    case 'attribute':
      // Attributes are inspected from their parent element/component node.
      return;
    default:
      break;
  }

  // `<script>` / `<style>` payloads are JS/CSS, not copy.
  if (node.type === 'element' && (node.name === 'script' || node.name === 'style')) {
    return;
  }

  for (const attr of node.attributes ?? []) {
    if (attr?.type !== 'attribute' || !VISIBLE_ATTRS.has(attr.name)) continue;
    if (!LITERAL_KINDS.has(attr.kind)) continue;
    const value = normalize(attr.value);
    if (value && isCandidate(value)) {
      findings.push({ file, line: startLine(attr), kind: `attr:${attr.name}`, literal: value });
    }
  }

  for (const child of node.children ?? []) {
    walk(child, file, findings);
  }
}

async function loadCompiler(projectDir) {
  // The compiler lives either at the repo root (web/ is a root workspace
  // member, installed once at the root) or in the app's own node_modules
  // (nexo-web is standalone and installs from its own lockfile). Resolve from
  // the script location first, then from the project, so the guard runs under
  // both CI install layouts.
  const parents = [import.meta.url, path.join(projectDir, 'package.json')];
  let lastError;
  for (const parent of parents) {
    try {
      const resolved = createRequire(parent).resolve('@astrojs/compiler');
      const mod = await import(pathToFileURL(resolved).href);
      const parseFn = mod.parse ?? mod.default?.parse;
      if (typeof parseFn === 'function') return parseFn;
    } catch (error) {
      lastError = error;
    }
  }
  throw new Error(
    `Could not resolve '@astrojs/compiler' for ${projectDir}${lastError ? `: ${lastError.message}` : ''}`,
  );
}

async function loadDictionary(absPath) {
  const mod = await import(pathToFileURL(absPath).href);
  const dict = Object.values(mod).find(
    (value) => value && typeof value === 'object' && !Array.isArray(value),
  );
  if (!dict) {
    throw new Error(`No dictionary object export found in ${absPath}`);
  }
  return dict;
}

async function checkDictionaries(projectDir, fileRel, findings) {
  for (const pair of DICTIONARY_PAIRS) {
    const esPath = path.join(projectDir, 'src', pair.es);
    const enPath = path.join(projectDir, 'src', pair.en);
    if (!existsSync(esPath) || !existsSync(enPath)) continue;

    const [es, en] = await Promise.all([loadDictionary(esPath), loadDictionary(enPath)]);
    const esKeys = Object.keys(es).sort();
    const enKeys = Object.keys(en).sort();
    const esSet = new Set(esKeys);
    const enSet = new Set(enKeys);

    const missing = enKeys.filter((key) => !esSet.has(key));
    const extra = esKeys.filter((key) => !enSet.has(key));
    if (missing.length) {
      findings.push({
        file: `${fileRel}/src/${pair.es}`,
        line: 0,
        kind: 'dict',
        literal: `missing keys present in ${pair.en}: ${missing.join(', ')}`,
      });
    }
    if (extra.length) {
      findings.push({
        file: `${fileRel}/src/${pair.es}`,
        line: 0,
        kind: 'dict',
        literal: `extra keys absent from ${pair.en}: ${extra.join(', ')}`,
      });
    }

    for (const [label, dict, rel] of [
      ['ES', es, pair.es],
      ['EN', en, pair.en],
    ]) {
      for (const [key, value] of Object.entries(dict)) {
        if (typeof value !== 'string' || value.trim().length === 0) {
          findings.push({
            file: `${fileRel}/${rel}`,
            line: 0,
            kind: 'dict',
            literal: `${label} empty value for '${key}'`,
          });
        }
      }
    }

    return { keyCount: esKeys.length };
  }
  return { keyCount: null };
}

async function main() {
  const projectDir = resolveProjectDir(process.argv[2]);
  if (!existsSync(projectDir)) {
    console.error(`Project directory not found: ${process.argv[2]}`);
    process.exit(2);
  }
  const fileRel = toRepoRelative(projectDir);
  const parse = await loadCompiler(projectDir);

  const srcDir = path.join(projectDir, 'src');
  const astroFiles = existsSync(srcDir) ? await collectAstroFiles(srcDir) : [];
  astroFiles.sort();

  const findings = [];
  for (const file of astroFiles) {
    const source = await readFile(file, 'utf8');
    let ast;
    try {
      ({ ast } = await parse(source));
    } catch (error) {
      findings.push({
        file: toRepoRelative(file),
        line: 0,
        kind: 'parse',
        literal: error instanceof Error ? error.message : String(error),
      });
      continue;
    }
    walk(ast, toRepoRelative(file), findings);
  }

  const { keyCount } = await checkDictionaries(projectDir, fileRel, findings);

  findings.sort(
    (a, b) =>
      a.file.localeCompare(b.file) || a.line - b.line || a.kind.localeCompare(b.kind) ||
      a.literal.localeCompare(b.literal),
  );

  for (const finding of findings) {
    console.log(`${finding.file}:${finding.line} ${finding.kind} ${finding.literal}`);
  }

  const i18nSummary = keyCount === null ? 'no dictionaries found' : `i18n es/en: ${keyCount} keys each`;
  if (findings.length > 0) {
    console.log(
      `${fileRel}: FAIL — ${astroFiles.length} astro files, ${findings.length} finding${findings.length === 1 ? '' : 's'} (${i18nSummary})`,
    );
    process.exitCode = 1;
    return;
  }

  console.log(
    `${fileRel}: OK — ${astroFiles.length} astro files, 0 findings (${i18nSummary})`,
  );
}

await main();
