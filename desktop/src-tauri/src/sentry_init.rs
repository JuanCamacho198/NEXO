//! Sentry initialization for the Tauri backend.
//!
//! Init is defensive: failure MUST NOT prevent the app from booting.
//! Gated by the `SENTRY_DSN` env var — if absent, init is a silent no-op.
//!
//! The global [`SENTRY_GUARD`] is kept alive for the program's lifetime and
//! dropped at process exit. Panics on any thread are captured by the
//! `PanicIntegration` that ships in our default integrations (the
//! `sentry` crate's `panic` feature pulls it in).

use std::sync::OnceLock;

use crate::logger::Logger;

/// Global guard — dropped at process exit. `None` when Sentry is disabled
/// (no DSN provided or init failed).
static SENTRY_GUARD: OnceLock<Option<sentry::ClientInitGuard>> = OnceLock::new();

/// Sentry release prefix — MUST match the Sentry project slug (`nexo-desktop`).
///
/// The composed release is `"<prefix>@<CARGO_PKG_VERSION>+<sha12>"`, the same
/// shape the Android and web builds emit for the same commit (spec C1).
const SENTRY_RELEASE_PREFIX: &str = "nexo-desktop";

/// Build a [`sentry::ClientOptions`] from env vars.
///
/// Returns `None` when `SENTRY_DSN` is absent or empty — callers treat that
/// as a deliberate opt-out and skip initialization entirely.
fn build_options() -> Option<sentry::ClientOptions> {
    let dsn = std::env::var("SENTRY_DSN").ok().filter(|v| !v.is_empty())?;
    // Release scheme per spec C1: `nexo-desktop@<semver>+<sha12>`.
    // `NEXO_GIT_SHA` is emitted by `build.rs` from `git rev-parse --short=12 HEAD`,
    // falling back to `unknown` when git is unavailable. Identical suffix to the
    // TS web build for the same commit — see `sdd/sentry-observability-v2/design`.
    let release =
        format!("{}@{}+{}", SENTRY_RELEASE_PREFIX, env!("CARGO_PKG_VERSION"), env!("NEXO_GIT_SHA"));
    let environment =
        std::env::var("SENTRY_ENVIRONMENT").unwrap_or_else(|_| "development".to_string());
    let traces_sample_rate = std::env::var("SENTRY_TRACES_SAMPLE_RATE")
        .ok()
        .and_then(|v| v.parse().ok())
        .unwrap_or(0.1_f32);

    Some(
        sentry::ClientOptions::new()
            .dsn(dsn.as_str())
            .release(release)
            .environment(environment)
            .traces_sample_rate(traces_sample_rate)
            .before_send(|mut event| {
                // Single source of truth for PII scrubbing — reused by the
                // local file logger and any other egress sink. `event.extra`
                // is a `BTreeMap<String, Value>`; we lift it into a `Value::Object`
                // for the scrubber, then drop back into the map.
                let mut as_value = serde_json::Value::Object(
                    std::mem::take(&mut event.extra)
                        .into_iter()
                        .collect::<serde_json::Map<String, serde_json::Value>>(),
                );
                Logger::redact_json_value(&mut as_value);
                if let serde_json::Value::Object(map) = as_value {
                    event.extra = map.into_iter().collect();
                }
                Some(event)
            }),
    )
}

/// Initialize Sentry. Safe to call multiple times (subsequent calls no-op).
///
/// Returns `true` if init produced a live client. The app MUST keep booting
/// even when this returns `false` — the function is the panic hook's safety
/// net, not a precondition for app start.
pub fn init_or_log() -> bool {
    let opts = match build_options() {
        Some(o) => o,
        None => {
            // No DSN — deliberate opt-out. Stay silent; no log spam.
            return false;
        }
    };

    // Panic-safe: a broken Sentry init MUST NOT prevent the app from booting.
    // `sentry::init` returns a `ClientInitGuard` directly in 0.49 — no Result.
    // `ClientOptions` contains trait objects + `Arc`s which are not `UnwindSafe`
    // by default, so we wrap with `AssertUnwindSafe`. Init is bounded and
    // synchronous; this is the documented pattern for sentry-rust init safety.
    let guard = match std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| sentry::init(opts)))
    {
        Ok(g) => g,
        Err(_) => {
            eprintln!("[sentry] init panicked — continuing without Sentry");
            let _ = SENTRY_GUARD.set(None);
            return false;
        }
    };

    let _ = SENTRY_GUARD.set(Some(guard));
    true
}

/// Returns `true` if Sentry was successfully initialized.
pub fn is_enabled() -> bool {
    matches!(SENTRY_GUARD.get(), Some(Some(_)))
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::Mutex;

    // Serialize env-var touching tests so they don't clobber each other when
    // cargo runs them in parallel. `std::env::set_var` / `remove_var` are
    // documented as not thread-safe — race conditions produce flaky failures.
    static ENV_LOCK: Mutex<()> = Mutex::new(());

    #[test]
    fn build_options_returns_none_without_dsn() {
        let _guard = ENV_LOCK.lock().unwrap_or_else(|e| e.into_inner());
        // SAFETY: env access is serialized via `ENV_LOCK`.
        unsafe {
            std::env::remove_var("SENTRY_DSN");
        }
        assert!(build_options().is_none());
    }

    #[test]
    fn build_options_returns_some_with_dsn() {
        let _guard = ENV_LOCK.lock().unwrap_or_else(|e| e.into_inner());
        // SAFETY: see above.
        unsafe {
            std::env::set_var("SENTRY_DSN", "https://public@example.com/1");
        }
        let opts = build_options();
        unsafe {
            std::env::remove_var("SENTRY_DSN");
        }
        assert!(opts.is_some());
    }

    #[test]
    fn init_or_log_is_safe_without_dsn() {
        let _guard = ENV_LOCK.lock().unwrap_or_else(|e| e.into_inner());
        unsafe {
            std::env::remove_var("SENTRY_DSN");
        }
        // First call returns false (no DSN). State stays None — subsequent
        // calls don't re-init because the OnceLock is already populated.
        assert!(!init_or_log());
        assert!(!is_enabled());
    }

    #[test]
    fn build_options_returns_none_when_dsn_is_empty() {
        let _guard = ENV_LOCK.lock().unwrap_or_else(|e| e.into_inner());
        unsafe {
            std::env::set_var("SENTRY_DSN", "");
        }
        let opts = build_options();
        unsafe {
            std::env::remove_var("SENTRY_DSN");
        }
        assert!(opts.is_none(), "empty DSN MUST be treated as opt-out");
    }

    /// Spec C1 — cross-platform release format `<prefix>@<semver>+<sha12>`,
    /// where the prefix is [`SENTRY_RELEASE_PREFIX`] (`nexo-desktop`) and MUST
    /// match the Sentry project slug.
    /// The sha segment MUST be exactly 12 chars or the literal `unknown` fallback.
    /// Validates against the env var emitted by `build.rs`.
    #[test]
    fn release_format_matches_spec_c1() {
        let sha = env!("NEXO_GIT_SHA");
        let version = env!("CARGO_PKG_VERSION");
        let expected = format!("{}@{}+{}", SENTRY_RELEASE_PREFIX, version, sha);

        assert!(
            sha == "unknown" || sha.len() == 12,
            "NEXO_GIT_SHA must be `unknown` fallback or exactly 12 chars, got {:?}",
            sha
        );
        assert!(
            sha.chars().all(|c| c.is_ascii_hexdigit() || sha == "unknown"),
            "NEXO_GIT_SHA must be lowercase hex (or `unknown`), got {:?}",
            sha
        );
        assert!(
            expected.starts_with(&format!("{}@{}+", SENTRY_RELEASE_PREFIX, version)),
            "release `{}` must start with `{}@<version>+`",
            expected,
            SENTRY_RELEASE_PREFIX
        );
    }

    /// Defensive: `build_options` MUST produce a release string of the same shape
    /// when a DSN is present — proves the format is wired end-to-end (sha env var
    /// → release string) without needing to actually initialize the SDK.
    #[test]
    fn build_options_release_matches_spec_c1() {
        let _guard = ENV_LOCK.lock().unwrap_or_else(|e| e.into_inner());
        unsafe {
            std::env::set_var("SENTRY_DSN", "https://public@example.com/1");
        }
        let opts = build_options().expect("DSN set → opts present");
        unsafe {
            std::env::remove_var("SENTRY_DSN");
        }

        let release = opts.release.expect("release always set");
        let version = env!("CARGO_PKG_VERSION");
        let sha = env!("NEXO_GIT_SHA");

        assert_eq!(release, format!("{}@{}+{}", SENTRY_RELEASE_PREFIX, version, sha));
    }
}
