/**
 * Overlay-pattern freeze (perf-stability-cleanup P3).
 *
 * Decision: the six hand-rolled overlay roots below stay hand-rolled.
 * Migrating them to bits-ui is weeks of work and was explicitly deferred;
 * what ends today is the ambiguity, not the migration. New overlay roots
 * must either reuse Modal/Dialog (preferred) or update this allowlist
 * with a recorded reason — never silently add a seventh `fixed inset-0`.
 */
import { readdirSync, readFileSync } from 'node:fs';
import { join, relative, resolve, sep } from 'node:path';
import { describe, expect, it } from 'vitest';

const SKIPPED_DIRS = new Set(['node_modules', 'dist', 'build', '.svelte-kit']);

/** A hand-rolled overlay root: Tailwind fixed full-viewport backdrop. */
const OVERLAY_ROOT = /fixed\s+inset-0/;

/** Bits-ui-owned overlays are not hand-rolled, so they never count. */
const BITS_UI_IMPORT = /from\s+['"]bits-ui['"]/;

/**
 * Exact known set, relative to src/lib with forward slashes. Sorted.
 * Removing an entry (migration or deletion) or adding one (new overlay)
 * fails loudly so the freeze decision is revisited on purpose, never by
 * drift. bits-ui overlays (Modal, NoteEditorModal, FeedbackDialog) carry
 * `fixed inset-0` on their Overlay element but are excluded by the scanner:
 * this list pins hand-rolled roots only. Five remain: the NoteEditorModal and
 * FeedbackDialog migrations (perf-stability-cleanup M1/M2), the
 * CollectionManager deletion (dead code — no trigger ever opened it), and the
 * SettingsPanel overlay-mode deletion (dead code — page mode was the only mode used).
 */
const KNOWN_OVERLAY_ROOTS = [
  'features/reader/chrome/BookmarkSidebar.svelte',
  'features/reader/chrome/ReaderTextSettings.svelte',
  'features/reader/chrome/ReaderTocPanel.svelte',
  'features/reader/chrome/ReaderWorkspace.svelte',
  'shared/ui/feedback/ErrorFallback.svelte',
];

/** Surfaces migrated off hand-rolled overlays; they must not slide back. */
const MIGRATED_DIALOGS = [
  'shared/ui/layout/Modal.svelte',
  'features/reader/highlight/NoteEditorModal.svelte',
  'shared/ui/feedback/FeedbackDialog.svelte',
];

function resolveLibPath(): string {
  const candidates = [
    resolve(process.cwd(), 'src', 'lib'),
    resolve(process.cwd(), 'desktop', 'src', 'lib'),
  ];
  const found = candidates.find((dir) => {
    try {
      return readdirSync(dir, { withFileTypes: true }).length > 0;
    } catch {
      return false;
    }
  });
  if (!found) throw new Error('src/lib not found from ' + process.cwd());
  return found;
}

function collectSvelteFiles(dir: string): string[] {
  const out: string[] = [];
  for (const entry of readdirSync(dir, { withFileTypes: true })) {
    if (SKIPPED_DIRS.has(entry.name)) continue;
    const full = join(dir, entry.name);
    if (entry.isDirectory()) out.push(...collectSvelteFiles(full));
    else if (entry.isFile() && entry.name.endsWith('.svelte')) out.push(full);
  }
  return out;
}

function overlayRoots(): string[] {
  const lib = resolveLibPath();
  return collectSvelteFiles(lib)
    .filter((file) => {
      const source = readFileSync(file, 'utf8');
      return OVERLAY_ROOT.test(source) && !BITS_UI_IMPORT.test(source);
    })
    .map((file) => relative(lib, file).split(sep).join('/'))
    .sort();
}

describe('overlay pattern freeze', () => {
  it('pins the exact set of hand-rolled overlay roots', () => {
    const actual = overlayRoots();
    expect(
      actual,
      'new or removed hand-rolled overlay root — update KNOWN_OVERLAY_ROOTS with a recorded reason',
    ).toEqual(KNOWN_OVERLAY_ROOTS);
  });

  it('keeps every migrated dialog surface on bits-ui', () => {
    const lib = resolveLibPath();
    for (const relativePath of MIGRATED_DIALOGS) {
      const source = readFileSync(join(lib, ...relativePath.split('/')), 'utf8');
      // A migrated surface reaches bits-ui directly OR through the shared Modal
      // facade (its own file is checked above and stays on bits-ui). The
      // NoteEditorModal/FeedbackDialog migrations moved them to the facade, so
      // "on bits-ui" must accept the facade import as well as a direct one.
      const onBitsUi =
        source.includes('bits-ui') || /from\s+['"][^'"]*layout\/Modal\.svelte['"]/.test(source);
      expect(onBitsUi, `${relativePath} left bits-ui`).toBe(true);
    }
  });
});
