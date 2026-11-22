/**
 * Layout recipes for the NEXO ember language (DESIGN.md, "NEXO Ember"). Controls
 * are atoms (`Button`, `FilterChip`); these are the few non-control patterns the
 * redesigned screens share.
 */

export const focusRing =
  'focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-(--color-brand) focus-visible:ring-offset-2 focus-visible:ring-offset-(--color-background)';

/** A sibling block on the shell ground (never nested in another). */
export const panelClass =
  'rounded-(--radius-lg) border border-(--color-edge) bg-(--color-home-card)';

/** The vertical ember bar that marks a page or section heading. */
export const headingMarker =
  'w-1 shrink-0 rounded-full bg-linear-to-b from-(--color-brand-start) to-(--color-brand-end)';

/** Book-first catalogue grid: columns size to a cover, not to the window. */
export const coverGrid = 'grid grid-cols-[repeat(auto-fill,minmax(9.5rem,1fr))] gap-x-4 gap-y-8';

/** A rail is one row of DISCOVER_RAIL_LIMIT (6) covers; narrow windows split it 3 + 3. */
export const railGrid = 'grid grid-cols-3 gap-x-4 gap-y-8 sm:grid-cols-6';
