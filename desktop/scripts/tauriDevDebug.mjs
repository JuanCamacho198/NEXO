/**
 * Launch the Tauri dev app with the WebView2 remote-debugging port open.
 *
 * Usage:  bun run tauri:dev:debug
 *         $env:NEXO_CDP_PORT=9333; bun run tauri:dev:debug   # pwsh
 *
 * ## Why this exists at all
 *
 * `WEBVIEW2_ADDITIONAL_BROWSER_ARGUMENTS` cannot work on this stack, and the CDP
 * harness needs the port. Both halves are measured, not assumed:
 *
 *   - **wry always sets `AdditionalBrowserArguments`.** `wry-0.55.1/src/webview2/mod.rs`
 *     builds `CoreWebView2EnvironmentOptions` and calls
 *     `set_additional_browser_arguments(...)` unconditionally, defaulting to
 *     `--disable-features=msWebOOUI,msPdfOOUI,msSmartScreenProtection`. WebView2
 *     documents that the property replaces the environment variable, so the
 *     variable is dead the moment the option is set. MEASURED: with the variable
 *     exported and the app running, the nexo browser process command line was
 *
 *       ... --user-data-dir="...\com.nexo.desktop\EBWebView" --noerrdialog
 *       --embedded-browser-webview-dpi-awareness=2 --autoplay-policy=no-user-gesture-required
 *       --disable-features=msWebOOUI,msPdfOOUI,msSmartScreenProtection --lang=es-ES
 *
 *     — wry's defaults, no `--remote-debugging-port`, and nothing listening on 9222.
 *   - **The config key IS wired.** `tauri-runtime-2.11.3/src/webview.rs:483`
 *     turns `WindowConfig.additional_browser_args` into
 *     `builder.additional_browser_args(...)`, which `tauri-runtime-wry` forwards
 *     to `wry`'s `with_additional_browser_args`. That is the one path that
 *     reaches the browser process.
 *
 * So the launcher passes `tauri dev --config <merged config>` instead of relying
 * on the environment. The file is generated from `src-tauri/tauri.conf.json`
 * with the switch injected into every window — read, not duplicated, so the
 * debug run cannot drift from the real one. `--config` merges by JSON Merge
 * Patch (RFC 7396), which REPLACES arrays, so the windows array is passed whole.
 *
 * Two things are deliberately kept in the generated arguments, because setting
 * `additionalBrowserArgs` replaces wry's default rather than extending it:
 * the `--disable-features` list and `--autoplay-policy=no-user-gesture-required`
 * that the running app already carries.
 *
 * The port is deliberately NOT a build setting and NEVER enabled by the app
 * itself: it exists only for the lifetime of a process launched through this
 * script. See `e2e/tauri/README.md` for the full run recipe and its limits.
 */
import { spawn } from 'node:child_process';
import { mkdtempSync, readFileSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';

const DEFAULT_CDP_PORT = 9222;

/**
 * The WebView2 arguments the app runs with today, kept verbatim, plus whatever
 * this script adds. Dropping one of these would be a silent behaviour change to
 * the app under test, not to the debug port.
 */
const INHERITED_BROWSER_ARGS =
  '--disable-features=msWebOOUI,msPdfOOUI,msSmartScreenProtection --autoplay-policy=no-user-gesture-required';

const repoDesktopDir = join(fileURLToPath(new URL('.', import.meta.url)), '..');

const rawPort = process.env.NEXO_CDP_PORT ?? String(DEFAULT_CDP_PORT);
const port = Number.parseInt(rawPort, 10);

if (!Number.isInteger(port) || port < 1 || port > 65535) {
  console.error(`[nexo-cdp] NEXO_CDP_PORT must be a TCP port (1-65535); got ${rawPort}`);
  process.exit(1);
}

const additionalArgs = `${INHERITED_BROWSER_ARGS} --remote-debugging-port=${port}`;

/**
 * Writes the config `tauri dev` is told to merge: the project's own config with
 * `additionalBrowserArgs` injected into every window. Written outside the repo
 * so a debug run cannot leave a generated file behind.
 */
function writeMergedConfig() {
  const confPath = join(repoDesktopDir, 'src-tauri', 'tauri.conf.json');
  const conf = JSON.parse(readFileSync(confPath, 'utf8'));
  const windows = conf?.app?.windows;

  if (!Array.isArray(windows) || windows.length === 0) {
    console.error(
      `[nexo-cdp] no app.windows in ${confPath}, so there is no window to set ` +
        `additionalBrowserArgs on. The port would never open.`,
    );
    process.exit(1);
  }

  conf.app.windows = windows.map((window) => ({
    ...window,
    additionalBrowserArgs: additionalArgs,
  }));

  const dir = mkdtempSync(join(tmpdir(), 'nexo-cdp-'));
  const out = join(dir, 'tauri.cdp.conf.json');
  writeFileSync(out, JSON.stringify(conf), 'utf8');
  return out;
}

const configPath = writeMergedConfig();

console.log(`[nexo-cdp] launching tauri dev with ${additionalArgs}`);
console.log(`[nexo-cdp] CDP endpoint will be http://127.0.0.1:${port}`);
console.log(`[nexo-cdp] merged config: ${configPath}`);
console.log('[nexo-cdp] run the CDP specs with: bun run test:e2e --project=tauri-cdp');

// `shell: true` is required on Windows: the tauri CLI is a `.cmd` shim, and
// Node refuses to spawn `.cmd` without a shell (EINVAL). Both interpolated
// values are a port-validated integer and a path this script just created, so
// there is no injection surface for enabling the shell.
const child = spawn(`bunx tauri dev --config "${configPath}"`, {
  stdio: 'inherit',
  shell: true,
  env: {
    ...process.env,
    WEBVIEW2_ADDITIONAL_BROWSER_ARGUMENTS: additionalArgs,
    NEXO_CDP_PORT: String(port),
  },
});

child.on('exit', (code, signal) => {
  if (signal) {
    process.kill(process.pid, signal);
    return;
  }
  process.exit(code ?? 0);
});

child.on('error', (error) => {
  console.error(`[nexo-cdp] failed to start tauri dev: ${error.message}`);
  process.exit(1);
});
