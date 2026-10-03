use std::path::PathBuf;

use tauri::{AppHandle, Manager, State};

use crate::models::IndexBookTextInput;
use crate::services::epub_extractor::{
    extract_plain_texts, EpubChapterContent, EpubExtractor, EpubMetadataExtract,
};
use crate::state::AppState;

const CACHE_VERSION: u32 = 4;

fn get_cache_dir(app: &AppHandle, book_id: &str) -> PathBuf {
    let app_data = app.path().app_data_dir().expect("app data dir");
    crate::layout::cache_dir(&app_data).join("epub").join(book_id)
}

fn cache_version_path(cache_dir: &std::path::Path) -> PathBuf {
    cache_dir.join(".cache_version")
}

fn is_cache_stale(cache_dir: &std::path::Path, meta: &EpubMetadataExtract) -> bool {
    // Version check
    let version_path = cache_version_path(cache_dir);
    let version_ok = std::fs::read_to_string(&version_path)
        .ok()
        .and_then(|v| v.trim().parse::<u32>().ok())
        .map(|v| v == CACHE_VERSION)
        .unwrap_or(false);
    if !version_ok {
        return true;
    }
    // spine.json len check
    let spine_path = cache_dir.join("spine.json");
    if !spine_path.exists() {
        return true;
    }
    let data = match std::fs::read_to_string(&spine_path) {
        Ok(d) => d,
        Err(_) => return true,
    };
    let spine: Vec<String> = match serde_json::from_str(&data) {
        Ok(s) => s,
        Err(_) => return true,
    };
    if spine.len() != meta.total_chapters {
        return true;
    }
    if spine.len() != meta.spine_hrefs.len() {
        return true;
    }
    false
}

/// Parse an EPUB file: extract metadata, chapters, and resources into a cache directory.
/// Returns metadata + TOC. Subsequent calls use the cache.
/// Cache version 3: purges when spine.json len != totalChapters.
#[tauri::command(rename_all = "camelCase")]
pub async fn parse_epub(
    app: AppHandle,
    _state: State<'_, AppState>,
    file_path: String,
    book_id: String,
) -> Result<EpubMetadataExtract, String> {
    let cache_dir = get_cache_dir(&app, &book_id);

    // Check if already cached
    let metadata_path = cache_dir.join("metadata.json");
    if metadata_path.exists() {
        let data = std::fs::read_to_string(&metadata_path)
            .map_err(|e| format!("Failed to read cached metadata: {}", e))?;
        match serde_json::from_str::<EpubMetadataExtract>(&data) {
            Ok(meta) => {
                if is_cache_stale(&cache_dir, &meta) {
                    let _ = std::fs::remove_dir_all(&cache_dir);
                } else {
                    return Ok(meta);
                }
            }
            Err(_) => {
                // Old shape or corrupted -> purge
                let _ = std::fs::remove_dir_all(&cache_dir);
            }
        }
    } else {
        // Even if metadata missing, check stale version file alone
        let version_path = cache_version_path(&cache_dir);
        if version_path.exists() {
            let version_ok = std::fs::read_to_string(&version_path)
                .ok()
                .and_then(|v| v.trim().parse::<u32>().ok())
                .map(|v| v == CACHE_VERSION)
                .unwrap_or(false);
            if !version_ok && cache_dir.exists() {
                let _ = std::fs::remove_dir_all(&cache_dir);
            }
        }
    }

    // Extract and cache
    let meta = EpubExtractor::extract(std::path::Path::new(&file_path), &cache_dir)?;

    // Cache metadata for future calls
    std::fs::create_dir_all(&cache_dir)
        .map_err(|e| format!("Failed to create cache dir: {}", e))?;
    let data =
        serde_json::to_string(&meta).map_err(|e| format!("Failed to serialize metadata: {}", e))?;
    std::fs::write(&metadata_path, &data)
        .map_err(|e| format!("Failed to write metadata cache: {}", e))?;

    // Write cache version (bumped 2→3)
    let version_path = cache_version_path(&cache_dir);
    let _ = std::fs::write(&version_path, CACHE_VERSION.to_string());

    // Ensure spine.json is written (extractor already does, but verify)
    let spine_path = cache_dir.join("spine.json");
    if !spine_path.exists() {
        let spine_data = serde_json::to_string(&meta.spine_hrefs)
            .map_err(|e| format!("Failed to serialize spine: {}", e))?;
        let _ = std::fs::write(&spine_path, &spine_data);
    }

    Ok(meta)
}

/// Get a specific chapter's content from the EPUB cache.
#[tauri::command(rename_all = "camelCase")]
pub async fn get_epub_chapter(
    app: AppHandle,
    book_id: String,
    chapter_index: usize,
) -> Result<EpubChapterContent, String> {
    let cache_dir = get_cache_dir(&app, &book_id);
    EpubExtractor::get_chapter(&cache_dir, chapter_index)
}

/// Get a resource file from the EPUB cache.
#[tauri::command(rename_all = "camelCase")]
pub async fn get_epub_resource(
    app: AppHandle,
    book_id: String,
    resource_path: String,
) -> Result<Vec<u8>, String> {
    let cache_dir = get_cache_dir(&app, &book_id);
    EpubExtractor::get_resource(&cache_dir, &resource_path)
}

/// Check if an EPUB has been parsed and cached.
#[tauri::command(rename_all = "camelCase")]
pub async fn is_epub_cached(app: AppHandle, book_id: String) -> Result<bool, String> {
    let cache_dir = get_cache_dir(&app, &book_id);
    let metadata_path = cache_dir.join("metadata.json");
    Ok(metadata_path.exists())
}

/// Delete the EPUB cache for a book.
#[tauri::command(rename_all = "camelCase")]
pub async fn clear_epub_cache(app: AppHandle, book_id: String) -> Result<(), String> {
    let cache_dir = get_cache_dir(&app, &book_id);
    if cache_dir.exists() {
        std::fs::remove_dir_all(&cache_dir)
            .map_err(|e| format!("Failed to clear EPUB cache: {}", e))?;
    }
    Ok(())
}

/// Index EPUB text for FTS5 search.
/// Reads cached chapter files, strips HTML, and indexes into FTS5.
#[tauri::command(rename_all = "camelCase")]
pub async fn index_epub_text(
    app: AppHandle,
    state: State<'_, AppState>,
    book_id: String,
) -> Result<(), String> {
    let cache_dir = get_cache_dir(&app, &book_id);
    if !cache_dir.exists() {
        return Err("EPUB not cached — run parse_epub first".to_string());
    }

    let chunks = extract_plain_texts(&cache_dir)?;
    if chunks.is_empty() {
        return Ok(()); // Nothing to index
    }

    let input =
        IndexBookTextInput {
            book_id: book_id.clone(),
            chunks: chunks
                .into_iter()
                .map(|(locator, text_content, chunk_index)| {
                    crate::models::IndexBookTextChunkInput { locator, chunk_index, text_content }
                })
                .collect(),
        };

    let mut repository = state.repository.lock().map_err(|e| format!("{}", e))?;
    repository.index_book_text(input).map_err(|e| format!("{}", e))
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::services::epub_extractor::{EpubChapterMeta, EpubMetadataExtract};
    use std::fs;

    fn make_meta(total: usize, spine_len: usize) -> EpubMetadataExtract {
        EpubMetadataExtract {
            title: "t".to_string(),
            author: "a".to_string(),
            language: None,
            publisher: None,
            toc: vec![EpubChapterMeta {
                index: 0,
                id: "chapter-0".to_string(),
                label: "C".to_string(),
                href: "x.xhtml".to_string(),
                depth: 0,
            }],
            spine_hrefs: vec!["a.xhtml".to_string(); spine_len],
            total_chapters: total,
            resources_path: "/tmp".to_string(),
        }
    }

    #[test]
    fn cache_stale_when_version_mismatch() {
        let dir = std::env::temp_dir().join(format!("epub_cache_ver_{}", std::process::id()));
        let _ = fs::remove_dir_all(&dir);
        fs::create_dir_all(&dir).unwrap();
        // Write old version
        fs::write(dir.join(".cache_version"), "2").unwrap();
        fs::write(
            dir.join("spine.json"),
            serde_json::to_string(&vec!["a.xhtml".to_string()]).unwrap(),
        )
        .unwrap();
        let meta = make_meta(1, 1);
        assert!(is_cache_stale(&dir, &meta), "version 2 should be stale for CACHE_VERSION 3");
        let _ = fs::remove_dir_all(&dir);
    }

    #[test]
    fn cache_stale_when_spine_len_mismatch() {
        let dir = std::env::temp_dir().join(format!("epub_cache_spine_{}", std::process::id()));
        let _ = fs::remove_dir_all(&dir);
        fs::create_dir_all(&dir).unwrap();
        fs::write(dir.join(".cache_version"), CACHE_VERSION.to_string()).unwrap();
        // spine.json has 24 but meta says 20 -> stale (Historia 24/20)
        fs::write(
            dir.join("spine.json"),
            serde_json::to_string(&vec!["a.xhtml".to_string(); 24]).unwrap(),
        )
        .unwrap();
        let meta = make_meta(20, 20);
        assert!(is_cache_stale(&dir, &meta));
        let _ = fs::remove_dir_all(&dir);
    }

    #[test]
    fn cache_fresh_when_version_and_len_match() {
        let dir = std::env::temp_dir().join(format!("epub_cache_fresh_{}", std::process::id()));
        let _ = fs::remove_dir_all(&dir);
        fs::create_dir_all(&dir).unwrap();
        fs::write(dir.join(".cache_version"), CACHE_VERSION.to_string()).unwrap();
        fs::write(
            dir.join("spine.json"),
            serde_json::to_string(&vec!["a.xhtml".to_string(); 5]).unwrap(),
        )
        .unwrap();
        let meta = make_meta(5, 5);
        assert!(!is_cache_stale(&dir, &meta));
        let _ = fs::remove_dir_all(&dir);
    }
}
