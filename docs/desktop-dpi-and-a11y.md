# Desktop DPI scaling and accessibility baseline

Audit item #19. Split in two halves that are honest about their nature: the DPI
check is a **manual procedure with expected values**, and the accessibility half
is a **code baseline plus what still needs a human**.

## Part 1 — DPI scaling (manual, not automated)

There is no code artefact that can assert "the app looks right at 150%". This is
a repeatable procedure with values to compare against, so a failure can be named
instead of felt.

### Windows

Set scaling per monitor: **Settings → System → Display → Scale**, then relaunch
NEXO (Tauri reads the scale factor at window creation; a live change may leave
the window at the old size).

| Scale | Window at 1280×800 logical | Expected: titlebar height | Expected: body text | Failure to look for |
| --- | --- | --- | --- | --- |
| 100% | 1280×800 px | 36 px (rendered, one line of controls) | 14 px reader body, crisp | — |
| 150% | 1920×1200 px | 54 px (36 × 1.5) | 21 px effective, crisp | Blurry text, 1 px borders doubling, titlebar buttons overflowing |
| 200% | 2560×1600 px | 72 px (36 × 2) | 28 px effective, crisp | Controls clipped, drag region collapsing to zero width |

Pass criteria, all three scales:

1. Window controls (minimize / maximize / close) are fully visible and clickable.
2. The drag region still drags the window from the bar's empty middle.
3. Reader text is sharp — not upscaled — at the fractional scales Windows offers
   (125%, 175% included).
4. Modal dialogs stay inside the viewport at 200% on a 1366×768 laptop panel,
   which is the realistic worst case.

### macOS

Retina is the only supported configuration; there is no user-facing scale slider.

| Display | Expected | Failure to look for |
| --- | --- | --- |
| Retina (2× backing) | Crisp text, 36 pt bar, traffic lights vertically centred against the bar | Traffic lights overlapping drag region, controls offset by a half-pixel |
| Non-Retina / external 1× | Text still legible, bar still 36 pt | Hairline borders disappearing |

macOS traffic-light geometry lives in `tauri.conf.json`
(`trafficLightPosition: {x: 16, y: 20}`) and cannot be assertion-tested on
Windows. Commit `9b5a1b08` owns that layout.

## Part 2 — Accessibility baseline

### What the code now guarantees

`src/lib/shared/ui/layout/CustomTitlebar.svelte` is the window's entire chrome
(`decorations: false` everywhere), so for a screen reader it *is* the app frame.
Before T15 it was a bare `div`.

| Element | Before | After |
| --- | --- | --- |
| Bar root | bare `div` | `role="banner"` + `aria-label="Window"` |
| Branding "NEXO" | loose text announced inline | `aria-hidden="true"` (the landmark carries the name) |
| Drag region | bare empty `div` | `role="presentation"` + `aria-hidden="true"` — pointer affordance, not content |
| Minimize / Close | `aria-label` + `title` (already present) | unchanged |
| Maximize | `aria-label="Maximize"` even when maximized | name flips to `Restore` with state |

Locked by `src/test/unit/components/CustomTitlebar.a11y.test.ts` (5 assertions).

Elsewhere the baseline is unchanged and already holds: 154 files use
`aria-`/`role=`, and six focus-trapped surfaces keep their trap
(`BookmarkSidebar`, `ReaderTextSettings`, `ReaderTocPanel`, `SearchPanel` plus the
Modal/Dropdown/DropMenu adapters). `src/test/unit/guards/focusTrapImporters.test.ts`
enforces the importer set mechanically, so a seventh hand-rolled overlay cannot
appear silently.

### What still needs a screen reader (not verifiable here)

These are recorded rather than claimed:

1. **Landmark announcement.** NVDA/JAWS on Windows and VoiceOver on macOS should
   announce "banner, Window" when entering the bar. The attribute is present; the
   announcement is a runtime behaviour of the AT + WebView2/WKWebView pair.
2. **Drag region skip.** Confirm the AT does not stop on the drag region even
   with `role="presentation"`.
3. **Maximize→Restore announcement timing.** The name changes when
   `onResized` fires; confirm an AT re-reads it rather than caching the old name.
4. **macOS traffic lights under VoiceOver.** The native controls are not in the
   DOM, so VoiceOver's own window-controls group is what a user reaches. Confirm
   there is no duplicate or dead stop between the reserved 78px block and the
   first DOM element.

### Keyboard-only pass (must cover)

Run with the mouse unplugged, at 100% and 200% scale:

1. `Tab` from app launch reaches the titlebar buttons in visual order.
2. `Enter`/`Space` on each fires the action; focus is not lost after maximize.
3. `Escape` closes every open overlay (reader panels, search, settings, modals).
4. Focus is trapped inside each open overlay and restored to its invoker on close.
5. Settings → About → **Copy diagnostics** is reachable by keyboard and its
   status text is announced (it lives in `role="status"` with `aria-live`).
6. No focus lands on a drag region.
