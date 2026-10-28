---
name: NEXO Android
description: Dark-first Material 3 reading app; the resume-from-where-you-stopped surface outranks library administration.
colors:
  bg-main: "#0B1120"
  bg-surface: "#161F33"
  bg-card-hover: "#1E293B"
  bg-header: "#0D1322"
  border-subtle: "#242A3A"
  outline: "#30363D"
  primary: "#3B82F6"
  primary-hover: "#2563EB"
  icon-bg-blue: "#1E3A8A"
  text-primary: "#FFFFFF"
  text-secondary: "#94A3B8"
  text-accent: "#3B82F6"
  accent-yellow: "#EAB308"
  bg-yellow-transparent: "#423419"
  accent-green: "#22C55E"
  bg-green-transparent: "#143A27"
  accent-purple: "#A855F7"
  bg-purple-transparent: "#311C4A"
  accent-blue: "#3B82F6"
  bg-blue-transparent: "#1E3A8A"
  navbar-active: "#ADC6FF"
  navbar-inactive: "#C2C6D6"
  error-soft: "#F85149"
typography:
  display:
    fontFamily: "Lato"
    fontSize: "57sp"
    fontWeight: 700
    lineHeight: "64sp"
  headline-large:
    fontFamily: "Lato"
    fontSize: "32sp"
    fontWeight: 600
    lineHeight: "40sp"
  headline-medium:
    fontFamily: "Lato"
    fontSize: "28sp"
    fontWeight: 500
    lineHeight: "36sp"
  headline-small:
    fontFamily: "Lato"
    fontSize: "24sp"
    fontWeight: 500
    lineHeight: "32sp"
  title-large:
    fontFamily: "Lato"
    fontSize: "22sp"
    fontWeight: 600
    lineHeight: "28sp"
  title-medium:
    fontFamily: "Lato"
    fontSize: "16sp"
    fontWeight: 500
    lineHeight: "24sp"
  title-small:
    fontFamily: "Lato"
    fontSize: "14sp"
    fontWeight: 500
    lineHeight: "20sp"
  body-large:
    fontFamily: "Lato"
    fontSize: "16sp"
    fontWeight: 400
    lineHeight: "24sp"
  body-medium:
    fontFamily: "Lato"
    fontSize: "14sp"
    fontWeight: 400
    lineHeight: "20sp"
  body-small:
    fontFamily: "Lato"
    fontSize: "12sp"
    fontWeight: 400
    lineHeight: "16sp"
  label-large:
    fontFamily: "Lato"
    fontSize: "14sp"
    fontWeight: 500
    lineHeight: "20sp"
  label-medium:
    fontFamily: "Lato"
    fontSize: "12sp"
    fontWeight: 500
    lineHeight: "16sp"
  label-small:
    fontFamily: "Lato"
    fontSize: "11sp"
    fontWeight: 500
    lineHeight: "16sp"
rounded:
  xs: "4dp"
  sm: "8dp"
  md: "12dp"
  lg: "16dp"
  xl: "20dp"
  pill: "50dp"
spacing:
  xs: "4dp"
  sm: "8dp"
  md: "16dp"
  lg: "24dp"
  xl: "32dp"
components:
  top-app-bar-home:
    backgroundColor: "{colors.bg-header}"
    textColor: "{colors.text-primary}"
    typography: "{typography.title-medium}"
    height: "64dp"
    padding: "16dp 20dp"
  top-app-bar-detail:
    backgroundColor: "{colors.bg-header}"
    textColor: "{colors.text-primary}"
    typography: "{typography.title-medium}"
    height: "56dp"
    padding: "0dp 16dp"
  card-surface:
    backgroundColor: "{colors.bg-surface}"
    textColor: "{colors.text-primary}"
    rounded: "{rounded.md}"
    padding: "16dp"
  chip:
    backgroundColor: "{colors.bg-surface}"
    textColor: "{colors.text-secondary}"
    rounded: "{rounded.pill}"
    padding: "8dp 14dp"
  chip-selected:
    backgroundColor: "{colors.primary}"
    textColor: "{colors.bg-surface}"
    rounded: "{rounded.pill}"
    padding: "8dp 14dp"
  cover:
    backgroundColor: "{colors.bg-surface}"
    rounded: "{rounded.sm}"
---

# Design System: NEXO Android

Read from the shipped code in `app/src/main/java/com/nexo/`. Every value below exists in
Compose today. Where the code and `design/nextPage-movil.pen` disagree, the disagreement is
stated rather than resolved.

## Overview

**Creative North Star: "The Quiet Reading Room"**

NEXO's Android surface is a dark, low-noise reading room. The canvas (`bg-main`, #0B1120) is a
near-black blue; content sits one step lighter (`bg-surface`, #161F33); interaction is one blue
(`primary`, #3B82F6) that appears almost nowhere. Depth comes from a two-step background ramp and
a 1dp hairline, never from a shadow.

That sparseness is what makes drift loud. With no shadows and one accent, a title that is
`headlineMedium` on one screen and `headlineSmall` on the next is the most conspicuous thing on
the page. The parts of this system that still disagree with each other are listed under Components;
they are measured, not paraphrased.

**Key Characteristics:**
- Dark-first. The light scheme exists; the `.pen` only specifies the dark one.
- One accent. `primary` is the only interactive colour.
- Lato everywhere, on a Material 3 type scale.
- 4dp spacing grid, expressed as `NexoDimens`.
- Header strip (`bg-header`, #0D1322) is *darker* than the canvas, not lighter.

## Colors

A near-black blue field, one electric blue accent, four category duotones.

### Primary
- **Electric Book Blue** (`primary`, #3B82F6): selected chips, links, focused borders, the active
  bottom-nav item, in-flight spinners. `text-accent` holds the same value, so accent text and
  primary are the same blue by design.
- **Pressed Blue** (`primary-hover`, #2563EB): pressed state for filled controls.
- **Deep Icon Navy** (`icon-bg-blue`, #1E3A8A): container tint for icon badges.

### Neutral
- **Reading Room Black** (`bg-main`, #0B1120): app canvas.
- **Shelf Surface** (`bg-surface`, #161F33): cards, chips, inputs.
- **Hover Slate** (`bg-card-hover`, #1E293B): pressed/hover fill, neutral avatar placeholder.
- **Header Ink** (`bg-header`, #0D1322): the top app bar strip. One step *darker* than the canvas,
  so the bar reads as chrome rather than as a raised card.
- **Hairline** (`border-subtle`, #242A3A): dividers and card outlines. A second, lighter outline
  (`outline`, #30363D) is wired into the Material scheme.

### Category duotones
Yellow #EAB308 / #423419, Green #22C55E / #143A27, Purple #A855F7 / #311C4A, Blue #3B82F6 /
#1E3A8A. Status and statistics, not primary actions.

### Named Rules
**The One Voice Rule.** `primary` is the only interactive colour. A second colour doing an
interaction's job means the interaction is wrong, not the palette.

**The Flat Rule.** Nothing casts a shadow. `NexoCardSurface` never sets `shadowElevation`; depth
is background step plus a 1dp `border-subtle` hairline.

## Typography

**Font:** Lato, four static weights shipped in `res/font/` (Regular, Medium, SemiBold, Bold).
Roboto never appears. The `.pen` names Inter/Roboto/system-sans; code wins.

### Hierarchy
- **Display** (Bold, 57sp/64sp): declared, referenced by no screen.
- **Headline Large** (SemiBold, 32sp/40sp): the letter fallback inside a book cover.
- **Headline Medium** (Medium, 28sp/36sp): Library screen title only.
- **Headline Small** (Medium, 24sp/32sp): formerly the Discover title; now unreferenced by a top bar.
- **Title Large** (SemiBold, 22sp/28sp): Material `TopAppBar` titles on settings sub-pages.
- **Title Medium** (Medium, 16sp/24sp): every bar title, section headers, card titles.
- **Body Medium** (Regular, 14sp/20sp): body copy, metadata, bar subtitles.
- **Body Small** (Regular, 12sp/16sp): rail card titles.
- **Label Small** (Medium, 11sp/16sp): the smallest metadata.

### Named Rules
**The Role Rule.** Text takes its size from the role token. A call site that overrides with
`.copy(fontSize = N.sp)` is a missing role, not a local decision. Live overrides worth naming:

| Where | Override | Effective |
|---|---|---|
| Discover rail section title | `titleMedium.copy(fontSize = 18.sp)` | 18sp / SemiBold |
| Discover grid card title | `bodyMedium.copy(fontSize = 13.sp)` | 13sp / Medium |
| Discover grid card author | `labelSmall.copy(fontSize = 11.sp)` | 11sp (no-op — the role is already 11sp) |
| Discover "Ver todo" / retry buttons | `labelMedium.copy(fontSize = 13.sp / 14.sp)` | 13sp or 14sp |
| Cover letter fallback | `headlineLarge.copy(fontSize = 48.sp / 32.sp)` | 48sp grid, 32sp default |

**The Two-Title Rule.** One screen, one title step. Before the extraction this rule was violated
(Library `headlineMedium`+Bold, Discover `headlineSmall`+Bold, Home `titleMedium`+SemiBold); all
three now share the `titleMedium`+SemiBold bar.

## Layout

4dp grid via `NexoDimens`: `spacingXs` 4, `spacingSm` 8, `spacingMd` 16, `spacingLg` 24,
`spacingXl` 32. Bar-specific tokens added by the extraction: `topBarGap` 12dp, `topBarTitleGap`
2dp, `topBarActionGap` 8dp, `topBarBadgeSize` 10dp, `coverCornerRadius` 8dp.

Screen-edge padding is not uniform, and that is deliberate on Home only:

| Surface | Horizontal inset | Owner |
|---|---|---|
| Home | 24dp | `HomeScreen`'s own `LazyColumn` padding |
| Discover | 20dp inside the bar, 16dp for the body | `NexoTopAppBar` + `DiscoverScreen` |
| Library | 16dp | `LibraryHeader` |
| Results grid | 16dp, 12dp column gap, 16dp row gap | `DiscoverResultsGrid` |

**Unresolved:** the `.pen` declares `screen_pad` = 20dp; Home (24dp) and Library (16dp) both
differ. Code wins per screen until the 20dp is decided.

## Elevation & Depth

No shadows, with one legacy exception: `BookCard` still sets `shadowElevation = 2.dp` and uses
`shapes.medium` (16dp). It is drift, not precedent. `NexoColors` has no elevation token at all.

**The Flat-By-Default Rule.** New surfaces start flat. If one must read as raised, change the
background step and add the hairline.

## Shapes

`NexoShapes` maps Material slots to 4dp / 8dp / 16dp / 50dp / 50dp. That mapping does not cover
the value the system actually uses most:

- **12dp** — `NexoDimens.cardCornerRadius`, used by `NexoCardSurface`. This is the card radius.
- **8dp** — covers (`NexoDimens.coverCornerRadius`), Discover grid cell, cover slot in the detail sheet.
- **Pill (CircleShape / 50dp)** — `NexoChip`, avatars.
- **20dp** — `NexoFilterTabs` tabs, and the inline search field in `LibraryHeader` (24dp).
- **16dp** — `MaterialTheme.shapes.medium`, still used by `BookCard`.

**Unresolved:** `MaterialTheme.shapes.medium` is 16dp while the card token is 12dp, so any surface
that reaches for the Material slot lands on the wrong radius. Use `NexoCardSurface` or
`NexoDimens.cardCornerRadius`.

**Unresolved:** 184 `RoundedCornerShape(` literals across 79 Kotlin files bypass the shape tokens
entirely. That is the mechanism by which one concept acquired four radii.

## Components

### Top App Bar

One implementation: **`NexoTopAppBar`** (`ui/components/molecules/NexoTopAppBar.kt`), two variants.

- **`TopLevelVariant.Home`** — 64dp tall, `bg-header`, 20dp horizontal / 16dp vertical padding.
  40dp avatar (initial or neutral person placeholder) · 12dp gap · title `titleMedium` SemiBold
  with optional `bodyMedium` subtitle in `text-secondary` · 8dp-gapped 40dp trailing buttons
  (search, notifications with a 10dp `error` dot). Used by Home, Discover, Library.
- **`TopLevelVariant.Detail`** — 56dp tall, `bg-header`, 16dp horizontal padding, no vertical
  padding. 40dp back button with a 24dp arrow · title `titleMedium` SemiBold. Used by pushed
  sub-screens.

Title role is identical in both variants, and the bar is a **fixed 64dp** — it does not grow for
a subtitle, so the title and subtitle must fit one 64dp row.

**Still not on the shared bar (checked, not assumed):**
- `MaterialTopAppBar` inline copies remain in `LegalPolicyScreen`, `AddonCapabilityDetailScreen`,
  `AddonManagementScreen`, `EditBookMetadataScreen`, `SettingsDevicesScreen`, `ReadAccessSheet`,
  `BookDetailScreen` (Material default 64dp, `titleLarge`+Bold, no `bg-header` fill).
- `NexoSettingsSubPage` wraps Material `TopAppBar` the same way.

### Cards / Containers

**`NexoCardSurface`** (`ui/components/molecules/NexoCardSurface.kt`) is the one card: 12dp radius,
`bg-surface` fill, optional 1dp `border-subtle`, 16dp padding (pass `PaddingValues(0.dp)` for a
bleed-to-edge cover). No elevation. Consumed by the Discover coverless rail card.

| Card | Radius | Border | Cover | Padding |
|---|---|---|---|---|
| `NexoCardSurface` | 12dp | 1dp `border-subtle` | caller-drawn | 16dp |
| `DiscoverCard` (results grid) | 8dp (on the column, click area only) | none | 160dp fixed height, full width | 0dp text block, 8dp gap |
| `DiscoverRailCard` | 8dp on the cover; no card fill | none | 110×165dp | 8dp gap, no panel |
| `RailTextCard` (coverless) | 12dp via `NexoCardSurface` | yes | — | 8dp |
| `BookCard` (Library) | 16dp (`shapes.medium`) | none, 2dp shadow | 140dp | 16dp |

The results grid card and the rail card are **not** `NexoCardSurface` consumers: they are
edge-bleed cover cells with type underneath, and they deliberately carry no panel. The 160dp grid
cover versus the 165dp rail cover is a 5dp disagreement with no spec reason.

### Chips

**`NexoChip`** (`ui/components/molecules/NexoChip.kt`): pill, 14dp × 8dp, `labelLarge` (14sp),
optional 14dp icon with a 6dp gap. Resting = `bg-surface` + `text-secondary`; selected =
`primary` fill + `bg-surface` text + SemiBold. `DiscoverChipRow` delegates to it.

Still different:
- **`NexoFilterTabs`** (Library status row) — 20dp radius, not a pill; resting fill is
  `surfaceVariant` (#1E293B) rather than `bg-surface`; selected text is `onPrimary` (#FFFFFF)
  rather than `bg-surface`; label is `labelMedium` (12sp) rather than 14sp.
- **`MetadataChips`** — 8dp radius, `secondaryContainer`. Belongs to editable metadata, not
  filters; keep it separate.

### Inputs

- **Discover search** — `OutlinedTextField`, 48dp tall, 12dp radius, `bg-surface` fill, focused
  border `primary` / unfocused `border-subtle`.
- **Library search** — `NexoTextField`, 24dp radius, no fixed height. Two search fields, two radii.

### Navigation

Material 3 bottom navigation (`NexoBottomNavBar`), four destinations, 20dp icons
(`NexoDimens.iconNavBar`). Top-level screens receive `contentPadding` from the host and apply it
themselves; there is no per-screen `Scaffold`, which is why each bar used to invent its own
vertical padding.

## Do's and Don'ts

### Do:
- **Do** compose `NexoTopAppBar` for any new bar. The two variants cover avatar bars and detail bars.
- **Do** source colour from `NexoColors` or `MaterialTheme.colorScheme`; no `Color(0x…)` outside
  `presentation/theme/`.
- **Do** express spacing through `NexoDimens` on the 4dp grid.
- **Do** use 12dp for card corners, 8dp for covers, a full radius for chips.
- **Do** keep 40dp buttons inside the bar so the 64dp row stays aligned.

### Don't:
- **Don't** add shadow to signal elevation. Change the background step, add the hairline.
- **Don't** add a third bar variant. If a screen needs a different density, say why in the
  component, not in the screen.
- **Don't** reach for `MaterialTheme.shapes.medium` for a card — it is 16dp, the card is 12dp.
- **Don't** use `secondaryContainer` for a filter chip; that role belongs to editable metadata.
- **Don't** override `.copy(fontSize = …)` on a type role without deciding the role is missing.
