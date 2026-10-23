/**
 * Legal-access resolver (WU3 port of Android `BookAccessResolver`): maps a
 * `CatalogBook` identity to grouped web-reading options. Pure string building
 * over the stdlib — no I/O, no Tauri dependency, fully testable offline.
 *
 * Rules (Android parity):
 * - Groups are exactly FREE / BUY / SUBSCRIBE; there is intentionally no
 *   lending branch.
 * - Every emitted link is an `https` web link; anything else is dropped.
 * - Only `isPublicDomain === true` books with an `https` download URL resolve
 *   to an in-app download path (`canDownloadInApp`); everything else is
 *   external-open only.
 * - Books without identity resolve to generic web-search links.
 */
import type { MessageKey } from '$lib/shared/i18n/messages.en';
import type { CatalogBook } from './CatalogProvider';

export type AccessGroup = 'FREE' | 'BUY' | 'SUBSCRIBE';

/**
 * Legal terms of one option, mirroring the protocol's
 * `behaviorHints.accessType` verbatim (`StreamAccessType` in
 * `@nexo/addon-protocol`) so no adapter has to invent a second vocabulary.
 * A built-in web link is neither public domain nor a loan, so it is `unknown`.
 */
export type AccessType = 'public-domain' | 'borrow' | 'purchase' | 'unknown';

/** One external web option; `titleKey` is localized at the render boundary. */
export interface AccessOption {
  group: AccessGroup;
  titleKey: MessageKey;
  url: string;
  /** True only for the public-domain in-app download. */
  opensInApp: boolean;
  /**
   * Addon stream provenance (additive): the browser-openable form of `url`
   * when the stream carried one, so the sheet can tell "open here" from
   * "open in the browser". Absent for links built by this resolver.
   */
  externalUrl?: string;
  /** Addon stream provenance (additive); absent for links built here. */
  accessType?: AccessType;
}

/**
 * Resolved access for `bookId`. `downloadUrl` is non-null only when
 * `canDownloadInApp` is true (public-domain `https` download).
 */
export interface LegalAccess {
  bookId: string;
  canDownloadInApp: boolean;
  downloadUrl: string | null;
  options: AccessOption[];
  /**
   * True when the local library already holds this item. Only a producer that
   * can consult the local catalog (the addon stream path, by `sourceItemId`)
   * sets it; this pure, I/O-free resolver leaves it absent, which reads as
   * "not known to be owned".
   */
  owned?: boolean;
}

const HTTPS_PREFIX = 'https://';
const OPEN_LIBRARY_BASE = 'https://openlibrary.org';
const INTERNET_ARCHIVE_BASE = 'https://archive.org/details/';
const GOOGLE_BOOKS_PREVIEW_BASE = 'https://books.google.com/books?id=';
const GUTENBERG_EBOOK_BASE = 'https://www.gutenberg.org/ebooks/';
const WEB_SEARCH_BASE = 'https://www.google.com/search?q=';
const GUTENDEX_ID_PREFIX = 'gutendex:';
const MIN_GUTENBERG_ID = 1;
const MAX_AUTHORS_IN_QUERY = 3;
const QUERY_AUTHOR_SEPARATOR = ', ';

const BUY_KEYWORD = 'buy book';
const SUBSCRIBE_KEYWORD = 'subscription library';
const ISBN_KEYWORD = 'isbn';

/** True only for non-blank `https://` URLs. */
export function isHttpsUrl(url: string | null | undefined): boolean {
  return url != null && url.trim() !== '' && url.startsWith(HTTPS_PREFIX);
}

/** Spaces encode as %20 (desktop parity), never `+`. */
function encodeQuery(value: string): string {
  return encodeURIComponent(value);
}

function describeQuery(book: CatalogBook): string {
  const authors = book.authors.slice(0, MAX_AUTHORS_IN_QUERY).join(QUERY_AUTHOR_SEPARATOR);
  return authors.trim() === '' ? book.title : `${book.title} ${authors}`;
}

function gutendexIdOf(book: CatalogBook): number | null {
  if (!book.id.startsWith(GUTENDEX_ID_PREFIX)) return null;
  const parsed = Number(book.id.slice(GUTENDEX_ID_PREFIX.length));
  return Number.isInteger(parsed) && parsed >= MIN_GUTENBERG_ID ? parsed : null;
}

/**
 * Resolve grouped legal access for `book`. Never throws for malformed or
 * missing identity — unknown fields simply yield fewer specific links plus the
 * generic web-search fallbacks.
 */
export function resolveAccess(book: CatalogBook): LegalAccess {
  const canDownloadInApp = book.isPublicDomain === true && isHttpsUrl(book.downloadUrl);
  const downloadUrl = canDownloadInApp ? book.downloadUrl : null;
  const query = describeQuery(book);
  const isbn = book.isbn13 ?? book.isbn10 ?? null;
  const options: AccessOption[] = [];

  if (canDownloadInApp && downloadUrl !== null) {
    options.push({
      group: 'FREE',
      titleKey: 'discover.accessDownload',
      url: downloadUrl,
      opensInApp: true,
    });
  }
  const workId = book.openLibraryWorkId?.trim();
  if (workId != null && workId !== '') {
    const path = workId.startsWith('/') ? workId : `/${workId}`;
    options.push({
      group: 'FREE',
      titleKey: 'discover.accessOpenLibrary',
      url: OPEN_LIBRARY_BASE + path,
      opensInApp: false,
    });
  }
  const archiveId = book.internetArchiveId?.trim();
  if (archiveId != null && archiveId !== '') {
    options.push({
      group: 'FREE',
      titleKey: 'discover.accessInternetArchive',
      url: INTERNET_ARCHIVE_BASE + encodeQuery(archiveId),
      opensInApp: false,
    });
  }
  const googleId = book.googleBooksId?.trim();
  if (googleId != null && googleId !== '') {
    options.push({
      group: 'FREE',
      titleKey: 'discover.accessGooglePreview',
      url: GOOGLE_BOOKS_PREVIEW_BASE + encodeQuery(googleId),
      opensInApp: false,
    });
  }
  const gutenbergId = gutendexIdOf(book);
  if (gutenbergId !== null) {
    options.push({
      group: 'FREE',
      titleKey: 'discover.accessGutenberg',
      url: GUTENBERG_EBOOK_BASE + String(gutenbergId),
      opensInApp: false,
    });
  }
  if (isbn != null && isbn.trim() !== '') {
    options.push({
      group: 'BUY',
      titleKey: 'discover.accessBuy',
      url: WEB_SEARCH_BASE + encodeQuery(`${ISBN_KEYWORD} ${isbn} ${BUY_KEYWORD}`),
      opensInApp: false,
    });
    options.push({
      group: 'SUBSCRIBE',
      titleKey: 'discover.accessSubscribe',
      url: WEB_SEARCH_BASE + encodeQuery(`${ISBN_KEYWORD} ${isbn} ${SUBSCRIBE_KEYWORD}`),
      opensInApp: false,
    });
  } else {
    options.push({
      group: 'BUY',
      titleKey: 'discover.accessBuy',
      url: WEB_SEARCH_BASE + encodeQuery(`${query} ${BUY_KEYWORD}`),
      opensInApp: false,
    });
    options.push({
      group: 'SUBSCRIBE',
      titleKey: 'discover.accessSubscribe',
      url: WEB_SEARCH_BASE + encodeQuery(`${query} ${SUBSCRIBE_KEYWORD}`),
      opensInApp: false,
    });
  }
  const hasFree = options.some((option) => option.group === 'FREE');
  if (!hasFree) {
    options.unshift({
      group: 'FREE',
      titleKey: 'discover.accessWebSearch',
      url: WEB_SEARCH_BASE + encodeQuery(query),
      opensInApp: false,
    });
  }

  return {
    bookId: book.id,
    canDownloadInApp,
    downloadUrl,
    options: options.filter((option) => isHttpsUrl(option.url)),
  };
}
