use super::map_command_error;
use crate::commands::confine::{resolve_within_app_data, AppDataRoot};
use crate::state::AppState;
use std::fs;
use std::path::PathBuf;
use tauri::State;

/// The confinement rule lives in `commands::confine`; these wrappers only map
/// the typed error onto the `String` the IPC boundary already returns.
fn confined<A: AppDataRoot>(app: &A, candidate: &str) -> Result<PathBuf, String> {
    resolve_within_app_data(app, candidate).map_err(map_command_error)
}

#[allow(non_snake_case)]
#[tauri::command(rename_all = "camelCase")]
pub async fn getFileBytes(app: tauri::AppHandle, file_path: String) -> Result<Vec<u8>, String> {
    let path = confined(&app, &file_path)?;
    fs::read(&path).map_err(|err| format!("Failed to read file: {}", err))
}

#[allow(non_snake_case)]
#[tauri::command(rename_all = "camelCase")]
pub async fn getFileSize(app: tauri::AppHandle, file_path: String) -> Result<u64, String> {
    let path = confined(&app, &file_path)?;
    let metadata =
        std::fs::metadata(&path).map_err(|err| format!("Failed to read file metadata: {}", err))?;
    Ok(metadata.len())
}

#[allow(non_snake_case)]
#[tauri::command(rename_all = "camelCase")]
pub async fn readFileRange(
    app: tauri::AppHandle,
    file_path: String,
    offset: u64,
    length: u64,
) -> Result<Vec<u8>, String> {
    use std::io::{Read, Seek, SeekFrom};
    let path = confined(&app, &file_path)?;
    let mut file =
        std::fs::File::open(&path).map_err(|err| format!("Failed to open file: {}", err))?;
    file.seek(SeekFrom::Start(offset)).map_err(|err| format!("Failed to seek in file: {}", err))?;
    let mut buf = vec![0u8; length as usize];
    let n = file.read(&mut buf).map_err(|err| format!("Failed to read file: {}", err))?;
    buf.truncate(n);
    Ok(buf)
}

#[allow(non_snake_case)]
#[tauri::command(rename_all = "camelCase")]
pub async fn fileExists(app: tauri::AppHandle, path: String) -> Result<bool, String> {
    Ok(confined(&app, &path)?.exists())
}

#[allow(non_snake_case)]
#[tauri::command(rename_all = "camelCase")]
pub async fn updateBookProgress(
    state: State<'_, AppState>,
    book_id: String,
    current_page: i32,
) -> Result<(), String> {
    let repository = state.repository.lock().map_err(|e| format!("{}", e))?;
    repository.update_book_progress(&book_id, current_page).map_err(|e| format!("{}", e))
}

#[allow(non_snake_case)]
#[tauri::command(rename_all = "camelCase")]
pub async fn saveBookFile(
    app: tauri::AppHandle,
    state: State<'_, AppState>,
    id: String,
    data: Vec<u8>,
    title: Option<String>,
    author: Option<String>,
    format: Option<String>,
) -> Result<(), String> {
    // No caller-supplied path here: the destination is derived from `id` inside
    // the repository, which already resolves it under `app_data_dir` (see
    // `repository::files::save_book_file`). The id still crosses the IPC
    // boundary, so it is refused when it would climb out of app data instead of
    // being joined blindly.
    let root = app.app_data_root().map_err(map_command_error)?;
    let destination = root.join("books").join(&id);
    resolve_within_app_data(&app, &destination.to_string_lossy())
        .map_err(map_command_error)
        .map(|_| ())?;
    let repository = state.repository.lock().map_err(|e| format!("{}", e))?;
    repository
        .save_book_file(&app, &id, &data, title.as_deref(), author.as_deref(), format.as_deref())
        .map_err(map_command_error)
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::error::{AppError, AppResult};
    use std::path::Path;

    struct TempRoot {
        dir: tempfile::TempDir,
        root: PathBuf,
    }

    impl TempRoot {
        fn new() -> Self {
            let dir = tempfile::tempdir().expect("temp dir");
            std::fs::create_dir_all(dir.path().join("books")).expect("books");
            let root = std::fs::canonicalize(dir.path()).expect("canonical root");
            Self { dir, root }
        }
    }

    impl AppDataRoot for TempRoot {
        fn app_data_root(&self) -> AppResult<PathBuf> {
            let _ = &self.dir;
            Ok(self.root.clone())
        }
    }

    fn fixture() -> (TempRoot, std::path::PathBuf) {
        let root = TempRoot::new();
        let book = root.root.join("books").join("inside.pdf");
        std::fs::write(&book, b"abcdef").expect("fixture");
        (root, book)
    }

    #[test]
    fn reading_a_book_inside_app_data_still_works() {
        let (root, book) = fixture();
        let path = confined(&root, &book.to_string_lossy()).expect("inside");
        assert_eq!(fs::read(path).expect("read"), b"abcdef");
    }

    #[test]
    fn reading_a_file_outside_app_data_is_refused() {
        let (root, _) = fixture();
        let outside = tempfile::tempdir().expect("outside");
        let secret = std::fs::canonicalize(outside.path()).expect("canonical").join("secret.txt");
        std::fs::write(&secret, b"secret").expect("secret");
        let err = confined(&root, &secret.to_string_lossy()).expect_err("must refuse");
        assert!(err.contains("outside the application data directory"), "{err}");
        assert!(matches!(
            resolve_within_app_data(&root, &secret.to_string_lossy()),
            Err(AppError::InvalidInput(_))
        ));
    }

    #[test]
    fn a_traversal_out_of_app_data_is_refused() {
        let (root, _) = fixture();
        let candidate = root.root.join("books").join("..").join("..").join("etc").join("passwd");
        assert!(confined(&root, &candidate.to_string_lossy()).is_err());
        assert!(confined(&root, "../../etc/passwd").is_err());
    }

    #[test]
    fn a_missing_file_inside_app_data_behaves_as_before() {
        let (root, _) = fixture();
        let missing = root.root.join("books").join("absent.pdf");
        let path = confined(&root, &missing.to_string_lossy()).expect("inside");
        let err = fs::read(&path).expect_err("the file is absent");
        assert_eq!(err.kind(), std::io::ErrorKind::NotFound);
        assert!(!Path::new(&path).exists());
    }
}
