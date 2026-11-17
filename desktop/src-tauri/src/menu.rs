//! Native application menu.
//!
//! The webview already owns every reader and app shortcut (`globalShortcuts.ts`),
//! and it has to keep owning them: those bindings are context-sensitive and run
//! inside the document, so no OS-level accel can replace them. What the webview
//! cannot provide is what this menu adds:
//!
//! - A menu bar at all. On macOS the application menu is a platform
//!   requirement, not a preference, and its absence is what a "browser tab"
//!   looks like.
//! - Native clipboard items. Copy/Paste inside the webview currently work only
//!   through the web engine's own handling; `PredefinedMenuItem` wires the real
//!   OS edit commands, which is also what makes them correct in menus the
//!   webview does not own (text inputs, dialogs).
//! - OS accelerators for quitting.
//!
//! App-specific items do not invent a second command path. They emit
//! [`MENU_EVENT`] with a command id, and the frontend runs that id through the
//! SAME dispatcher the keyboard uses (`runMenuCommand` in `globalShortcuts.ts`).

use tauri::menu::{MenuBuilder, SubmenuBuilder};
use tauri::{AppHandle, Runtime};

/// Event name the frontend listens for. Payload is a bare command id string.
pub const MENU_EVENT: &str = "nexo://menu-command";

/// Command ids handed to the frontend, in menu order. Palette ids
/// (`COMMAND_REGISTRY`) and shortcut ids (`SHORTCUT_REGISTRY`) both appear
/// deliberately: a menu click and a keyboard press must reach the same code,
/// so whichever registry owns the action is the id the menu emits.
pub const CMD_IMPORT_BOOKS: &str = "action-import-books";
pub const CMD_NAV_LIBRARY: &str = "nav-library";
pub const CMD_NAV_SETTINGS: &str = "nav-settings";
pub const CMD_NAV_DISCOVER: &str = "nav-discover";
pub const CMD_COMMAND_PALETTE: &str = "app-command-palette";

/// `app-toggle-dark`, `app-help` and `app-command-palette` are SHORTCUT ids
/// (`SHORTCUT_REGISTRY`): those three actions exist only as shortcuts, so the
/// frontend resolves them through `runShortcutId`, which is the same map the
/// keydown dispatcher uses. Everything else above is a `COMMAND_REGISTRY` id.
pub const CMD_TOGGLE_THEME: &str = "app-toggle-dark";
pub const CMD_SHOW_HELP: &str = "app-help";

/// The app-specific commands, in menu order. Kept as a slice so a test can
/// assert the menu and the emitted set cannot drift apart.
pub const APP_COMMANDS: &[&str] = &[
    CMD_IMPORT_BOOKS,
    CMD_NAV_LIBRARY,
    CMD_NAV_DISCOVER,
    CMD_NAV_SETTINGS,
    CMD_TOGGLE_THEME,
    CMD_SHOW_HELP,
    CMD_COMMAND_PALETTE,
];

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum MenuKind {
    /// A menu bar owned by the window.
    Window,
    /// macOS's global menu bar. The first submenu must be the application menu;
    /// the OS uses it for About, Services, Hide and Quit regardless of what the
    /// app puts there.
    Global,
}

impl MenuKind {
    pub fn for_current_platform() -> Self {
        if cfg!(target_os = "macos") {
            Self::Global
        } else {
            Self::Window
        }
    }
}

/// One node of the menu, platform-independent. The builder below turns this
/// into `tauri::menu` items; the shape is separate so it can be asserted.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum MenuEntry {
    Submenu {
        text: &'static str,
        items: &'static [MenuEntry],
    },
    /// An app-specific item. The frontend runs `command`.
    Command {
        text: &'static str,
        command: &'static str,
        accelerator: Option<&'static str>,
    },
    /// A `PredefinedMenuItem` by role. `text_of` supplies the label; this
    /// enum only names which one.
    Predefined(PredefinedEntry),
    Separator,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum PredefinedEntry {
    About,
    Preferences,
    Services,
    Hide,
    HideOthers,
    ShowAll,
    Quit,
    Undo,
    Redo,
    Copy,
    Cut,
    Paste,
    SelectAll,
    Minimize,
    CloseWindow,
    Fullscreen,
    BringAllToFront,
}

// ── Menu content ────────────────────────────────────────────────────────────
//
// Accelerators: `CmdOrCtrl+…` is the portable spelling and resolves to Cmd on
// macOS and Ctrl elsewhere. Every combination below is one the webview does NOT
// bind (checked against `SHORTCUT_REGISTRY`), except the command palette and
// theme items, which reuse their existing ids on purpose: same action, same id.

const APP_MENU: &[MenuEntry] = &[
    MenuEntry::Predefined(PredefinedEntry::About),
    MenuEntry::Separator,
    MenuEntry::Predefined(PredefinedEntry::Preferences),
    MenuEntry::Separator,
    MenuEntry::Predefined(PredefinedEntry::Services),
    MenuEntry::Separator,
    MenuEntry::Predefined(PredefinedEntry::Hide),
    MenuEntry::Predefined(PredefinedEntry::HideOthers),
    MenuEntry::Predefined(PredefinedEntry::ShowAll),
    MenuEntry::Separator,
    MenuEntry::Predefined(PredefinedEntry::Quit),
];

const FILE_MENU: &[MenuEntry] = &[
    MenuEntry::Command {
        text: "Import Books…",
        command: CMD_IMPORT_BOOKS,
        accelerator: Some("CmdOrCtrl+O"),
    },
    MenuEntry::Separator,
    MenuEntry::Predefined(PredefinedEntry::CloseWindow),
    #[cfg(not(target_os = "macos"))]
    MenuEntry::Predefined(PredefinedEntry::Quit),
];

const EDIT_MENU: &[MenuEntry] = &[
    MenuEntry::Predefined(PredefinedEntry::Undo),
    MenuEntry::Predefined(PredefinedEntry::Redo),
    MenuEntry::Separator,
    MenuEntry::Predefined(PredefinedEntry::Cut),
    MenuEntry::Predefined(PredefinedEntry::Copy),
    MenuEntry::Predefined(PredefinedEntry::Paste),
    MenuEntry::Predefined(PredefinedEntry::SelectAll),
];

const VIEW_MENU: &[MenuEntry] = &[
    MenuEntry::Command {
        text: "Library",
        command: CMD_NAV_LIBRARY,
        accelerator: Some("CmdOrCtrl+1"),
    },
    MenuEntry::Command {
        text: "Discover",
        command: CMD_NAV_DISCOVER,
        accelerator: Some("CmdOrCtrl+2"),
    },
    MenuEntry::Command {
        text: "Command Palette…",
        command: CMD_COMMAND_PALETTE,
        accelerator: Some("CmdOrCtrl+K"),
    },
    MenuEntry::Command {
        text: "Toggle Dark Mode",
        command: CMD_TOGGLE_THEME,
        accelerator: Some("CmdOrCtrl+D"),
    },
    MenuEntry::Separator,
    // Fullscreen is a predefined item so the OS owns the transition and the
    // window's own state; it also cannot fight the custom titlebar.
    MenuEntry::Predefined(PredefinedEntry::Fullscreen),
];

const WINDOW_MENU: &[MenuEntry] = &[
    MenuEntry::Predefined(PredefinedEntry::Minimize),
    MenuEntry::Separator,
    MenuEntry::Predefined(PredefinedEntry::BringAllToFront),
];

const HELP_MENU: &[MenuEntry] = &[
    MenuEntry::Command {
        text: "Keyboard Shortcuts",
        command: CMD_SHOW_HELP,
        accelerator: Some("CmdOrCtrl+/"),
    },
    MenuEntry::Separator,
    MenuEntry::Command {
        text: "Settings…",
        command: CMD_NAV_SETTINGS,
        accelerator: Some("CmdOrCtrl+,"),
    },
];

const WINDOW_BAR: &[MenuEntry] = &[
    MenuEntry::Submenu { text: "File", items: FILE_MENU },
    MenuEntry::Submenu { text: "Edit", items: EDIT_MENU },
    MenuEntry::Submenu { text: "View", items: VIEW_MENU },
    MenuEntry::Submenu { text: "Help", items: HELP_MENU },
];

const GLOBAL_BAR: &[MenuEntry] = &[
    MenuEntry::Submenu { text: "NEXO", items: APP_MENU },
    MenuEntry::Submenu { text: "File", items: FILE_MENU },
    MenuEntry::Submenu { text: "Edit", items: EDIT_MENU },
    MenuEntry::Submenu { text: "View", items: VIEW_MENU },
    MenuEntry::Submenu { text: "Window", items: WINDOW_MENU },
    MenuEntry::Submenu { text: "Help", items: HELP_MENU },
];

/// The bar this platform gets. Pure: no Tauri runtime involved.
pub fn bar(kind: MenuKind) -> &'static [MenuEntry] {
    match kind {
        MenuKind::Window => WINDOW_BAR,
        MenuKind::Global => GLOBAL_BAR,
    }
}

/// Every app-specific command the given bar can emit, walked from the model.
pub fn commands_in(kind: MenuKind) -> Vec<&'static str> {
    fn walk(entries: &'static [MenuEntry], out: &mut Vec<&'static str>) {
        for entry in entries {
            match entry {
                MenuEntry::Submenu { items, .. } => walk(items, out),
                MenuEntry::Command { command, .. } => out.push(command),
                MenuEntry::Predefined(_) | MenuEntry::Separator => {}
            }
        }
    }
    let mut out = Vec::new();
    walk(bar(kind), &mut out);
    out
}

// ── Runtime construction ────────────────────────────────────────────────────

fn text_of(entry: PredefinedEntry) -> &'static str {
    match entry {
        PredefinedEntry::About => "About NEXO",
        PredefinedEntry::Preferences => "Preferences…",
        PredefinedEntry::Services => "Services",
        PredefinedEntry::Hide => "Hide NEXO",
        PredefinedEntry::HideOthers => "Hide Others",
        PredefinedEntry::ShowAll => "Show All",
        PredefinedEntry::Quit => "Quit NEXO",
        PredefinedEntry::Undo => "Undo",
        PredefinedEntry::Redo => "Redo",
        PredefinedEntry::Copy => "Copy",
        PredefinedEntry::Cut => "Cut",
        PredefinedEntry::Paste => "Paste",
        PredefinedEntry::SelectAll => "Select All",
        PredefinedEntry::Minimize => "Minimize",
        PredefinedEntry::CloseWindow => "Close Window",
        PredefinedEntry::Fullscreen => "Toggle Full Screen",
        PredefinedEntry::BringAllToFront => "Bring All to Front",
    }
}

fn build_submenu<R: Runtime>(
    app: &AppHandle<R>,
    text: &'static str,
    entries: &'static [MenuEntry],
) -> tauri::Result<tauri::menu::Submenu<R>> {
    let mut builder = SubmenuBuilder::new(app, text);
    for entry in entries {
        builder = match entry {
            MenuEntry::Submenu { text: sub_text, items } => {
                // Built recursively, then appended through the shared path so
                // nesting does not need a second code branch.
                let panel = build_submenu(app, sub_text, items)?;
                builder.item(&panel)
            }
            MenuEntry::Command { text: cmd_text, command: cmd_id, accelerator } => {
                // The menu id IS the frontend command id: the handler in
                // `on_menu_event` forwards it verbatim, so there is exactly one
                // registry of ids and it lives in the frontend.
                let mut item = tauri::menu::MenuItemBuilder::with_id(*cmd_id, *cmd_text);
                if let Some(accel) = accelerator {
                    item = item.accelerator(*accel);
                }
                let item = item.build(app)?;
                builder.item(&item)
            }
            MenuEntry::Predefined(role) => {
                let text = text_of(*role);
                let item = match role {
                    PredefinedEntry::About => {
                        tauri::menu::PredefinedMenuItem::about(app, Some(text), None)?
                    }
                    PredefinedEntry::Preferences => {
                        // macOS convention is the app menu; elsewhere it lives
                        // under the app-specific id so the same click reaches the
                        // same handler on every OS.
                        let item = tauri::menu::MenuItemBuilder::with_id(CMD_NAV_SETTINGS, text)
                            .accelerator("CmdOrCtrl+,")
                            .build(app)?;
                        builder = builder.item(&item);
                        continue;
                    }
                    PredefinedEntry::Services => {
                        tauri::menu::PredefinedMenuItem::services(app, Some(text))?
                    }
                    PredefinedEntry::Hide => {
                        tauri::menu::PredefinedMenuItem::hide(app, Some(text))?
                    }
                    PredefinedEntry::HideOthers => {
                        tauri::menu::PredefinedMenuItem::hide_others(app, Some(text))?
                    }
                    PredefinedEntry::ShowAll => {
                        tauri::menu::PredefinedMenuItem::show_all(app, Some(text))?
                    }
                    PredefinedEntry::Quit => {
                        tauri::menu::PredefinedMenuItem::quit(app, Some(text))?
                    }
                    PredefinedEntry::Undo => {
                        tauri::menu::PredefinedMenuItem::undo(app, Some(text))?
                    }
                    PredefinedEntry::Redo => {
                        tauri::menu::PredefinedMenuItem::redo(app, Some(text))?
                    }
                    PredefinedEntry::Copy => {
                        tauri::menu::PredefinedMenuItem::copy(app, Some(text))?
                    }
                    PredefinedEntry::Cut => tauri::menu::PredefinedMenuItem::cut(app, Some(text))?,
                    PredefinedEntry::Paste => {
                        tauri::menu::PredefinedMenuItem::paste(app, Some(text))?
                    }
                    PredefinedEntry::SelectAll => {
                        tauri::menu::PredefinedMenuItem::select_all(app, Some(text))?
                    }
                    PredefinedEntry::Minimize => {
                        tauri::menu::PredefinedMenuItem::minimize(app, Some(text))?
                    }
                    PredefinedEntry::CloseWindow => {
                        tauri::menu::PredefinedMenuItem::close_window(app, Some(text))?
                    }
                    PredefinedEntry::Fullscreen => {
                        tauri::menu::PredefinedMenuItem::fullscreen(app, Some(text))?
                    }
                    PredefinedEntry::BringAllToFront => {
                        tauri::menu::PredefinedMenuItem::bring_all_to_front(app, Some(text))?
                    }
                };
                builder.item(&item)
            }
            MenuEntry::Separator => builder.separator(),
        };
    }
    builder.build()
}

/// Build and install the menu bar for the running platform.
pub fn install<R: Runtime>(app: &AppHandle<R>) -> tauri::Result<()> {
    let kind = MenuKind::for_current_platform();
    let entries = bar(kind);

    let mut builder = MenuBuilder::new(app);
    for entry in entries {
        // The bar itself only ever holds submenus.
        if let MenuEntry::Submenu { text, items } = entry {
            let submenu = build_submenu(app, text, items)?;
            builder = builder.item(&submenu);
        }
    }
    let menu = builder.build()?;
    app.set_menu(menu)?;
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn window_bar_has_file_edit_view_help() {
        let texts: Vec<&str> = bar(MenuKind::Window)
            .iter()
            .filter_map(|e| match e {
                MenuEntry::Submenu { text, .. } => Some(*text),
                _ => None,
            })
            .collect();
        assert_eq!(texts, vec!["File", "Edit", "View", "Help"]);
    }

    #[test]
    fn global_bar_starts_with_the_application_menu() {
        // macOS requires the first submenu to be the app menu. If this ever
        // becomes File, the About/Hide/Quit items land in the wrong menu.
        let bar = bar(MenuKind::Global);
        match bar.first() {
            Some(MenuEntry::Submenu { text, items }) => {
                assert_eq!(*text, "NEXO");
                let has_quit =
                    items.iter().any(|e| *e == MenuEntry::Predefined(PredefinedEntry::Quit));
                let has_hide =
                    items.iter().any(|e| *e == MenuEntry::Predefined(PredefinedEntry::Hide));
                let has_about =
                    items.iter().any(|e| *e == MenuEntry::Predefined(PredefinedEntry::About));
                assert!(has_quit && has_hide && has_about);
            }
            other => panic!("first entry is not a submenu: {other:?}"),
        }
    }

    #[test]
    fn global_bar_has_all_macos_conventional_menus() {
        let texts: Vec<&str> = bar(MenuKind::Global)
            .iter()
            .filter_map(|e| match e {
                MenuEntry::Submenu { text, .. } => Some(*text),
                _ => None,
            })
            .collect();
        assert_eq!(texts, vec!["NEXO", "File", "Edit", "View", "Window", "Help"]);
    }

    #[test]
    fn edit_menu_uses_predefined_clipboard_items() {
        fn walk(entries: &'static [MenuEntry], out: &mut Vec<PredefinedEntry>) {
            for e in entries {
                match e {
                    MenuEntry::Submenu { items, .. } => walk(items, out),
                    MenuEntry::Predefined(p) => out.push(*p),
                    _ => {}
                }
            }
        }
        let mut found = Vec::new();
        walk(bar(MenuKind::Window), &mut found);
        for required in [
            PredefinedEntry::Cut,
            PredefinedEntry::Copy,
            PredefinedEntry::Paste,
            PredefinedEntry::SelectAll,
            PredefinedEntry::Undo,
            PredefinedEntry::Redo,
        ] {
            assert!(
                found.contains(&required),
                "missing predefined {required:?}; hand-rolled items would not wire the OS edit commands"
            );
        }
    }

    #[test]
    fn every_command_emitted_is_a_known_frontend_id() {
        // Mirrors `APP_COMMANDS`. A menu row pointing at an id the frontend does
        // not dispatch would be a dead click.
        for kind in [MenuKind::Window, MenuKind::Global] {
            for cmd in commands_in(kind) {
                assert!(
                    APP_COMMANDS.contains(&cmd),
                    "{cmd} is emitted but not listed in APP_COMMANDS"
                );
            }
        }
        for cmd in APP_COMMANDS {
            assert!(
                commands_in(MenuKind::Window).contains(cmd),
                "{cmd} is listed but no window-bar row emits it"
            );
        }
    }

    #[test]
    fn quit_has_a_live_menu_path_on_every_platform() {
        // Cmd+Q on macOS, and a Quit item on Windows/Linux: the window menu has
        // File > Quit, the app menu has the predefined Quit.
        fn walk(entries: &'static [MenuEntry], out: &mut Vec<PredefinedEntry>) {
            for e in entries {
                match e {
                    MenuEntry::Submenu { items, .. } => walk(items, out),
                    MenuEntry::Predefined(p) => out.push(*p),
                    _ => {}
                }
            }
        }
        let mut found = Vec::new();
        walk(bar(MenuKind::Global), &mut found);
        assert!(
            found.contains(&PredefinedEntry::Quit),
            "the global bar must expose the predefined Quit (Cmd+Q)"
        );
        let mut win = Vec::new();
        walk(bar(MenuKind::Window), &mut win);
        assert!(win.contains(&PredefinedEntry::Quit), "the window bar must expose Quit (Ctrl+Q)");
    }

    #[test]
    fn accelerators_do_not_collide_with_webview_bindings() {
        // The webview owns Cmd/Ctrl+F (reader search), +/- (zoom) and the bare
        // keys. Re-binding any of them here would fire twice or shadow them.
        fn accels(entries: &'static [MenuEntry], out: &mut Vec<&'static str>) {
            for e in entries {
                match e {
                    MenuEntry::Submenu { items, .. } => accels(items, out),
                    MenuEntry::Command { accelerator: Some(a), .. } => out.push(*a),
                    _ => {}
                }
            }
        }
        let mut all = Vec::new();
        accels(bar(MenuKind::Global), &mut all);
        for forbidden in ["CmdOrCtrl+F", "CmdOrCtrl+-", "CmdOrCtrl+="] {
            assert!(!all.contains(&forbidden), "{forbidden} is bound by the reader in the webview");
        }
        let mut sorted = all.clone();
        sorted.sort_unstable();
        sorted.dedup();
        assert_eq!(sorted.len(), all.len(), "duplicate accelerator: {all:?}");
    }
}
