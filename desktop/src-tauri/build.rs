use std::process::Command;

/// Resolve the current commit's short SHA (12 chars) at build time and emit it
/// as the `NEXO_GIT_SHA` env var so `sentry_init.rs` can compose the
/// release string `nexo-desktop@{CARGO_PKG_VERSION}+{sha12}`.
///
/// Falls back to `unknown` when git is unavailable (e.g. Docker scratch
/// image) so `cargo build` never fails because of missing git history.
/// Spec C1 mandates 12-char SHA — see `sdd/sentry-observability-v2/design`
/// decision "Release sha length".
fn main() {
    tauri_build::build();

    let sha = Command::new("git")
        .args(["rev-parse", "--short=12", "HEAD"])
        .output()
        .ok()
        .filter(|out| out.status.success())
        .map(|out| String::from_utf8_lossy(&out.stdout).trim().chars().take(12).collect::<String>())
        .filter(|s| !s.is_empty())
        .unwrap_or_else(|| "unknown".to_string());

    println!("cargo:rustc-env=NEXO_GIT_SHA={}", sha);
}
