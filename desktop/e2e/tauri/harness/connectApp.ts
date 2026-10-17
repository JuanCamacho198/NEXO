/**
 * Attach Playwright to the running NEXO Tauri app over the WebView2 CDP port.
 *
 * ## Why a custom fixture instead of `connectOptions`
 *
 * Playwright's `use: { connectOptions }` looks like the natural fit, but its
 * built-in fixture calls `browserType.connect()` — the **Playwright protocol**
 * — not `connectOverCDP()`. Verified in the installed runner:
 *
 *   browser: [async ({ playwright, browserName, _browserOptions, connectOptions }, use) => {
 *     if (connectOptions) {
 *       const browser = await playwright[browserName].connect(connectOptions.wsEndpoint, ...)
 *
 * A WebView2 started with `--remote-debugging-port` speaks **CDP only** (it has
 * no Playwright-protocol host), so `connectOptions` would hang on a protocol
 * handshake that never completes. The attachment has to go through
 * `chromium.connectOverCDP(endpointURL)`, which is what this module does.
 *
 * ## Which CDP target is the app
 *
 * CDP exposes more than the app's page. `--remote-debugging-port` is inherited
 * by every renderer Chromium creates, so the target list can include the page
 * itself, `about:blank` placeholders, and (when the inspector is open) a
 * `devtools://` page for the DevTools frontend. Picking `pages()[0]` blind is
 * how you end up driving the wrong surface.
 *
 * The criterion used here is the **document origin**: in `tauri dev` the webview
 * loads `devUrl` from `tauri.conf.json`, i.e. `http://127.0.0.1:1420`, so the
 * app target is the one whose URL starts with that origin. `tauri.localhost` is
 * deliberately not matched: it only exists in a built app (`frontendDist`), and
 * this harness targets the dev flow. Targets with a `devtools://` or `about:`
 * scheme are skipped outright.
 *
 * To make the wait deterministic rather than a sleep, the harness:
 *   1. waits for the CDP HTTP endpoint to answer at all (the app may still be
 *      compiling its Rust deps when the test starts), then
 *   2. connects, then
 *   3. polls the attached pages until one reports the expected origin AND has a
 *      real DOM document, then
 *   4. waits for the app shell to mount (the sidebar nav), which is the first
 *      thing that appears once `AppState.init()` has run.
 */
import { chromium, expect, test as base, type Browser, type Page } from '@playwright/test';

/** Origin the webview loads in `tauri dev` (`tauri.conf.json` `devUrl`). */
export const APP_ORIGIN = 'http://127.0.0.1:1420';

/** The CDP HTTP endpoint; port is overridable so two runs can coexist. */
export function cdpEndpoint(): string {
  const port = process.env.NEXO_CDP_PORT ?? '9222';
  return `http://127.0.0.1:${port}`;
}

type AppFixtures = {
  /** The live app window's page, attached over CDP. */
  appPage: Page;
};

/** Polls `${endpoint}/json/version` until the CDP server answers. */
async function waitForCdpServer(endpoint: string, timeoutMs: number): Promise<void> {
  const deadline = Date.now() + timeoutMs;
  let lastError: unknown = null;

  while (Date.now() < deadline) {
    try {
      const response = await fetch(`${endpoint}/json/version`);
      if (response.ok) return;
      lastError = new Error(`CDP endpoint answered ${response.status}`);
    } catch (error) {
      lastError = error;
    }
    await new Promise((resolve) => setTimeout(resolve, 500));
  }

  throw new Error(
    `CDP endpoint never became reachable at ${endpoint} within ${timeoutMs}ms. ` +
      `Start the app with "bun run tauri:dev:debug" (default port 9222, or set ` +
      `NEXO_CDP_PORT on both the app and this test). Last error: ${String(lastError)}`,
  );
}

/**
 * Finds the app page among every target the CDP server exposes.
 *
 * `browser.contexts()` on a `connectOverCDP` attachment is the browser's
 * existing contexts (Playwright does not create one). The app page lives in the
 * default context; we still scan all of them so a future Tauri version that
 * opens a second window does not silently break this.
 */
async function findAppPage(browser: Browser, timeoutMs: number): Promise<Page> {
  const deadline = Date.now() + timeoutMs;
  const seen = new Set<string>();

  while (Date.now() < deadline) {
    for (const context of browser.contexts()) {
      for (const page of context.pages()) {
        const url = page.url();
        seen.add(url);

        // Skip the inspector frontend and any placeholder target.
        if (url.startsWith('devtools://') || url.startsWith('about:')) continue;
        if (!url.startsWith(APP_ORIGIN)) continue;

        // The URL can be the app origin while the document is still blank
        // (navigation committed, body not parsed). Require a mounted document.
        const hasBody = await page.evaluate(() => document.body !== null).catch(() => false);
        if (hasBody) return page;
      }
    }
    await new Promise((resolve) => setTimeout(resolve, 250));
  }

  throw new Error(
    `No page with origin ${APP_ORIGIN} was found over CDP within ${timeoutMs}ms. ` +
      `Targets seen: ${seen.size > 0 ? [...seen].join(', ') : '(none)'}. ` +
      `A page that is present but not matching means the webview loaded a ` +
      `different origin (check devUrl) or the app failed to mount.`,
  );
}

export const test = base.extend<AppFixtures>({
  appPage: async ({}, use) => {
    const endpoint = cdpEndpoint();
    const connectTimeout = 60_000;

    await waitForCdpServer(endpoint, connectTimeout);

    const browser = await chromium.connectOverCDP(endpoint, {
      // Local by design: Playwright and the app share a filesystem, so
      // traces/downloads need no bridging.
      isLocal: true,
      timeout: connectTimeout,
    });

    try {
      const page = await findAppPage(browser, connectTimeout);

      // The shell renders its sidebar once `AppState.init()` resolves. Waiting
      // on it here means a spec starts from a booted app rather than a blank
      // document, and turns "the app never came up" into a clear failure.
      //
      // OR the reader surface. The reader route replaces the whole shell — the
      // sidebar is not in the DOM there — so a strict sidebar wait made the
      // suite one-shot: a spec that opened a book left the app in the reader,
      // and the *next* attach timed out on a sidebar that will never appear. A
      // chapter iframe (`title="chapter"`, mounted only by the EPUB viewer) is
      // the reader's own booted marker, so accepting either keeps the "app came
      // up" guarantee on both routes.
      await expect(
        page.locator('aside nav > button').first().or(page.locator('iframe[title="chapter"]')).first(),
      ).toBeVisible({ timeout: 60_000 });

      await use(page);
    } finally {
      // Detach without closing: the app is the developer's, not the test's.
      // Playwright's `browser.close()` on a CDP attachment disconnects only,
      // but being explicit keeps the intent obvious.
      await browser.close();
    }
  },
});

export { expect };
