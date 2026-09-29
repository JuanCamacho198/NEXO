# Refactor Tauri Backend - Rollback Checklist

## Goal
Rollback safely per slice while keeping public commands and invoke_handler compatibility.

## Slice checklist

- settings/library
  - Revert `repository/settings.rs` + `repository/library.rs` delegation in `repository/mod.rs`
  - Keep command symbols unchanged in `commands/mod.rs` and `main.rs`

- progress/highlights
  - Revert `repository/progress.rs` + `repository/highlights.rs`
  - Keep DTO serde `rename_all = "camelCase"` contract checks passing

- bookmarks/collections/search/files
  - Revert affected `repository/*.rs` slice files
  - Preserve command wrappers and aliases (snake_case/camelCase)

## verify gate

- Run parity + integration tests before merge/release
- If any parity contract fails, rollback last touched slice only
- Do not remove transitional facade until verify is green
