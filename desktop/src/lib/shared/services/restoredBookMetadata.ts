/**
 * Metadata repair for a book whose file was just restored from Drive.
 *
 * The FK guard used to create placeholder `books` rows titled
 * `Unknown Title (sync stub) …` for a book that had never been downloaded here.
 * When the real file later arrives, `save_book_file` repairs `file_path` but the
 * placeholder title stays behind — the row keeps presenting itself as a stub
 * while holding the real book, with no cover and no author.
 *
 * `isPlaceholderBookTitle` identifies that stuck state so the sync can offer the
 * real values it just read out of the EPUB bytes, and a healthy row is left
 * untouched.
 */
import { extractEpubMetadataFromBytes } from './epubImportMetadata';

/** Mirrors the placeholder format written by the Rust FK guard. */
const PLACEHOLDER_TITLE_RE = /^Unknown Title \(sync stub\)/i;

/**
 * True when the stored title is the placeholder the FK guard produced rather
 * than a title a human or a parser ever supplied. Matching only the known
 * prefix keeps a real book that happens to mention "sync stub" in its title out
 * of this path.
 */
export function isPlaceholderBookTitle(title: string | null | undefined): boolean {
  return typeof title === 'string' && PLACEHOLDER_TITLE_RE.test(title.trim());
}

export type RestoredBookMeta = { title?: string; author?: string; format?: string };

/**
 * Read real title/author out of the restored bytes when — and only when — the
 * stored title is still the placeholder.
 *
 * Returns `null` for a healthy row: the caller then omits metadata entirely, and
 * `save_book_file` preserves the stored title. A parse failure also returns
 * `null` rather than throwing, so a book whose OPF cannot be read is still
 * persisted with its file — never left half-restored.
 */
export async function extractRestoredBookMeta(
  book: { title?: string | null; format?: string | null },
  bytes: Uint8Array,
): Promise<RestoredBookMeta | null> {
  if (!isPlaceholderBookTitle(book.title)) return null;
  if ((book.format ?? 'epub').toLowerCase() !== 'epub') return null;

  try {
    const meta = await extractEpubMetadataFromBytes(bytes);
    const title = meta.title?.trim();
    const author = meta.author?.trim();
    if (!title && !author) return null;
    return {
      ...(title ? { title } : {}),
      ...(author ? { author } : {}),
      ...(book.format ? { format: book.format } : {}),
    };
  } catch {
    return null;
  }
}
