/**
 * Reset primitives for the real-use smoke test.
 *
 * ## Why this module exists
 *
 * The reader persists every movement and reloads its state on boot: the reading
 * position, the zoom, the text settings and the debug flag all survive a
 * restart. A smoke test that inherited any of them would be measuring the
 * developer's last session instead of the app, and a test that depends on
 * ambient state is the exact defect class this whole change exists to remove
 * (`odd/tasks/reader-confidence.md`).
 *
 * So the smoke spec resets before it asserts. Two mechanisms, each chosen for
 * what it can actually reach:
 *
 * ## 1. Persisted state — the app's own IPC commands
 *
 * The reader's durable state lives in SQLite, opened by the *Rust* process
 * (`resolve_db_path` -> `nexo.db`). A test process cannot write that file
 * safely while the app holds it, and the previous attempt at this in the
 * project was an external `sqlite3` write, which is a second writer against a
 * live database. The app already exposes every write that matters as a Tauri
 * command, so the reset goes through those, as a user would:
 *
 *   - `saveProgress` seeds a known CFI + percentage for the book under test.
 *   - `upsertSettings` seeds the reader settings the assertions depend on
 *     (zoom, pagination) and neutralises the diagnostic flag, which is read
 *     from `debug.enabled` at boot and changes the reader's render path.
 *
 * ## 2. In-memory state — a reload
 *
 * `saveProgress` writes SQLite, but the *running* app already read the old
 * progress into `readerState` on boot; the store is not subscribed to the
 * database. Seeding over IPC and then asserting would therefore measure the
 * stale in-memory value, not the seeded one. A `page.reload()` re-runs
 * `AppState.init()`, so the store re-reads what was just written. The reload is
 * the mechanism that makes (1) observable, and it is why this module is a
 * two-step reset rather than a "write the DB and carry on".
 *
 * Reload only stands in for a cold boot in what it resets: module state is held
 * to HMR as documented in `AppRouter.svelte`. Under `tauri dev` the frontend is
 * the Vite dev server, so an HMR update between two runs of this spec can let
 * one survive. Nothing in the smoke test depends on module state surviving or
 * not — everything it asserts about starts from a WAL-backed row — so that is
 * documented here rather than worked around.
 */
import { expect, type Page } from '@playwright/test';

/**
 * A reader setting worth pinning before an assertion runs.
 */
export type ReaderSettingSeed = {
  key: string;
  /** Serialized with `JSON.stringify`, which is what the app stores. */
  value: unknown;
};

/** A reading position worth pinning before an assertion runs. */
export type ProgressSeed = {
  cfiLocation: string;
  percentage: number;
};

/**
 * The seeds the smoke test opens with. Kept as data so the reset is auditable:
 * every value that could otherwise be inherited from a previous run is named
 * here, and nothing is left to whatever the app happened to be left on.
 *
 * The position is the first page of the prologue — a spine item with real body
 * text rather than a stored bookmark, so "the position was kept" is a claim
 * about a value the test itself chose.
 *
 * Spine item 1 is the *cover*: `<h1 class="oculto" title="Cubierta">` plus an
 * inline `<svg><image/></svg>`, and nothing that renders as text. Seeding it
 * made the spec's own `innerText.length > 0` assertion unsatisfiable before the
 * reader had done anything (measured: textLen = 0), which is why the seed points
 * at the prologue instead. Verified against the real book: this CFI opens on
 * "PRÓLOGO. La pregunta de Yali" with textLen = 56814.
 */
export const SMOKE_PROGRESS: ProgressSeed = {
  cfiLocation: 'epubcfi(/6/8!/4/4,/1:0,/1:1)',
  percentage: 0,
};

export const SMOKE_READER_SETTINGS: ReaderSettingSeed[] = [
  { key: 'reader.epub.fontSize', value: 100 },
  // Pagination is off by default and its own feature doc records it as broken
  // (`odd/tasks/epub-pagination.md`). The smoke test opens with the default
  // mode so it measures the reader a normal user gets, not the experimental one.
  { key: 'reader.paginated', value: false },
  { key: 'reader.textAlign', value: 'left' },
  { key: 'reader.themeMode', value: 'paper' },
];

/**
 * The key the diagnostic mode lives under.
 *
 * Mirrors `DEBUG_ENABLED_SETTING_KEY` in
 * `src/lib/shared/services/debugPreferences.ts`, which is the module the app
 * loads the flag from at boot (`loadDebugEnabled`). `debugState.enabled`
 * (`src/lib/shared/debug/debugState.svelte.ts`) is the in-memory mirror
 * `AppRouter.svelte` reads for `showDiagnostics`; its value comes from this row,
 * so neutralising the row and reloading is what turns the chrome off.
 *
 * It is duplicated here rather than imported: this is a standalone Playwright
 * spec that runs against the built app through CDP, and reaching into `$lib`
 * would couple the harness to the frontend build graph.
 */
export const DEBUG_ENABLED_SETTING_KEY = 'debug.enabled';

/**
 * The seeds that are *not* reader settings but still survive a restart. Applied
 * by the smoke test's reset so a machine left in diagnostic mode cannot change
 * what the run measures.
 */
export const SMOKE_ENVIRONMENT_SETTINGS: ReaderSettingSeed[] = [
  { key: DEBUG_ENABLED_SETTING_KEY, value: false },
];

/** Reads one persisted setting, or null when it was never written. */
export async function readSetting(page: Page, key: string): Promise<unknown> {
  const raw = (await readSettings(page))[key];
  if (raw === undefined) return null;
  try {
    return JSON.parse(raw) as unknown;
  } catch {
    return null;
  }
}

type Invoke = (cmd: string, args?: unknown) => Promise<unknown>;

/**
 * The live IPC bridge. Throws when absent — a spec that silently no-ops its
 * reset would go on to assert against ambient state and pass for the wrong
 * reason, which is the failure this module is here to prevent.
 */
async function invokeInApp(page: Page, cmd: string, args?: unknown): Promise<unknown> {
  const result = await page.evaluate(
    async ([command, commandArgs]) => {
      const internals = (window as unknown as { __TAURI_INTERNALS__?: { invoke: Invoke } })
        .__TAURI_INTERNALS__;
      if (!internals) throw new Error('__TAURI_INTERNALS__ is missing — not a Tauri webview');
      return internals.invoke(command as string, commandArgs);
    },
    [cmd, args] as const,
  );
  return result;
}

export async function getProgress(
  page: Page,
  bookId: string,
): Promise<{ cfiLocation: string; percentage: number } | null> {
  return invokeInApp(page, 'getProgress', { bookId }) as Promise<{
    cfiLocation: string;
    percentage: number;
  } | null>;
}

/** Alias with a name that reads better at the call site of a capture/restore pair. */
export const readProgress = getProgress;

/** Seeds a known reading position for `bookId`. */
export async function seedProgress(
  page: Page,
  bookId: string,
  seed: ProgressSeed = SMOKE_PROGRESS,
): Promise<void> {
  await invokeInApp(page, 'saveProgress', {
    payload: { bookId, cfiLocation: seed.cfiLocation, percentage: seed.percentage },
  });
}

/**
 * Puts `bookId` back to the position {@link readProgress} captured, or leaves it
 * alone when there was none (a book that had never been opened has no row, and
 * writing one would invent state rather than restore it).
 */
export async function restoreProgress(
  page: Page,
  bookId: string,
  progress: { cfiLocation: string; percentage: number } | null,
): Promise<void> {
  if (!progress) return;
  await invokeInApp(page, 'saveProgress', {
    payload: {
      bookId,
      cfiLocation: progress.cfiLocation,
      percentage: progress.percentage,
    },
  });
}

/** Seeds one `app_settings` row. `value` is stringified the way the app stores it. */
export async function seedSetting(page: Page, key: string, value: unknown): Promise<void> {
  await invokeInApp(page, 'upsertSettings', {
    settings: [{ key, valueJson: JSON.stringify(value), updatedAt: new Date().toISOString() }],
  });
}

/**
 * Seeds every non-reader setting the smoke test needs neutral, currently the
 * diagnostic flag. Kept as its own entry point so the reset reads as "these are
 * the things this run must not inherit" rather than a fourth magic row in the
 * middle of the reader settings.
 */
export async function seedEnvironment(
  page: Page,
  seeds: ReaderSettingSeed[] = SMOKE_ENVIRONMENT_SETTINGS,
): Promise<void> {
  await invokeInApp(page, 'upsertSettings', {
    settings: seeds.map(({ key, value }) => ({
      key,
      valueJson: JSON.stringify(value),
      updatedAt: new Date().toISOString(),
    })),
  });
}

/** Persisted `app_settings` rows, as the app itself reads them. */
export async function readSettings(page: Page): Promise<Record<string, string>> {
  const rows = (await invokeInApp(page, 'getSettings')) as Array<{
    key: string;
    valueJson: string;
  }>;
  return Object.fromEntries(rows.map((row) => [row.key, row.valueJson]));
}

/**
 * Restores the `app_settings` rows captured by {@link readSettings} before the
 * run. Used on teardown so the smoke test's seeds and the diagnostic flag do not
 * leak into the specs that share this app.
 */
export async function restoreSettings(page: Page, previous: Record<string, string>): Promise<void> {
  const rows = Object.entries(previous).map(([key, valueJson]) => ({
    key,
    valueJson,
    updatedAt: new Date().toISOString(),
  }));
  await invokeInApp(page, 'upsertSettings', { settings: rows });
}

/**
 * Reloads the webview and waits for the shell to be usable again.
 *
 * This is the step that makes a seed observable: the stores were populated from
 * the database at boot, so a write that arrives afterwards is not seen until
 * the next boot. Both mounted states are accepted, because the app can come
 * back on whatever route it was left on — the reader route replaces the whole
 * shell (`AppRouter.svelte` branches on `route`, and `showSidebar` is false for
 * `reader`), so only the sidebar is guaranteed on a cold library start.
 */
export async function reloadApp(page: Page): Promise<void> {
  await page.reload();
  await expect(
    page.locator('aside nav > button').first().or(page.locator('iframe[title="chapter"]')).first(),
  ).toBeVisible({ timeout: 60_000 });
}
