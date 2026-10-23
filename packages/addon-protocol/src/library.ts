/**
 * Library metadata: consulting a metadata addon about a book the reader
 * already owns. Pure, no I/O.
 *
 * Every `meta` request in the catalog paths addresses an item the addon itself
 * listed. A library book arrives with its own identity — the frozen
 * `source_item_id` prefix or an ISBN — so the same resource can describe a book
 * the reader imported years ago. Three decisions live here and nowhere else:
 * which ids may be asked about (`candidateIds`), which installed addons are
 * allowed to be asked (`shouldConsultLibraryMeta`), and what may be written
 * back (`mergeMeta`).
 *
 * Coverage is only as good as the identifiers: a book with no namespaced source
 * id and no ISBN yields no candidate and is skipped. Falling back to a title
 * match is a different capability with a different consent implication, and is
 * deliberately not implemented.
 */

import { ID_PREFIXES, TYPE_BOOK } from './constants';
import type { AddonManifestV3 } from './manifest';

const RESOURCE_META = 'meta';

/** `^\d{10}$` or `^\d{13}$`: an ISBN is digits, never a formatted string. */
const ISBN_DIGITS = /^(?:\d{10}|\d{13})$/;

const HTTPS_PREFIX = 'https://';

/** Book identity as stored on the library row (all three may be absent). */
export interface LibraryIdentity {
  /** `books.source_item_id`, e.g. `gutenberg:1342`. */
  sourceItemId?: string | null;
  /** `books.isbn13`, digits only. */
  isbn13?: string | null;
}

/** Non-empty after trimming, or `null`. */
function present(value: string | null | undefined): string | null {
  if (typeof value !== 'string') return null;
  const trimmed = value.trim();
  return trimmed.length === 0 ? null : trimmed;
}

function isFrozenPrefix(id: string): boolean {
  return (ID_PREFIXES as readonly string[]).some((prefix) => id.startsWith(prefix));
}

/**
 * The ids an addon may be asked about, most specific first. A namespaced source
 * item id is the identity the addon itself minted, so it wins outright; the ISBN
 * is the portable edition key used only when the source id is absent or sits
 * under a namespace this client does not know.
 */
export function candidateIds(identity: LibraryIdentity): string[] {
  const sourceItemId = present(identity.sourceItemId);
  if (sourceItemId !== null && isFrozenPrefix(sourceItemId)) return [sourceItemId];

  const isbn13 = present(identity.isbn13);
  if (isbn13 !== null && ISBN_DIGITS.test(isbn13)) return [`isbn:${isbn13}`];
  return [];
}

export interface ConsultOptions {
  /**
   * The descriptor's `enabled` flag. A disabled addon is installed but not
   * asked anything; omitted means "not known to be disabled".
   */
  enabled?: boolean;
}

/**
 * Whether one installed addon may be asked about any of these candidates.
 *
 * `meta` with no `idPrefixes` is NEVER consulted. An addon that answers `meta`
 * for any id it is handed would receive the reader's whole library one request
 * at a time; requiring the addon to declare which namespaces it understands is
 * what keeps a metadata authority from becoming a library-disclosure endpoint.
 * The prefixes are read per resource first, then from the top level.
 */
export function shouldConsultLibraryMeta(
  manifest: AddonManifestV3,
  candidates: readonly string[],
  opts: ConsultOptions = {},
): boolean {
  if (opts.enabled === false) return false;
  if (candidates.length === 0) return false;

  const resource = manifest.resources.find((entry) => entry.name === RESOURCE_META);
  if (resource === undefined) return false;
  if (resource.types !== undefined && !resource.types.includes(TYPE_BOOK)) return false;

  const prefixes =
    resource.idPrefixes !== undefined && resource.idPrefixes.length > 0
      ? resource.idPrefixes
      : (manifest.idPrefixes ?? []);
  if (prefixes.length === 0) return false;

  return candidates.some((id) => prefixes.some((prefix) => id.startsWith(prefix)));
}

/**
 * The fields a metadata response can fill, in this client's vocabulary. A
 * platform's own meta shape (the package `MetaPreview`, a shaped adapter, a
 * test fixture) satisfies it structurally.
 */
export interface LibraryMetaSource {
  description?: string | null;
  publisher?: string | null;
  /** Publication year or date; `releaseInfo` is the protocol's name for it. */
  publishedDate?: string | null;
  releaseInfo?: string | null;
  languages?: readonly string[] | null;
  subjects?: readonly string[] | null;
  poster?: string | null;
}

/**
 * The library row as the merge sees it: only the descriptive fields it may fill
 * plus the cover slot the caller owns. Any other member is carried through
 * untouched.
 */
export interface LibraryMetaBook {
  description?: string | null;
  publisher?: string | null;
  publishedDate?: string | null;
  language?: string | null;
  genre?: string | null;
  tags?: readonly string[] | null;
  /** Local path of the stored cover; `null` means the book has none yet. */
  coverPath?: string | null;
}

/** What the merge actually wrote: the caller persists exactly these keys. */
export interface LibraryMetaPatch {
  description?: string;
  publisher?: string;
  publishedDate?: string;
  language?: string;
  genre?: string;
  tags?: string[];
  /**
   * A cover the caller must download and persist. It is deliberately not a
   * `coverPath`: this function has no I/O, so the stored path does not exist
   * until the caller writes the bytes the same way import writes a cover.
   */
  coverUrl?: string;
}

function blankText(value: string | null | undefined): boolean {
  return present(value) === null;
}

function blankList(value: readonly string[] | null | undefined): boolean {
  if (value === null || value === undefined) return true;
  return value.every((entry) => blankText(entry));
}

function textList(value: readonly string[] | null | undefined): string[] {
  if (value === null || value === undefined) return [];
  return value.map((entry) => entry.trim()).filter((entry) => entry.length > 0);
}

/**
 * Fill-only merge of a metadata response onto an owned book.
 *
 * Only blank descriptive fields are written, so a response can never erase
 * metadata the reader already has, and identity, file facts, reading progress,
 * status, rating and provenance are never touched. A cover is signalled only
 * when the book has none and the response carries an https poster; persisting it
 * is the caller's job and must reuse the import cover path.
 */
export function mergeMeta<T extends LibraryMetaBook>(
  book: T,
  meta: LibraryMetaSource,
): { book: T; changed: LibraryMetaPatch } {
  const merged: T = { ...book };
  const changed: LibraryMetaPatch = {};

  const description = present(meta.description);
  if (description !== null && blankText(book.description)) {
    merged.description = description;
    changed.description = description;
  }

  const publisher = present(meta.publisher);
  if (publisher !== null && blankText(book.publisher)) {
    merged.publisher = publisher;
    changed.publisher = publisher;
  }

  const publishedDate = present(meta.publishedDate) ?? present(meta.releaseInfo);
  if (publishedDate !== null && blankText(book.publishedDate)) {
    merged.publishedDate = publishedDate;
    changed.publishedDate = publishedDate;
  }

  const language = textList(meta.languages)[0];
  if (language !== undefined && blankText(book.language)) {
    merged.language = language;
    changed.language = language;
  }

  const subjects = textList(meta.subjects);
  const genre = subjects[0];
  if (genre !== undefined && blankText(book.genre)) {
    merged.genre = genre;
    changed.genre = genre;
  }

  if (subjects.length > 0 && blankList(book.tags)) {
    merged.tags = subjects;
    changed.tags = subjects;
  }

  const poster = present(meta.poster);
  if (poster !== null && poster.startsWith(HTTPS_PREFIX) && blankText(book.coverPath)) {
    changed.coverUrl = poster;
  }

  return { book: merged, changed };
}
