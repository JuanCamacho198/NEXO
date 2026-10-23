import { describe, expect, it } from 'vitest';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import {
  CHROME_DIVIDER,
  CHROME_ICON_ACTION,
  CHROME_ICON_BUTTON,
  CHROME_ICON_TOGGLE_ACTIVE,
  CHROME_ICON_TOGGLE_IDLE,
  CHROME_PAGE_FIELD,
  chromeIconButton,
} from '$lib/features/reader/chrome/chromeControl';

/**
 * The chrome controls share ONE class-string recipe (ADR-003). A component that
 * hand-rolls its own control classes is how the two chrome systems drifted apart
 * in the first place, so the source itself is the subject of these assertions.
 *
 * Honest boundary: this reads the component source, not the rendered DOM. The
 * class-string half of the contract lives in ReaderChromeAccessibility.test.ts;
 * the "no pill survives" grep below is what makes a reintroduced local recipe
 * fail loudly.
 */
const CHROME_DIR = join(__dirname, '../../../lib/features/reader/chrome');

function readComponent(name: string): string {
  return readFileSync(join(CHROME_DIR, name), 'utf8');
}

const CONSUMERS = [
  'ReaderTopBar.svelte',
  'ReaderBottomBar.svelte',
  'FitControls.svelte',
  'ZoomDropdown.svelte',
  'PageInput.svelte',
];

// The four components whose controls must be recipe-built. ReaderBottomBar's
// progress-bar track/fill legitimately uses `rounded-full`, so the "no pill"
// grep targets the control class values, not the whole file.
const BUTTON_CONSUMERS = [
  'ReaderTopBar.svelte',
  'ReaderBottomBar.svelte',
  'FitControls.svelte',
  'ZoomDropdown.svelte',
];

describe('chrome control recipe (U4.1)', () => {
  it('exposes the shared constants with canonical Tailwind v4 syntax', () => {
    expect(CHROME_ICON_BUTTON).toContain('min-w-11');
    expect(CHROME_ICON_BUTTON).toContain('min-h-11');
    expect(CHROME_ICON_BUTTON).toContain('rounded-lg');
    expect(CHROME_PAGE_FIELD).toContain('w-12.5');
    expect(CHROME_DIVIDER).toBe('h-5 w-px bg-(--color-border)');
    for (const value of [
      CHROME_ICON_BUTTON,
      CHROME_ICON_ACTION,
      CHROME_ICON_TOGGLE_IDLE,
      CHROME_ICON_TOGGLE_ACTIVE,
      CHROME_PAGE_FIELD,
      CHROME_DIVIDER,
    ]) {
      expect(value).not.toContain('[var(');
    }
  });

  it('builds the toggle variants and the action variant', () => {
    expect(chromeIconButton('action')).toBe(CHROME_ICON_ACTION);
    expect(chromeIconButton('toggle', false)).toBe(CHROME_ICON_TOGGLE_IDLE);
    expect(chromeIconButton('toggle', true)).toBe(CHROME_ICON_TOGGLE_ACTIVE);
    expect(chromeIconButton('toggle')).toBe(CHROME_ICON_TOGGLE_IDLE);
  });

  // The field sits in a bar that is now a MINIMUM height, so it must stay one
  // of the shorter things in the row. `styles.css` sets
  // `input[type='text'] { width: 100% }` unlayered, which beats every layered
  // width utility: without a max-width the field measured 149px instead of
  // 50px, and the "/ 24" label beside it wrapped onto two lines.
  it('keeps the page field at its recipe width against the global input rule', () => {
    expect(CHROME_PAGE_FIELD).toContain('max-w-12.5');
    // Explicit small vertical padding, not the 10px the global rule implies.
    expect(CHROME_PAGE_FIELD).toContain('py-1');
  });
});

describe('chrome consumers import the recipe (U4.10)', () => {
  for (const name of CONSUMERS) {
    it(`${name} imports chromeControl`, () => {
      expect(readComponent(name)).toContain("from './chromeControl'");
    });
  }
});

describe('no local chrome control class string remains (U4.3)', () => {
  it('no button consumer hand-rolls a pill control', () => {
    // The recipe's rejected forms: a `rounded-full` border pill and the paper
    // `bg-(--color-surface)` plane. A component that reassembles either has
    // forked the recipe, which is how the two chrome systems drifted apart.
    for (const name of BUTTON_CONSUMERS) {
      expect(readComponent(name)).not.toContain('border border-(--color-border) rounded-full');
      expect(readComponent(name)).not.toContain('rounded-full bg-(--color-surface)');
      expect(readComponent(name)).not.toContain('inline-flex items-center gap-1 rounded-full');
    }
  });

  it('the recipe itself carries no pill', () => {
    const source = readComponent('chromeControl.ts');
    expect(source).not.toContain('rounded-full');
    expect(source).not.toContain('bg-(--color-surface)');
  });

  it('FitControls uses the recipe for both fit buttons', () => {
    const source = readComponent('FitControls.svelte');
    expect(source).toContain("chromeIconButton('action')");
    expect(source).not.toContain('rounded-full');
    expect(source).not.toContain('bg-(--color-surface)');
  });
});

describe('ZoomDropdown announces the right unit (U4.7)', () => {
  it('declares labelKind as a required prop', () => {
    const source = readComponent('ZoomDropdown.svelte');
    expect(source).toContain("labelKind: 'zoom' | 'fontSize'");
    expect(source).not.toContain('labelKind?:');
  });

  it('builds the accessible name from labelKind', () => {
    const source = readComponent('ZoomDropdown.svelte');
    expect(source).toContain("t('reader.zoomLevel'");
    expect(source).toContain("t('reader.font_size_level'");
  });
});
