# Shared Drive filename fixtures

Two contract surfaces, both consumed by the Rust (`desktop/src-tauri/src/filename.rs`),
Kotlin (`android/.../data/sync/DriveFilenameTest.kt`) and TypeScript
(`desktop/src/test/unit/sync/driveFilename.test.ts`) runners so a platform that
drifts fails its own test instead of staying latent.

## `canonical[]` — id-canonicalization

`{ "input", "expectedStem" }`. Pins `canonicalStem`: the one algorithm that turns
a raw catalog book id into a Windows-safe stem.

## `readable[]` — readable object name (new uploads only)

`{ "title", "bookId", "extension", "expectedName" }`. Pins
`readableObjectName` / `readableDriveObjectName` / `readable_drive_object_name`:
the user-facing `<sanitized title> -- <id>.<ext>` name used for NEW uploads. The
id-canonicalization branch is exercised through `bookId` -> `expectedName`, so
this surface also re-pins the canonical stem without duplicating `canonical[]`.

The 80-character title bound counts **Unicode code points** (Rust `.chars().take`,
TypeScript `Array.from(...).slice`, Kotlin `takeCodePoints`), never UTF-16 units:
a title whose 80th character is astral keeps the whole character instead of a
lone surrogate. `truncation[]` pins exactly that boundary.

## `truncation[]` — the code-point bound

`{ "title", "expectedSanitized" }`. Pins `sanitizeTitleForFilename` alone, so the
80 code-point boundary is asserted directly rather than inferred from a name.
