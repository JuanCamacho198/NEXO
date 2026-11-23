/** Shared class-string recipe for the reader chrome controls (ADR-003). */

// NEXO ember (DESIGN.md): chrome controls are round, quiet until hovered, and an
// open panel's toggle takes the ember wash. The paper plane is still never worn
// by a control.
export const CHROME_ICON_BUTTON =
  'flex min-w-11 min-h-11 cursor-pointer items-center justify-center rounded-full transition-colors focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-(--color-brand) disabled:cursor-not-allowed disabled:opacity-35';

export const CHROME_ICON_ACTION = `${CHROME_ICON_BUTTON} text-(--color-primary) hover:bg-(--color-surface-hover) disabled:hover:bg-transparent`;

export const CHROME_ICON_TOGGLE_IDLE = `${CHROME_ICON_BUTTON} text-(--color-text-muted) hover:bg-(--color-surface-hover) hover:text-(--color-primary)`;

export const CHROME_ICON_TOGGLE_ACTIVE = `${CHROME_ICON_BUTTON} bg-(--color-brand-soft) text-(--color-brand-text)`;

export const CHROME_PAGE_FIELD =
  // `max-w-12.5` is load-bearing, not decoration. `styles.css` sets
  // `input[type='text'] { width: 100% }` OUTSIDE any cascade layer, so it beats
  // every layered width utility; the field measured 149px instead of its 50px
  // recipe and squeezed the "/ M" label beside it down to an 18px column, where
  // the slash and the number each took a line. A max-width is not what that
  // rule sets, so it wins. The same unlayered rule paints a border, a surface
  // and an 8px radius, hence the `!` on those three: the field sits bare inside
  // the pager track and only lights up on hover and focus.
  'w-12.5 max-w-12.5 min-h-11 rounded-full! border-transparent! bg-transparent! px-1 py-1 text-center text-sm font-semibold tabular-nums text-(--color-primary) transition-colors hover:bg-(--color-surface-hover)! focus:border-(--color-brand)! focus:outline-none';

/** The pager track: previous, the page field and next read as one instrument. */
export const CHROME_PAGER_TRACK =
  'flex shrink-0 items-center gap-0.5 rounded-full border border-(--color-edge) bg-(--color-home-card) p-0.5';

export const CHROME_DIVIDER = 'h-5 w-px bg-(--color-border)';

export function chromeIconButton(variant: 'action' | 'toggle', active = false): string {
  if (variant === 'action') return CHROME_ICON_ACTION;
  return active ? CHROME_ICON_TOGGLE_ACTIVE : CHROME_ICON_TOGGLE_IDLE;
}
