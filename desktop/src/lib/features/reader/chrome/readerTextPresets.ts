import type { ReaderTextAlign, ReaderThemeMode } from '$lib/shared/types';

export const LINE_HEIGHT_PRESETS: readonly number[] = [1.4, 1.6, 1.8, 2.0, 2.2, 2.4] as const;

export const PARAGRAPH_SPACING_PRESETS: readonly number[] = [0, 0.5, 1, 1.5, 2, 3] as const;

/**
 * Margin bounds for the two sliders. The old named bundles (compacto/cómodo/…)
 * are gone: they moved four values at once and never said how much, so the
 * control had to be tried to be understood.
 */
export const MARGIN_MIN_REM = 0;
export const MARGIN_MAX_REM = 4;
export const MARGIN_STEP_REM = 0.25;

export const ALIGN_CYCLE: readonly ReaderTextAlign[] = [
  'left',
  'center',
  'right',
  'justify',
] as const;

/**
 * Reader themes. Four, each with a purpose — `dark` (#444444) was a UI grey
 * with no reading case and `blue` (#5b7fa3) had no principle behind it.
 * `bg` mirrors the surface `getThemeStyles` paints in the chapter iframe.
 */
export const THEME_PRESETS: readonly { name: ReaderThemeMode; bg: string }[] = [
  { name: 'paper', bg: '#faf8f5' },
  { name: 'sepia', bg: '#f5eedd' },
  { name: 'night', bg: '#151b2e' },
  { name: 'oled', bg: '#000000' },
] as const;

/**
 * Reader face options.
 *
 * Every entry must render with certainty inside the chapter iframe:
 *  - `''` is the book's own face (the override rule is simply not emitted).
 *  - The self-hosted families (Newsreader, Literata, Manrope, Atkinson) are
 *    bundled by `styles.css` and re-declared inside the iframe, which is a
 *    separate document and does not inherit the app's @font-face set.
 *  - The three CSS generics always resolve through the OS.
 *
 * Georgia and Palatino were removed on purpose: they are OS face names, so a
 * pick either silently fell back to serif or rendered differently per machine.
 */
export type ReaderFontPreset = {
  /** Stored value: the CSS font-family stack, or '' for the book's own face. */
  value: string;
  /** i18n key for the option label. */
  labelKey: string;
  /** Self-hosted families are re-declared inside the iframe. */
  selfHosted?: true;
};

export const FONT_FAMILY_PRESETS: readonly ReaderFontPreset[] = [
  { value: '', labelKey: 'reader.fontDefault' },
  { value: 'Newsreader', labelKey: 'reader.fontNewsreader', selfHosted: true },
  { value: 'Literata', labelKey: 'reader.fontLiterata', selfHosted: true },
  { value: 'Manrope', labelKey: 'reader.fontManrope', selfHosted: true },
  { value: 'Atkinson Hyperlegible', labelKey: 'reader.fontAtkinson', selfHosted: true },
  { value: 'serif', labelKey: 'reader.fontSerifGeneric' },
  { value: 'sans-serif', labelKey: 'reader.fontSansGeneric' },
  { value: 'monospace', labelKey: 'reader.fontMonoGeneric' },
] as const;

export function cyclePreset<T>(current: T, presets: readonly T[]): T {
  const idx = presets.indexOf(current);
  if (idx >= 0 && idx < presets.length - 1) return presets[idx + 1] as T;
  return presets[0] as T;
}

export function cyclePresetBy<T>(
  current: T,
  presets: readonly T[],
  equal: (a: T, b: T) => boolean,
): T {
  const idx = presets.findIndex((p) => equal(p, current));
  if (idx >= 0 && idx < presets.length - 1) return presets[idx + 1] as T;
  return presets[0] as T;
}
