/**
 * Normalizers, PD predicates, download resolution, merge and pagination math.
 * Pure functions — fully testable offline with fixture JSON.
 */
import { catalogError } from './errors';
import {
  BUILTIN_GUTENDEX,
  BUILTIN_GOOGLEBOOKS,
  BUILTIN_OPENLIBRARY,
  BUILTIN_STANDARD_EBOOKS,
} from './CatalogProvider';
import type { CatalogBook, PagedResult } from './CatalogProvider';

export interface GutendexAuthor {
  name: string;
}
export interface GutendexRecord {
  id: number;
  title: string;
  authors?: GutendexAuthor[];
  copyright?: boolean | null;
  languages?: string[];
  subjects?: string[];
  formats?: Record<string, string>;
  summaries?: string[];
}

export interface OpenLibraryDoc {
  key: string;
  title: string;
  author_name?: string[];
  cover_i?: number;
  ebook_access?: string;
  language?: string[];
  subject?: string[];
  isbn?: string[];
  ia?: string[];
}

/** Gutendex record is public-domain only when `copyright === false`. */
export function isGutendexPublicDomain(record: GutendexRecord): boolean {
  return record.copyright === false;
}

/** OL doc is usable only with full public ebook access (never borrowable). */
export function isOpenLibraryPublic(doc: OpenLibraryDoc): boolean {
  return doc.ebook_access === 'public';
}

export function openLibraryCoverUrl(coverId: number | undefined): string | null {
  if (typeof coverId !== 'number') return null;
  return `https://covers.openlibrary.org/b/id/${coverId}-M.jpg`;
}

/**
 * Project Gutenberg cover derived from the record id (no mirroring, no HTML
 * scraping). Mirrors Android `gutenbergCoverUrl`.
 */
export function gutenbergCoverUrl(gutenbergId: number): string | null {
  if (!Number.isInteger(gutenbergId) || gutenbergId < 1) return null;
  return `https://www.gutenberg.org/cache/epub/${gutenbergId}/pg${gutenbergId}.cover.medium.jpg`;
}

/** Map a Gutendex record; null when in-copyright (excluded, never surfaced). */
export function mapGutendexBook(record: GutendexRecord): CatalogBook | null {
  if (!isGutendexPublicDomain(record)) return null;
  const formats = record.formats ?? {};
  let downloadUrl: string | null = null;
  try {
    downloadUrl = resolveDownloadUrl(formats, true);
  } catch {
    downloadUrl = null;
  }
  const description =
    (record.summaries ?? []).join('\n\n').trim() === ''
      ? undefined
      : (record.summaries ?? []).join('\n\n');
  return {
    id: `gutendex:${record.id}`,
    provider: BUILTIN_GUTENDEX,
    title: record.title,
    authors: (record.authors ?? []).map((a) => a.name),
    coverUrl: gutenbergCoverUrl(record.id),
    languages: record.languages ?? [],
    subjects: record.subjects ?? [],
    downloadUrl,
    description,
    formats,
    isPublicDomain: record.copyright == null ? null : !record.copyright,
  };
}

/** Map an OL doc; null when borrow-restricted or non-public. */
export function mapOpenLibraryDoc(doc: OpenLibraryDoc): CatalogBook | null {
  if (!isOpenLibraryPublic(doc)) return null;
  return {
    id: `openlibrary:${doc.key}`,
    provider: BUILTIN_OPENLIBRARY,
    title: doc.title,
    authors: doc.author_name ?? [],
    coverUrl: openLibraryCoverUrl(doc.cover_i),
    languages: doc.language ?? [],
    subjects: (doc.subject ?? []).slice(0, 8),
    downloadUrl: null,
    isbn13: firstIsbn13(doc.isbn ?? []),
    isbn10: firstIsbn10(doc.isbn ?? []),
    openLibraryWorkId: doc.key.trim() === '' ? null : doc.key,
    internetArchiveId: (doc.ia ?? [])[0] ?? null,
  };
}

const EPUB_MIMES = ['application/epub+zip', 'application/x-mobipocket-ebook'];
const FALLBACK_MIMES = ['text/plain', 'text/html', 'application/pdf'];

function digitsOf(value: string): string {
  return value.replace(/\D/g, '');
}

/** First ISBN with 13 digits (dashes/spaces ignored); null when absent. */
export function firstIsbn13(isbns: string[]): string | null {
  const found = isbns.find((v) => digitsOf(v).length === 13);
  return found == null ? null : digitsOf(found);
}

/** First ISBN with 10 digits (dashes/spaces ignored); null when absent. */
export function firstIsbn10(isbns: string[]): string | null {
  const found = isbns.find((v) => digitsOf(v).length === 10);
  return found == null ? null : digitsOf(found);
}

/**
 * Deterministic download priority: EPUB-first (or fallback-first when
 * `preferEpub` is false). First `https://` match wins, else UNAVAILABLE_DOWNLOAD.
 */
export function resolveDownloadUrl(formats: Record<string, string>, preferEpub: boolean): string {
  const order = preferEpub
    ? [...EPUB_MIMES, ...FALLBACK_MIMES]
    : [...FALLBACK_MIMES, ...EPUB_MIMES];
  for (const mime of order) {
    const url = formats[mime];
    if (typeof url === 'string' && url.startsWith('https://')) return url;
  }
  throw catalogError('UNAVAILABLE_DOWNLOAD', 'no usable format');
}

/** Normalized title+author key used to pair an enrichment doc with a primary book. */
export function normalizeMatchKey(title: string, authors: string[]): string {
  const norm = (s: string): string =>
    s
      .toLowerCase()
      .normalize('NFD')
      .replace(/[̀-ͯ]/g, '')
      .replace(/[^a-z0-9]+/g, ' ')
      .trim();
  // Author names arrive in different orders per source ("Austen, Jane" vs
  // "Jane Austen"), so compare sorted word bags instead of raw strings.
  const authorKey = (s: string): string => norm(s).split(' ').filter(Boolean).sort().join(' ');
  return `${norm(title)}|${authors.map(authorKey).sort().join(',')}`;
}

/**
 * Merge sources: the left-hand (primary) provider wins every field it
 * supplies; an empty primary `coverUrl` is filled from the matching
 * enrichment doc. Identity gaps (isbn13/isbn10/work key/IA id) are filled from
 * the same match — no parallel identity model. Under the default composite
 * fan-out the primary is Google Books (or Gutendex in a keyless build) and the
 * enrichment list is what remains of Open Library.
 */
export function mergeResults(
  primaryBooks: CatalogBook[],
  enrichmentBooks: CatalogBook[],
): CatalogBook[] {
  const enrichmentByKey = new Map(
    enrichmentBooks.map((b) => [normalizeMatchKey(b.title, b.authors), b]),
  );
  const usedKeys = new Set<string>();
  const merged = primaryBooks.map((g) => {
    const key = normalizeMatchKey(g.title, g.authors);
    const match = enrichmentByKey.get(key);
    if (!match) return g;
    usedKeys.add(key);
    return {
      ...g,
      coverUrl: g.coverUrl ?? match.coverUrl,
      isbn13: g.isbn13 ?? match.isbn13 ?? null,
      isbn10: g.isbn10 ?? match.isbn10 ?? null,
      openLibraryWorkId: g.openLibraryWorkId ?? match.openLibraryWorkId ?? null,
      internetArchiveId: g.internetArchiveId ?? match.internetArchiveId ?? null,
    };
  });
  for (const book of enrichmentBooks) {
    if (!usedKeys.has(normalizeMatchKey(book.title, book.authors))) merged.push(book);
  }
  return merged;
}

/** Primary provider's count wins when present, else the enrichment source's. */
export function resolveTotalCount(primaryCount: number | null, enrichmentCount: number): number {
  if (typeof primaryCount === 'number' && primaryCount >= 0) return primaryCount;
  return Math.max(0, enrichmentCount);
}

/** Next 1-based page, or null when `offset + pageLength` reached `totalCount`. */
export function computeNextPage(
  page: number,
  pageLength: number,
  totalCount: number,
): number | null {
  const offset = (page - 1) * pageLength;
  return offset + pageLength < totalCount ? page + 1 : null;
}

// ── Google Books (WU3) ──────────────────────────────────────────────

/** Google Books `industryIdentifiers` entry (`ISBN_10` / `ISBN_13` / other). */
export interface GoogleBooksIndustryIdentifier {
  type?: string | null;
  identifier?: string | null;
}

export interface GoogleBooksImageLinks {
  thumbnail?: string | null;
  smallThumbnail?: string | null;
}

export interface GoogleBooksVolumeInfo {
  title?: string | null;
  authors?: string[] | null;
  description?: string | null;
  language?: string | null;
  categories?: string[] | null;
  imageLinks?: GoogleBooksImageLinks | null;
  industryIdentifiers?: GoogleBooksIndustryIdentifier[] | null;
}

export interface GoogleBooksVolumeItem {
  id?: string | null;
  volumeInfo?: GoogleBooksVolumeInfo | null;
}

export interface GoogleBooksSearchResponse {
  totalItems?: number | null;
  items?: GoogleBooksVolumeItem[] | null;
}

/** Google Books `industryIdentifiers` type literal for ISBN-13. */
const GOOGLE_ISBN13_TYPE = 'ISBN_13';
/** Google Books `industryIdentifiers` type literal for ISBN-10. */
const GOOGLE_ISBN10_TYPE = 'ISBN_10';

/**
 * Normalize a Google Books thumbnail to https (mixed-content safe). The live
 * API returns `http://books.google.com/...`; anything not https afterwards is
 * dropped so the UI falls back to the initial-letter block.
 */
export function googleBooksCoverUrl(thumbnail: string | null | undefined): string | null {
  if (thumbnail == null || thumbnail.trim() === '') return null;
  const https = thumbnail.startsWith('http://')
    ? `https://${thumbnail.slice('http://'.length)}`
    : thumbnail;
  return https.startsWith('https://') ? https : null;
}

/** Blank-safe identifier of `type` from a Google Books `industryIdentifiers` list. */
export function googleIndustryIdentifier(
  identifiers: GoogleBooksIndustryIdentifier[] | null | undefined,
  type: string,
): string | null {
  const found = (identifiers ?? []).find((entry) => entry.type === type)?.identifier;
  return found == null || found.trim() === '' ? null : found;
}

/**
 * Map a Google Books volume; null when the volume carries no usable id/title.
 * Google Books is a metadata/enrichment source — `downloadUrl` is always null,
 * so the in-app download path can never come from a Google Books hit.
 */
export function mapGoogleBooksVolume(item: GoogleBooksVolumeItem): CatalogBook | null {
  const id = (item.id ?? '').trim();
  const info = item.volumeInfo ?? {};
  const title = (info.title ?? '').trim();
  if (id === '' || title === '') return null;
  const isbn13 = digitsOf(
    googleIndustryIdentifier(info.industryIdentifiers, GOOGLE_ISBN13_TYPE) ?? '',
  );
  const isbn10 = digitsOf(
    googleIndustryIdentifier(info.industryIdentifiers, GOOGLE_ISBN10_TYPE) ?? '',
  );
  const language = info.language;
  const description = info.description;
  return {
    id: `googlebooks:${id}`,
    provider: BUILTIN_GOOGLEBOOKS,
    title,
    authors: info.authors ?? [],
    coverUrl: googleBooksCoverUrl(info.imageLinks?.thumbnail ?? info.imageLinks?.smallThumbnail),
    languages: language != null && language.trim() !== '' ? [language] : [],
    subjects: (info.categories ?? []).slice(0, 8),
    downloadUrl: null,
    description: description != null && description.trim() !== '' ? description : undefined,
    isbn13: isbn13.length === 13 ? isbn13 : null,
    isbn10: isbn10.length === 10 ? isbn10 : null,
    isPublicDomain: null,
    googleBooksId: id,
  };
}

export function toPagedResult(
  results: CatalogBook[],
  page: number,
  totalCount: number,
): PagedResult {
  return { results, nexoPage: computeNextPage(page, results.length, totalCount), totalCount };
}

// ── Standard Ebooks (Atom/OPDS acquisition feed) ────────────────────

/** Atom namespace the Standard Ebooks feed declares on every element. */
const ATOM_NS = 'http://www.w3.org/2005/Atom';
const MEDIA_NS = 'http://search.yahoo.com/mrss/';

/** The enclosure title the feed marks as its default EPUB. */
const SE_RECOMMENDED_EPUB = 'Recommended compatible epub';
const SE_EPUB_MIME = 'application/epub+zip';

/**
 * Namespaced book id from the entry permalink
 * (`https://standardebooks.org/ebooks/<author-slug>/<book-slug>[/<translator-slug>]`).
 * The slug pair is stable and unique per work, and the `standard-ebooks:` prefix
 * keeps it from ever colliding with another provider's id space.
 */
export function standardEbooksSlugOf(permalink: string): string | null {
  const marker = '/ebooks/';
  const at = permalink.indexOf(marker);
  if (at === -1) return null;
  const segments = permalink
    .slice(at + marker.length)
    .split('/')
    .map((segment) => segment.trim())
    .filter((segment) => segment !== '');
  // Two segments (author/book) identify the work; a third is the translator and
  // is deliberately dropped so the id stays stable if the editio retranslates.
  if (segments.length < 2) return null;
  return `${segments[0]}/${segments[1]}`;
}

/**
 * First https value of a translated-element text content, or null. `querySelector`
 * is avoided on purpose: the feed's `<content type="html">` carries escaped
 * markup, and selector matching across the whole document would be ambiguous.
 */
function atomText(parent: Element, localName: string): string | null {
  for (const child of Array.from(parent.children)) {
    if (child.localName === localName && child.namespaceURI === ATOM_NS) {
      const text = (child.textContent ?? '').trim();
      return text === '' ? null : text;
    }
  }
  return null;
}

/**
 * Map the Standard Ebooks Atom feed to catalog books. Entries without a title,
 * an author or an https EPUB link are dropped: a rail card needs all three, and
 * a book with no download could never be borrowed or read from here.
 *
 * `languages` stays empty rather than guessed: the feed carries no language
 * element (no `xml:lang`, no `dc:language`), and inventing `['en']` would make
 * the card claim a fact the payload does not state.
 */
export function mapStandardEbooksAtomFeed(xml: string, parser: DOMParser): CatalogBook[] {
  const doc = parser.parseFromString(xml, 'application/xml');
  // `DOMParser` reports malformed XML as a document-level <parsererror>; an empty
  // or wrong-root document is never a legitimate "no books" answer.
  if (doc.getElementsByTagName('parsererror').length > 0) return [];
  const feed = doc.documentElement;
  if (feed === null || feed.localName !== 'feed') return [];
  const entries = Array.from(feed.children).filter(
    (child) => child.localName === 'entry' && child.namespaceURI === ATOM_NS,
  );
  const books: CatalogBook[] = [];
  for (const entry of entries) {
    const title = atomText(entry, 'title');
    const permalink = atomText(entry, 'id');
    if (title === null || permalink === null) continue;
    const slug = standardEbooksSlugOf(permalink);
    if (slug === null) continue;
    const authorNodes = Array.from(entry.children).filter(
      (child) => child.localName === 'author' && child.namespaceURI === ATOM_NS,
    );
    const authors = authorNodes
      .map((author) => atomText(author, 'name'))
      .filter((name): name is string => name !== null);
    if (authors.length === 0) continue;

    const links = Array.from(entry.children).filter(
      (child) => child.localName === 'link' && child.namespaceURI === ATOM_NS,
    );
    const epub = links.find(
      (link) =>
        link.getAttribute('rel') === 'enclosure' &&
        link.getAttribute('type') === SE_EPUB_MIME &&
        link.getAttribute('title') === SE_RECOMMENDED_EPUB,
    );
    // An acquisition URL that is not https is dropped, exactly like
    // `resolveDownloadUrl` treats every other provider's download link.
    const rawDownload = (epub?.getAttribute('href') ?? '').trim();
    const downloadUrl = rawDownload.startsWith('https://') ? rawDownload : null;
    if (downloadUrl === null) continue;

    const thumbnail = Array.from(entry.children).find(
      (child) => child.localName === 'thumbnail' && child.namespaceURI === MEDIA_NS,
    );
    const rawCover = (thumbnail?.getAttribute('url') ?? '').trim();
    const coverUrl = rawCover.startsWith('https://') ? rawCover : null;

    const subjects = Array.from(entry.children)
      .filter((child) => child.localName === 'category' && child.namespaceURI === ATOM_NS)
      .map((category) => (category.getAttribute('term') ?? '').trim())
      .filter((term) => term !== '')
      .slice(0, 8);
    const summary = atomText(entry, 'summary');

    books.push({
      id: `standard-ebooks:${slug}`,
      provider: BUILTIN_STANDARD_EBOOKS,
      title,
      authors,
      coverUrl,
      languages: [],
      subjects,
      downloadUrl,
      description: summary ?? undefined,
      isPublicDomain: true,
    });
  }
  return books;
}
