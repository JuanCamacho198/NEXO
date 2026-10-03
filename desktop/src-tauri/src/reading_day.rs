//! Canonical cross-device reading-day contract (desktop side).
//!
//! A reading day is the calendar day the user experienced, derived from an
//! instant plus an EXPLICIT zone offset. Nothing in this module reads a device
//! default: the offset is always a parameter.
//!
//! The persisted representation is 00:00 UTC of that local calendar day — a
//! date-only value any reader can recover without knowing the writer's zone.
//! The same file is consumed by the Android client through the shared fixture
//! at `packages/reading-day-fixtures/reading-day-fixtures.json`.

use chrono::{DateTime, FixedOffset, NaiveDate, Utc};

/// Shared fixture read by BOTH the Rust test here and the Kotlin test on
/// Android. One file, no copies, no transcribed expectations.
pub const READING_DAY_FIXTURE_JSON: &str =
    include_str!("../../../packages/reading-day-fixtures/reading-day-fixtures.json");

/// The user's local calendar day for `instant` at an explicit
/// `zone_offset_minutes` (positive = east of UTC, matching the value returned
/// by `-Date.prototype.getTimezoneOffset()`).
pub fn reading_day(instant: DateTime<Utc>, zone_offset_minutes: i32) -> NaiveDate {
    let offset = FixedOffset::east_opt(zone_offset_minutes.saturating_mul(60))
        .unwrap_or_else(|| FixedOffset::east_opt(0).expect("UTC offset is always valid"));
    instant.with_timezone(&offset).date_naive()
}

/// Canonical persisted value: the local calendar day encoded as 00:00 UTC.
pub fn reading_day_start_utc(day: NaiveDate) -> DateTime<Utc> {
    day.and_hms_opt(0, 0, 0).expect("midnight is always valid").and_utc()
}

/// Canonical persisted RFC3339 string for `(instant, zone)`.
pub fn reading_day_timestamp(instant: DateTime<Utc>, zone_offset_minutes: i32) -> String {
    reading_day_start_utc(reading_day(instant, zone_offset_minutes)).to_rfc3339()
}

/// Explicit zone carried by an RFC3339 instant's own offset (minutes east of
/// UTC). This is the client-supplied zone; it is never a device default.
pub fn zone_offset_minutes_from_rfc3339(value: &str) -> Option<i32> {
    DateTime::parse_from_rfc3339(value.trim()).ok().map(|dt| dt.offset().local_minus_utc() / 60)
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde::Deserialize;

    #[derive(Deserialize)]
    struct Case {
        instant: String,
        #[serde(rename = "zoneOffsetMinutes")]
        zone_offset_minutes: i32,
        #[serde(rename = "expectedDay")]
        expected_day: String,
    }

    fn fixture_cases() -> Vec<Case> {
        serde_json::from_str(READING_DAY_FIXTURE_JSON).expect("shared fixture must parse")
    }

    #[test]
    fn shared_fixture_matches_local_day_contract() {
        let cases = fixture_cases();
        assert!(cases.len() >= 8, "shared fixture must pin the load-bearing cases");
        for case in cases {
            let instant = DateTime::parse_from_rfc3339(&case.instant).unwrap().with_timezone(&Utc);
            let day = reading_day(instant, case.zone_offset_minutes);
            assert_eq!(
                day.format("%Y-%m-%d").to_string(),
                case.expected_day,
                "instant={} zoneOffsetMinutes={}",
                case.instant,
                case.zone_offset_minutes
            );
        }
    }

    #[test]
    fn utc_plus_13_and_minus_11_land_on_different_correct_days() {
        let instant =
            DateTime::parse_from_rfc3339("2026-10-01T12:00:00Z").unwrap().with_timezone(&Utc);

        // Same instant, two explicit zones: each must be locally correct.
        assert_eq!(reading_day(instant, 780).format("%Y-%m-%d").to_string(), "2026-10-02");
        assert_eq!(reading_day(instant, -660).format("%Y-%m-%d").to_string(), "2026-10-01");
    }

    #[test]
    fn canonical_timestamp_is_utc_midnight_of_the_local_day() {
        let instant =
            DateTime::parse_from_rfc3339("2026-10-01T12:00:00Z").unwrap().with_timezone(&Utc);
        // +13 local day 2026-10-02 encoded as 00:00 UTC.
        assert_eq!(reading_day_timestamp(instant, 780), "2026-10-02T00:00:00+00:00");
        // Recovering the day from the canonical value must not need the zone.
        let encoded = DateTime::parse_from_rfc3339(&reading_day_timestamp(instant, 780)).unwrap();
        assert_eq!(
            encoded.with_timezone(&Utc).date_naive().format("%Y-%m-%d").to_string(),
            "2026-10-02"
        );
    }

    #[test]
    fn rfc3339_offset_is_used_as_explicit_zone() {
        assert_eq!(zone_offset_minutes_from_rfc3339("2026-10-01T21:00:00-05:00"), Some(-300));
        assert_eq!(zone_offset_minutes_from_rfc3339("2026-10-02T09:00:00+13:00"), Some(780));
        assert_eq!(zone_offset_minutes_from_rfc3339("2026-10-01T12:00:00Z"), Some(0));
        assert_eq!(zone_offset_minutes_from_rfc3339("not-a-date"), None);
    }
}
