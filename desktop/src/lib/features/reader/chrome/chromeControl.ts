/** Shared class-string recipe for the reader chrome controls (ADR-003). */

export const CHROME_ICON_BUTTON =
  'flex min-w-11 min-h-11 cursor-pointer items-center justify-center rounded-lg transition-colors';

export const CHROME_ICON_ACTION = `${CHROME_ICON_BUTTON} text-(--color-text-inverse) hover:bg-(--color-surface-hover)`;

export const CHROME_ICON_TOGGLE_IDLE = `${CHROME_ICON_BUTTON} text-(--color-text-auxiliary) hover:bg-(--color-surface-hover)`;

export const CHROME_ICON_TOGGLE_ACTIVE = `${CHROME_ICON_BUTTON} bg-(--color-accent-soft) text-(--color-accent-blue) hover:bg-(--color-surface-hover)`;

export const CHROME_PAGE_FIELD =
  'w-12.5 min-h-11 rounded-md border border-(--color-border) bg-(--color-elevated) px-1 text-center text-xs text-(--color-primary)';

export const CHROME_DIVIDER = 'h-5 w-px bg-(--color-border)';

export function chromeIconButton(variant: 'action' | 'toggle', active = false): string {
  if (variant === 'action') return CHROME_ICON_ACTION;
  return active ? CHROME_ICON_TOGGLE_ACTIVE : CHROME_ICON_TOGGLE_IDLE;
}
