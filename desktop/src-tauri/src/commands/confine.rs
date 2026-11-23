//! Path confinement for the file commands.
//!
//! The `fs` capability in `capabilities/default.json` reads like a sandbox, but
//! every command in this tree talks to `std::fs` directly and never goes through
//! the `fs` plugin, so the capability protects nothing. Any script running in
//! the webview could `invoke('getFileBytes', { filePath: 'C:\\anything' })`.
//!
//! The guard here is the single place that decides whether a path coming across
//! the IPC boundary may be touched. Canonicalisation is done on both sides so
//! that symlinks and `..` segments cannot be used to step out of app data.

use std::path::{Component, Path, PathBuf};

use crate::error::{AppError, AppResult};

/// Resolve `candidate` and require the result to live under `root`.
///
/// `root` is expected to be an existing directory (the app data dir). The
/// candidate itself does not have to exist: a write path is resolved through
/// its parent, so `root/books/x.epub` is accepted before `x.epub` is created,
/// while `root/../../etc/x` is not.
pub(crate) fn resolve_within(root: &Path, candidate: &str) -> AppResult<PathBuf> {
    let trimmed = candidate.trim();
    if trimmed.is_empty() {
        return Err(AppError::InvalidInput("empty path".to_string()));
    }

    let root_real = canonical_dir(root).ok_or_else(|| {
        AppError::InvalidInput(format!("app data directory is unavailable: {}", root.display()))
    })?;

    let raw = Path::new(trimmed);
    let current_dir = std::env::current_dir().map_err(AppError::Io)?;
    let absolute = if raw.is_absolute() {
        raw.to_path_buf()
    } else if let Ok(cwd) = std::fs::canonicalize(&current_dir) {
        // A relative path from the frontend is a real filesystem path first
        // (book ids stored as `books/<id>.epub` arrive relative), so it is
        // anchored at the process working directory. Callers that mean "inside
        // app data" pass an absolute path under it.
        cwd.join(raw)
    } else {
        root_real.join(raw)
    };

    // Lexical check first: a `..` that climbs above the root is refused even
    // when nothing exists yet, so a traversal cannot be "fixed" by creating
    // the parent later.
    //
    // `root_real` comes from `std::fs::canonicalize`, which on Windows returns
    // a verbatim path (`\\?\C:\…`). `absolute` never carries that prefix, so
    // comparing them directly reports every real path as outside the root —
    // the guard refused valid books under `data/books` because of it. The
    // prefix is stripped for the lexical comparison only; the canonical
    // comparison below (the one that defeats symlinks) still uses `root_real`.
    let lexical = normalize_lexically(&absolute);
    if !starts_with_ignoring_verbatim(&lexical, &root_real) {
        return Err(outside(trimmed));
    }

    if absolute.exists() {
        return match std::fs::canonicalize(&absolute) {
            Ok(real) => ensure_inside(&root_real, &real, trimmed),
            Err(err) => Err(AppError::Io(err)),
        };
    }

    // Write path: the file is not there yet, so the parent decides.
    let parent = lexical.parent().ok_or_else(|| outside(trimmed))?;
    let parent_real = match std::fs::canonicalize(parent) {
        Ok(real) => real,
        // Nothing between root and the target exists yet. The lexical check
        // above already proved the target sits under the root, and a path that
        // does not exist cannot be a link out, so the lexical answer stands.
        Err(err) if err.kind() == std::io::ErrorKind::NotFound => return Ok(lexical),
        Err(err) => return Err(AppError::Io(err)),
    };
    ensure_inside(&root_real, &parent_real, trimmed)?;

    let file_name = lexical.file_name().ok_or_else(|| outside(trimmed))?;
    Ok(parent_real.join(file_name))
}

/// The directory the file commands are confined to. Expressed as a trait so the
/// guard can be exercised without standing up a Tauri application.
pub(crate) trait AppDataRoot {
    fn app_data_root(&self) -> AppResult<PathBuf>;
}

impl AppDataRoot for tauri::AppHandle {
    fn app_data_root(&self) -> AppResult<PathBuf> {
        use tauri::Manager;
        self.path().app_data_dir().map_err(|err| {
            AppError::InvalidInput(format!("app data directory is unavailable: {err}"))
        })
    }
}

/// Guard for the file commands: resolve `candidate` inside the app data dir.
pub(crate) fn resolve_within_app_data<A: AppDataRoot>(
    app: &A,
    candidate: &str,
) -> AppResult<PathBuf> {
    resolve_within(&app.app_data_root()?, candidate)
}

fn ensure_inside(root_real: &Path, resolved: &Path, candidate: &str) -> AppResult<PathBuf> {
    if resolved.starts_with(root_real) {
        Ok(resolved.to_path_buf())
    } else {
        Err(outside(candidate))
    }
}

/// Containment test that tolerates the Windows verbatim prefix on either side.
///
/// `std::fs::canonicalize` returns `\\?\C:\…` on Windows while a path built by
/// the frontend is plain `C:\…`. `Path::starts_with` compares components, so
/// the two report as unrelated even though they name the same directory. Both
/// sides are stripped of the prefix before comparing; on every other platform
/// this is a no-op.
fn starts_with_ignoring_verbatim(path: &Path, base: &Path) -> bool {
    strip_verbatim(path).starts_with(strip_verbatim(base))
}

fn strip_verbatim(path: &Path) -> PathBuf {
    let text = path.to_string_lossy();
    for prefix in ["\\\\?\\UNC\\", "\\\\?\\"] {
        if let Some(rest) = text.strip_prefix(prefix) {
            // A verbatim UNC path (`\\?\UNC\server\share`) maps back to
            // `\\server\share`; a plain verbatim path drops the prefix only.
            let restored =
                if prefix == "\\\\?\\UNC\\" { format!("\\\\{rest}") } else { rest.to_string() };
            return PathBuf::from(restored);
        }
    }
    path.to_path_buf()
}

/// A path guard refusal. The path is kept so a log line still names what was
/// refused, but the home directory prefix is replaced with `~`: the same text
/// reaches a retry UI (`discoverDownloadImport` returns `error.message`), and a
/// user-facing surface should not disclose the account name.
fn outside(candidate: &str) -> AppError {
    AppError::PathOutsideAppData(format!(
        "path is outside the application data directory: {}",
        redact_home(candidate)
    ))
}

fn redact_home(candidate: &str) -> String {
    let home = std::env::var_os("HOME").or_else(|| std::env::var_os("USERPROFILE"));
    let Some(home) = home.and_then(|h| h.into_string().ok()) else {
        return candidate.to_string();
    };
    if home.is_empty() {
        return candidate.to_string();
    }
    candidate.replace(&home, "~")
}

fn canonical_dir(path: &Path) -> Option<PathBuf> {
    std::fs::canonicalize(path).ok().or_else(|| {
        if path.is_dir() {
            Some(normalize_lexically(&if path.is_absolute() {
                path.to_path_buf()
            } else {
                std::env::current_dir().ok()?.join(path)
            }))
        } else {
            None
        }
    })
}

/// Lexical `..`/`.` resolution; safe for paths that do not exist yet.
fn normalize_lexically(path: &Path) -> PathBuf {
    let mut normalized = PathBuf::new();
    for component in path.components() {
        match component {
            Component::ParentDir => {
                normalized.pop();
            }
            Component::CurDir => {}
            other => normalized.push(other.as_os_str()),
        }
    }
    normalized
}

#[cfg(test)]
mod tests {
    use super::*;

    fn app_data() -> tempfile::TempDir {
        let dir = tempfile::tempdir().expect("temp dir");
        std::fs::create_dir_all(dir.path().join("books")).expect("books dir");
        std::fs::write(dir.path().join("books").join("inside.epub"), b"ok").expect("fixture");
        dir
    }

    /// The canonical form of the fixture root: `TempDir::path()` is the
    /// uncanonicalised short-name path, and the guard works on the real one.
    fn root_of(dir: &tempfile::TempDir) -> PathBuf {
        std::fs::canonicalize(dir.path()).expect("canonical root")
    }

    #[test]
    fn a_path_inside_app_data_reads_as_before() {
        let dir = app_data();
        let root = root_of(&dir);
        let candidate = root.join("books").join("inside.epub");
        let resolved = resolve_within(&root, &candidate.to_string_lossy()).expect("inside");
        assert_eq!(std::fs::read(resolved).expect("read"), b"ok");
    }

    #[test]
    fn a_relative_path_is_anchored_at_the_working_directory_not_app_data() {
        let dir = app_data();
        let root = root_of(&dir);
        // `books/inside.epub` relative means the process cwd, which is not app
        // data, so it is refused rather than silently rebased onto app data.
        assert!(resolve_within(&root, "books/inside.epub").is_err());
    }

    #[test]
    fn a_file_that_does_not_exist_yet_resolves_inside_for_writing() {
        let dir = app_data();
        let root = root_of(&dir);
        let candidate = root.join("books").join("new.epub");
        let resolved = resolve_within(&root, &candidate.to_string_lossy())
            .expect("write target inside app data is allowed");
        assert_eq!(
            std::fs::canonicalize(resolved.parent().expect("parent")).expect("parent exists"),
            std::fs::canonicalize(root.join("books")).expect("books exists")
        );
        assert_eq!(resolved.file_name().and_then(|n| n.to_str()), Some("new.epub"));
    }

    #[test]
    fn a_missing_file_inside_app_data_is_not_an_error() {
        let dir = app_data();
        let root = root_of(&dir);
        let candidate = root.join("books").join("absent.epub");
        assert!(resolve_within(&root, &candidate.to_string_lossy()).is_ok());
        assert!(!candidate.exists());
    }

    #[test]
    fn an_absolute_path_outside_app_data_is_rejected() {
        let dir = app_data();
        let root = root_of(&dir);
        let outside = tempfile::tempdir().expect("other dir");
        let secret =
            std::fs::canonicalize(outside.path()).expect("canonical outside").join("secret.txt");
        std::fs::write(&secret, b"no").expect("fixture");
        let err = resolve_within(&root, &secret.to_string_lossy())
            .expect_err("outside path must be refused");
        assert!(matches!(err, AppError::PathOutsideAppData(_)), "{err:?}");
    }

    #[test]
    fn a_traversal_that_escapes_app_data_is_rejected() {
        let dir = app_data();
        let root = root_of(&dir);
        let escaping = format!("{}/../../../etc/passwd", root.display());
        let err = resolve_within(&root, &escaping).expect_err("traversal must be refused");
        assert!(matches!(err, AppError::PathOutsideAppData(_)), "{err:?}");
    }

    #[test]
    fn a_relative_traversal_that_escapes_app_data_is_rejected() {
        let dir = app_data();
        let root = root_of(&dir);
        let err = resolve_within(&root, "../../etc/passwd")
            .expect_err("relative traversal must be refused");
        assert!(matches!(err, AppError::PathOutsideAppData(_)), "{err:?}");
    }

    #[test]
    fn a_traversal_into_a_missing_directory_is_still_rejected() {
        let dir = app_data();
        let root = root_of(&dir);
        let escaping = root.join("books").join("..").join("..").join("escape.epub");
        let err = resolve_within(&root, &escaping.to_string_lossy())
            .expect_err("traversal out of root must be refused even if the target is absent");
        assert!(matches!(err, AppError::PathOutsideAppData(_)), "{err:?}");
    }

    #[test]
    fn an_empty_path_is_rejected() {
        let dir = app_data();
        assert!(resolve_within(&root_of(&dir), "   ").is_err());
    }

    /// `std::fs::canonicalize` returns a verbatim path (`\\?\C:\…`) on Windows
    /// while a path built by the frontend is plain (`C:\…`). `Path::starts_with`
    /// compares components, so the two look unrelated even though they name the
    /// same directory — which made the guard refuse every real book under
    /// `data/books` with PATH_OUTSIDE_APP_DATA.
    ///
    /// This pins the containment helper directly: the fixture root is
    /// canonicalised (verbatim on Windows) exactly as the guard does it, and the
    /// candidate is spelled the way the frontend spells it.
    #[test]
    fn a_verbatim_root_contains_its_plain_candidate() {
        let dir = app_data();
        let root = root_of(&dir);
        // `root_of` canonicalises, so on Windows `root` carries `\\?\`.
        let candidate = root.join("books").join("inside.epub");
        let plain = strip_verbatim(&candidate);
        if plain == candidate {
            // Not Windows: no verbatim prefix to strip, nothing to prove.
            return;
        }
        assert!(
            !plain.starts_with(&root) || strip_verbatim(&root).starts_with(&root),
            "precondition: the plain form must not match the verbatim root"
        );
        assert!(
            starts_with_ignoring_verbatim(&plain, &root),
            "a plain path inside a verbatim root must still count as inside"
        );
        // And a real sibling that genuinely leaves the root stays refused.
        let outer = tempfile::tempdir().expect("outer");
        let sibling = std::fs::canonicalize(outer.path()).expect("canonical").join("secret.txt");
        assert!(
            !starts_with_ignoring_verbatim(&sibling, &root),
            "a path outside the root must not be tolerated by the prefix rule"
        );
    }

    #[test]
    fn a_sibling_directory_sharing_the_prefix_is_rejected() {
        let outer = tempfile::tempdir().expect("temp dir");
        std::fs::create_dir_all(outer.path().join("appdata")).expect("root");
        std::fs::create_dir_all(outer.path().join("appdata-evil")).expect("sibling");
        let canonical = std::fs::canonicalize(outer.path()).expect("canonical outer");
        let root = canonical.join("appdata");
        let target = canonical.join("appdata-evil").join("secret.txt");
        std::fs::write(&target, b"no").expect("fixture");
        assert!(
            resolve_within(&root, &target.to_string_lossy()).is_err(),
            "prefix sharing must not count as containment"
        );
    }
}
