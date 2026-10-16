/**
 * The viability gate for driving the real app.
 *
 * This spec is **local only and must never run in CI**: it needs a compiled
 * Rust build, a graphical session, and an app already launched with the WebView2
 * debug port open. Run it with:
 *
 *   bun run tauri:dev:debug      # terminal 1
 *   bun run test:e2e:cdp         # terminal 2
 *
 * ## What it asserts, and why the stub cannot fake it
 *
 * The existing `chromium` suite runs against `e2e/harness/tauriStub.ts`, which
 * replaces `window.__TAURI_INTERNALS__.invoke` with an in-memory double. In
 * that double every unknown command resolves to `null`, and `listLibraryBooks`
 * resolves to seeded fiction. So an assertion on the library list would pass
 * against the stub too and prove nothing about the real app.
 *
 * The probe here goes the other way: it calls `diagnose`, a command whose answer
 * is computed *inside Rust* (`src-tauri/src/services/diagnostics.rs`) by running
 * `SELECT 1` against the real SQLite file, checking the sync queue, stat-ing the
 * book files and stat-ing the log file. The stub returns `null` for it, and the
 * browser shim (`browserTauriShim.ts`) does not implement it either. A
 * `database: 'healthy'` string can therefore only come from the real backend
 * talking to a real database — which is exactly the escape this harness exists
 * to demonstrate.
 *
 * The second assertion reads the document origin. `tauri dev` loads
 * `devUrl` (`http://127.0.0.1:1420`), so a matching origin additionally proves
 * the page being driven is the webview's own document and not a CDP placeholder
 * or the inspector frontend.
 */
import { expect, test } from './harness/connectApp';

/**
 * A structural subset of the Rust `DiagnoseResult` enough to assert on. Declared
 * locally rather than imported from `$lib`: an e2e spec should depend on the IPC
 * wire shape, not on app source, so a refactor of the app cannot silently
 * weaken this check.
 */
type DiagnoseResult = {
  database: string;
  queue: string;
  filesystem: string;
  logFile: string;
};

test('the live app answers a real backend command over CDP', async ({ appPage }) => {
  // The harness already waited for the shell; confirm we are on the app origin.
  expect(await appPage.evaluate(() => window.location.origin)).toBe('http://127.0.0.1:1420');

  // `diagnose` exists only on the Rust side. Calling it through the real IPC
  // bridge is the point of the whole harness.
  const result = await appPage.evaluate(async (): Promise<DiagnoseResult> => {
    const internals = (
      window as unknown as {
        __TAURI_INTERNALS__?: { invoke: (cmd: string) => Promise<unknown> };
      }
    ).__TAURI_INTERNALS__;

    if (!internals) {
      throw new Error('__TAURI_INTERNALS__ is missing — this page is not a Tauri webview');
    }

    return (await internals.invoke('diagnose')) as DiagnoseResult;
  });

  expect(result).toBeTruthy();
  // The real database answered `SELECT 1`. A stub answers `null`, which is
  // falsy and fails the line above before this one is reached.
  expect(result.database).toBe('healthy');
  expect(['healthy', 'degraded']).toContain(result.queue);
  expect(typeof result.filesystem).toBe('string');
  expect(typeof result.logFile).toBe('string');
});
