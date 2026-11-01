# Hardcoded string gate

`app/build.gradle.kts` registers `verifyAuthScreenNoHardcodedStrings`, which
fails the build when a user-facing string literal is written inline in Compose
code instead of being declared in `res/values/strings.xml` +
`res/values-es/strings.xml` and referenced with `stringResource(R.string.<name>)`.

## What it scans

| Before (T1 start) | Now |
| --- | --- |
| Only `presentation/screen/AuthScreen.kt` | All `.kt` files under `presentation/` and `debug/` |
| Line-based regex | Whole-file scan (multiline `Text(` calls match) |

The old scope was effectively a no-op: `presentation/screen/AuthScreen.kt` is a
thin delegate stub that forwards to `presentation/feature/auth/AuthScreen.kt`,
which holds the real UI. The gate never read a single user-facing string.

## Detectable shapes

1. `Text("literal")` — inline first argument
2. `text = "literal"` — named argument
3. `Text(\n    "literal",\n)` — literal on its own line

Not detected: a literal assigned to a `val` and passed later
(`val label = "Book"; Text(text = label)`). That indirection is out of scope for a
textual gate; keep such values in string resources regardless.

## False positives filtered out

A match is skipped when it is not translatable prose:

- empty (`""`)
- symbols/digits/emoji only, no run of 3+ letters (`"—"`, `"42%"`)
- a lone interpolation with no surrounding words (`"$streakDays"`, `"${x}"`)

`testTag = "..."` and `contentDescription = "..."` are not matched at all —
neither uses a `Text(` call or a `text =` argument.

Known gap (out of scope for this gate): hardcoded `contentDescription` values are
not caught. Measured examples include `NexoNavHost.kt:471`
(`contentDescription = "Debug"`) and `SettingsDevicesScreen.kt:61`
(`contentDescription = "Back"`). These are accessibility strings and *are*
user-facing; they are excluded only because matching `contentDescription =` also
hits technical/no-op values like `null` and dynamic variables. Extending the gate
to cover them requires filtering `null`/identifier right-hand sides first.

## Baseline

`app/hardcoded-strings-baseline.txt` freezes the pre-existing findings, mirroring
the `app/lint-baseline.xml` convention: **only NEW violations fail the build.**

```powershell
# Regenerate after intentionally adding or fixing findings (same PR).
& cmd /c "gradlew.bat --no-daemon -Dorg.gradle.vfs.watch=false updateHardcodedStringsBaseline"
```

Do not hand-edit the baseline. Remove entries by moving the string to resources.

`verifyNoHardcodedStrings` is the real task; `verifyAuthScreenNoHardcodedStrings`
is kept as its alias because `AGENTS.md` and `docs/e2e.md` reference the old name.

## Known baseline debt (T1 diagnostic, 16 entries)

| File | Count | Notes |
| --- | --- | --- |
| `presentation/debug/DebugPanel.kt` | 5 | Debug-only panel |
| `presentation/screen/ReaderChrome.kt` | 3 | Reader toolbar chrome |
| `debug/CrashDetailActivity.kt` | 2 | Crash screen labels |
| `debug/DebugPanel.kt` | 2 | Debug log rows |
| 9 other files | 1 each | See the baseline file |

Strings are mostly diagnostic/tooling surfaces. `debug/FeedbackSheet.kt`
(multi-line "página X de 318") is deliberately **not** in this baseline and is
handled by a separate task.
