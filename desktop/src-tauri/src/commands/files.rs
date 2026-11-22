use super::{lock_repository, CommandError};
use crate::commands::confine::{resolve_within_app_data, AppDataRoot};
use crate::state::AppState;
use std::fs;
use std::path::PathBuf;
use tauri::State;

/// The confinement rule lives in `commands::confine`; these wrappers only map
/// the typed error onto the `CommandError` the IPC boundary already returns.
fn confined<A: AppDataRoot>(app: &A, candidate: &str) -> Result<PathBuf, CommandError> {
    resolve_within_app_data(app, candidate).map_err(CommandError::from)
}

fn read_failure(context: &str, err: std::io::Error) -> CommandError {
    CommandError::from(crate::error::AppError::Io(std::io::Error::new(
        err.kind(),
        format!("{context}: {err}"),
    )))
}

#[allow(non_snake_case)]
#[tauri::command(rename_all = "camelCase")]
pub async fn getFileBytes(
    app: tauri::AppHandle,
    file_path: String,
) -> Result<Vec<u8>, CommandError> {
    let path = confined(&app, &file_path)?;
    fs::read(&path).map_err(|err| read_failure("Failed to read file", err))
}

#[allow(non_snake_case)]
#[tauri::command(rename_all = "camelCase")]
pub async fn getFileSize(app: tauri::AppHandle, file_path: String) -> Result<u64, CommandError> {
    let path = confined(&app, &file_path)?;
    let metadata = std::fs::metadata(&path)
        .map_err(|err| read_failure("Failed to read file metadata", err))?;
    Ok(metadata.len())
}

#[allow(non_snake_case)]
#[tauri::command(rename_all = "camelCase")]
pub async fn readFileRange(
    app: tauri::AppHandle,
    file_path: String,
    offset: u64,
    length: u64,
) -> Result<Vec<u8>, CommandError> {
    use std::io::{Read, Seek, SeekFrom};
    let path = confined(&app, &file_path)?;
    let mut file =
        std::fs::File::open(&path).map_err(|err| read_failure("Failed to open file", err))?;
    file.seek(SeekFrom::Start(offset))
        .map_err(|err| read_failure("Failed to seek in file", err))?;
    let mut buf = vec![0u8; length as usize];
    let n = file.read(&mut buf).map_err(|err| read_failure("Failed to read file", err))?;
    buf.truncate(n);
    Ok(buf)
}

#[allow(non_snake_case)]
#[tauri::command(rename_all = "camelCase")]
pub async fn fileExists(app: tauri::AppHandle, path: String) -> Result<bool, CommandError> {
    Ok(confined(&app, &path)?.exists())
}

#[allow(non_snake_case)]
#[tauri::command(rename_all = "camelCase")]
pub async fn updateBookProgress(
    state: State<'_, AppState>,
    book_id: String,
    current_page: i32,
) -> Result<(), CommandError> {
    let repository = lock_repository(&state)?;
    repository.update_book_progress(&book_id, current_page).map_err(CommandError::from)
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
) -> Result<(), CommandError> {
    // No caller-supplied path here: the destination is derived from `id` inside
    // the repository, which already resolves it under `app_data_dir` (see
    // `repository::files::save_book_file`). The id still crosses the IPC
    // boundary, so it is refused when it would climb out of app data instead of
    // being joined blindly.
    let root = app.app_data_root().map_err(CommandError::from)?;
    // The destination must be built the same way the repository builds it, or
    // this guard validates a path the write never uses. `books_dir(root)` is the
    // legacy tree; after the layout migration every book lives in
    // `data/books`, so joining `books` here refused every post-migration write
    // with PATH_OUTSIDE_APP_DATA while the file itself was written elsewhere.
    let destination = crate::layout::resolved_books_dir(&root).join(&id);
    resolve_within_app_data(&app, &destination.to_string_lossy())
        .map_err(CommandError::from)
        .map(|_| ())?;
    let repository = lock_repository(&state)?;
    repository
        .save_book_file(&app, &id, &data, title.as_deref(), author.as_deref(), format.as_deref())
        .map_err(CommandError::from)
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

    /// After the layout migration every book lives under `data/books`, and the
    /// write command must validate the path the repository will actually use.
    /// Building `app_data/books/<id>` here instead refused every post-migration
    /// upload with PATH_OUTSIDE_APP_DATA: the guard approved a path nothing
    /// writes, while the real destination was never checked at all.
    ///
    /// This pins the two paths to the same layout helper, which is what the
    /// command now does. It is a guard against the two drifting apart again;
    /// the end-to-end proof is the live app.
    #[test]
    fn the_write_destination_follows_the_migrated_layout() {
        let root = TempRoot::new();
        std::fs::create_dir_all(crate::layout::books_dir(&root.root)).expect("data/books");
        std::fs::write(
            crate::layout::marker_path(&root.root),
            serde_json::json!({ "version": crate::layout::LAYOUT_VERSION }).to_string(),
        )
        .expect("marker");

        let resolved_dir = crate::layout::resolved_books_dir(&root.root);
        assert!(!resolved_dir.ends_with("books") || resolved_dir.ends_with("data/books"));
        assert!(
            resolved_dir.starts_with(crate::layout::data_dir(&root.root)),
            "with the marker at the current version the repository writes under \
             data/, so the guard must resolve the same tree: {}",
            resolved_dir.display()
        );
        let destination = resolved_dir.join("book-1.epub");
        let resolved = confined(&root, &destination.to_string_lossy()).expect("inside app data");
        assert_eq!(resolved.file_name().and_then(|n| n.to_str()), Some("book-1.epub"));
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
        let wire: serde_json::Value = serde_json::from_str(&err.payload()).expect("JSON");
        assert!(
            wire["message"]
                .as_str()
                .is_some_and(|m| m.contains("outside the application data directory")),
            "{wire}"
        );
        assert!(matches!(
            resolve_within_app_data(&root, &secret.to_string_lossy()),
            Err(AppError::PathOutsideAppData(_))
        ));
    }

    /// A confinement refusal is a security event, not a generic validation
    /// failure. It must reach the frontend with its own code so it can be
    /// logged, tested and observed as a distinct thing, while the message keeps
    /// naming the offending path for the log line.
    #[test]
    fn a_confinement_refusal_carries_its_own_code() {
        let (root, _) = fixture();
        let outside = tempfile::tempdir().expect("outside");
        let secret = std::fs::canonicalize(outside.path()).expect("canonical").join("secret.txt");
        std::fs::write(&secret, b"secret").expect("secret");
        let err = confined(&root, &secret.to_string_lossy()).expect_err("must refuse");
        let wire: serde_json::Value =
            serde_json::from_str(&err.payload()).expect("the wire format is JSON");
        assert_eq!(wire["code"], "PATH_OUTSIDE_APP_DATA", "{wire}");
        assert!(wire["message"].as_str().is_some_and(|m| m.contains("secret.txt")), "{wire}");
    }

    /// The refusal message reaches a retry UI (`discoverDownloadImport` returns
    /// `error.message`), so the home directory prefix must not travel with it.
    #[test]
    fn a_confinement_refusal_does_not_leak_the_home_directory() {
        let home = std::env::var("USERPROFILE").or_else(|_| std::env::var("HOME"));
        let Ok(home) = home else {
            return;
        };
        let candidate = format!("{home}\\outside\\secret.txt");
        let err = confined(&TempRoot::new(), &candidate).expect_err("must refuse");
        let wire: serde_json::Value = serde_json::from_str(&err.payload()).expect("JSON");
        let message = wire["message"].as_str().expect("message");
        assert!(!message.contains(&home), "the home directory must be redacted: {message}");
        assert!(message.contains("secret.txt"), "the refused path stays identifiable: {message}");
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
