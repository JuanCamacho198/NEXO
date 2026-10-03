# Reading-day contract

**One reading day = one calendar day on every device.**

## The rule

A reading day is the **user's local calendar day**, derived at write time from

```
(instant, explicit zone offset) -> local calendar day
```

Nothing derives a day from a UTC instant and nothing reads a device default
implicitly. The zone is always an explicit parameter:

- **Desktop:** the client sends `zoneOffsetMinutes` (positive = east of UTC,
  matching `-Date.prototype.getTimezoneOffset()`); the Rust command requires it.
- **Android:** the DI composition root injects an explicit `ZoneId`; the helper
  takes it as a parameter.

## Persisted representation

The day is stored as **00:00 UTC of that local calendar day** — a date-only
value encoded in the existing `timestamptz`/epoch-millis slot. Any reader
recovers the day by taking the UTC date part, with no need to know the writer's
zone. This is what makes desktop- and Android-originated rows agree.

## Where it is computed

| Platform | Helper | Call sites |
|---|---|---|
| Rust | `src/reading_day.rs` `reading_day(instant, zone_offset_minutes)` | `repository/progress.rs` save / streak / activity / today-minutes |
| Kotlin | `domain/model/ReadingDay.kt` `readingDay(instant, zone)` | `ReadingStatsRepositoryImpl`, `GetStatisticsUseCase`, `HomeRepositoryImpl` |

## Shared fixture

`reading-day-fixtures.json` is read by BOTH tests:
`desktop/src-tauri/src/reading_day.rs` (Rust, `include_str!`) and
`android/.../ReadingDayFixtureTest.kt` (Kotlin). One file, no copies, no
transcribed expectations — if either client drifts, that client's test fails.

## Cutover rule (existing rows)

No history is rewritten. Legacy rows with no stored day (`date IS NULL`) fall
back to their raw `started_at` instant, interpreted as UTC — the pre-contract
behavior. New rows always carry the canonical day. A user who crosses the
meridian while reading keeps each day as lived; one calendar date can later
carry two rows, which is the intended semantic ("the day I read").
