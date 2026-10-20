/**
 * Canonical Drive filename (FR-06). This is the single desktop implementation,
 * mirrored byte-for-byte by the Rust (`desktop/src-tauri/src/filename.rs`) and
 * Kotlin (`android/.../data/sync/DriveFilename.kt`) implementations. All three
 * are pinned by the SAME shared contract,
 * `packages/drive-filename-fixtures/fixtures.json`.
 *
 * Rules, applied in order: NFKC-normalize, lowercase, keep `[a-z0-9_-]`, bound
 * to {@link MAX_BOOK_ID_CHARS}, fall back to {@link FALLBACK_BOOK_ID} when empty
 * or fully filtered, then guard reserved Windows device names (`con` -> `con_`).
 * Lowercase is applied BEFORE the reserved-name guard, so `CON` and `con` both
 * map to `con_`. Dropping (never replacing) illegal characters cannot inject a
 * character that collides with a genuine id character, and lowercase closes the
 * NTFS case-insensitive collision class. The mapping is deterministic and
 * idempotent: re-canonicalizing an already-canonical stem is a no-op.
 */

export const MAX_BOOK_ID_CHARS = 120;
export const FALLBACK_BOOK_ID = 'book';
export const DEFAULT_EXTENSION = 'epub';
const MAX_EXT_CHARS = 5;

const RESERVED_DEVICE_NAMES: ReadonlySet<string> = new Set([
  'CON',
  'PRN',
  'AUX',
  'NUL',
  ...Array.from({ length: 9 }, (_, index) => `COM${index + 1}`),
  ...Array.from({ length: 9 }, (_, index) => `LPT${index + 1}`),
]);

function isCanonicalChar(ch: string): boolean {
  return (ch >= 'a' && ch <= 'z') || (ch >= '0' && ch <= '9') || ch === '-' || ch === '_';
}

/** Canonical, Windows-safe stem for a raw book id (see module docs). */
export function canonicalStem(rawBookId: string): string {
  const filtered = Array.from(rawBookId.normalize('NFKC').toLowerCase())
    .filter(isCanonicalChar)
    .slice(0, MAX_BOOK_ID_CHARS)
    .join('');
  const stem = filtered.length === 0 ? FALLBACK_BOOK_ID : filtered;
  return RESERVED_DEVICE_NAMES.has(stem.toUpperCase()) ? `${stem}_` : stem;
}

/** `[a-z0-9]{1,5}` extension, defaulting to {@link DEFAULT_EXTENSION}. */
export function canonicalExtension(raw: string | null | undefined): string {
  const filtered = (raw ?? '')
    .toLowerCase()
    .split('')
    .filter((ch) => (ch >= 'a' && ch <= 'z') || (ch >= '0' && ch <= '9'))
    .join('');
  return filtered.length === 0 || filtered.length > MAX_EXT_CHARS ? DEFAULT_EXTENSION : filtered;
}

/** Canonical Drive object name: canonical stem plus canonical extension. */
export function canonicalDriveObjectName(bookId: string, extension?: string | null): string {
  return `${canonicalStem(bookId)}.${canonicalExtension(extension)}`;
}

/**
 * Separator between the human-readable title and the identity-bearing book id in
 * a readable Drive object name (`Title -- <uuid>.epub`). Chosen because it cannot
 * appear in a canonical stem (`[a-z0-9_-]` only) and survives Drive/NTFS intact.
 */
export const READABLE_NAME_SEPARATOR = ' -- ';

/** Max characters kept from the sanitized title, so the composed name stays short. */
export const MAX_TITLE_CHARS = 80;

/**
 * Sanitizes a book title into a filename-safe segment. Replaces the
 * Windows/Drive reserved characters `/ \ : * ? " < > |` and control characters
 * with a space, collapses runs of whitespace, and trims leading/trailing dots
 * and spaces (NTFS rejects a trailing dot or space). Returns an empty string
 * when nothing usable remains, so the caller can fall back to the id-only name.
 * Unlike a canonical stem this is NOT lowercased and keeps Unicode letters: it
 * is meant to be read.
 *
 * The 80-character bound counts Unicode code points, not UTF-16 units. Slicing
 * UTF-16 units can split a surrogate pair and leave a lone surrogate in the
 * name, which produces a different (and broken) filename per platform; counting
 * code points matches the Rust and Kotlin implementations and `Array.from`.
 */
export function sanitizeTitleForFilename(title: string): string {
  const cleaned = Array.from(title.normalize('NFKC'))
    .map((ch) => {
      const code = ch.codePointAt(0) ?? 0;
      if (code < 0x20 || code === 0x7f) return ' ';
      return '<>:"/\\|?*'.includes(ch) ? ' ' : ch;
    })
    .join('')
    .replace(/\s+/g, ' ')
    .replace(/^[.\s]+|[.\s]+$/g, '');
  return Array.from(cleaned).slice(0, MAX_TITLE_CHARS).join('').trim();
}

/**
 * Readable Drive object name for a NEW upload: `<sanitized title> -- <id>.<ext>`.
 * The full book id is retained after the separator so identity is stable
 * (`parseReadableBookName` recovers it) and two devices uploading concurrently
 * for the same book cannot collide on the name. When the title is empty after
 * sanitization the id-only canonical name is returned, so a nameless book never
 * produces a dangling separator. This is for NEW objects only; existing
 * id-only files are never renamed.
 */
export function readableDriveObjectName(
  title: string,
  bookId: string,
  extension?: string | null,
): string {
  const safeTitle = sanitizeTitleForFilename(title);
  const idPart = canonicalStem(bookId);
  const ext = canonicalExtension(extension);
  return safeTitle.length === 0
    ? `${idPart}.${ext}`
    : `${safeTitle}${READABLE_NAME_SEPARATOR}${idPart}.${ext}`;
}

/**
 * Recovers the identity-bearing book id from a readable object name stem
 * (`Title -- <id>` -> `<id>`). Returns null when the stem carries no separator,
 * so an id-only or title-only stem is not mistaken for a readable name. Splits
 * on the LAST separator so a title that itself contains ` -- ` still resolves.
 */
export function parseReadableStem(stem: string): string | null {
  const idx = stem.lastIndexOf(READABLE_NAME_SEPARATOR);
  if (idx < 0) return null;
  const idPart = stem.slice(idx + READABLE_NAME_SEPARATOR.length);
  return idPart.length === 0 ? null : stripDriveCollisionSuffix(idPart);
}

/** Drive's collision suffix (` (1)`) on an id-only name; kept out of the readable path. */
const ID_COLLISION_SUFFIX_RE = /\s+\((\d+)\)$/;

/** Strips a trailing Drive collision suffix without importing the contract module. */
function stripDriveCollisionSuffix(bookId: string): string {
  return bookId.replace(ID_COLLISION_SUFFIX_RE, '');
}

/**
 * Branded canonical stem (FR-06). Constructible only through
 * {@link canonicalName}; the write guard's upload entry (FR-08) accepts this
 * type so an uncanonicalized raw string cannot reach a Drive upload.
 */
declare const canonicalNameBrand: unique symbol;
export type CanonicalName = string & { readonly [canonicalNameBrand]: true };

/** Canonical stem wrapped in the {@link CanonicalName} newtype. */
export function canonicalName(rawBookId: string): CanonicalName {
  return canonicalStem(rawBookId) as CanonicalName;
}

/**
 * Canonical Drive object name from an already-canonical stem. Equivalent to
 * {@link canonicalDriveObjectName} but keeps the guard's entry type-checked,
 * since only a {@link CanonicalName} can be passed here.
 */
export function canonicalObjectName(book: CanonicalName, extension?: string | null): string {
  return `${book}.${canonicalExtension(extension)}`;
}

function dropFilter(raw: string): string {
  return raw
    .split('')
    .filter(
      (ch) =>
        (ch >= 'a' && ch <= 'z') ||
        (ch >= 'A' && ch <= 'Z') ||
        (ch >= '0' && ch <= '9') ||
        ch === '-' ||
        ch === '_',
    )
    .join('');
}

function dashFilter(raw: string): string {
  const replaced = raw
    .toLowerCase()
    .replace(/[^a-z0-9_-]/g, '-')
    .replace(/^-+|-+$/g, '');
  return replaced.length === 0 ? 'unknown' : replaced;
}

function underscoreFilter(raw: string): string {
  return raw.replace(/[^A-Za-z0-9._-]/g, '_');
}

/**
 * Every stem a raw book id may have been stored under by a pre-WU4 client:
 * canonical, the desktop drop form (case-preserved and lowercased), the Android
 * `sanitizeIdToken` dash form, the Android `sanitize` underscore form, and the
 * raw unsanitized id (colon-bearing desktop names). The reconciler matches
 * against this set; it never deletes a match.
 */
export function legacyForms(rawBookId: string): Set<string> {
  const forms = new Set<string>();
  const add = (value: string): void => {
    if (value.length > 0) forms.add(value);
  };
  add(canonicalStem(rawBookId));
  add(dropFilter(rawBookId));
  add(dropFilter(rawBookId).toLowerCase());
  add(dashFilter(rawBookId));
  add(underscoreFilter(rawBookId));
  add(rawBookId);
  return forms;
}

/**
 * Canonical `_state.json` name for a book id, consumed by the desktop Drive
 * state sync so it never writes the raw colon-bearing `${bookId}_state.json`
 * an NTFS/Drive segment cannot carry.
 */
export function canonicalStateName(rawBookId: string): string {
  return `${canonicalStem(rawBookId)}_state.json`;
}

/**
 * Legacy `_state.json` names for a book id, including the raw colon-bearing
 * desktop form (`gutendex:2701_state.json`).
 */
export function legacyStateNames(rawBookId: string): Set<string> {
  return new Set([canonicalStateName(rawBookId), `${rawBookId}_state.json`]);
}
