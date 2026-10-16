/**
 * Run the CDP specs against an already-running app.
 *
 * Same reason as `tauriDevDebug.mjs` for existing at all: `VAR=x cmd` is POSIX
 * shell syntax, and npm/bun run scripts through `cmd.exe`/`pwsh` on Windows
 * where it does not work. This launcher sets the two variables on the child
 * environment directly.
 *
 *   - `NEXO_CDP_PORT` is forwarded so the Playwright config resolves the same
 *     endpoint the app was launched with. Set it only if you changed it for
 *     `tauri:dev:debug`.
 *   - `NEXO_E2E_CDP_ONLY=1` tells the config to omit `webServer`. Without it
 *     Playwright would start the stubbed Vite server, which is exactly the
 *     fiction this project exists to escape.
 *
 * Arguments after `--` are forwarded to Playwright, e.g.
 *   bun run test:e2e:cdp -- smoke.spec.ts
 */
import { spawn } from 'node:child_process';

const forwarded = process.argv.slice(2);

const child = spawn(
  ['bunx', 'playwright', 'test', '--project=tauri-cdp', ...forwarded].join(' '),
  {
    // `shell: true` is required on Windows for the `bunx` `.cmd` shim; the
    // command is built from a fixed prefix and argv, never user shell syntax.
    shell: true,
    stdio: 'inherit',
    env: {
      ...process.env,
      NEXO_E2E_CDP_ONLY: '1',
      NEXO_CDP_PORT: process.env.NEXO_CDP_PORT ?? '9222',
    },
  },
);

child.on('exit', (code, signal) => {
  if (signal) {
    process.kill(process.pid, signal);
    return;
  }
  process.exit(code ?? 0);
});

child.on('error', (error) => {
  console.error(`[nexo-cdp] failed to start playwright: ${error.message}`);
  process.exit(1);
});
