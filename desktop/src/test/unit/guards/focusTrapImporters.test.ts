/**
 * focusTrap disposition guard (design D4).
 *
 * `shared/utils/focusTrap.ts` is retained: its importers are hand-rolled overlays
 * that are not built on the Modal / Dropdown / DropMenu adapters, so retiring the
 * utility would force a fourth primitive swap on surfaces this change does not own.
 *
 * The decision is only worth anything if it stays true, so the invariant is
 * mechanical instead of prose: the production importer set is exactly those four
 * reader surfaces that keep the hand-rolled `focusTrap`, and none of the swapped
 * wrappers imports the utility.
 */
import { existsSync, readdirSync, readFileSync } from 'node:fs';
import { join, relative, resolve, sep } from 'node:path';
import { describe, expect, it } from 'vitest';

const SKIPPED_DIRS = new Set(['node_modules', 'dist', 'build', '.svelte-kit']);

const FOCUS_TRAP_IMPORT = /from\s+['"][^'"]*focusTrap['"]/;

const SWAPPED_WRAPPERS = [
  'shared/ui/layout/Modal.svelte',
  'shared/ui/navigation/Dropdown.svelte',
  'shared/ui/navigation/DropMenu.svelte',
  'features/reader/highlight/NoteEditorModal.svelte',
];

const EXPECTED_IMPORTERS = [
  // NoteEditorModal migrated to bits-ui Dialog (perf-stability-cleanup M1):
  // its hand-rolled focusTrap + Escape pair is gone, three chrome importers
  // remained until SearchPanel's drawer adoption (reader-search-panel-fix) made
  // it the fourth — it lives in panels/, not chrome/, so the set is reader
  // surfaces rather than chrome consumers.
  'features/reader/chrome/BookmarkSidebar.svelte',
  'features/reader/chrome/ReaderTextSettings.svelte',
  'features/reader/chrome/ReaderTocPanel.svelte',
  'features/reader/panels/SearchPanel.svelte',
];

interface SourceFile {
  path: string;
  source: string;
}

function resolveLibPath(): string {
  const candidates = [
    resolve(process.cwd(), 'src', 'lib'),
    resolve(process.cwd(), 'desktop', 'src', 'lib'),
  ];
  const found = candidates.find((candidate) => existsSync(candidate));

  if (!found) {
    throw new Error(`src/lib not found. Looked in: ${candidates.join(', ')}`);
  }

  return found;
}

function collectSourceFiles(dir: string, root: string, found: string[] = []): string[] {
  for (const entry of readdirSync(dir, { withFileTypes: true })) {
    if (SKIPPED_DIRS.has(entry.name)) continue;

    const full = join(dir, entry.name);
    if (entry.isDirectory()) {
      collectSourceFiles(full, root, found);
    } else if (entry.name.endsWith('.svelte') || entry.name.endsWith('.ts')) {
      found.push(relative(root, full).split(sep).join('/'));
    }
  }

  return found;
}

function readProductionSources(libPath: string): SourceFile[] {
  return collectSourceFiles(libPath, libPath).map((path) => ({
    path,
    source: readFileSync(join(libPath, path), 'utf8'),
  }));
}

function findFocusTrapImporters(files: SourceFile[]): string[] {
  return files
    .filter((file) => FOCUS_TRAP_IMPORT.test(file.source))
    .map((file) => file.path)
    .sort();
}

const LIB_PATH = resolveLibPath();
const IMPORTERS = findFocusTrapImporters(readProductionSources(LIB_PATH));

describe('focusTrap production importer set (D4)', () => {
  it('is exactly the four reader surfaces that keep the hand-rolled focusTrap', () => {
    expect(IMPORTERS).toEqual(EXPECTED_IMPORTERS);
  });

  it.each(SWAPPED_WRAPPERS)('has no importer in %s', (wrapperPath) => {
    const source = readFileSync(join(LIB_PATH, wrapperPath), 'utf8');

    expect(IMPORTERS).not.toContain(wrapperPath);
    expect(source).not.toMatch(FOCUS_TRAP_IMPORT);
  });

  it('does not count the utility itself as an importer', () => {
    expect(IMPORTERS).not.toContain('shared/utils/focusTrap.ts');
  });
});

describe('focusTrap importer matcher (non-vacuity)', () => {
  it('detects an aliased import', () => {
    const files = [
      {
        path: 'shared/ui/layout/Modal.svelte',
        source: "  import { createFocusTrap } from '$lib/shared/utils/focusTrap';",
      },
    ];

    expect(findFocusTrapImporters(files)).toEqual(['shared/ui/layout/Modal.svelte']);
  });

  it('detects a relative import', () => {
    const files = [
      {
        path: 'features/reader/chrome/ReaderTocPanel.svelte',
        source: "import { createFocusTrap } from '../../../shared/utils/focusTrap';",
      },
    ];

    expect(findFocusTrapImporters(files)).toEqual(['features/reader/chrome/ReaderTocPanel.svelte']);
  });

  it('ignores the utility call sites and its doc comment', () => {
    const files = [
      {
        path: 'shared/utils/focusTrap.ts',
        source: [
          ' *   const trap = createFocusTrap(containerEl, { onDeactivate });',
          'export function createFocusTrap(container: HTMLElement): void {}',
        ].join('\n'),
      },
    ];

    expect(findFocusTrapImporters(files)).toEqual([]);
  });

  it('ignores a module whose path only starts with focusTrap', () => {
    const files = [
      {
        path: 'shared/ui/layout/Modal.svelte',
        source: "import { reflow } from '$lib/shared/utils/focusTrapHelpers';",
      },
    ];

    expect(findFocusTrapImporters(files)).toEqual([]);
  });
});
