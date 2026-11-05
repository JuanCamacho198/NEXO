/**
 * The marker that says "this surface belongs to a floating reader menu".
 *
 * ## The rule it encodes
 *
 * `ReaderWorkspace` listens for `pointerdown` on `window` in the CAPTURE phase
 * and dismisses both floating menus, so a press anywhere else closes them. A
 * press that landed INSIDE one of them must not: the button the reader aimed at
 * has to receive its own `click`, which is the very next event.
 *
 * The menus cannot defend themselves. `HighlightContextMenu` stops propagation on
 * `click` — that is bubble phase, and the workspace's dismissal is capture-phase
 * `pointerdown`, which always runs first. MEASURED on the live app, pressing the
 * menu's Delete button:
 *
 *   t+ 43.3ms  input:pointerdown   svg.h-4 w-4 <<IN-HIGHLIGHT-MENU>> @247,171
 *   t+ 43.5ms  dismissToolbar      workspace-pointerdown
 *   t+ 50.7ms  input:pointerup     div.fixed inset-0 z-[99]   <- the menu is gone
 *   t+ 50.9ms  input:click         div.fixed inset-0 z-[99]   <- the click hit the backdrop
 *
 * — the menu was unmounted by the press itself, the `click` landed on the
 * backdrop behind it, and the action never ran.
 *
 * ## Why an attribute and not a class list in the guard
 *
 * The surfaces are separate components, and the highlight menu also opens a
 * colour picker and a tag popover. Enumerating them in the guard means a sixth
 * surface silently inherits the defect; naming the rule once, here, and marking
 * every surface with the attribute is what makes the exemption the property of
 * being a floating menu rather than a list someone remembered to update.
 *
 * The attribute is written literally (`data-floating-menu="true"`) in each
 * surface's root element: Svelte cannot interpolate an attribute NAME, and a
 * spread of a computed key would hide the marker from a plain grep. The surfaces
 * that carry it today:
 *
 *   - `highlight/SelectionToolbar.svelte`   (the selection menu)
 *   - `highlight/HighlightContextMenu.svelte`
 *   - `highlight/ColorPickerPopover.svelte` (opened by the menu's colour trigger)
 *   - `highlight/TagPopover.svelte`         (opened by the menu's tag trigger)
 */
export const FLOATING_MENU_SELECTOR = '[data-floating-menu]';
