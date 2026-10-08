/**
 * Lightweight one-shot metadata extractor for EPUB files used at import time.
 *
 * The full `EpubReaderService` (in `src/lib/features/reader/viewer-epub/epub.ts`)
 * is geared toward rendering and tears down the epubjs `Book` on every cleanup.
 * For import we only need the title and author once, so we build a dedicated,
 * side-effect-free helper.
 *
 * Strategy (defense in depth, in case any single layer returns empty for an
 * otherwise-valid EPUB):
 *
 *  1. Read the file bytes via the Tauri command (or accept them in memory
 *     via `extractEpubMetadataFromBytes`, e.g. right after a Drive download).
 *  2. Hand them to epubjs and `await book.ready` (with a timeout so a
 *     broken file doesn't hang the import).
 *  3. Pull `book.package.metadata.title` / `.creator`. epubjs sets these
 *     from the OPF's `<dc:title>` and `<dc:creator>` elements via
 *     `getElementText`.
 *  4. If the values came back empty (e.g. the OPF uses a non-standard
 *     namespace that epubjs's parser didn't pick up), parse the OPF
 *     ourselves as a last resort using the browser's `DOMParser`. This
 *     is the same path the existing reader takes on the chapter HTML,
 *     so it should work for any EPUB that the reader itself can open.
 *  5. Always release the epubjs instance in a `finally`.
 *
 * Failures are swallowed at every step — the caller falls back to the
 * filename. A short `logger.debug` is logged so the user can see why
 * a particular file came up empty (most often: the EPUB genuinely has
 * no `<dc:title>` / `<dc:creator>`).
 */
import ePub from 'epubjs';
import type { LibraryPort } from '$lib/shared/ports/LibraryPort';
import { TauriLibraryAdapter } from '$lib/shared/ports/adapters/tauri/TauriLibraryAdapter';
import { validateEpubMetadata } from '$lib/shared/validation/importSchemas';
import { logger } from '$lib/shared/logger/Logger';

export type ImportEpubMetadata = {
  title: string | null;
  author: string | null;
  subject: string | null;
  subjects: string[];
};

// Generous: a valid EPUB is parsed in well under a second; anything past
// this is a hung parser and we should fall back to the filename.
const PARSE_TIMEOUT_MS = 5000;

// Minimal type for the slice of epubjs Book we actually touch.
type EpubJsBook = {
  ready: Promise<unknown>;
  destroy(): Promise<void> | void;
  package?: { metadata?: Record<string, unknown> };
};

let libraryPort: LibraryPort = new TauriLibraryAdapter();
export function setEpubMetadataLibraryPort(port: LibraryPort): void {
  libraryPort = port;
}

const EMPTY_METADATA: ImportEpubMetadata = {
  title: null,
  author: null,
  subject: null,
  subjects: [],
};

const trimToNull = (value: unknown): string | null => {
  if (typeof value !== 'string') return null;
  const trimmed = value.trim();
  return trimmed.length > 0 ? trimmed : null;
};

const withTimeout = async <T>(promise: Promise<T>, ms: number): Promise<T> => {
  let timeoutId: ReturnType<typeof setTimeout> | null = null;
  const timeout = new Promise<T>((_, reject) => {
    timeoutId = setTimeout(() => reject(new Error('epub parse timeout')), ms);
  });
  try {
    return await Promise.race([promise, timeout]);
  } finally {
    if (timeoutId) clearTimeout(timeoutId);
  }
};

/** Parse the OPF's <metadata> block from raw EPUB bytes (works without unzipping). */
const parseOpfFromBytes = (bytes: Uint8Array): ImportEpubMetadata => {
  // Many EPUBs store the OPF inside the zip at OEBPS/content.opf or
  // similar. Walking the full zip would be heavy; instead we just convert
  // the bytes to text and look for the metadata block. The zip local-file
  // header is plain ASCII-ish; for our regex needs (find
  // <metadata>...</metadata>) we can scan the whole byte string as latin1
  // without unzipping.
  const text = new TextDecoder('utf-8', { fatal: false }).decode(bytes);
  // Match the first <metadata>...</metadata> block, allowing any
  // namespace prefix on the title/creator tags.
  const metaBlock = /<metadata[\s\S]*?<\/metadata>/i.exec(text);
  if (!metaBlock) return EMPTY_METADATA;
  const block = metaBlock[0];
  const titleMatch = /<(?:\w+:)?title[^>]*>([\s\S]*?)<\/(?:\w+:)?title>/i.exec(block);
  const creatorMatch = /<(?:\w+:)?creator[^>]*>([\s\S]*?)<\/(?:\w+:)?creator>/i.exec(block);
  // dc:subject is repeating in EPUB OPF (one tag per subject, in
  // document order). The `g` flag + matchAll lets us collect every
  // one; the same `(?:\w+:)?` namespace prefix accepts `dc:subject`,
  // `dc11:subject`, and `opf:subject`.
  const subjectRegex = /<(?:\w+:)?subject[^>]*>([\s\S]*?)<\/(?:\w+:)?subject>/gi;
  const subjects: string[] = [];
  for (const match of block.matchAll(subjectRegex)) {
    const trimmed = trimToNull(match[1]);
    if (trimmed !== null) {
      subjects.push(trimmed);
    }
  }
  return {
    title: titleMatch ? trimToNull(titleMatch[1]) : null,
    author: creatorMatch ? trimToNull(creatorMatch[1]) : null,
    subject: subjects[0] ?? null,
    subjects,
  };
};

/** epubjs structured parse over an in-memory ArrayBuffer; never throws. */
const parseEpubJsBytes = async (buffer: ArrayBuffer): Promise<ImportEpubMetadata> => {
  let book: EpubJsBook | null = null;
  try {
    book = ePub(buffer) as unknown as EpubJsBook;
    await withTimeout(book.ready, PARSE_TIMEOUT_MS);
    const metadata = book.package?.metadata ?? {};
    const subjectsRaw = metadata.subject;
    const subjectsList = Array.isArray(subjectsRaw)
      ? subjectsRaw
      : subjectsRaw !== undefined && subjectsRaw !== null
        ? [subjectsRaw]
        : [];
    const subjectsTrimmed = subjectsList
      .map((value) => trimToNull(value))
      .filter((value): value is string => value !== null);
    return {
      title: trimToNull(metadata.title),
      author: trimToNull(metadata.creator),
      subject: subjectsTrimmed[0] ?? null,
      subjects: subjectsTrimmed,
    };
  } catch (err) {
    logger.debug(
      '[epub-import-meta] epubjs parse failed, will try OPF fallback',
      { error: err instanceof Error ? err.message : String(err) },
      'import',
    );
    return EMPTY_METADATA;
  } finally {
    if (book) {
      try {
        await book.destroy();
      } catch {
        // best-effort cleanup
      }
    }
  }
};

const combineMetadata = (
  epubResult: ImportEpubMetadata,
  opfResult: ImportEpubMetadata,
): ImportEpubMetadata => ({
  title: epubResult.title ?? opfResult.title,
  author: epubResult.author ?? opfResult.author,
  subject: epubResult.subject ?? opfResult.subject,
  subjects: epubResult.subjects.length > 0 ? epubResult.subjects : opfResult.subjects,
});

/**
 * Last-resort parser: when epubjs's structured metadata came back empty,
 * drop down to the raw OPF and look for `<dc:title>` / `<dc:creator>` with
 * a wildcard namespace (matches `dc:title`, `dc11:title`, etc.). This is
 * the same path the reader takes for chapter HTML, so if the reader can
 * open the file we should be able to find the metadata here.
 *
 * Exported (not just module-private) so the regex parsing can be
 * unit-tested with synthetic byte payloads without standing up the
 * epubjs pipeline.
 */
export const parseOpfDirectly = async (filePath: string): Promise<ImportEpubMetadata> => {
  try {
    const bytes = await libraryPort.getFileBytes(filePath);
    return parseOpfFromBytes(new Uint8Array(bytes));
  } catch {
    return EMPTY_METADATA;
  }
};

/**
 * Extract EPUB title/author (and subjects) from bytes already in memory —
 * used right after a Drive download, before the book is persisted, so a
 * book whose catalog row only carries a UUID title can still land with the
 * REAL title/author. Never throws: any failure yields nulls and the caller
 * falls back to the catalog/filename title.
 */
export const extractEpubMetadataFromBytes = async (
  bytes: Uint8Array,
): Promise<ImportEpubMetadata> => {
  const buffer = new Uint8Array(bytes).buffer as ArrayBuffer;
  const epubResult = await parseEpubJsBytes(buffer);

  // If epubjs returned both fields, we're done. If either is still empty,
  // fall back to parsing the raw OPF from the same bytes.
  if (epubResult.title && epubResult.author && epubResult.subject) {
    return epubResult;
  }

  const opfResult = parseOpfFromBytes(new Uint8Array(bytes));
  const combined = combineMetadata(epubResult, opfResult);

  if (!combined.title && !combined.author) {
    logger.debug(
      '[epub-import-meta] no metadata found in OPF for in-memory bytes — caller will fall back to filename / unknown author',
      undefined,
      'import',
    );
  }

  const validation = validateEpubMetadata(combined);
  if (!validation.ok) {
    logger.debug(
      '[epub-import-meta] metadata DTO failed validation — caller will fall back to filename / unknown author',
      { key: validation.key },
      'import',
    );
    return { ...EMPTY_METADATA };
  }

  return combined;
};

/**
 * Validate an epub/PDF metadata DTO against the import schema.
 * Never throws: a missing fallback key surfaces the raw fallback string.
 */
export const validateEpubImportMetadataDto = (dto: unknown): boolean => {
  try {
    return validateEpubMetadata(dto).ok;
  } catch {
    return false;
  }
};

export const extractEpubImportMetadata = async (filePath: string): Promise<ImportEpubMetadata> => {
  const fileData = await libraryPort.getFileBytes(filePath);
  return extractEpubMetadataFromBytes(new Uint8Array(fileData));
};
