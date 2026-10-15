import { describe, expect, it } from 'vitest';
import {
  ALIGN_CYCLE,
  FONT_FAMILY_PRESETS,
  LINE_HEIGHT_PRESETS,
  MARGIN_PRESETS,
  PARAGRAPH_SPACING_PRESETS,
  THEME_PRESETS,
  cyclePreset,
  cyclePresetBy,
} from '$lib/features/reader/chrome/readerTextPresets';

describe('readerTextPresets', () => {
  it('exports 5 preset groups with expected lengths', () => {
    expect(LINE_HEIGHT_PRESETS).toHaveLength(6);
    expect(PARAGRAPH_SPACING_PRESETS).toHaveLength(6);
    expect(MARGIN_PRESETS).toHaveLength(5);
    expect(ALIGN_CYCLE).toHaveLength(4);
    expect(THEME_PRESETS).toHaveLength(4);
  });

  it('offers four themes, each with a distinct surface and a reason to exist', () => {
    expect(THEME_PRESETS.map((t) => t.name)).toEqual(['paper', 'sepia', 'night', 'oled']);
    // No two themes may share a background, or the swatch row is a lie.
    const bgs = THEME_PRESETS.map((t) => t.bg);
    expect(new Set(bgs).size).toBe(bgs.length);
    // OLED is the true-black surface; night must not be pure black.
    expect(THEME_PRESETS.find((t) => t.name === 'oled')?.bg).toBe('#000000');
    expect(THEME_PRESETS.find((t) => t.name === 'night')?.bg).not.toBe('#000000');
  });

  it('FONT_FAMILY_PRESETS has 8 entries with the book face first', () => {
    expect(FONT_FAMILY_PRESETS).toHaveLength(8);
    expect(FONT_FAMILY_PRESETS[0]?.value).toBe('');
  });

  it('every preset carries a label key, and only real faces are self-hosted', () => {
    for (const preset of FONT_FAMILY_PRESETS) {
      expect(preset.labelKey).toBeTruthy();
      // Generic CSS families resolve through the OS; they must not claim a face.
      if (['serif', 'sans-serif', 'monospace', ''].includes(preset.value)) {
        expect(preset.selfHosted).toBeUndefined();
      } else {
        expect(preset.selfHosted).toBe(true);
      }
    }
  });

  it('LINE_HEIGHT_PRESETS values are sorted ascending 1.4..2.4', () => {
    expect([...LINE_HEIGHT_PRESETS]).toEqual([1.4, 1.6, 1.8, 2.0, 2.2, 2.4]);
  });

  it('MARGIN_PRESETS top/left progression', () => {
    expect(MARGIN_PRESETS[0]).toEqual({ top: 0.5, bottom: 0.5, left: 0.75, right: 0.75 });
    expect(MARGIN_PRESETS[4]).toEqual({ top: 2.5, bottom: 2.5, left: 3, right: 3 });
  });
});

describe('cyclePreset generic', () => {
  it('cycles to next element', () => {
    expect(cyclePreset(1.4, LINE_HEIGHT_PRESETS)).toBe(1.6);
    expect(cyclePreset('left', ALIGN_CYCLE)).toBe('center');
    expect(cyclePreset('center', ALIGN_CYCLE)).toBe('right');
  });

  it('wraps from last to first', () => {
    expect(cyclePreset(2.4, LINE_HEIGHT_PRESETS)).toBe(1.4);
    expect(cyclePreset(3, PARAGRAPH_SPACING_PRESETS)).toBe(0);
    expect(cyclePreset('justify', ALIGN_CYCLE)).toBe('left');
    expect(
      cyclePreset(
        'Newsreader',
        FONT_FAMILY_PRESETS.map((f) => f.value),
      ),
    ).toBe('Literata');
  });

  it('returns first when current not in array', () => {
    expect(cyclePreset(99 as never, LINE_HEIGHT_PRESETS)).toBe(1.4);
    expect(cyclePreset('unknown' as never, ALIGN_CYCLE)).toBe('left');
  });

  it('works for theme names', () => {
    const themeNames = THEME_PRESETS.map((t) => t.name);
    expect(cyclePreset('paper', themeNames)).toBe('sepia');
    expect(cyclePreset('blue', themeNames)).toBe('paper');
  });

  it('cyclePresetBy handles object presets with deep equality', () => {
    const first = MARGIN_PRESETS[0];
    const last = MARGIN_PRESETS[4];
    const equal = (
      a: (typeof MARGIN_PRESETS)[number],
      b: (typeof MARGIN_PRESETS)[number],
    ): boolean =>
      a.top === b.top && a.bottom === b.bottom && a.left === b.left && a.right === b.right;

    // copy with same values but different reference should cycle
    const copyFirst = { ...first };
    expect(cyclePresetBy(copyFirst, MARGIN_PRESETS, equal)).toEqual(MARGIN_PRESETS[1]);

    const copyLast = { ...last };
    expect(cyclePresetBy(copyLast, MARGIN_PRESETS, equal)).toEqual(MARGIN_PRESETS[0]);

    // not found → first
    const unknown = { top: 9, bottom: 9, left: 9, right: 9 } as never;
    expect(cyclePresetBy(unknown, MARGIN_PRESETS, equal)).toEqual(MARGIN_PRESETS[0]);
  });
});
