/**
 * Which selection-menu feedback RESERVES space in the placement, and which does
 * not.
 *
 * ## The two kinds of answer, and why they are not the same question
 *
 * The toolbar shows two different things below its pill, and only one of them is
 * a result panel:
 *
 *   - **Feedback** — Copy and Add to Dictionary. A one-line message that names
 *     what just happened ("Copied", "Added to the dictionary"). It is transient
 *     (1.5s), it is short, and it is about an action the reader already knew the
 *     outcome of.
 *   - **Results** — Translate and Define. A real panel carrying the service's
 *     answer, which is data-dependent and can be many lines tall.
 *
 * Both render through `role="status"` and both are siblings below the pill, so
 * the earlier code put all six states in one bag — `resultPanelOpen` — and let
 * the panel budget be reserved whenever ANY of them was set.
 *
 * That is what produced the reported defect: pressing Copy raised a one-line
 * message, the placement suddenly believed the menu was ~120px taller, the menu
 * was moved UP to make room for a panel that was never going to need it, and a
 * moment later — when the message expired — it moved back DOWN. The reader sees
 * a menu that jumps at every button press.
 *
 * The distinction is therefore not cosmetic: only the two that carry a RESULT
 * have a height the placement cannot predict, and only those may move the menu.
 * Feedback keeps rendering exactly as before — it simply stops reserving room
 * (and therefore stops moving the menu).
 *
 * ## Why this is a function and not an inline expression
 *
 * It is the DECISION, and it is pure: six booleans in, one out, no DOM and no
 * viewport. jsdom cannot lay anything out, so the placement itself is only
 * testable as arithmetic; keeping the "does this reserve?" rule out here is what
 * makes the table above a unit test rather than a comment nobody executes.
 */

/** The six states the menu can be showing something for. */
export type SelectionPanelState = {
  copyFeedback: boolean;
  dictionaryFeedback: boolean;
  translating: boolean;
  translation: boolean;
  defining: boolean;
  definition: boolean;
  definitionFailed: boolean;
};

/**
 * Whether a RESULT panel is on screen — Translate or Define only.
 *
 * This is the set whose height the placement cannot predict, so it is the set
 * the panel budget is reserved for. Copy and Dictionary feedback are deliberately
 * NOT here: they are a single short line, and reserving panel room for them is
 * what moved the menu on every press.
 */
export function reservesResultPanelSpace(state: SelectionPanelState): boolean {
  return Boolean(
    state.translating ||
    state.translation ||
    state.defining ||
    state.definition ||
    state.definitionFailed,
  );
}

/**
 * Whether SHORT feedback is on screen — Copy or Add to Dictionary.
 *
 * It renders, it just does not reserve. Kept as its own named predicate so the
 * rendering condition and the reservation condition cannot drift apart by
 * accident: a state that is in neither function renders nowhere and reserves
 * nothing, which is a bug the tests below make loud.
 */
export function showsShortFeedback(state: SelectionPanelState): boolean {
  return Boolean(state.copyFeedback || state.dictionaryFeedback);
}

/**
 * Whether a button tooltip may be shown right now.
 *
 * ## The defect this answers
 *
 * The tooltips used to reveal on `group-hover` AND `group-focus-within`. Pressing
 * a button gives it focus, so the tooltip stayed up — and it sits in the same
 * band below the pill as the feedback / result panel, so "Copiar" and "Copiado al
 * portapapeles" were drawn on top of each other and neither could be read.
 *
 * The reveal itself is not the bug, so it is NOT removed: `group-focus-within` is
 * what makes the tooltip visible to someone navigating with Tab, and that reader
 * has no hover. The bug is the tooltip sharing the screen with an ANSWER. So the
 * rule is about the answer, not about the press:
 *
 *   - nothing below the pill  -> the tooltip may show (hover, focus, both)
 *   - something below the pill -> no tooltip, whatever the pointer and focus do
 *
 * "Something below the pill" is every state the pane can render — feedback and
 * result alike — which is exactly the union the two predicates above already
 * describe. Written as that union so a new panel state cannot be added to one
 * list and forgotten in the other.
 *
 * It is here, and pure, for the usual reason: jsdom cannot decide "would these
 * two boxes overlap?", but it can execute this table.
 */
export function showsTooltips(state: SelectionPanelState): boolean {
  return !showsShortFeedback(state) && !reservesResultPanelSpace(state);
}
