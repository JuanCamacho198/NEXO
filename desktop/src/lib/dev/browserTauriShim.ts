/**
 * Dev-only Tauri IPC shim.
 *
 * `bun run dev` serves the frontend through plain Vite, so opening the app in a
 * normal browser leaves `window.__TAURI_INTERNALS__` undefined and every
 * `invoke` call rejects. That makes the whole shell unreachable: `AppState`
 * cannot read `auth.json`, so the app parks on the welcome screen and no route
 * past it can be navigated or reviewed.
 *
 * This module installs a minimal in-memory stand-in for the Tauri IPC bridge so
 * `bun run dev` boots directly into the authenticated shell. It is a review and
 * iteration aid, not a backend: unknown commands resolve to `null`, no real
 * file, network, crypto or database work happens, and the seeded data is
 * in-memory only.
 *
 * It stays inert in three cases, in order:
 *   1. A production build — `import.meta.env.DEV` is `false`, so Vite drops the
 *      body as dead code.
 *   2. A real Tauri window — the genuine bridge is already on `window`.
 *   3. A test harness that installed its own bridge first (Playwright's
 *      `e2e/harness/tauriStub.ts`), which must keep its own seed untouched.
 */

type SeedBook = {
  id: string;
  title: string;
  author: string;
  format: 'epub' | 'pdf';
  currentPage: number;
  totalPages: number;
  progressPercentage: number;
  minutesRead: number;
  readingStatus: 'to_read' | 'reading' | 'completed';
};

const TIMESTAMP = '2026-01-01T00:00:00.000Z';

const SEED_BOOKS: SeedBook[] = [
  {
    id: 'shim-book-1',
    title: 'C Notes for Professionals',
    author: 'GoalKicker.com',
    format: 'pdf',
    currentPage: 4,
    totalPages: 341,
    progressPercentage: 1,
    minutesRead: 6,
    readingStatus: 'reading',
  },
  {
    id: 'shim-book-2',
    title: 'Refactoring UI',
    author: 'Adam Wathan & Steve Schoger',
    format: 'pdf',
    currentPage: 218,
    totalPages: 218,
    progressPercentage: 100,
    minutesRead: 412,
    readingStatus: 'completed',
  },
  {
    id: 'shim-book-3',
    title: 'Designing Data-Intensive Applications',
    author: 'Martin Kleppmann',
    format: 'epub',
    currentPage: 0,
    totalPages: 616,
    progressPercentage: 0,
    minutesRead: 0,
    readingStatus: 'to_read',
  },
];

/** Resolves a Tauri fs path to the basename the in-memory map is keyed by. */
function resolveKey(path: string): string {
  const normalized = path.replace(/\\/g, '/');
  return normalized.slice(normalized.lastIndexOf('/') + 1);
}

function createInternals(): Record<string, unknown> {
  const files = new Map<string, string>();

  // Seeded as legacy plaintext on purpose: the app re-seals it on first read,
  // which exercises the same migration path the E2E harness covers.
  files.set(
    'auth.json',
    JSON.stringify({
      kind: 'local',
      profile: { name: 'Juan Camacho', email: null, avatarUrl: null, localOnly: true },
    }),
  );

  const libraryRows = SEED_BOOKS.map((book) => ({
    id: book.id,
    title: book.title,
    author: book.author,
    format: book.format,
    currentPage: book.currentPage,
    totalPages: book.totalPages,
    progressPercentage: book.progressPercentage,
    coverPath: null,
    minutesRead: book.minutesRead,
    updatedAt: TIMESTAMP,
    createdAt: TIMESTAMP,
    collectionIds: [],
    readingStatus: book.readingStatus,
  }));

  const sourceRows = SEED_BOOKS.map((book) => ({
    id: book.id,
    title: book.title,
    author: book.author,
    filePath: `C:/shim/${book.id}.${book.format}`,
    format: book.format,
    syncStatus: 'local',
    currentPage: book.currentPage,
    totalPages: book.totalPages,
    createdAt: TIMESTAMP,
    updatedAt: TIMESTAMP,
  }));

  const stats = {
    totalMinutesRead: SEED_BOOKS.reduce((sum, book) => sum + book.minutesRead, 0),
    totalSessions: 7,
    booksStarted: SEED_BOOKS.filter((book) => book.readingStatus !== 'to_read').length,
    booksCompleted: SEED_BOOKS.filter((book) => book.readingStatus === 'completed').length,
    avgProgressPercentage: 34,
  };

  // Storage: the Almacenamiento screen renders nothing at all when these
  // answer null (unknown commands fall through to null below), so seed them to
  // keep the screen reviewable in the browser. In-memory only, like the rest.
  const perBookSizes = libraryRows.map((book, index) => ({
    id: book.id,
    title: book.title,
    bytes: 180_000_000 - index * 40_000_000,
  }));
  const coversBytes = 2_400_000;
  let tempBytes = 86_000_000;
  const readStorageStats = (): Record<string, number> => {
    const dbBytes = perBookSizes.reduce((sum, row) => sum + row.bytes, 0);
    return {
      totalBytes: dbBytes + coversBytes + tempBytes,
      dbBytes,
      coversBytes,
      tempBytes,
      cacheBytes: 12_500_000,
      coverBytes: coversBytes,
    };
  };

  const channelResults: Record<string, unknown> = {
    listBooks: sourceRows,
    listLibraryBooks: libraryRows,
    listCollections: [],
    getSettings: [],
    getReadingStats: stats,
    getReadingStatsForRange: stats,
    getReadingActivity: [],
    getReadingStreak: 4,
    listSyncOutboxReady: [],
    listBookmarks: [],
    listHighlights: [],
    listTags: [],
    listDictionaryWords: [],
    listInstalledAddons: [],
    listAddonConsents: [],
  };

  return {
    transformCallback: (callback: unknown): number => {
      const id = Math.floor(Math.random() * 1_000_000_000);
      (window as unknown as Record<string, unknown>)[`_${id}`] = callback;
      return id;
    },
    unregisterCallback: (): undefined => undefined,
    convertFileSrc: (filePath: string): string => filePath,
    invoke: async (cmd: string, args?: { path?: unknown; data?: unknown }): Promise<unknown> => {
      // Secrets: opaque reversible base64 transform, never real crypto.
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

      // OS identity: report Windows so the custom titlebar renders, matching the
      // real desktop window instead of the browser-collapsed variant.
      if (cmd === 'plugin:os|type') return 'windows';
      if (cmd === 'plugin:os|platform') return 'win32';

      if (cmd.startsWith('plugin:fs|')) {
        const key = typeof args?.path === 'string' ? resolveKey(args.path) : '';
        if (cmd === 'plugin:fs|exists') return files.has(key);
        if (cmd === 'plugin:fs|read_text_file') {
          // plugin-fs decodes a byte array, not a string.
          return Array.from(new TextEncoder().encode(files.get(key) ?? ''));
        }
        if (cmd === 'plugin:fs|write_text_file') {
          const bytes =
            args?.data instanceof Uint8Array
              ? args.data
              : Uint8Array.from((args?.data as ArrayLike<number>) ?? []);
          files.set(key, new TextDecoder().decode(bytes));
          return null;
        }
        if (cmd === 'plugin:fs|remove') {
          files.delete(key);
          return null;
        }
        if (cmd === 'plugin:fs|rename') {
          const raw = args as unknown as Record<string, unknown>;
          const from =
            typeof raw.oldPath === 'string'
              ? resolveKey(raw.oldPath)
              : typeof raw.from === 'string'
                ? resolveKey(raw.from)
                : undefined;
          const to =
            typeof raw.newPath === 'string'
              ? resolveKey(raw.newPath)
              : typeof raw.to === 'string'
                ? resolveKey(raw.to)
                : undefined;
          if (from !== undefined && to !== undefined && files.has(from)) {
            files.set(to, files.get(from) as string);
            files.delete(from);
          }
          return null;
        }
        if (cmd === 'plugin:fs|read_dir') return [];
        if (cmd === 'plugin:fs|mkdir' || cmd === 'plugin:fs|copy_file') return null;
        return null;
      }

      // Storage commands mutate in-memory state, so they run before the
      // read-only channel lookup (which cannot express clearCache's effect).
      if (cmd === 'getStorageStats') return readStorageStats();
      if (cmd === 'getPerBookSizes') return perBookSizes;
      if (cmd === 'cleanupOrphans') return { removed: 0 };
      if (cmd === 'clearCache') {
        const freedBytes = tempBytes;
        tempBytes = 0;
        return { freedBytes };
      }
      if (cmd === 'deleteBookData') {
        const bookId = (args as unknown as { bookId?: string } | undefined)?.bookId;
        const index = perBookSizes.findIndex((row) => row.id === bookId);
        if (index >= 0) perBookSizes.splice(index, 1);
        return null;
      }

      if (Object.prototype.hasOwnProperty.call(channelResults, cmd)) {
        return channelResults[cmd];
      }

      return null;
    },
  };
}

function installBrowserTauriShim(): void {
  if (!import.meta.env.DEV) return;
  const scope = window as unknown as Record<string, unknown>;
  if (scope.__TAURI_INTERNALS__ !== undefined) return;
  scope.__TAURI_INTERNALS__ = createInternals();
}

installBrowserTauriShim();
