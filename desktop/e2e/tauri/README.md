# Driving the real Tauri app over CDP

This directory holds the specs that drive the **actual** NEXO desktop app — the
one with Rust behind it, a real SQLite database and real file dialogs — instead
of the stubbed frontend the rest of `e2e/` exercises.

The two paths are complementary and both are kept:

| Path | Project | Backend | Runs in CI | Purpose |
|------|---------|---------|------------|---------|
| `e2e/**` | `chromium` | `tauriStub.ts` (in-memory double) | Yes | Fast, deterministic UI and visual gate |
| `e2e/tauri/**` | `tauri-cdp` | The real Rust app | **No** | Prove behaviour against the real backend |

## Why CDP

`tauri dev` on Windows renders through **WebView2**, which is Chromium. Chromium
exposes a debugger over the Chrome DevTools Protocol (CDP) when it is started
with `--remote-debugging-port`. Playwright can attach to any CDP endpoint with
`chromium.connectOverCDP()`, so the real window becomes drivable without a
Playwright-protocol host (WebView2 does not provide one).

## Running it

Two terminals. The app first, then the specs.

```powershell
# terminal 1 — launch the app with the debug port open
bun run tauri:dev:debug

# terminal 2 — attach and run the CDP specs
bun run test:e2e:cdp
```

`tauri:dev:debug` compiles and starts the Rust app (the first build is slow; a
warm one is seconds). Wait until the window is up before running the specs.

### Choosing a different port

The debug port defaults to **9222**. Override it when you need a second
instance, because CDP exposes **one debug endpoint per process** and two apps on
the same port collide:

```powershell
$env:NEXO_CDP_PORT = '9333'
bun run tauri:dev:debug     # terminal 1, uses 9333
bun run test:e2e:cdp        # terminal 2, reads the same variable
```

Both commands read `NEXO_CDP_PORT` from the environment, so setting it once in
the shell covers both.

### Running the whole suite

`bun run test:e2e` runs **only** the `chromium` project. The CDP project is
excluded from it on purpose (see "Limits"). Use `bun run test:e2e:cdp` for the
CDP specs.

## How it works

- `scripts/tauriDevDebug.mjs` — sets `WEBVIEW2_ADDITIONAL_BROWSER_ARGUMENTS`
  (which WebView2 reads) to `--remote-debugging-port=<port>`, then spawns
  `tauri dev`. It exists as a script because `VAR=x cmd` is POSIX shell syntax
  and does not work in `cmd.exe`/`pwsh`, which is what npm/bun use on Windows.
- `e2e/tauri/harness/connectApp.ts` — resolves the endpoint, waits for the CDP
  server, attaches with `connectOverCDP`, finds **the app page** among the CDP
  targets, waits for the shell to mount, and exposes it as the `appPage`
  fixture. Import `test`/`expect` from here, not from `@playwright/test`.
- `e2e/tauri/smoke.spec.ts` — the viability gate. It calls `diagnose`, a
  command implemented in Rust that queries the real database, so a passing run
  proves the backend is genuinely behind the page.

### Finding the right page

A CDP endpoint can list more than one target: the app page, `about:blank`
placeholders, and a `devtools://` page when the inspector is open. The harness
picks the target whose URL starts with `http://127.0.0.1:1420` — the `devUrl`
the webview loads in `tauri dev` — and skips `devtools://` and `about:` targets.
`tauri.localhost` is deliberately *not* matched: it belongs to a built app
(`frontendDist`), not the dev flow this harness targets.

To write a new spec, import the fixture and use `appPage`:

```ts
import { expect, test } from './harness/connectApp';

test('opening a book renders its chapter', async ({ appPage }) => {
  // appPage is the live window; click, type and read it directly.
});
```

## Limits

These are real and worth knowing before relying on this path.

- **Windows only.** CDP is a Chromium feature. macOS uses **WKWebView**, which
  has no CDP equivalent, so this harness cannot run there. Linux (`webkit2gtk`)
  is likewise out; a Chromium-based Linux webview could work but is untested.
- **One port per process.** CDP exposes a single debug endpoint per process, so
  two attached runs must use different `NEXO_CDP_PORT` values. Specs run in
  series (the project is configured for one worker); there is no parallel
  attachment to the same app.
- **Not a CI gate.** It needs a compiled Rust build and, on Windows, a
  graphical session to open a window. CI has neither, so `bun run test:e2e`
  deliberately does not include it. Treat it as a local instrument, not a gate.
- **A live window is not a baseline.** The project runs with the screenshot gate
  off: a real app's pixels depend on the developer's data, window size and
  fonts, so there is nothing stable to compare against.

### What cannot be automated even this way

- **PDF pixel fidelity.** PDFs render to `<canvas>` through the GPU. The raster
  output varies with the graphics driver, DPI and hardware acceleration, so a
  pixel assertion is not reproducible across machines.
- **System fonts.** Screenshots taken here derive between machines as installed
  fonts and their versions differ. Anything font-metric sensitive is not a
  portable assertion.
- **Aesthetic judgement without a baseline.** "Does this look right?" has no
  oracle. This harness can read the DOM and geometry, but a judgment call needs a
  human or a committed baseline.
- **Native OS dialogs.** File pickers, message boxes and the like are drawn by
  the OS, outside the webview, and are not in the DOM. They cannot be clicked
  through CDP.

## Troubleshooting

- **"CDP endpoint never became reachable"** — the app is not running, or it was
  started with `bun run tauri:dev` (which does **not** open the port). Use
  `bun run tauri:dev:debug`.
- **"No page with origin ... was found"** — the app started but the webview
  loaded a different origin, or the shell failed to mount. Check the terminal
  running the app for errors.
- **Port already in use** — another app (or a previous run) holds 9222. Set
  `NEXO_CDP_PORT` to a free port as shown above.
