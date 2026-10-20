import { canonicalDriveObjectName, parseReadableStem } from '$lib/shared/sync/driveFilename';

export const DRIVE_PROVIDER = 'google_drive' as const;
export const DRIVE_SCOPE = 'https://www.googleapis.com/auth/drive.file' as const;
export const DRIVE_BOOKS_PATH = 'Nexo/books' as const;
export const PROTOCOL_VERSION = 1 as const;
export type Lifecycle = 'available' | 'imported' | 'unavailable' | 'deleted';
export type SyncErrorCode =
  | 'AUTH_REQUIRED'
  | 'AUTH_EXPIRED'
  | 'PERMISSION_DENIED'
  | 'DRIVE_NOT_CONNECTED'
  | 'REMOTE_NOT_FOUND'
  | 'HASH_MISMATCH'
  | 'CONFLICT'
  | 'UNAVAILABLE'
  | 'COVER_FAILED';
export interface RemoteReference {
  provider: typeof DRIVE_PROVIDER;
  fileId: string | null;
  canonicalPath: string;
  fileName: string;
  protocolVersion: typeof PROTOCOL_VERSION;
}
export interface CatalogRow {
  userId: string;
  bookId: string;
  format: string;
  contentHash: string | null;
  lifecycle: Lifecycle;
  remoteRef: RemoteReference | null;
  catalogVersion: number;
}
export interface SyncError {
  code: SyncErrorCode;
  message: string;
  retryable: boolean;
  correlationId: string;
  bookId?: string;
}
/** Stable COVER_FAILED error for cover upload/render failures; cover failures never block book import. */
export function coverError(correlationId: string, bookId?: string): SyncError {
  return {
    code: 'COVER_FAILED',
    message: 'Cover upload or render failed',
    retryable: true,
    correlationId,
    bookId,
  };
}
/**
 * Typed SyncError factory (Error instance carrying the stable code + retryable flag).
 * Messages are always redacted (DTL-3): tokens, JWTs, and long hex hashes never reach logs or the UI.
 */
export function syncError(
  code: SyncErrorCode,
  message: string,
  retryable = false,
): Error & SyncError {
  const err = new Error(redactLogLine(message)) as Error & SyncError;
  err.code = code;
  err.retryable = retryable;
  err.correlationId = crypto.randomUUID();
  return err;
}
/**
 * Canonical Drive book filename. Delegates to the one implementation in
 * `$lib/shared/sync/driveFilename` (no private sanitizer copy): for
 * `gutendex:2701` the name is `gutendex2701.epub`, not the raw colon-bearing
 * `gutendex:2701.epub` an NTFS/Drive segment cannot carry.
 */
export function canonicalBookName(bookId: string, format: string): string {
  return canonicalDriveObjectName(bookId, format);
}
/**
 * Drive's collision suffix: when two uploads of the same name race, Drive
 * renames the loser to `name (1).ext`, then `name (2).ext`, and so on. It is
 * the visible fingerprint of the same accident that produces a duplicate
 * `Nexo (1)` root folder, so a book listed twice under `X.pdf` and
 * `X (1).pdf` is ONE book, not two.
 */
const DRIVE_COLLISION_SUFFIX_RE = /\s+\((\d+)\)$/;

/**
 * Strips a trailing Drive collision suffix (` (1)`, ` (2)`, …) from a book id.
 * Applied to the id only, never to a name that legitimately ends in digits: the
 * suffix is only recognised when it is a parenthesised integer.
 */
export function stripDriveCollisionSuffix(bookId: string): string {
  return bookId.replace(DRIVE_COLLISION_SUFFIX_RE, '');
}

/**
 * Inverse of `canonicalBookName` (and of the readable new-file format): parse a
 * Drive filename into `{ bookId, ext }`. Returns null for sync-state files
 * (`_state.json`, bare `state.json`), empty names, names without a dot,
 * trailing-dot names, and any `.json` file (the Books folder only carries book
 * binaries + JSON state). Splits on the LAST dot (bookIds are UUIDs without
 * dots, so the last dot is always the extension separator) and lowercases the
 * extension.
 *
 * Two name shapes resolve to the SAME bookId:
 * - id-only, the original format (`<id>.epub`);
 * - readable, the new-upload format (`<title> -- <id>.epub`), where the id after
 *   the last ` -- ` is recovered so a readable file and its id-only twin dedupe
 *   to one book.
 *
 * A trailing Drive collision suffix on the id (`X (1).pdf`) is stripped in both
 * shapes, so the same book uploaded twice under two names resolves to ONE
 * bookId and dedupes against its unsuffixed twin. Without this the duplicate
 * listed as a separate book with a `(1)`-suffixed id.
 */
export function parseCanonicalBookName(fileName: string): { bookId: string; ext: string } | null {
  if (!fileName || fileName.endsWith('_state.json') || fileName.endsWith('.json')) return null;
  const idx = fileName.lastIndexOf('.');
  if (idx <= 0 || idx === fileName.length - 1) return null;
  const stem = fileName.slice(0, idx);
  // Readable form first: the identity is the id after the last ` -- `.
  const readableId = parseReadableStem(stem);
  const bookId = readableId ?? stripDriveCollisionSuffix(stem);
  if (bookId.length === 0) return null;
  return { bookId, ext: fileName.slice(idx + 1).toLowerCase() };
}
export function canonicalBookPath(bookId: string, format: string): string {
  return `${DRIVE_BOOKS_PATH}/${canonicalBookName(bookId, format)}`;
}
export async function reconcileLegacyReference(
  stableId: string | null,
  candidates: Array<{ fileId: string; name: string; download: () => Promise<Uint8Array> }>,
  bookId: string,
  format: string,
  expectedHash: string | null,
): Promise<RemoteReference | null> {
  const canonicalName = canonicalBookName(bookId, format),
    expected = expectedHash?.replace(/^sha256:/, '');
  const match =
    (stableId ? candidates.find((f) => f.fileId === stableId) : undefined) ??
    candidates.find((f) => f.name === canonicalName);
  const hash = async (file: (typeof candidates)[number]): Promise<string | null> => {
    try {
      const bytes = await file.download();
      // digest() requires an ArrayBuffer-backed view; Uint8Array<ArrayBufferLike> may be
      // SharedArrayBuffer-backed. Runtime data is a plain ArrayBuffer, so this cast is safe.
      const d = await crypto.subtle.digest('SHA-256', bytes as BufferSource);
      return [...new Uint8Array(d)].map((b) => b.toString(16).padStart(2, '0')).join('');
    } catch {
      return null;
    }
  };
  let resolved = match;
  if (resolved) {
    if (expected && (await hash(resolved)) !== expected) return null;
  } else if (expected) {
    const verified = await Promise.all(candidates.map(async (f) => [f, await hash(f)] as const));
    resolved = verified.find(([, h]) => h === expected)?.[0];
  }
  return resolved
    ? {
        provider: DRIVE_PROVIDER,
        fileId: resolved.fileId,
        canonicalPath: canonicalBookPath(bookId, format),
        fileName: canonicalName,
        protocolVersion: PROTOCOL_VERSION,
      }
    : null;
}
export function mergeCatalogVersion<T extends { catalogVersion: number; lifecycle: Lifecycle }>(
  current: T,
  incoming: T,
): T {
  return incoming.catalogVersion >= current.catalogVersion ? incoming : current;
}
/** Deterministic catalog conflict winner: (catalog_version, updated_at, id) lexicographic. */
export function catalogRowWinner<
  T extends { catalogVersion?: number; updatedAt: string; id: string },
>(current: T, incoming: T): T {
  const curVersion = current.catalogVersion ?? 0;
  const incVersion = incoming.catalogVersion ?? 0;
  if (incVersion !== curVersion) return incVersion > curVersion ? incoming : current;
  if (incoming.updatedAt !== current.updatedAt)
    return incoming.updatedAt > current.updatedAt ? incoming : current;
  return incoming.id > current.id ? incoming : current;
}
/** Redacted observability: structured code/correlation/latency only; never tokens, binaries, or user paths. */
export function redactLogLine(line: string): string {
  return line
    .replace(/(token|authorization|bearer|password|refresh_token)=([^&\s]+)/gi, '$1=[REDACTED]')
    .replace(/eyJ[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+/g, '[JWT_REDACTED]')
    .replace(/[0-9a-f]{64}/gi, '[HASH_REDACTED]');
}
