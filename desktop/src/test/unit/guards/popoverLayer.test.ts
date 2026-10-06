/**
 * Portalled popover/menu layering guard (modal re-critique).
 *
 * Regression pinned: `Dropdown.svelte` portalled its bits-ui `Select.Content`
 * with a raw `z-[60]`. A dialog is `z-(--layer-dialog)` (1100), so a dropdown
 * opened from inside `ShelfDetailModal` painted BEHIND the scrim and was
 * unreachable — the reading status and genre pickers could not be changed.
 * A raw numeric z-index has no meaning against the documented `--layer-*`
 * scale; portalled popover/menu content must sit on it.
 *
 * jsdom does not compute z-index, so a component test cannot observe the bug.
 * This is a source-level guard, same shape as `overlayFreeze.test.ts`: read
 * the sources, match with regex, fail loudly with a clear message.
 */
import { readdirSync, readFileSync } from 'node:fs';
import { join, relative, resolve, sep } from 'node:path';
import { describe, expect, it } from 'vitest';

const SKIPPED_DIRS = new Set(['node_modules', 'dist', 'build', '.svelte-kit']);

/** A framework portal lifts a floating layer out of the page flow. */
const PORTAL = /\.Portal\b/;

/**
 * The bits-ui primitives this guard owns: content that must outrank the dialog
 * it can be spawned from. `Tooltip` is deliberately NOT here — a tooltip must
 * stay BELOW dialogs (its trigger sits behind the scrim), so `--layer-popover`
 * would be the wrong token for it. Tooltips are still caught by the raw-z
 * allowlist test below.
 */
const POPOVER_PORTAL_PRIMITIVES =
  /<(Select|DropdownMenu|Popover|Combobox|ContextMenu|Menubar)\.Portal\b/;

/** A raw numeric z-index utility: `z-50`, `z-[60]`, `z-9999`. */
const RAW_Z = /\bz-\[?\d+\]?/g;

/**
 * Portalled surfaces allowed to keep a raw numeric z-index. Every entry needs
 * a recorded reason; a new one fails the guard so the layer decision is made
 * on purpose.
 */
const RAW_Z_ALLOWLIST: Record<string, string> = {
  'shared/ui/layout/AppSidebar.svelte':
    'Tooltip content: a tooltip must stay below dialogs (its trigger is behind the scrim), so --layer-popover would be wrong.',
};

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

interface SourceFile {
  path: string;
  source: string;
}

function readSources(): SourceFile[] {
  const lib = resolveLibPath();
  return collectSvelteFiles(lib).map((file) => ({
    path: relative(lib, file).split(sep).join('/'),
    source: readFileSync(file, 'utf8'),
  }));
}

function rawZMatches(source: string): string[] {
  return source.match(RAW_Z) ?? [];
}

const SOURCES = readSources();

function portalledPopoverWrappers(): string[] {
  return SOURCES.filter((file) => POPOVER_PORTAL_PRIMITIVES.test(file.source))
    .map((file) => file.path)
    .sort();
}

describe('portalled popover/menu layering', () => {
  it('discovers exactly the two popover wrappers (non-vacuity)', () => {
    expect(portalledPopoverWrappers()).toEqual([
      'shared/ui/navigation/DropMenu.svelte',
      'shared/ui/navigation/Dropdown.svelte',
    ]);
  });

  it.each(portalledPopoverWrappers())(
    'places the portalled content on --layer-popover in %s',
    (wrapperPath) => {
      const file = SOURCES.find((candidate) => candidate.path === wrapperPath);
      if (!file) throw new Error(`source not found for ${wrapperPath}`);

      expect(file.source).toContain('z-(--layer-popover)');
      expect(
        rawZMatches(file.source),
        `${wrapperPath}: portalled popover/menu content must use z-(--layer-popover), not a raw z-index`,
      ).toEqual([]);
    },
  );

  it('keeps every other portalled surface off a raw z-index unless allowlisted', () => {
    const offenders = SOURCES.filter(
      (file) => PORTAL.test(file.source) && rawZMatches(file.source).length > 0,
    )
      .map((file) => file.path)
      .filter((path) => !(path in RAW_Z_ALLOWLIST))
      .sort();

    expect(
      offenders,
      'portalled surface uses a raw z-index — put it on the --layer-* scale or add it to RAW_Z_ALLOWLIST with a recorded reason',
    ).toEqual([]);
  });

  it('detects portal + raw z-index in a synthetic source (non-vacuity)', () => {
    const synthetic = '<Foo.Portal><div class="z-[60]"></div></Foo.Portal>';
    expect(PORTAL.test(synthetic)).toBe(true);
    expect(rawZMatches(synthetic)).toEqual(['z-[60]']);
  });
});

describe('layer token ordering', () => {
  it('declares --layer-popover above the dialogs and below the debug overlay', () => {
    const lib = resolveLibPath();
    const css = readFileSync(join(lib, 'shared', 'styles', 'tokens.css'), 'utf8');
    const value = (name: string): number => {
      const match = css.match(new RegExp(`${name}:\\s*(\\d+)`));
      if (!match) throw new Error(`token ${name} not found`);
      return Number(match[1]);
    };

    expect(value('--layer-popover')).toBeGreaterThan(value('--layer-dialog-reader'));
    expect(value('--layer-dialog-reader')).toBeGreaterThan(value('--layer-dialog'));
    expect(value('--layer-popover')).toBeLessThan(9999);
  });
});
