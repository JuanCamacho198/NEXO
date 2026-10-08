/**
 * DISC-04c lazy per-book authority resolve.
 *
 * Search now runs on Google Books alone, so a result carries no Gutendex
 * download and no Open Library work/IA identity: the FREE Open Library and
 * Internet Archive links (and the in-app download) would never render. This
 * module buys them back lazily, once per book, from the two remaining
 * authorities, as *additional* enrichment on detail open.
 *
 * It is pure orchestration over injected lookups: no I/O, no cache, no UI. The
 * composite owns routing and caching (under the existing detail key); this
 * function only decides which authority to ask and how to merge the answers.
 * A failed or empty lookup contributes nothing — it is never an error state.
 */
import { normalizeMatchKey } from './mappers';
import type { CatalogBook } from './CatalogProvider';

/** Identity fields read from a fields-projected Open Library ISBN lookup. */
export interface OpenLibraryIdentity {
  workId: string | null;
  archiveId: string | null;
}

export interface BookAuthorityLookups {
  /** Gutendex keyword search (plain `title author`, never a colon operator). */
  gutendex(query: string): Promise<CatalogBook[]>;
  /** Open Library ISBN identity lookup (fields-projected). */
  openLibraryByIsbn(isbn: string): Promise<OpenLibraryIdentity | null>;
}

/** Plain `title author` Gutendex query — a colon operator is never emitted. */
export function authorityQuery(book: CatalogBook): string {
  const authors = book.authors.join(' ').trim();
  const title = book.title.trim();
  return authors === '' ? title : `${title} ${authors}`;
}

/** First present, non-blank ISBN-13/10 on the book; null when it has none. */
export function isbnOf(book: CatalogBook): string | null {
  for (const candidate of [book.isbn13, book.isbn10]) {
    if (typeof candidate === 'string' && candidate.trim() !== '') return candidate.trim();
  }
  return null;
}

/**
 * True once the book already carries any resolved authority. Used as the
 * cache fast-path: a book that already has a download, a public-domain flag,
 * or an Open Library / Internet Archive identity is never resolved again.
 */
export function hasResolvedAuthorities(book: CatalogBook): boolean {
  return (
    book.downloadUrl != null ||
    book.isPublicDomain != null ||
    blank(book.openLibraryWorkId) === false ||
    blank(book.internetArchiveId) === false
  );
}

function blank(value: string | null | undefined): boolean {
  return value == null || value.trim() === '';
}

/** Gutendex is only asked while the book has neither a download nor a PD flag. */
function needsGutendex(book: CatalogBook): boolean {
  return book.downloadUrl == null && book.isPublicDomain == null;
}

/** Open Library is only asked while the book lacks a work id and has an ISBN. */
function needsOpenLibrary(book: CatalogBook): boolean {
  return blank(book.openLibraryWorkId);
}

/**
 * The public-domain Gutendex edition matching `book`, or null. Matching reuses
 * the composite merge key (title + sorted author words) so Gutendex's ranked
 * search can never attach an unrelated PD edition's download.
 */
function matchingGutendexEdition(book: CatalogBook, results: CatalogBook[]): CatalogBook | null {
  const key = normalizeMatchKey(book.title, book.authors);
  return (
    results.find(
      (candidate) =>
        candidate.isPublicDomain === true &&
        normalizeMatchKey(candidate.title, candidate.authors) === key,
    ) ?? null
  );
}

/**
 * Resolve a book's authorities, best-effort and per authority. A book without
 * an ISBN never asks Open Library (a skip, not a failure); a book that already
 * has an authority is not asked again. Returns `book` unchanged when nothing
 * resolves, so a caller writes the cache only on a real change.
 */
export async function resolveBookAuthorities(
  book: CatalogBook,
  lookups: BookAuthorityLookups,
): Promise<CatalogBook> {
  let next = book;

  if (needsGutendex(book)) {
    try {
      const edition = matchingGutendexEdition(book, await lookups.gutendex(authorityQuery(book)));
      if (edition) {
        next = {
          ...next,
          downloadUrl: next.downloadUrl ?? edition.downloadUrl ?? null,
          isPublicDomain: true,
        };
      }
    } catch {
      // Best-effort: an unreachable Gutendex leaves the seed untouched.
    }
  }

  const isbn = isbnOf(book);
  if (isbn !== null && needsOpenLibrary(book)) {
    try {
      const identity = await lookups.openLibraryByIsbn(isbn);
      if (identity) {
        next = {
          ...next,
          openLibraryWorkId: next.openLibraryWorkId ?? identity.workId ?? null,
          internetArchiveId: next.internetArchiveId ?? identity.archiveId ?? null,
        };
      }
    } catch {
      // Best-effort: an unreachable Open Library leaves the seed untouched.
    }
  }

  return next;
}
