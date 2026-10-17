import { defineConfig, devices } from '@playwright/test';

// Chromium-only on purpose: one repeatable browser and one visual gate, no
// browser matrix. `toHaveScreenshot` is the gate; the `screenshot` use-option
// only decides whether a *failing* check keeps an artifact, so a green run
// still compares pixels against the committed baselines.
const PORT = 1420;
const BASE_URL = `http://127.0.0.1:${PORT}`;

// webServer uses the plain Vite dev server (frontend only). CI only needs the
// bun toolchain and Chromium installed — no Rust/Tauri build and no backend.
const WEB_SERVER_COMMAND = 'bun run dev';

// The WebView2 remote-debugging endpoint for the real Tauri app. `tauri:dev:debug`
// opens this port on the launched process; the `tauri-cdp` project connects to
// it instead of starting anything. Default 9222 is a convention, not a
// constant: `NEXO_CDP_PORT` overrides it for local runs so two can coexist,
// because CDP exposes one debug endpoint per process.
const CDP_PORT = process.env.NEXO_CDP_PORT ?? '9222';
const CDP_ENDPOINT = `http://127.0.0.1:${CDP_PORT}`;

// When set, the config is being run for the CDP project only and MUST NOT start
// a server: `tauri-cdp` attaches to an app that is already running. Playwright
// has no per-project `webServer` (the `TestProject` type does not carry one;
// `webServer` is config-level only, verified against
// `playwright/types/test.d.ts`), so the only supported way to stop the server
// from firing is to omit it from the resolved config. The `test:e2e:cdp` script
// sets this flag.
const CDP_ONLY = process.env.NEXO_E2E_CDP_ONLY === '1';


export default defineConfig({
  testDir: './e2e',
  fullyParallel: false,
  workers: 1,
  forbidOnly: Boolean(process.env.CI),
  retries: process.env.CI ? 1 : 0,
  reporter: process.env.CI ? [['list'], ['html', { open: 'never' }]] : 'list',
  use: {
    baseURL: BASE_URL,
    trace: 'off',
    screenshot: 'only-on-failure',
    video: 'off',
  },
  // The visual gate. `maxDiffPixelRatio` is deliberately tight enough that a
  // shifted overlay or an off-token colour fails (see the deliberate-regression
  // proof in slice 4); `animations: 'disabled'` removes the fly/fade timing as a
  // flake source. Loosening either value is a visible change in review.
  expect: {
    toHaveScreenshot: {
      maxDiffPixelRatio: 0.002,
      animations: 'disabled',
    },
  },
  // The stub suite needs no backend; CI only needs the bun toolchain and
  // Chromium installed. Unchanged from the original config except for the
  // `CDP_ONLY` guard: a `--project=tauri-cdp` run must attach to an app it did
  // not start, so the server is omitted from the resolved config entirely.
  //
  // `undefined` is the documented way to omit it — Playwright treats a missing
  // `webServer` as "nothing to launch". The `chromium` project's behaviour
  // (command, url, reuseExistingServer, timeout, pipes) is byte-identical to
  // before.
  projects: [
    {
      name: 'chromium',
      // The stub suite. `testIgnore` keeps the CDP specs out: they need a live
      // Rust-backed window, which this project's `webServer` does not provide.
      testIgnore: 'tauri/**',
      use: { ...devices['Desktop Chrome'] },
    },
    {
      // Drives the *real* Tauri app. No server is started for this project —
      // the app must already be running under `bun run tauri:dev:debug`.
      //
      // Deliberately NOT part of `bun run test:e2e`: that script pins
      // `--project=chromium`, so the default suite stays runnable with no app,
      // no Rust build and no graphical session. Running it here would make it
      // wait 30s for a window that is not there and fail, which is how the
      // whole suite came to look broken. Reach it through `test:e2e:cdp`.
      //
      // CDP attachment is done by `e2e/tauri/harness/connectApp.ts`, which
      // exposes `test`/`expect` extensions whose `appPage` fixture calls
      // `chromium.connectOverCDP`. The `connectOptions` use-option is
      // deliberately NOT used: the built-in fixture implements it with
      // `browserType.connect()` (the Playwright protocol), not
      // `connectOverCDP()`, so it cannot attach to a WebView2 that only speaks
      // CDP. See the harness header for the full rationale.
      name: 'tauri-cdp',
      testDir: './e2e/tauri',
      use: {
        // This is a local diagnostic, not a pixel gate: a live window has no
        // committed baselines, so the screenshot gate stays off.
        screenshot: 'only-on-failure',
        trace: 'off',
        video: 'off',
      },
    },
  ],
  webServer: CDP_ONLY
    ? undefined
    : {
        command: WEB_SERVER_COMMAND,
        url: BASE_URL,
        reuseExistingServer: !process.env.CI,
        timeout: 120_000,
        stdout: 'ignore',
        stderr: 'pipe',
      },
});

