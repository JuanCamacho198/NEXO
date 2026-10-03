#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

use nexo_desktop::commands;
use nexo_desktop::db::{open_and_migrate, resolve_db_path};
use nexo_desktop::queue::repository::QueueRepository;
use nexo_desktop::repository::LibraryRepository;
use nexo_desktop::sentry_init;
use nexo_desktop::state::AppState;
use rusqlite::Connection;
use tauri::{AppHandle, Manager};
use tauri_plugin_deep_link::DeepLinkExt;

fn build_state(app: &AppHandle) -> Result<AppState, String> {
    let db_path = resolve_db_path(app).map_err(|err| err.to_string())?;
    let connection = open_and_migrate(&db_path).map_err(|err| err.to_string())?;
    let app_data_dir = app.path().app_data_dir().map_err(|e| e.to_string())?;

    // WU6 (FR-02, INV-5): version-gated, idempotent layout migration. Moves
    // files, rewrites stored paths and verifies BEFORE the marker is bumped; a
    // verification failure leaves the legacy tree live and the app boots on it.
    match nexo_desktop::layout::migrate_layout(&app_data_dir, &connection) {
        Ok(outcome) => eprintln!("[nexo] layout migration: {outcome:?}"),
        Err(err) => eprintln!("[nexo] layout migration failed (legacy tree kept): {err}"),
    }

    let queue_connection = Connection::open(&db_path).map_err(|err| err.to_string())?;
    queue_connection.execute_batch("PRAGMA foreign_keys = ON;").map_err(|err| err.to_string())?;
    let repository = LibraryRepository::new(connection);
    let queue_repository = QueueRepository::new(queue_connection);

    // FR-14: startup-idle retention. Runs on its own connection/thread so it
    // never blocks the setup path; prunes tombstones older than 30 days, then
    // vacuums. There is no pg_cron in this repo, so desktop is the executor for
    // both the local SQLite store (here) and the documented Supabase function.
    let retention_db_path = db_path.clone();
    std::thread::spawn(move || {
        if let Ok(connection) = Connection::open(&retention_db_path) {
            let _ = nexo_desktop::retention::run_retention(
                &connection,
                nexo_desktop::retention::RETENTION_DAYS,
            );
        }
    });

    Ok(AppState::new(repository, queue_repository, app_data_dir, db_path))
}

/// Pure helper: first nexo://, nextpage:// or nextpage-desktop:// argument in argv.
fn extract_install_url(argv: &[String]) -> Option<String> {
    argv.iter().skip(1).find_map(|arg| {
        let lower = arg.to_ascii_lowercase();
        if lower.starts_with("nexo://")
            || lower.starts_with("nextpage://")
            || lower.starts_with("nextpage-desktop://")
        {
            Some(arg.clone())
        } else {
            None
        }
    })
}

/// Pure helper: host component of a scheme://host/... URL.
fn deep_link_host(url: &str) -> String {
    let rest = match url.split_once("://") {
        Some((_, rest)) => rest,
        None => return String::new(),
    };
    rest.split(['/', '?']).next().unwrap_or_default().to_string()
}

#[cfg(test)]
mod deep_link_tests {
    use super::*;

    #[test]
    fn extract_install_url_finds_first_legacy_arg() {
        let argv = vec![
            "nexo-desktop.exe".to_string(),
            "nextpage://install?url=https://example.com/manifest.json".to_string(),
        ];
        assert_eq!(
            extract_install_url(&argv).as_deref(),
            Some("nextpage://install?url=https://example.com/manifest.json")
        );
    }

    #[test]
    fn extract_install_url_accepts_canonical_nexo_scheme() {
        let argv = vec![
            "app.exe".to_string(),
            "nexo://install?url=https://example.com/m.json".to_string(),
        ];
        assert_eq!(
            extract_install_url(&argv).as_deref(),
            Some("nexo://install?url=https://example.com/m.json")
        );
    }

    #[test]
    fn extract_install_url_accepts_legacy_scheme() {
        let argv = vec![
            "app.exe".to_string(),
            "nextpage-desktop://install?url=https://example.com/m.json".to_string(),
        ];
        assert!(extract_install_url(&argv).is_some());
    }

    #[test]
    fn extract_install_url_ignores_plain_args() {
        let argv = vec!["app.exe".to_string(), "--flag".to_string()];
        assert_eq!(extract_install_url(&argv), None);
    }

    #[test]
    fn deep_link_host_parses_install_host() {
        assert_eq!(deep_link_host("nextpage://install?url=https://x"), "install");
        assert_eq!(deep_link_host("nextpage://auth/callback"), "auth");
        assert_eq!(deep_link_host("not a url"), "");
    }
}

fn main() {
    // Initialize Sentry BEFORE the Tauri Builder. Init failures are logged
    // and swallowed; the app boots regardless of Sentry availability.
    if sentry_init::init_or_log() {
        eprintln!("[nexo] Sentry initialized for desktop backend");
    } else {
        eprintln!("[nexo] Sentry disabled (no SENTRY_DSN or init failed)");
    }

    tauri::Builder::default()
        .plugin(tauri_plugin_single_instance::init(|app, argv, _cwd| {
            // sdd/addon-deeplink-v1: warm-start forwarding. Must be the FIRST
            // plugin (Tauri docs). Scan argv for a deep-link URL; only
            // install URLs are emitted, everything else is logged for QA.
            if let Some(url) = extract_install_url(&argv) {
                if deep_link_host(&url) == "install" {
                    use tauri::Emitter;
                    let _ = app.emit("deep-link-install", url);
                } else {
                    eprintln!("[nexo] unhandled deep-link argv: {argv:?}");
                }
            } else if argv.iter().skip(1).any(|arg| arg.contains("://")) {
                eprintln!("[nexo] unhandled deep-link argv: {argv:?}");
            }
            if let Some(window) = app.get_webview_window("main") {
                let _ = window.set_focus();
            }
        }))
        .plugin(tauri_plugin_deep_link::init())
        .plugin(tauri_plugin_oauth::init())
        .plugin(tauri_plugin_opener::init())
        .plugin(tauri_plugin_process::init())
        .plugin(tauri_plugin_os::init())
        .plugin(tauri_plugin_dialog::init())
        .plugin(tauri_plugin_updater::Builder::new().build())
        .plugin(tauri_plugin_fs::init())
        .setup(|app| {
            let state = build_state(app.handle()).map_err(std::io::Error::other)?;
            app.manage(state);

            // Register the scheme(s) declared in `tauri.conf.json`. `nexo` is the
            // canonical NEXO scheme; the `nextpage*` schemes are legacy and are
            // retained for the transition window (already-issued auth callbacks
            // and shared addon links must keep resolving). `deep_link_host`
            // parses by host, so the handler is scheme-agnostic.
            #[cfg(desktop)]
            app.deep_link().register("nexo")?;
            #[cfg(desktop)]
            app.deep_link().register("nextpage-desktop")?;
            #[cfg(desktop)]
            app.deep_link().register("nextpage")?;

            Ok(())
        })
        .invoke_handler(tauri::generate_handler![
            commands::listBooks,
            commands::upsertBook,
            commands::getSettings,
            commands::upsertSettings,
            commands::listLibraryBooks,
            commands::scanFolder,
            commands::getProgress,
            commands::saveProgress,
            commands::upsertProgress,
            commands::saveReadingSession,
            commands::getReadingStats,
            commands::getReadingActivity,
            commands::getReadingStatsForRange,
            commands::getReadingStreak,
            commands::upsertRemoteReadingSessions,
            commands::upsertRemoteHighlights,
            commands::indexBookText,
            commands::searchBookText,
            commands::importBook,
            commands::deleteBook,
            commands::hideBookFromLibrary,
            commands::getFileBytes,
            commands::getFileSize,
            commands::readFileRange,
            commands::updateBookProgress,
            commands::fileExists,
            commands::saveBookFile,
            commands::upsertBookCover,
            commands::extractEpubCover,
            commands::deleteBookCover,
            commands::listHighlights,
            commands::saveHighlight,
            commands::updateHighlight,
            commands::deleteHighlight,
            commands::listTags,
            commands::listTagsForHighlight,
            commands::createTag,
            commands::saveHighlightTags,
            commands::listDictionaryWords,
            commands::addDictionaryWord,
            commands::removeDictionaryWord,
            commands::updateDictionaryWord,
            commands::updateDictionaryEvidence,
            commands::searchDictionaryWords,
            commands::exportDictionary,
            commands::importDictionary,
            commands::listBookmarks,
            commands::saveBookmark,
            commands::deleteBookmark,
            commands::createCollection,
            commands::deleteCollection,
            commands::listCollections,
            commands::addBookToCollection,
            commands::removeBookFromCollection,
            commands::getBookCollections,
            commands::setReadingStatus,
            commands::addSyncOutboxItem,
            commands::addCoalescedSyncOutboxItem,
            commands::listSyncOutboxReady,
            commands::markSyncOutboxFailed,
            commands::deleteSyncOutboxItem,
            commands::pruneSyncOutbox,
            commands::reportErrorEvent,
            commands::logEvent,
            commands::open_devtools,
            commands::diagnose,
            commands::getLogs,
            commands::parse_epub,
            commands::get_epub_chapter,
            commands::get_epub_resource,
            commands::is_epub_cached,
            commands::clear_epub_cache,
            commands::index_epub_text,
            commands::getStorageStats,
            commands::clearCache,
            commands::getPerBookSizes,
            commands::deleteBookData,
            commands::cleanupOrphans,
            commands::getDailyGoalMinutes,
            commands::saveDailyGoalMinutes,
            commands::getTodayMinutes,
            commands::fetchAddonResource,
            commands::listInstalledAddons,
            commands::upsertInstalledAddon,
            commands::setAddonEnabled,
            commands::deleteInstalledAddon,
            commands::listAddonConsents,
            commands::setAddonConsent,
            commands::discoverCacheRead,
            commands::discoverCachePut,
            commands::downloadRemoteBook,
            commands::cancelRemoteDownload,
            commands::discardRemoteDownload,
            commands::protectSecret,
            commands::unprotectSecret
        ])
        .run(tauri::generate_context!())
        .expect("error while running tauri application");
}
