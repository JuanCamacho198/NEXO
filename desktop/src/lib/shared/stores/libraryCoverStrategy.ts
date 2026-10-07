import type { LibraryPort } from '$lib/shared/ports/LibraryPort';
import { extractPdfMetadata } from '$lib/shared/services/pdfThumbnail';
import { logger } from '$lib/shared/logger/Logger';
import type { ReaderBook } from '$lib/shared/types';
export const THUMBNAIL_CONCURRENCY = 3;
export async function ensureEpubCover(
  book: ReaderBook,
  ctx: { libraryPort: LibraryPort; loadLibrary: () => Promise<void>; inFlight: Set<string> },
): Promise<void> {
  if (ctx.inFlight.has(book.id)) return;
  ctx.inFlight.add(book.id);
  try {
    const found = await ctx.libraryPort.extractEpubCover(book.id, book.filePath);
    if (found) await ctx.loadLibrary();
  } catch (e) {
    logger.error(
      '[Library] ensureEpubCover failed:',
      { error: e instanceof Error ? e.message : String(e) },
      'library',
    );
  } finally {
    ctx.inFlight.delete(book.id);
  }
}
export async function ensurePdfCover(
  book: ReaderBook,
  ctx: { libraryPort: LibraryPort; loadLibrary: () => Promise<void>; inFlight: Set<string> },
): Promise<void> {
  if (ctx.inFlight.has(book.id)) return;
  ctx.inFlight.add(book.id);
  try {
    const m = await extractPdfMetadata(book.filePath);
    if (m.thumbnailBytes)
      await ctx.libraryPort.upsertBookCover({
        bookId: book.id,
        data: Array.from(m.thumbnailBytes),
        mimeType: 'image/png',
      });
    const na = !!(m.author && (!book.author || book.author.trim() === ''));
    const np = !!(m.totalPages && (!book.totalPages || book.totalPages === 0));
    if (na || np)
      await ctx.libraryPort.upsertBook({
        id: book.id,
        title: book.title,
        author: m.author || book.author || '',
        filePath: book.filePath,
        format: book.format,
        syncStatus: 'local' as const,
        currentPage: book.currentPage,
        totalPages: m.totalPages || book.totalPages || 0,
        createdAt: book.createdAt,
        updatedAt: book.updatedAt,
      });
    await ctx.loadLibrary();
  } catch (e) {
    logger.error(
      '[Library] ensurePdfCover failed:',
      { error: e instanceof Error ? e.message : String(e) },
      'library',
    );
  } finally {
    ctx.inFlight.delete(book.id);
  }
}
export async function runCoverBatches(
  books: ReaderBook[],
  ctx: {
    shouldGeneratePdfCover: (b: ReaderBook) => boolean;
    shouldGenerateEpubCover: (b: ReaderBook) => boolean;
    attempted: Set<string>;
    ensureEpubCover: (b: ReaderBook) => Promise<void>;
    ensurePdfCover: (b: ReaderBook) => Promise<void>;
  },
): Promise<void> {
  const pendingPdf = books.filter((b) => {
    if (!ctx.shouldGeneratePdfCover(b)) return false;
    if (ctx.attempted.has(b.id)) return false;
    ctx.attempted.add(b.id);
    return true;
  });
  const pendingEpub = books.filter((b) => {
    if (!ctx.shouldGenerateEpubCover(b)) return false;
    if (ctx.attempted.has(b.id)) return false;
    ctx.attempted.add(b.id);
    return true;
  });
  for (let i = 0; i < pendingPdf.length; i += THUMBNAIL_CONCURRENCY) {
    const batch = pendingPdf.slice(i, i + THUMBNAIL_CONCURRENCY);
    await Promise.all(batch.map((b) => ctx.ensurePdfCover(b)));
  }
  for (let i = 0; i < pendingEpub.length; i += THUMBNAIL_CONCURRENCY) {
    const batch = pendingEpub.slice(i, i + THUMBNAIL_CONCURRENCY);
    await Promise.all(batch.map((b) => ctx.ensureEpubCover(b)));
  }
}
