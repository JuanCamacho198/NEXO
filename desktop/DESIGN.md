---
name: NEXO
description: A continuous reading environment for importing, organizing, and reading books across desktop and mobile.
colors:
  ground: "#08111f"
  surface: "rgba(16, 28, 44, 0.84)"
  surface-elevated: "#0f1d30"
  surface-recessed: "rgba(9, 17, 29, 0.6)"
  rail: "#0b1626"
  ink: "#f8fbff"
  ink-secondary: "#dbe7f6"
  ink-muted: "#8fa3bf"
  hairline: "rgba(148, 173, 206, 0.18)"
  hairline-strong: "rgba(148, 173, 206, 0.3)"
  nexo-blue: "#4e8cff"
  nexo-cyan: "#49d4ff"
  signal-wash: "rgba(73, 212, 255, 0.1)"
  signal-ink: "#061222"
  coral: "#ffb59a"
  coral-wash: "rgba(255, 181, 154, 0.12)"
  error: "#ff7b83"
  danger: "#ff9fa5"
  success: "#4ade80"
  warning: "#fbbf24"
typography:
  display:
    fontFamily: "Manrope, Segoe UI, Arial, sans-serif"
    fontSize: "2.5rem"
    fontWeight: 700
    lineHeight: 1.2
  page:
    fontFamily: "Manrope, Segoe UI, Arial, sans-serif"
    fontSize: "1.875rem"
    fontWeight: 600
    lineHeight: 1.4
  title:
    fontFamily: "Manrope, Segoe UI, Arial, sans-serif"
    fontSize: "1.25rem"
    fontWeight: 600
    lineHeight: 1.4
  body:
    fontFamily: "Manrope, Segoe UI, Arial, sans-serif"
    fontSize: "1rem"
    fontWeight: 400
    lineHeight: 1.5
  label:
    fontFamily: "Manrope, Segoe UI, Arial, sans-serif"
    fontSize: "0.875rem"
    fontWeight: 500
    lineHeight: 1.5
  micro:
    fontFamily: "Manrope, Segoe UI, Arial, sans-serif"
    fontSize: "0.75rem"
    fontWeight: 600
    lineHeight: 1.5
    letterSpacing: "0.18em"
  reading:
    fontFamily: "Newsreader, Times New Roman, serif"
    fontSize: "1.125rem"
    fontWeight: 400
    lineHeight: 1.7
rounded:
  sm: "8px"
  md: "12px"
  lg: "16px"
  xl: "24px"
  2xl: "28px"
spacing:
  xs: "4px"
  sm: "8px"
  md: "16px"
  lg: "24px"
  xl: "32px"
components:
  button-primary:
    backgroundColor: "{colors.ink}"
    textColor: "{colors.ground}"
    rounded: "{rounded.lg}"
    padding: "8px 16px"
  button-accent:
    backgroundColor: "{colors.nexo-blue}"
    textColor: "{colors.signal-ink}"
    rounded: "{rounded.lg}"
    padding: "8px 16px"
  button-secondary:
    backgroundColor: "{colors.surface}"
    textColor: "{colors.ink}"
    rounded: "{rounded.lg}"
    padding: "8px 16px"
  button-danger:
    backgroundColor: "{colors.error}"
    textColor: "{colors.ground}"
    rounded: "{rounded.lg}"
    padding: "8px 16px"
  panel:
    backgroundColor: "{colors.surface}"
    rounded: "{rounded.xl}"
    padding: "16px"
  card:
    backgroundColor: "{colors.surface}"
    rounded: "{rounded.xl}"
    padding: "16px"
  input:
    backgroundColor: "{colors.surface}"
    textColor: "{colors.ink}"
    rounded: "{rounded.sm}"
    padding: "10px"
  chip:
    backgroundColor: "{colors.surface}"
    textColor: "{colors.ink}"
    rounded: "9999px"
    padding: "2px 10px"
---

# Design System: NEXO

## Overview

**Creative North Star: "The Reading Study"**

NEXO's desktop is a private study for one reader. It is furnished quietly: a deep navy room, a single cool blue light that marks where information and the current action live, and one warm counterpoint used sparingly when a second, lesser action must be distinguishable. Nothing in the room competes with the book. The layout is a desk, not a dashboard — the active reading session holds the largest surface, and everything administrative recedes to the edges.

The system is deliberately low-contrast in its structure and high-contrast in its content. Surfaces stack as thin-bordered, softly shadowed planes over a navy ground rather than as nested cards, and the only saturated color on a typical screen is the single accent that answers "what can I do here?". Depth is ambient, never theatrical: shadows are diffuse and dark-on-dark in the default theme, and the one place glow is allowed is beneath a live accent element, never as decoration.

This world was chosen against a confirmed anti-reference: the generic dark-SaaS dashboard — a glass hero, gradient-text headlines, a wall of equal KPI tiles, and cyan glow used as ornament. NEXO keeps the navy and the cool blue but refuses the stock dashboard grammar. The difference is legible in the reading surfaces: real cover artwork, named progress, tinted fields instead of glowing pills, and a warm coral that reads as "import", not as a second brand color.

**Key Characteristics:**

- A single deep navy ground with two theme modes, dark by default.
- One cool blue signal (`nexo-blue` → `nexo-cyan`) for information and reading actions; one warm coral for the secondary, non-destructive action.
- Tonal layering with thin hairline borders and diffuse ambient shadows; no nested cards, no glass as ornament.
- A serif reserved for reading text; a geometric sans for the entire interface.
- Consistent rounded geometry (8–28px) with generous internal padding and calm, slow state transitions.
- Every state — loading, empty, unavailable, error — is designed to explain itself in place.

## Colors

A calm nocturnal palette: a navy ground, a cool blue signal, and one warm counterpoint, with a neutral ink hierarchy carrying all text.

### Primary

- **NEXO Blue** (#4e8cff): The information and action signal. It is the start of the accent gradient, the color of active navigation, the focus ring's normative hue, and the fill of the primary reading CTA.
- **NEXO Cyan** (#49d4ff): The light end of the same signal. It pairs with NEXO Blue in gradients (`{colors.nexo-blue}` → `{colors.nexo-cyan}`) and reads as the brighter accent on dark surfaces. `signal-wash` (rgba(73, 212, 255, 0.1)) is its tinted floor for emphasis backgrounds — never a surface tint for whole panels.

### Secondary

- **Reader Coral** (#ffb59a): A warm, deliberately secondary action color, owned by "Import". Its dark value is a soft coral; `coral-wash` (rgba(255, 181, 154, 0.12)) is the quiet field behind it. It stays clearly distinct from `error` and must never grow into a second primary.

### Tertiary

- **Reading Amber** (#fbbf24): Reserved for the reading streak and warning states. It is a state color, not an accent; it never labels a primary action.

### Neutral

- **Deep Navy Ground** (#08111f): The page. The body background is a layered gradient built from this navy plus two soft radial washes, not a flat fill.
- **Glass Surface** (rgba(16, 28, 44, 0.84)): The default plane for panels, cards, and modals, typically with `backdrop-blur`.
- **Elevated Slate** (#0f1d30): The solid raised plane for menus and popovers that must sit above a blurred surface.
- **Recessed Surface** (rgba(9, 17, 29, 0.6)): A darker, read-only plane (`variant="surface"`) that lets a block recede while its text keeps AA contrast.
- **Rail Navy** (#0b1626): The sidebar's distinct plane, slightly darker than the page so the navigation reads as chrome.
- **Ink White** (#f8fbff): Primary text and headings — and, by inversion, the fill of the `primary` button.
- **Mist Blue** (#dbe7f6): Secondary text and labels.
- **Quiet Slate** (#8fa3bf): Muted text, placeholders, metadata, icon default.
- **Hairline** (rgba(148, 173, 206, 0.18)): The default border. **Hairline Strong** (rgba(148, 173, 206, 0.3)) is for hover and active edges only.

### Light theme counterparts

The same roles invert on `[data-theme='light']`: ground becomes `#eef2f7`, surfaces become white-tinted, ink becomes `#111827`, the signal deepens to `#006ec8`, and the coral darkens to `#9a3412` to stay legible. Every value above is defined once in `src/lib/shared/styles/tokens.css`; the theme blocks are the source of truth and no component may restate them.

### Named Rules

**The One Signal Rule.** A single cool blue answers "what is actionable here". Accent coverage stays under roughly a tenth of any screen; its rarity is what makes it read.

**The Warm Counterpoint Rule.** Coral exists only for the secondary, non-destructive action (import). It is never used for navigation, never for a second concurrent CTA, and never for error — `error` and `danger` own those.

**The Token-Only Rule.** Components reference semantic tokens, never raw hex or `rgba()`. A theme-dependent color hardcoded in a component (`red-50`, `bg-[rgba(...)]`) is a bug, not a style.

## Typography

**Display Font:** Manrope (with Segoe UI, Arial, sans-serif)
**Body Font:** Manrope (with Segoe UI, Arial, sans-serif)
**Reading Font:** Newsreader (with Times New Roman, serif)

**Character:** A geometric, quietly technical sans carries the whole interface — navigation, controls, data — while a warm literary serif is reserved for the act of reading. The pairing separates "running the app" from "reading the book" with a single type change.

### Hierarchy

- **Display** (700, 2.5rem / `--text-4xl`, 1.2): Rare hero headings and the welcome screen only.
- **Page** (600, 1.875rem / `--text-3xl`, 1.4): Route and tab titles.
- **Title** (600, 1.25rem / `--text-xl`, 1.4): Section and panel headings (`SectionHeader`, `Panel`).
- **Body** (400, 1rem / `--text-base`, 1.5): Default interface text. Keep reading-width prose to a comfortable measure.
- **Label** (500, 0.875rem / `--text-sm`, 1.5): Field labels, control text, metadata, and every button. A button is always Label; its `size` changes padding, never the type.
- **Micro** (600, 0.75rem uppercase + 0.18em tracking / `--text-xs`, and 0.625rem / `--text-micro`): Uppercase micro-labels and helper or status copy only — never body text and never button labels.
- **Reading** (400, 1.125rem Newsreader / `--text-lg`, 1.7): Book content inside the reader. The reader's own text settings override size and family at runtime.

### Named Rules

**The Sans-Interface Rule.** Manrope carries every control, label, and heading. Newsreader appears only where the product is showing a book or a reading moment; never for UI chrome.

**The Micro-Label Rule.** Uppercase labels (panel hints, KPI captions) stay at `--text-xs` or `--text-micro` with generous tracking, and never carry body text.

## Layout

A two-column application shell: a fixed navigation rail beside a scrolling content region. The rail is `256px` expanded and `72px` collapsed (`w-64` ↔ `w-18`), sticky, full-height, hidden below the large breakpoint, and separated from content by a right hairline border. Content is a single vertical column of `Panel`/`Card` blocks with a `16px`–`24px` rhythm, sized to the window from `1280` to `1600` wide.

Density is calm: generous internal padding (`16px`–`24px`), clear scale jumps between a section title and its body, and no more than one dominant surface per screen. The Home "Reading Desk" puts the active book on the wide column with a compact progress field beside it; the Library "Catalogue with focus" puts a single quiet toolbar over a full-width grid and keeps advanced management secondary. Responsive behavior collapses the rail and reflows multi-column regions to a single column; the shared `--space-*` scale (`4 / 8 / 16 / 24 / 32px`) is the only spacing vocabulary.

## Elevation & Depth

Depth is ambient and tonal, not structural. Surfaces sit flat at rest over the navy ground and are separated first by a thin hairline border, then by a soft shadow whose job is to seat the plane, not to dramatize it: `--shadow-soft` (0 4px 20px rgba(0,0,0,0.2)) on panels and cards, `--shadow-panel` (0 16px 48px) on overlays that float without a scrim, and a single `glow` family (0 0 15–20px of accent) allowed **only** under a live accent element on hover. Glass (`backdrop-blur` over a translucent surface) is permitted on the shell and overlays to build atmosphere; it is never decorative.

### Shadow Vocabulary

- **Soft** (`box-shadow: 0 4px 20px rgba(0, 0, 0, 0.2)`): Default seating for panels and cards.
- **Panel / Hero** (`0 16px 48px` / `0 24px 80px`): Surfaces that must float without a scrim (drawers, menus, popovers) and the one hero surface. A modal sits on a dimmed scrim, and that scrim is its elevation — the modal is defined by its single hairline border, not a shadow.
- **Accent Glow** (`0 0 15px rgba(73, 212, 255, 0.15)`): Only beneath a hovered/focused accent control, never on a static surface.

### Named Rules

**The Flat-By-Default Rule.** Surfaces are flat at rest. Shadows appear as a response to elevation or state — never as ornament on a resting block.

**The One Glass Rule.** Translucency builds the shell and overlays only. A content card does not get its own blur to look modern.

## Shapes

The form language is consistently rounded with no sharp corners. Radii scale with a block's importance: `8px` (`sm`) for inputs and small tags, `12px` (`md`) for menus and dropdown popovers, `16px` (`lg`) for buttons and navigation items, `24px` (`xl`) for panels and cards, and `28px` (`2xl`) for the largest hero surfaces. Borders are always hairlines at ~1px; strong edges appear only on hover/active. Pills (`rounded-full`) are reserved for badges, avatars, and the active-nav indicator bar. Book covers keep their own aspect ratio and use a modest `12px` radius so artwork, not chrome, defines the grid.

## Components

### Buttons

- **Shape:** Consistent rounded `16px` (`--radius-lg`), no border on filled variants, hairline border on secondary.
- **Type:** Every button is the Label role (`--text-sm`, 500). `size` sets padding only and never changes the type — `lg` is not the Reading serif and no size is Body.
- **Sizes:** `sm` `6px 12px` (`px-3 py-1.5`), `md` `8px 16px` (`px-4 py-2`, default), `lg` `12px 24px` (`px-6 py-3`).
- **Primary:** Inverts the ink — `{colors.ink}` fill with `{colors.ground}` text.
- **Accent:** `{colors.nexo-blue}` fill with `{colors.signal-ink}` text; the reading CTA ("Reanudar"). The global primary is left untouched so unrelated screens do not shift.
- **Secondary / Ghost / Danger:** Secondary is a `{colors.surface}` fill with a hairline border; Ghost is transparent and gains a surface-hover fill on hover; Danger is an `{colors.error}` fill and is the only destructive action color.
- **Modifiers:** `fullWidth` stretches to the container; `leadingIcon` places a single icon before the label; `loading` disables the button and shows a spinner, optionally swapping in a `loadingLabel`; `as="label"` renders a `<label>` so a hidden file input can be the control instead of a `<label>` faking a button.
- **Hover / Focus / Active:** Hover drops opacity to 90%; active presses to `scale(0.96)` with an inner shadow; focus draws a 2px ring at 2px offset. Transitions use `--duration-fast` with `--ease-smooth`, and the press uses `--ease-bounce`.

### Chips & Badges

- **Style:** Pills at `rounded-full`, `--text-xs`, `2px 10px` padding.
- **State:** Badges carry a semantic fill with contrasting text; the ink must be checked per fill (the reference issue is the avatar set, where white failed AA and `#061222` replaced it). A chip that communicates state always pairs color with a label — color alone is never the signal.

### Cards & Panels

- **Corner Style:** `24px` (`--radius-xl`).
- **Background:** Glass surface by default; recessed surface for read-only blocks.
- **Shadow Strategy:** `--shadow-soft` at rest (see Elevation & Depth).
- **Border:** 1px `{colors.hairline}`; `Panel` headers use a bottom hairline at 80% opacity.
- **Internal Padding:** `12 / 16 / 24px` (`sm / md / lg`), with a `16px`–`20px` header band.
- **Rule:** Blocks are siblings on the ground, never cards inside cards. The Home continue-reading block is an accepted exception: it is a single bordered panel whose inner book row is borderless by design, so it reads as one visual plane rather than a card inside a card. The runtime detector's `nested-cards` hit on that block is a known false positive and must not be "fixed" by removing the panel border.

### Inputs & Fields

- **Style:** 1px hairline border, `{colors.surface}` background, `{colors.ink}` text, `8px` radius, `10px` internal padding. `Field` stacks a `label` (label weight) above the control with a `6px` gap.
- **Focus:** Border shifts to the signal and a visible ring is drawn. The normative ring is a 2px solid at the signal hue; the current `--color-accent-soft` (10% alpha) ring is below the floor and must be raised.
- **Error / Disabled:** Error text is `{colors.error}` below the field; disabled controls drop to 50% opacity with a not-allowed cursor.

### Navigation

- **Style:** The sidebar is a distinct rail plane with a right hairline. Items are `16px`-radius rows, `12px`/`8px` padding, label weight, icon at 16px / 1.8 stroke. Active items take `accent-nav-bg` with `accent-nav-fg` text and a 2px active-indicator bar pinned to the left edge; inactive items are muted and gain a faint accent field on hover.
- **Collapse:** The rail collapses to a 72px icon strip; labels move to tooltips rather than disappearing. The toggle persists across restarts.
- **Dropdowns:** Trigger is a `16px`-radius surface button with a hairline; content is a `12px`-radius elevated plane with a soft shadow, ring border, and a highlighted-item state that reads on both hover and keyboard.

### Feedback & Empty States

- **Loading:** `Skeleton` shimmer is a pulsing hairline→surface→hairline gradient, with `card`/`book` variants that mirror the real layout.
- **Empty:** `EmptyState` centers a circular icon medallion, a title, a muted description, and one action token.
- **Progress:** A `1.5px`-tall `rounded` track in `{colors.hairline}` with an ink fill; exposed as a labeled `progressbar`.
- **Toasts:** Bottom-right, `16px` radius, soft shadow, fly-in. The current `success`/`info`/`error` variants use light-theme Tailwind colors and must be re-tokenized so the shell stays dark-safe.

## Do's and Don'ts

### Do:

- **Do** reference semantic tokens only (`var(--color-*)`, `var(--space-*)`, `var(--radius-*)`); a raw hex or `rgba()` in a component is a defect.
- **Do** keep the accent under ~10% of a screen and reserve coral for the single secondary action.
- **Do** give every interactive control a visible `:focus-visible` ring at 2px, contrasting against its own background.
- **Do** let the active reading surface be the largest, highest-contrast block on Home; everything administrative recedes.
- **Do** design each state — loading, empty, no-results, error, offline — to explain itself and offer the next action in place.

### Don't:

- **Don't** reach for the stock dark-dashboard grammar: gradient-text headlines, a wall of equal KPI tiles, or glow as ornament.
- **Don't** nest cards or wrap a panel in another card; use the recessed surface variant to make a block recede instead.
- **Don't** use glass or gradients as decoration on resting content; they build the shell and overlays only.
- **Don't** hardcode a theme-specific color into the shell (the known leaks are `red-50`/`red-900` in the reader error banner and the light-theme fills in `Toast`).
- **Don't** let coral become a second primary, or amber/badge color carry meaning without a textual label.
