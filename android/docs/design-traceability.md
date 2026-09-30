# Design traceability

The canonical Pencil node ↔ screen index. This file is the single source of truth for which Pencil design node backs which Compose screen; the `## Design System` block in `AGENTS.md` mirrors the same mapping, but this file lives in the long-lived `docs/` folder so it survives the AGENTS.md being trimmed in the future.

## Purpose

The Pencil design file (`design/nextPage-movil.pen`) is the visual source of truth for the app. Every screen in code is anchored to a node ID in that file. When the design changes — a node ID is renamed, a screen is split, a new screen appears — this file is updated **first** and the code follows. If a code path references a node ID that is not in the table below, treat it as a bug.

## Node index

| Pencil node | Screen | Code entry |
|-------------|--------|------------|
| `W29xCr` | Welcome / AuthScreen | `app/src/main/java/com/nexo/presentation/screen/AuthScreen.kt` |
| `WDYjT` | Home | `app/src/main/java/com/nexo/presentation/screen/HomeScreen.kt` |
| `HQRl6` | Bookshelf / Library | `app/src/main/java/com/nexo/presentation/screen/LibraryScreen.kt` |
| `iSSWb` | Highlights | `app/src/main/java/com/nexo/presentation/screen/HighlightsScreen.kt` |
| `EQsNd` | Settings | `app/src/main/java/com/nexo/presentation/screen/SettingsScreen.kt` |

## How to use

1. **Reading code**: when you see a Pencil node ID in a comment, KDoc, or `## Design Traceability` table (e.g. inside `data-flow.md`), look it up here to find the screen and the file.
2. **Adding a screen**: author the screen in `presentation/screen/`, add a row to the table above, and reference the new node from the screen's UI doc.
3. **Editing the design**: when a node is renamed in `design/nextPage-movil.pen`, update this table **first**, then update every doc that references the old ID. `sdd-verify` can grep for stale references in a future change.
4. **Removing a screen**: delete the screen file, remove the row from the table, and grep the rest of `docs/` for the orphaned node ID.

> The Pencil node IDs are the contract. If you change the design without updating this table, the next reviewer will be unable to trace your code back to the design.
