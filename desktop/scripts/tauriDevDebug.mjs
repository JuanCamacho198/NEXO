/**
 * Launch the Tauri dev app with the WebView2 remote-debugging port open.
 *
 * Why a script instead of an inline env var in `package.json`: `VAR=x cmd` is
 * POSIX shell syntax and does not work in `cmd.exe` or `pwsh`, which is what
 * npm/bun use to run a script on Windows. There is no cross-platform inline
 * env syntax in those shells, and `cross-env` is not a dependency here. A tiny
 * Node/Bun launcher sets the variable on the child's environment directly,
 * which is already cross-platform, and lets the port be read from the invoking
 * environment instead of being hardcoded in `package.json`.
 *
 * Two variables are set, and both must reach the WebView2 process:
 *
 *   - `WEBVIEW2_ADDITIONAL_BROWSER_ARGUMENTS` is what WebView2 itself reads to
 *     append Chromium command-line switches. `--remote-debugging-port=NNNN` is
 *     the switch that opens CDP.
 *   - `NEXO_CDP_PORT` is our own override, read from the environment here so a
 *     second run can pick another port. CDP exposes one endpoint per process,
 *     so two concurrent runs on the default port would collide.
 *
 * The port is deliberately NOT a build setting and NEVER enabled by the app
 * itself: it exists only for the lifetime of a process launched through this
 * script. See `e2e/tauri/README.md` for the full run recipe and its limits.
 *
 * Usage:  bun run tauri:dev:debug
 *         $env:NEXO_CDP_PORT=9333; bun run tauri:dev:debug   # pwsh
 */
import { spawn } from 'node:child_process';

const DEFAULT_CDP_PORT = 9222;

const rawPort = process.env.NEXO_CDP_PORT ?? String(DEFAULT_CDP_PORT);
const port = Number.parseInt(rawPort, 10);

if (!Number.isInteger(port) || port < 1 || port > 65535) {
  console.error(`[nexo-cdp] NEXO_CDP_PORT must be a TCP port (1-65535); got ${rawPort}`);
  process.exit(1);
}

const additionalArgs = `--remote-debugging-port=${port}`;

console.log(`[nexo-cdp] launching tauri dev with ${additionalArgs}`);
console.log(`[nexo-cdp] CDP endpoint will be http://127.0.0.1:${port}`);
console.log('[nexo-cdp] run the CDP specs with: bun run test:e2e --project=tauri-cdp');

// `shell: true` is required on Windows: the tauri CLI is a `.cmd` shim, and
// Node refuses to spawn `.cmd` without a shell (EINVAL). The command string is
// a fixed literal — no user input is interpolated — so there is no injection
// surface for enabling the shell.
const child = spawn('bunx tauri dev', {
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
