import { chapterFromLocator } from '$lib/features/reader/chrome/spineToToc';

/**
 * The chapter a book was left on, worked out without opening it.
 *
 * The hero's "Capítulo actual" is recorded by the reader while it is open, so a
 * book not opened since that record started (or read on another device) showed
 * no chapter at all. Its saved reading position is enough to name it: the
 * position's spine document, mapped onto the book's table of contents the same
 * way the reader maps it (`chapterFromLocator`). `parse_epub` is cached per
 * book after the first open, so this costs a file read, not an extraction.
 *
 * Null whenever any step cannot answer — a PDF, no saved position, a locator
 * that is not an EPUB CFI, a position ahead of the outline. No line beats a
 * wrong line.
 */
export type LastChapterDeps = {
  getProgress: (bookId: string) => Promise<{ cfiLocation?: string | null } | null>;
  getToc: (
    bookId: string,
    filePath: string,
  ) => Promise<Array<{ index?: number; label?: string | null }>>;
};

export async function resolveLastChapter(
  book: { id: string; format?: string | null; filePath?: string | null },
  deps: LastChapterDeps,
): Promise<string | null> {
  if ((book.format ?? '').toLowerCase() !== 'epub' || !book.filePath) return null;
  try {
    const progress = await deps.getProgress(book.id);
    const cfi = progress?.cfiLocation ?? '';
    if (!cfi.startsWith('epubcfi(')) return null;
    const toc = await deps.getToc(book.id, book.filePath);
    const at = chapterFromLocator(cfi, toc, -1);
    if (at === null || at < 0) return null;
    const label = toc[at]?.label?.trim() ?? '';
    return label.length > 0 ? label : null;
  } catch {
    return null;
  }
}
