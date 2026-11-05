/**
 * Which floating menu the reader workspace is showing.
 *
 * There is at most one. That is the entire point of this module: the reader
 * shows the selection toolbar (Select text → colour it) OR the highlight
 * context menu (click an existing highlight → recolour / note / tag / delete),
 * and never both.
 *
 * ## Why a module and not two booleans in a component
 *
 * The menus used to be owned by two independent pieces of state — the
 * workspace's own `showToolbar` and `useHighlightMenu`'s `highlightMenu.open` —
 * and the frame's selection script had a third opinion. With three writers, "one
 * menu" was not a rule but a coincidence of ordering, and the reported defect was
 * exactly the coincidence failing: select a run that CONTAINS an existing
 * highlight, and the click handler raises the highlight menu while the
 * `mouseup` handler raises the toolbar. Both are open, both then see the other's
 * dismissal, and the reader ends up with neither.
 *
 * Here "one menu" is an invariant, not an ordering: `openMenu` is a total
 * function whose result names the menu that is open, so two open menus is not a
 * representable state. The transitions are pure and DOM-free, so they can be
 * asserted directly — the live-app behaviour is covered separately by the CDP
 * specs.
 */
export type OpenMenu = 'none' | 'selection' | 'highlight';

/** Which of the two menus a caller wants to raise. */
export type OpenMenuKind = Exclude<OpenMenu, 'none'>;

/**
 * Why a menu was closed. Every close path names itself, so a menu that fails to
 * disappear can be attributed to the path that ran (the workspace forwards the
 * reason to the log and the debug ring). The value is not interpreted here —
 * `closeMenu` produces `none` for any reason, including one this union does not
 * yet list.
 */
export type OpenMenuReason =
  | 'workspace-pointerdown'
  | 'color-applied'
  | 'inside-click'
  | 'escape'
  | 'scroll'
  | 'page-turn'
  | 'pagehide'
  | 'toc-navigate'
  | 'header-goto-page'
  | 'prev-page'
  | 'next-page'
  | 'pdf-onselectionclear'
  | 'selection-collapsed'
  | 'mouseup-collapsed'
  | 'iframe-blur'
  | 'highlight-deleted'
  | 'highlight-copied'
  | 'book-changed'
  | 'cleanup'
  | 'unknown';

/**
 * Raise `next`, closing whatever was open.
 *
 * The invariant "opening one closes the other" is enforced in the SAME step, not
 * by a listener reacting afterwards: the previous menu is not merely asked to
 * close, it is not open, because the result names one menu. Re-opening the menu
 * already open is idempotent.
 */
export function openMenu(_state: OpenMenu, next: OpenMenuKind): OpenMenu {
  return next;
}

/**
 * Close whatever is open.
 *
 * Total by construction: every reason, including an unlisted one, closes to
 * `none`, and closing an already-closed workspace is a no-op rather than an
 * error. A close is never a way to open.
 */
export function closeMenu(_state: OpenMenu, _reason: OpenMenuReason | string): OpenMenu {
  return 'none';
}
