import type { Page } from '@playwright/test';

// The Tauri IPC double shared by every E2E spec. Extracted verbatim from the
// Wave-1 library smoke spec so the smoke and the visual gate exercise the same
// boot path: a stubbed `plugin:fs` file map plus a stubbed `invoke` channel.
//
// No real Tauri, Supabase or reader call is made, and unknown commands resolve
// to `null` exactly as before — the shape is what keeps the app's optional
// integrations dormant instead of partially failing.
//
// Initial route state is preserved rather than parameterized: `auth.json` is
// seeded on every call, so the shell always boots authenticated on route
// `home`. The `welcome` route is reachable only through the SIGNED_OUT auth
// event (or an explicit sign-out), so no stub seed can boot into it — measured,
// not assumed.

export const DEFAULT_SEEDED_TITLE = 'Seeded Smoke Title';

/**
 * Seed for the in-browser double. Plain data only: it is serialized into the
 * page through `page.addInitScript`.
 */
export type TauriStubSeed = {
  /** Title of the first seeded book; further books are suffixed `#<n>`. */
  title?: string;
  /** How many library rows to seed. Defaults to 1, the smoke's original seed. */
  bookCount?: number;
};

type ResolvedSeed = Required<TauriStubSeed>;

export async function installTauriInvokeStub(page: Page, seed: TauriStubSeed = {}): Promise<void> {
  const resolved: ResolvedSeed = {
    title: seed.title ?? DEFAULT_SEEDED_TITLE,
    bookCount: seed.bookCount ?? 1,
  };

  await page.addInitScript(({ title, bookCount }: ResolvedSeed) => {
    const TIMESTAMP = '2026-01-01T00:00:00.000Z';

    const bookIds = Array.from({ length: bookCount }, (_, index) => index + 1);

    const bookTitles = bookIds.map((index) => (index === 1 ? title : `${title} #${index}`));

    const libraryRows = bookIds.map((index, position) => ({
      id: `smoke-book-${index}`,
      title: bookTitles[position],
      author: 'Smoke Author',
      format: 'epub',
      currentPage: 4,
      totalPages: 10,
      progressPercentage: 40,
      coverPath: null,
      minutesRead: 12,
      updatedAt: TIMESTAMP,
      createdAt: TIMESTAMP,
      collectionIds: [],
      readingStatus: 'reading',
    }));

    const sourceRows = bookIds.map((index, position) => ({
      id: `smoke-book-${index}`,
      title: bookTitles[position],
      author: 'Smoke Author',
      filePath:
        index === 1
          ? 'C:/smoke/seeded-smoke-title.epub'
          : `C:/smoke/seeded-smoke-title-${index}.epub`,
      format: 'epub',
      syncStatus: 'local',
      currentPage: 4,
      totalPages: 10,
      createdAt: TIMESTAMP,
      updatedAt: TIMESTAMP,
    }));

    const stats = {
      totalMinutesRead: 12,
      totalSessions: 1,
      booksStarted: 1,
      booksCompleted: 0,
      avgProgressPercentage: 40,
    };

    // Tiny in-memory FS backing @tauri-apps/plugin-fs. `auth.json` holds a
    // local profile so AppState boots straight to the authenticated shell
    // (route 'home') instead of the welcome screen, with no Supabase session.
    // Seeded as legacy plaintext on purpose: the app re-seals it to a DPAPI
    // envelope on first read, exercising the 0.3.5 migration path in E2E.
    const files = new Map<string, string>();
    files.set(
      'auth.json',
      JSON.stringify({
        kind: 'local',
        profile: { name: 'Smoke User', email: null, avatarUrl: null, localOnly: true },
      }),
    );

    const channelResults: Record<string, unknown> = {
      listBooks: sourceRows,
      listLibraryBooks: libraryRows,
      listCollections: [],
      getSettings: [],
      getReadingStats: stats,
      getReadingStatsForRange: stats,
      getReadingActivity: [],
      getReadingStreak: 0,
      listSyncOutboxReady: [],
      listBookmarks: [],
      listHighlights: [],
      listTags: [],
      listDictionaryWords: [],
      listInstalledAddons: [],
      listAddonConsents: [],
    };

    // Force the English UI so labels are stable regardless of the developer
    // machine's persisted locale.
    window.localStorage.setItem('nexo.ui.locale', 'en');

    const internals = {
      transformCallback: (callback: unknown) => {
        const id = Math.floor(Math.random() * 1_000_000_000);
        (window as unknown as Record<string, unknown>)[`_${id}`] = callback;
        return id;
      },
      unregisterCallback: () => undefined,
      convertFileSrc: (filePath: string) => filePath,
      invoke: async (cmd: string, args?: { path?: unknown; data?: unknown }) => {
        // 0.3.5 secrets encryption: mock the DPAPI Rust commands with an
        // opaque reversible transform (base64, never real crypto). Unknown
        // ciphertext rejects like the real `unprotectSecret` would on
        // tampered input.
        if (cmd === 'protectSecret') {
          const plaintext = (args as unknown as { plaintext?: unknown }).plaintext;
          return `stub-sealed:${btoa(typeof plaintext === 'string' ? plaintext : '')}`;
        }
        if (cmd === 'unprotectSecret') {
          const ciphertext = (args as unknown as { ciphertext?: unknown }).ciphertext;
          if (typeof ciphertext !== 'string' || !ciphertext.startsWith('stub-sealed:')) {
            throw new Error('stub DPAPI: bad ciphertext');
          }
          return atob(ciphertext.slice('stub-sealed:'.length));
        }
        if (cmd.startsWith('plugin:fs|')) {
          const path = typeof args?.path === 'string' ? args.path : '';
          if (cmd === 'plugin:fs|exists') return files.has(path);
          if (cmd === 'plugin:fs|read_text_file') {
            // plugin-fs decodes a byte array, not a string.
            return Array.from(new TextEncoder().encode(files.get(path) ?? ''));
          }
          if (cmd === 'plugin:fs|write_text_file') {
            const bytes =
              args?.data instanceof Uint8Array
                ? args.data
                : Uint8Array.from((args?.data as ArrayLike<number>) ?? []);
            files.set(path, new TextDecoder().decode(bytes));
            return null;
          }
          if (cmd === 'plugin:fs|remove') {
            files.delete(path);
            return null;
          }
          if (cmd === 'plugin:fs|rename') {
            // plugin-fs rename carries old/new paths; the stub only sees the
            // generic shape, so accept the known serializations best-effort.
            const raw = args as unknown as Record<string, unknown>;
            const from =
              typeof raw.oldPath === 'string'
                ? raw.oldPath
                : typeof raw.from === 'string'
                  ? raw.from
                  : undefined;
            const to =
              typeof raw.newPath === 'string'
                ? raw.newPath
                : typeof raw.to === 'string'
                  ? raw.to
                  : undefined;
            if (from !== undefined && to !== undefined && files.has(from)) {
              const content = files.get(from) as string;
              files.delete(from);
              files.set(to, content);
            }
            return null;
          }
          return null;
        }
        if (Object.prototype.hasOwnProperty.call(channelResults, cmd)) {
          return channelResults[cmd];
        }
        return null;
      },
    };

    (window as unknown as Record<string, unknown>).__TAURI_INTERNALS__ = internals;
  }, resolved);
}
