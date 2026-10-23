/** Shared class-string recipe for the reader chrome controls (ADR-003). */

export const CHROME_ICON_BUTTON =
  'flex min-w-11 min-h-11 cursor-pointer items-center justify-center rounded-lg transition-colors';

export const CHROME_ICON_ACTION = `${CHROME_ICON_BUTTON} text-(--color-text-inverse) hover:bg-(--color-surface-hover)`;

export const CHROME_ICON_TOGGLE_IDLE = `${CHROME_ICON_BUTTON} text-(--color-text-auxiliary) hover:bg-(--color-surface-hover)`;

export const CHROME_ICON_TOGGLE_ACTIVE = `${CHROME_ICON_BUTTON} bg-(--color-accent-soft) text-(--color-accent-blue) hover:bg-(--color-surface-hover)`;

export const CHROME_PAGE_FIELD =
  // `max-w-12.5` is load-bearing, not decoration. `styles.css` sets
  // `input[type='text'] { width: 100% }` OUTSIDE any cascade layer, so it beats
  // every layered width utility; the field measured 149px instead of its 50px
  // recipe and squeezed the "/ M" label beside it down to an 18px column, where
  // the slash and the number each took a line. A max-width is not what that
  // rule sets, so it wins. `py-1` replaces the 10px vertical padding it
  // inherited for the same reason the bar cannot be a fixed height: the field
  // should never be the tallest thing in the row.
  'w-12.5 max-w-12.5 min-h-11 rounded-md border border-(--color-border) bg-(--color-elevated) px-1 py-1 text-center text-xs tabular-nums text-(--color-primary)';

export const CHROME_DIVIDER = 'h-5 w-px bg-(--color-border)';

export function chromeIconButton(variant: 'action' | 'toggle', active = false): string {
  if (variant === 'action') return CHROME_ICON_ACTION;
  return active ? CHROME_ICON_TOGGLE_ACTIVE : CHROME_ICON_TOGGLE_IDLE;
}
