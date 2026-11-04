/**
 * Where a floating menu goes.
 *
 * A PURE function: numbers in, numbers out. No `window`, no DOM, no state — so
 * the whole placement ruleset is testable in Vitest without jsdom, and the two
 * callers that DO have a viewport (the selection toolbar, the highlight context
 * menu) only have to hand their measurement over.
 *
 * WHY A PREDICTED SIZE AND NOT THE MEASURED ONE
 * ---------------------------------------------
 * The tempting design is "render the menu, measure it, then place it". It is
 * the wrong one: the first frame renders at the provisional position, the
 * measurement lands, and the next frame snaps the menu to its real place. The
 * user sees a jump every time the menu opens. Placing from a size the caller
 * already knows (a fixed menu width, an estimate for a wrapping one) trades a
 * few pixels of precision for a menu that never moves after it appears.
 *
 * WHY THE OVERFLOW CASE RETURNS AN X AND NOT A WIDTH
 * --------------------------------------------------
 * When the menu is wider than the viewport there is no x that keeps it inside,
 * so the function cannot solve it by position alone. It does the only honest
 * thing left: pins the menu to the left padding and lets the caller render it
 * against the usable width (`viewport.width - 2 * edgePadding`). The value
 * returned is never negative — a negative x is what shoves a menu off-screen.
 */

export type FloatingMenuAnchor = {
  left: number;
  right: number;
  top: number;
  bottom: number;
};

export type FloatingMenuSize = {
  width: number;
  height: number;
};

export type FloatingMenuViewport = {
  width: number;
  height: number;
};

export type FloatingMenuOptions = {
  /** Minimum gap kept between the menu and every viewport edge. */
  edgePadding?: number;
  /** Gap between the menu and the anchor's top/bottom on the chosen side. */
  offset?: number;
  /**
   * The height the menu actually renders at, when that is LESS than
   * `menuSize.height`.
   *
   * `menuSize.height` is the box the placement has to RESERVE — a fixed estimate
   * of the row plus whatever a result panel might add to it, which is what the
   * above/below choice and the vertical clamp need. It is not the height the
   * element occupies, and the placement is position-only: it does not size
   * anything. A caller whose real box is shorter (a wrap cap the browser applies
   * inside the budget, a panel that is only sometimes there) says so here, and
   * the Y axis is then clamped against the real box instead of the reserved one.
   *
   * Omit it whenever the rendered box IS `menuSize.height`.
   */
  renderedHeight?: number;
};

export type FloatingMenuPlacement = 'above' | 'below';

export type FloatingMenuPosition = {
  x: number;
  y: number;
  placement: FloatingMenuPlacement;
};

/**
 * How the content has to adapt to the room it was given.
 *
 * Ordered by how much the reader notices it: `full` is the normal menu,
 * `collapsed` keeps every action and folds the palette into one swatch (barely
 * a change), and `wrapped` splits the row so the last resort still fits.
 */
export type FloatingMenuLayout = 'full' | 'collapsed' | 'wrapped';

export type FloatingMenuLayoutInput = {
  /** Natural width of the content when nothing is collapsed. */
  naturalWidth: number;
  /** Room between the viewport edges, already minus the edge padding. */
  availableWidth: number;
};

export type FloatingMenuLayoutOptions = {
  /**
   * Natural width with the palette folded into a single trigger. Omit it when
   * the caller has nothing to collapse and the ladder starts at `wrapped`.
   */
  collapsedWidth?: number;
};

const DEFAULT_EDGE_PADDING = 8;
const DEFAULT_OFFSET = 16;

/**
 * The room a caller can set aside BELOW its menu for a result panel, as a
 * multiple of the menu row itself.
 *
 * The selection toolbar renders its definition and translation panels as
 * siblings below the pill, each exactly as wide as the menu the placement
 * believes it is rendering — and `placeFloatingMenu` can only clamp the box it
 * is told about. A clamp that budgets the single 56px row lets a 300px
 * definition panel hang past the bottom edge of the screen. With this factor
 * the caller adds "a panel's worth of room" to the height it hands over: the
 * `above`/`below` choice then accounts for the panel, and the vertical clamp
 * keeps the whole thing on screen.
 *
 * Derived from the layout, not measured: a measured panel is the
 * place-then-correct jump {@link placeFloatingMenu} exists to avoid, and being
 * a few pixels optimistic moves the panel up a few pixels. The row already
 * carries the safety margins the width constants document, so a modest
 * multiplier is enough to cover the gaps and a wrapped row.
 */
export const PANEL_HEIGHT_FACTOR = 2;

/**
 * Places `menuSize` next to `anchor` inside `viewport`.
 *
 * Vertically the menu prefers the side with room for it and falls back to
 * whichever side has more space — never to a coordinate outside the viewport.
 */
export function placeFloatingMenu(
  anchor: FloatingMenuAnchor,
  menuSize: FloatingMenuSize,
  viewport: FloatingMenuViewport,
  options: FloatingMenuOptions = {},
): FloatingMenuPosition {
  const edgePadding = options.edgePadding ?? DEFAULT_EDGE_PADDING;
  const offset = options.offset ?? DEFAULT_OFFSET;

  const centerX = (anchor.left + anchor.right) / 2;
  // Clamp the LEFT edge of a centered menu. When the menu is wider than the
  // usable band, `max` wins and the menu pins to the left padding instead of
  // going negative, which is the only value that stays on screen.
  const maxX = viewport.width - menuSize.width - edgePadding;
  const x = Math.max(edgePadding, Math.min(centerX - menuSize.width / 2, maxX));

  const spaceAbove = anchor.top - offset;
  const spaceBelow = viewport.height - anchor.bottom - offset;

  let placement: FloatingMenuPlacement;
  if (spaceAbove >= menuSize.height) {
    placement = 'above';
  } else if (spaceBelow >= menuSize.height) {
    placement = 'below';
  } else {
    placement = spaceAbove >= spaceBelow ? 'above' : 'below';
  }

  const rawY =
    placement === 'above' ? anchor.top - menuSize.height - offset : anchor.bottom + offset;
  // The Y clamp is against the box that will be ON SCREEN, not against the box
  // that was reserved: a menu whose real height is under its budget can sit
  // lower and still keep its bottom edge inside the viewport. Clamping the
  // reserved height instead is what pinned a two-line menu to the top of a
  // tall-but-busy anchor.
  const occupiedHeight = Math.min(menuSize.height, options.renderedHeight ?? menuSize.height);
  const maxY = Math.max(0, viewport.height - occupiedHeight);
  const y = Math.max(0, Math.min(rawY, maxY));

  return { x, y, placement };
}

/**
 * The usable width inside `viewport` once both edges keep their gap.
 *
 * Kept beside the placement function because it is the SAME band the clamp
 * works in: `max(0, viewport.width - 2 * edgePadding)`. A caller that compares
 * its own natural width against something else is measuring a different box.
 */
export function floatingMenuAvailableWidth(
  viewport: FloatingMenuViewport,
  options: FloatingMenuOptions = {},
): number {
  return Math.max(0, viewport.width - 2 * (options.edgePadding ?? DEFAULT_EDGE_PADDING));
}

/**
 * Picks the layout that fits, taking the least disruptive rung that does:
 *
 *   0. `full`      — the content fits as it is. Nothing changes, ever.
 *   1. `collapsed` — it fits with the palette folded into a single trigger.
 *   2. `wrapped`   — the row breaks onto two lines so the band can hold it.
 *
 * ## The order, and why it is three rungs and not two
 *
 * The rungs are ordered by how disruptive they are to the reader, and each is
 * NARROWER than the one before it:
 *
 *   full      380px   the ordinary pill, five colour circles
 *   collapsed 240px   one palette trigger replaces the five circles
 *   wrapped   148px   the four actions on their own line (`min-w-37`)
 *
 * Collapsing is a smaller visual change than wrapping, so the ladder descends
 * one step at a time instead of falling straight to the extreme. The widths are
 * the caller's because the decision must NOT be made by measuring the rendered
 * element: a menu that renders first and re-lays-out after its measurement lands
 * shows a jump every time it opens. Predicted widths are occasionally a few
 * pixels off, and the placement clamp absorbs that — the layout only has to be
 * right enough to stop the row from leaving the screen.
 *
 * ## Why the ladder has to be able to REACH `wrapped`
 *
 * A band narrower than the collapsed pill is not a rare corner: this app's Tauri
 * window boots at ~270px (MEASURED, see `e2e/tauri/readerSelectionCollapsed.spec.ts`),
 * and the band here is `innerWidth - 2 * edgePadding` = 238px — two pixels under
 * the 240px collapsed pill. Returning `collapsed` there hands the reader a menu
 * wider than the room it has, and the wrap rung that exists precisely for that
 * case is never selected. The rungs below the collapsed width must keep
 * descending until one fits.
 *
 * The rung returned is the FIRST that fits, and `wrapped` when none does — the
 * extreme case stays the extreme case rather than becoming the answer for every
 * narrow window.
 */
export function resolveFloatingMenuLayout(
  input: FloatingMenuLayoutInput,
  options: FloatingMenuLayoutOptions = {},
): FloatingMenuLayout {
  const { naturalWidth, availableWidth } = input;

  if (naturalWidth <= availableWidth) return 'full';

  const { collapsedWidth } = options;
  if (collapsedWidth === undefined) {
    // Nothing to collapse: the ladder starts at its last rung.
    return 'wrapped';
  }

  if (collapsedWidth <= availableWidth) return 'collapsed';

  // Below the collapsed width neither the full row nor the pill fits, so the
  // only rung left that can is the wrap. It is the NARROWER of the two
  // remaining options by construction — the caller floors the wrapped row at
  // `min-w-37` (148px against the 240px pill) — so descending here gives the
  // reader LESS width than the pill they asked to leave, not more.
  return 'wrapped';
}
