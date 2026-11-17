# Desktop installers

What `desktop-v*` releases ship, what configuration produces them, and how a human proves each one installs on a machine that has never run NEXO.

Audit reference: `odd/tasks/desktop-security-checklist-audit.md` item #15.

> **Every row in the verification matrix below says `not run`.** No clean-machine install of a NEXO desktop build has been performed as part of this work. A table full of invented passes is worse than an empty one, so the column is left honest until someone actually installs the artefacts.

## What `bundle.targets: "all"` produces

`bundle.targets: "all"` in `desktop/src-tauri/tauri.conf.json` expands per host OS, not into a cross-platform set. Tauri cannot build a `.dmg` on Linux.

| Matrix leg (`.github/workflows/release-builds.yml`) | `targets: "all"` expands to | Published asset name |
| --- | --- | --- |
| `windows` (`windows-latest`) | `msi`, `nsis` | `Nexo.Desktop_0.7.0_x64_en-US.msi`, `Nexo.Desktop_0.7.0_x64-setup.exe` |
| `macos-universal` (`macos-latest`, `--target universal-apple-darwin`) | `app`, `dmg` | `Nexo.Desktop_0.7.0_universal.dmg` |
| `linux` (`ubuntu-latest`) | `appimage`, `deb`, `rpm` | `Nexo.Desktop_0.7.0_amd64.AppImage`, `Nexo.Desktop_0.7.0_amd64.deb`, `Nexo.Desktop-0.7.0-1.x86_64.rpm` |

The asset names above are the **released** names, which differ from local build output by one character: Tauri names a local bundle `Nexo Desktop_0.7.0_...` and the release uploader replaces the space with a dot. The `SHA256SUMS.txt` fan-in job and `nexo-web/src/lib/releases.ts` both depend on that; see the comment block above `Build and publish SHA256SUMS.txt`.

Updater artefacts ride alongside and are **not** user-facing downloads: `*.sig` signatures, `Nexo.Desktop_0.7.0_universal.app.tar.gz` (what `plugin-updater` actually installs on macOS), and `latest.json`.

### Toolchain per leg — confirmed present

Checked against the workflow, not assumed:

| Requirement | Where it comes from | Status |
| --- | --- | --- |
| WiX + NSIS (`.msi`, `.exe`) | Tauri downloads both into the tool cache on first build; `useLocalToolsDir` is false, so they are cached per-user, not in `target/` | present on `windows-latest`; no extra CI step needed |
| Universal macOS toolchain | `Add macOS universal targets` step installs `aarch64-apple-darwin` + `x86_64-apple-darwin` on the **pinned** 1.95.0 toolchain (read out of `rust-toolchain.toml`, not hardcoded) and fails early if either is missing | present |
| `hdiutil` (`.dmg`) | ships with macOS | present |
| `webkit2gtk-4.1`, `libsoup-3.0`, `gtk-3`, `libayatana-appindicator3`, `librsvg2`, `libjavascriptcoregtk-4.1` | `Install Linux system deps (Tauri)` step | present |
| `linuxdeploy` / AppImage runtime | downloaded by the Linux bundler into the tool cache | present |
| `rpmbuild` (`.rpm`) | **not** installed by any workflow step | ⚠️ see below |

`rpmbuild` is the one row I cannot confirm green. Ubuntu runners do not ship `rpm` by default, and the workflow does not install it. If `.rpm` production has been working, the bundler is using its own bundled `rpmbuild` — Tauri shells out to `rpmbuild` if present and otherwise has no fallback. **This is unverified**: it needs either a release run with an `.rpm` in the asset list, or an explicit `sudo apt-get install -y rpm` in the Linux deps step. Recorded here as a risk rather than fixed silently, because adding a package to the deps list is a release-path change that deserves its own decision.

## Configuration added for installers

### `.epub` file association

```json
"fileAssociations": [
  {
    "ext": ["epub"],
    "name": "EPUB document",
    "description": "EPUB document",
    "mimeType": "application/epub+zip",
    "role": "Viewer",
    "rank": "Owner"
  }
]
```

Verified against `tauri-utils 2.9.3` `src/config.rs`, `struct FileAssociation` (`ext`, `name`, `description`, `mimeType`, `role`, `rank`) — `deny_unknown_fields`, so a wrong name fails the build.

Deliberately consistent with the deep-link setup that already exists rather than a second registration path:

- `plugins.deep-link.desktop.schemes` already declares `nexo`, `nextpage-desktop`, `nextpage`.
- `desktop/src/lib/features/addons/installDeepLink.ts` accepts exactly those three schemes and only the `install` host.
- `desktop/src-tauri/src/main.rs` calls `app.deep_link().register(...)` for all three at startup.

The `.epub` association is a **file** association, not a URL scheme, so it does not collide with any of that — but it does mean a double-clicked `.epub` arrives as an argv path, which travels a different path than a `nexo://` URL. On Windows and Linux that argv is handled by the `single-instance` plugin callback in `main.rs` (which currently logs any argv containing `://` and emits only `install` deep links — an `.epub` path is neither, so it is currently ignored). On macOS the file arrives as an `open-file` event that nothing handles yet.

**So: registering the association without routing the file is an incomplete feature.** The association is correct and safe to ship — the OS will offer NEXO in "Open with" — but a double-clicked `.epub` will focus the window and do nothing until import-on-open is wired. That is a `desktop/src-tauri/src/` change and explicitly outside this task's scope.

`role: "Viewer"` and `rank: "Owner"` are honest: NEXO does not edit EPUB files, and as the only app in this product family it should be the default handler.

### Linux `.deb` / `.rpm` dependencies

Derived from what the crate actually links (`desktop/src-tauri/Cargo.toml`: `tauri` with `protocol-asset`, `tauri-plugin-*` including `notification` and `opener`), which pulls `webkit2gtk-4.1`, `gtk-3`, `libsoup-3.0` and the appindicator tray library:

```
deb.depends:  libwebkit2gtk-4.1-0, libgtk-3-0, libayatana-appindicator3-1, librsvg2-2
rpm.depends:  webkit2gtk4.1, gtk3, libayatana-appindicator-gtk3, librsvg2
```

Same package set the CI `Install Linux system deps (Tauri)` step installs, translated to the runtime package names each format uses. Without these, `dpkg -i` leaves the app installed and then it dies on launch with a missing shared object, which is the single most common Linux packaging bug. `section: "utils"` is set because the default section for an unpackaged app is unhelpful in package managers.

### Linux `.desktop` entry

Tauri generates `/usr/share/applications/<identifier>.desktop` from the bundle metadata — no separate file is needed or was added. The entry's `Name` comes from `productName` (`Nexo Desktop`), `Comment` from `shortDescription`, `Categories` from `category: "Productivity"`, and `MimeType=` is written from `fileAssociations[].mimeType` (`application/epub+zip`) — that is the documented behaviour of `FileAssociation.mime_type` in the vendored struct. `Icon=` resolves from `bundle.icon`.

Do not hand-write a `.desktop` template unless the generated one is proven wrong: `deb.desktopTemplate` exists, and adding it speculatively means maintaining a Handlebars file whose variables (`categories`, `comment`, `exec`, `icon`, `name`) must all stay in sync with `tauri.conf.json`.

## Clean-machine verification matrix

A "clean machine" means: a fresh VM or a user profile that has never had NEXO installed, never had the signing certificate trusted, and has default OS security settings (SmartScreen on, Gatekeeper on, no "run anyway" clicked previously). Reinstalling over a previous version proves nothing — silently installing over your own build hides exactly the failures this table exists to catch.

Fill the **Observed** column after running each row. Leave a row blank rather than guessing; an unrun row is a known unknown, a guessed pass is a lie.

| Platform | Artefact | How to test on a clean machine | Expected result | Observed |
| --- | --- | --- | --- | --- |
| Windows 11 | `Nexo.Desktop_0.7.0_x64_en-US.msi` | Fresh VM. Download the `.msi` **from the release page**, double-click. | SmartScreen may warn once; publisher line names the company. Installs without admin prompt failure. Start menu entry appears. Launch → window renders, no white screen. `Add/Remove Programs` shows `Nexo Desktop 0.7.0` with the right publisher. | `not run` |
| Windows 11 | `Nexo.Desktop_0.7.0_x64-setup.exe` (NSIS) | Same, on a second fresh VM, using the `.exe`. | Same as above. Uninstall via Settings removes the start menu entry and the install dir. | `not run` |
| Windows 10 22H2 | both Windows artefacts | Same as above on Windows 10 specifically. | Same. Also verify WebView2 is present or the installer offers to install it. | `not run` |
| macOS 14 (arm64) | `Nexo.Desktop_0.7.0_universal.dmg` | Fresh VM/user. Download the `.dmg`, open, drag to `/Applications`, launch from `/Applications` **not** from the mounted volume. | Gatekeeper first-launch confirmation only, `spctl -a -vv --type execute "/Applications/Nexo Desktop.app"` → `accepted source=Notarized Developer ID`. Window shows macOS traffic lights at the declared `trafficLightPosition` (16,20) — the custom titlebar only renders on Windows. | `not run` |
| macOS 13 (x86_64) | same `.dmg` | Same on an Intel Mac, to prove the universal slice runs natively. | Runs without Rosetta. `Activity Monitor` → Kind = Intel, not translated. | `not run` |
| Ubuntu 24.04 | `Nexo.Desktop_0.7.0_amd64.deb` | Fresh VM. `sudo apt install ./Nexo.Desktop_0.7.0_amd64.deb` (not `dpkg -i`, which will not resolve deps). | `apt` pulls `libwebkit2gtk-4.1-0` etc. from the declared `depends` with no manual installs. App appears in the application grid under Productivity. Launch works. `xdg-mime query default application/epub+zip` names the NEXO desktop entry. | `not run` |
| Fedora 41 | `Nexo.Desktop-0.7.0-1.x86_64.rpm` | Fresh VM. `sudo dnf install ./Nexo.Desktop-0.7.0-1.x86_64.rpm`. | `dnf` resolves the declared `rpm.depends`. App launches. `.epub` association registered. | `not run` |
| Ubuntu 24.04 | `Nexo.Desktop_0.7.0_amd64.AppImage` | Fresh VM. `chmod +x`, run from `~/Downloads`. | Launches with no install step. If it fails with a webkit symbol error, the AppImage has a linking bug — this is the row that catches missing bundled libraries. | `not run` |
| Windows / macOS / Linux | `.epub` double-click on a fresh default browser-`.epub` machine | After installing, double-click an `.epub` in Nautilus/Finder/Explorer. | **Expected today: nothing opens.** The association is registered but no handler routes the incoming path (`main.rs` only handles `nexo://` deep links). Record what actually happens; if the app does open the book, this row contradicts the code and the code should be re-read. | `not run` |
| Any | Updater from the previous release | Install 0.6.x clean, then take the 0.7.0 update in-app. | See `docs/desktop-update-cycle.md` — that flow has its own matrix. | `not run` |

## Known gaps recorded here, not fixed here

1. **`rpmbuild` is not installed by CI.** If `.rpm` production works today, it is because the bundler found a binary this workflow does not install. Unverified.
2. **`.epub` import-on-open is not wired.** The association ships; the handler does not exist. A double-clicked book focuses the window and stops.
3. **No ARM64 Windows artefact.** `windows-latest` builds x64 only; `Nexo.Desktop_0.7.0_arm64_*.msi` does not exist and the download page should not imply otherwise.
4. **No signatures verified in the matrix yet.** Every "Expected result" that mentions Gatekeeper or SmartScreen is blocked until `docs/desktop-code-signing.md` operator actions are complete.
