/**
 * Reactive store for books available for download from other devices.
 *
 * The shelf "Available from other devices" section sources its list directly
 * from Drive `Nexo/Books/` (REQ-01) via `loadAvailableFromDrive()`, filtered
 * against the local library — instead of the Supabase user_books catalog.
 * Populated on shelf mount by the screen; downloaded files land in the local
 * library and are removed from the section on success.
 *
 * Provides download orchestration with Drive auth fallback (GDriveProvider
 * refreshes the token when absent), retry with backoff, and error state
 * management.
 */
import type { SupabaseUserBookRow } from '$lib/shared/sync/SupabaseBookCatalogSync';
import { SupabaseBookCatalogSync } from '$lib/shared/sync/SupabaseBookCatalogSync';
import { GDriveProvider } from '$lib/shared/services/storage/GDriveProvider';
import { isDriveAuthorized } from '$lib/shared/services/DriveConnectService';
import { getIdentityProvider } from '$lib/shared/services/SupabaseAuthService';
import {
  declineKeyForUser,
  markDriveDeclined,
  shouldShowDrivePrompt,
} from '$lib/shared/services/driveConnectPromptGate';
import { parseCanonicalBookName } from '$lib/shared/protocol/DriveCatalogContract';
import { reportAuthError } from '$lib/shared/stores/syncAlert.svelte';
import { authState } from '$lib/shared/stores/AuthState.svelte';
import { importRecoveredBook } from '$lib/shared/recovery/desktopRecoveryImport';
import { extractEpubMetadataFromBytes } from '$lib/shared/services/epubImportMetadata';
import { logger } from '$lib/shared/logger/Logger';
import type { LibraryPort } from '$lib/shared/ports/LibraryPort';
import { TauriLibraryAdapter } from '$lib/shared/ports/adapters/tauri/TauriLibraryAdapter';

// ─── Types ─────────────────────────────────────────────────────────────

/** A book listed from Drive `Nexo/Books/` and absent from the local library. */
export interface AvailableDriveBook {
  id: string;
  ext: string;
  remoteName: string;
  displayTitle: string;
  /** Author from the Supabase catalog metadata, when a user session exists. */
  author?: string | null;
  /** Remote cover URL from the Supabase catalog metadata, when available. */
  coverUrl?: string | null;
}

// ─── Port ───────────────────────────────────────────────────────────────

let libraryPort: LibraryPort = new TauriLibraryAdapter();

export function setDownloadableCatalogPort(port: LibraryPort): void {
  libraryPort = port;
}

// ─── Reactive State ───────────────────────────────────────────────────

let downloadableBooks: AvailableDriveBook[] = $state([]);
let isDownloadingSet: Set<string> = $state(new Set());
let downloadErrorMsg: string | null = $state(null);
/**
 * Set only when the Drive LISTING itself failed. Distinct from
 * `downloadErrorMsg` (which also carries download-time messages, including the
 * "not connected" copy a user sees before ever connecting Drive). The empty
 * shelf renders this one — a genuine listing failure is worth showing even with
 * no books, while "not connected" is the ordinary state and must stay quiet.
 */
let loadErrorMsg: string | null = $state(null);

/**
 * One-time "Connect Google Drive?" pre-prompt request (login-drive-separation).
 * Set when a Google-signed-in user with no prior decline invokes a cloud
 * download while Drive is unauthorized. The `DriveConnectPrompt` dialog
 * (work unit 4) observes this flag; declining persists a per-user marker via
 * `declineDrivePrompt()` so later attempts degrade cleanly without re-offer.
 */
let drivePromptPending: boolean = $state(false);

export function isDrivePromptPending(): boolean {
  return drivePromptPending;
}

export function clearDrivePrompt(): void {
  drivePromptPending = false;
}

export function declineDrivePrompt(): void {
  drivePromptPending = false;
  const userId = authState.userId;
  const marker = markDriveDeclined(userId);
  if (marker === null || typeof localStorage === 'undefined') return;
  try {
    localStorage.setItem(declineKeyForUser(userId as string), marker);
  } catch {
    // best-effort: a declined prompt simply re-offers next launch
  }
}

function readDriveDeclineMarker(userId: string | null): string | null {
  if (userId === null || typeof localStorage === 'undefined') return null;
  try {
    return localStorage.getItem(declineKeyForUser(userId));
  } catch {
    return null;
  }
}

const DRIVE_NOT_CONNECTED_COPY =
  'Google Drive is not connected. Connect Google Drive in Settings to download books.';

// ─── Public Setters ───────────────────────────────────────────────────

export function setDownloadableBooks(books: AvailableDriveBook[]): void {
  downloadableBooks = books;
}

export function clearDownloadableBooks(): void {
  downloadableBooks = [];
}

export function removeDownloadableBook(bookId: string): void {
  downloadableBooks = downloadableBooks.filter((b) => b.id !== bookId);
}

export function setDownloadError(msg: string | null): void {
  downloadErrorMsg = msg;
}

export function clearDownloadError(): void {
  downloadErrorMsg = null;
}

export function clearLoadError(): void {
  loadErrorMsg = null;
}

// ─── Drive Listing (REQ-01) ───────────────────────────────────────────

/** Module-level in-flight guard: concurrent mounts/refreshes share one listing. */
let availableLoadPromise: Promise<void> | null = null;

/**
 * List `Nexo/Books/` via Drive and expose the books absent from the local
 * library (SCN-01/SCN-02). Unparseable filenames (`_state.json`, no dot,
 * trailing dot) are dropped. When a live user session exists the Supabase
 * catalog is fetched once and used to enrich titles/author/cover (falling back
 * to the filename minus its extension when absent); a catalog failure never
 * breaks the Drive listing. Auth-class failures surface on the global banner
 * via `reportAuthError` and in `error` for the inline banner. Never clears a
 * previously loaded list on failure.
 */
export async function loadAvailableFromDrive(): Promise<void> {
  if (availableLoadPromise) return availableLoadPromise;

  const promise = (async () => {
    try {
      const gdrive = new GDriveProvider();
      const userId = authState.userId;
      const catalogRows = userId
        ? await new SupabaseBookCatalogSync(userId)
            .fetchCatalog()
            .catch(() => [] as SupabaseUserBookRow[])
        : [];
      const catalogByBookId = new Map(catalogRows.map((r) => [r.id, r]));

      // Union EVERY `Nexo*` root the account owns, not just the first: two
      // devices uploading at once make Drive create `Nexo` and `Nexo (1)`, and
      // reading only one hides the books in the other. Read-only — nothing in
      // the reader's Drive is created, renamed, moved or deleted. A single-root
      // account (the majority) takes the same path with one root and no
      // duplicates; `listAllRoots` is a superset of `list('')`'s answer.
      const [roots, localBooks] = await Promise.all([
        gdrive.listAllRoots(),
        libraryPort.listLibraryBooks(),
      ]);
      const remoteNames = roots.names;

      const localIds = new Set(localBooks.map((b) => b.id));
      const available: AvailableDriveBook[] = [];
      // Dedupe by bookId: the same book present in both roots (or under a
      // collision-suffixed name) is ONE entry, not two.
      const seen = new Set<string>();
      let droppedInvalidName = 0;
      let droppedAlreadyLocal = 0;
      let droppedDuplicate = 0;
      for (const name of remoteNames) {
        const parsed = parseCanonicalBookName(name);
        if (!parsed) {
          droppedInvalidName++;
          continue; // sync-state / malformed names are not books
        }
        if (localIds.has(parsed.bookId)) {
          droppedAlreadyLocal++;
          continue; // already local
        }
        if (seen.has(parsed.bookId)) {
          droppedDuplicate++;
          continue; // same book from another root/twin name
        }
        seen.add(parsed.bookId);
        const meta = catalogByBookId.get(parsed.bookId);
        available.push({
          id: parsed.bookId,
          ext: parsed.ext,
          remoteName: name,
          displayTitle:
            meta?.title && meta.title.trim().length > 0
              ? meta.title
              : name.slice(0, name.lastIndexOf('.')),
          author: meta?.author ?? null,
          coverUrl: meta?.coverUrl ?? null,
        });
      }
      downloadableBooks = available;
      loadErrorMsg = null;
      // The contract's governing rule: a failure must be findable. This path
      // used to fail in absolute silence — the diagnosis below records every
      // count that decides the outcome, so "why is the shelf empty" is
      // answerable from the log without a reproduction.
      logger.info(
        'downloadableCatalog: drive listing',
        {
          rootCount: roots.rootCount,
          emptyRoots: roots.emptyRoots,
          remoteFiles: remoteNames.length,
          localBooks: localBooks.length,
          droppedInvalidName,
          droppedAlreadyLocal,
          droppedDuplicate,
          available: available.length,
        },
        'sync',
      );
    } catch (e) {
      reportAuthError(e);
      const message = e instanceof Error ? e.message : 'Failed to load books from Drive';
      downloadErrorMsg = message;
      // A genuine listing failure, not the ordinary "not connected" state: this
      // is what the empty shelf renders so the reader is told WHY it is empty.
      // A typed AUTH_REQUIRED/AUTH_EXPIRED means Drive was never connected (or
      // the grant lapsed) — that is the ordinary state, already surfaced on the
      // global auth banner, and must not raise a listing error on the shelf.
      const code = (e as { code?: unknown } | null)?.code;
      const isAuthClass =
        code === 'AUTH_REQUIRED' || code === 'AUTH_EXPIRED' || code === 'DRIVE_NOT_CONNECTED';
      loadErrorMsg = isAuthClass ? null : message;
      logger.error('downloadableCatalog: drive listing failed', { message, code }, 'sync');
    }
  })().finally(() => {
    if (availableLoadPromise === promise) availableLoadPromise = null;
  });

  availableLoadPromise = promise;
  return promise;
}

// ─── Download Orchestration (REQ-02) ─────────────────────────────────

/** Books whose catalog row was never enriched carry the UUID as title. */
const UUID_LIKE_TITLE_RE = /^[0-9a-f-]{36}$/i;
const isFallbackTitle = (title: string, id: string): boolean =>
  title === id || UUID_LIKE_TITLE_RE.test(title);

/**
 * Download a Drive book from the shelf section.
 *
 * Flow: Drive-authorization pre-check (unauthorized + Google provider +
 * no prior decline → raise the connect-prompt request and stop before any
 * Drive call; declined/non-Google → clean not-connected outcome) → download
 * temp bytes via GDriveProvider (it resolves the token from the Drive store
 * with a silent-refresh fallback) → atomic persist (Rust saveBookFile creates
 * row + temp/rename) → mark the catalog row imported (version bump) only
 * when a live user session exists → remove from downloadable on success.
 */
export async function downloadBook(bookId: string): Promise<void> {
  if (isDownloadingSet.has(bookId)) return;

  const book = downloadableBooks.find((b) => b.id === bookId);
  if (!book) {
    downloadErrorMsg = 'Book not found in downloadable list';
    return;
  }

  if (!(await isDriveAuthorized())) {
    const userId = authState.userId;
    const provider = await getIdentityProvider().catch(() => null);
    if (
      shouldShowDrivePrompt({
        driveEnabled: false,
        providerIsGoogle: provider === 'google',
        declinedForUser: readDriveDeclineMarker(userId),
        currentUser: userId,
      })
    ) {
      drivePromptPending = true;
    }
    downloadErrorMsg = DRIVE_NOT_CONNECTED_COPY;
    return;
  }

  isDownloadingSet.add(bookId);
  downloadErrorMsg = null;

  try {
    const gdrive = new GDriveProvider();
    // Synthetic catalog row so the verified import pipeline can be reused;
    // the resolved remote ref is ignored by the download closure below, which
    // uses the ORIGINAL Drive filename (safe for non-canonical names).
    const row: SupabaseUserBookRow = {
      id: book.id,
      userId: authState.userId ?? '',
      title: book.displayTitle,
      author: null,
      format: book.ext,
      filePath: null,
      coverUrl: null,
      description: null,
      totalPages: null,
      sourceDevice: null,
      importedAt: new Date().toISOString(),
      updatedAt: new Date().toISOString(),
      lifecycle: 'available',
      catalogVersion: 1,
      remoteName: book.remoteName,
    };
    const catalogSync = new SupabaseBookCatalogSync(row.userId);
    const result = await importRecoveredBook(row, {
      // book.remoteName is a non-null string (AvailableDriveBook) — the
      // synthetic row's optional remoteName would widen to `string | null`.
      download: () => gdrive.download(book.remoteName),
      persist: async (id, bytes, meta) => {
        // When the catalog row only carried the UUID as its title (book was
        // originally imported without extracted metadata), pull the REAL
        // title/author from the EPUB bytes before persisting. Falls back to
        // the catalog title on any parse failure — never breaks the download.
        let { title, author } = meta;
        if (book.ext === 'epub' && isFallbackTitle(title, id)) {
          try {
            const real = await extractEpubMetadataFromBytes(bytes);
            if (real.title) title = real.title;
            if (real.author) author = real.author;
          } catch {
            // keep catalog title
          }
        }
        await libraryPort.saveBookFile(id, Array.from(bytes), {
          title,
          author,
          format: meta.format,
        });
      },
      // Catalog upsert only when a live user session exists (no auth → no-op).
      markImported: row.userId
        ? (id, version) =>
            catalogSync.upsertBook({
              ...row,
              id,
              lifecycle: 'imported',
              catalogVersion: version,
              updatedAt: new Date().toISOString(),
            })
        : async () => {},
      findByHash: row.userId ? (hash) => catalogSync.findByHash(hash) : undefined,
    });

    if (result.outcome === 'imported' || result.outcome === 'already_imported') {
      removeDownloadableBook(bookId);
    } else if (result.error) {
      downloadErrorMsg = result.error.message;
    } else {
      downloadErrorMsg = 'Download failed unexpectedly';
    }
  } catch (e) {
    const msg = e instanceof Error ? e.message : 'Download failed unexpectedly';
    downloadErrorMsg = msg;
    // Keep book in downloadable list for later retry (per spec S5)
    throw e;
  } finally {
    isDownloadingSet.delete(bookId);
  }
}

// ─── Public API ───────────────────────────────────────────────────────

export const downloadableCatalog = {
  get books(): AvailableDriveBook[] {
    return downloadableBooks;
  },
  get count(): number {
    return downloadableBooks.length;
  },
  get isDownloading(): Set<string> {
    return isDownloadingSet;
  },
  get error(): string | null {
    return downloadErrorMsg;
  },
  /** A listing failure only — what the empty shelf shows. Never the "not connected" copy. */
  get loadError(): string | null {
    return loadErrorMsg;
  },
  get drivePromptPending(): boolean {
    return drivePromptPending;
  },
  setDownloadableBooks,
  clearDownloadableBooks,
  removeDownloadableBook,
  downloadBook,
  loadAvailableFromDrive,
  setDownloadError,
  clearDownloadError,
  clearLoadError,
  isDrivePromptPending,
  clearDrivePrompt,
  declineDrivePrompt,
};
