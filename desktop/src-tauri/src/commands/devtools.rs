use tauri::WebviewWindow;

/// Opens the webview inspector for the invoking window.
///
/// `WebviewWindow::open_devtools` exists in every build profile because the
/// `tauri` dependency enables its own `devtools` feature in `Cargo.toml`. If
/// that feature is ever removed, this call must be removed or cfg-gated on a
/// condition that actually matches, otherwise the build breaks.
// `rename` (not `rename_all`) sets the external IPC name; `rename_all` only
// affects argument casing. The frontend invokes `openDevtools`.
#[tauri::command(rename = "openDevtools", rename_all = "camelCase")]
pub fn open_devtools(window: WebviewWindow) {
    window.open_devtools();
}
