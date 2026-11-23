/**
 * One look for the reader's two floating menus: the selection toolbar (a new
 * selection) and the highlight context menu (an existing highlight). Same
 * pill, same 32px round actions, same swatches, same divider and tooltip, so
 * they read as one family wherever they open.
 */

export const MENU_ICON_BUTTON =
  'flex size-8 shrink-0 cursor-pointer items-center justify-center rounded-full text-(--color-text-inverse) transition-colors hover:bg-white/10 hover:text-(--color-brand-text) focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-(--color-brand) disabled:cursor-not-allowed disabled:opacity-40 disabled:hover:bg-transparent disabled:hover:text-(--color-text-inverse)';

export const MENU_ICON_BUTTON_DANGER =
  'flex size-8 shrink-0 cursor-pointer items-center justify-center rounded-full text-(--color-error) transition-colors hover:bg-(--color-error)/15 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-(--color-error)';

/** A colour swatch: a 20px dot inside a 28px target. */
export const MENU_SWATCH =
  'flex size-7 shrink-0 cursor-pointer items-center justify-center rounded-full focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-(--color-brand)';
export const MENU_SWATCH_DOT = 'block size-5 rounded-full transition-transform hover:scale-110';
export const MENU_SWATCH_DOT_ACTIVE =
  'ring-2 ring-white ring-offset-2 ring-offset-(--color-highlight-menu-bg)';

export const MENU_DIVIDER = 'mx-1 h-5 w-px shrink-0 bg-white/15';

export const MENU_TOOLTIP =
  'pointer-events-none absolute top-full left-1/2 z-10 mt-2 -translate-x-1/2 whitespace-nowrap rounded-md bg-black/85 px-2 py-1 text-xs font-medium text-white opacity-0 shadow-lg transition-opacity duration-150 group-hover:opacity-100 group-focus-within:opacity-100';
