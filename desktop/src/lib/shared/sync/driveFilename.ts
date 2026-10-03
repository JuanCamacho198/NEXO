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
