import { untrack } from 'svelte';
import { debugState } from '$lib/shared/debug/debugState.svelte';
import { authState } from '$lib/shared/stores/AuthState.svelte';
import { readerState } from '$lib/shared/stores/ReaderDomainState.svelte';
import { SyncOutboxDao } from '$lib/shared/outbox/SyncOutboxDao';
import type { HighlightDto } from '$lib/shared/types/book';
import type { LibraryBookDto } from '$lib/shared/types/library';
import type { SpineResolver } from './useSpineResolver.svelte';
import type { ViewerPort } from '$lib/shared/ports/ViewerPort';
import { TauriViewerAdapter } from '$lib/shared/ports/adapters/tauri/TauriViewerAdapter';
import { handleError } from '$lib/shared/utils/errors';
import { captureBreadcrumb } from '$lib/shared/logger/BreadcrumbsStore';
import { BREADCRUMB_LABELS } from '$lib/shared/logger/breadcrumbTypes';
import { logger } from '$lib/shared/logger/Logger';

type ActiveBook = LibraryBookDto & { filePath: string };

export type PersistedHighlight = {
  id: string;
  color: string;
  pageNumber: number;
  rects: Array<{ left: number; top: number; width: number; height: number }>;
  cfi?: string | null;
  text?: string;
  note?: string | null;
};

export type SelectionData = {
  text: string;
  bounds: { left: number; top: number; right: number; bottom: number };
  rects: Array<{ left: number; top: number; width: number; height: number }>;
  pageNumber: number;
  cfi: string | null;
  /** EPUB evidence: the containing paragraph (`null` when there is no block ancestor). */
  quote?: string | null;
  /** EPUB evidence: the chapter label the selection came from. */
  chapterTitle?: string | null;
};

export type HighlightsDeps = {
  getBook: () => ActiveBook | null;
  spine: SpineResolver;
  outbox: SyncOutboxDao;
  viewerPort?: ViewerPort;
  getHighlightsVersion?: () => number;
  getUserId?: () => string | null;
  getDebugState?: () => typeof debugState | null;
};

export function createHighlights(deps: HighlightsDeps): {
  persistedHighlights: PersistedHighlight[];
  reloadHighlights(): void;
  runReloadHighlights(): Promise<void>;
  handleColorSelect(color: string, data: SelectionData): Promise<void>;
  updateHighlightColor(id: string, color: string): void;
  updateHighlightNote(id: string, note: string | null): void;
  deleteHighlightById(id: string): void;
  enqueueHighlightUpdate(id: string, changes: { color?: string; note?: string | null }): void;
  cleanup(): void;
  readonly _timer: ReturnType<typeof setTimeout> | null;
  readonly _inFlight: boolean;
  readonly _queued: boolean;
} {
  const { getBook, spine, outbox } = deps;
  const viewerPort = deps.viewerPort ?? new TauriViewerAdapter();
  const getUserId = deps.getUserId ?? (() => authState.userId);
  const getDbg = deps.getDebugState ?? (() => debugState);

  let persistedHighlights = $state<PersistedHighlight[]>([]);
  let highlightReloadTimer: ReturnType<typeof setTimeout> | null = null;
  let highlightReloadInFlight = false;
  let highlightReloadQueued = false;

  function reloadHighlights(): void {
    if (highlightReloadTimer) clearTimeout(highlightReloadTimer);
    highlightReloadTimer = setTimeout(() => {
      highlightReloadTimer = null;
      void runReloadHighlights();
    }, 32);
  }

  async function runReloadHighlights(): Promise<void> {
    if (highlightReloadInFlight) {
      highlightReloadQueued = true;
      return;
    }
    highlightReloadInFlight = true;
    try {
      do {
        highlightReloadQueued = false;
        const book = untrack(getBook);
        if (!book) {
          persistedHighlights = [];
          break;
        }
        const bookId = book.id;
        try {
          const rows: HighlightDto[] = await viewerPort.listHighlights(bookId);
          if (untrack(() => getBook()?.id) !== bookId) {
            logger.debug(
              'RW: listHighlights stale bookId ignored',
              { bookId: bookId.slice(0, 4) },
              'reader',
            );
            continue;
          }
          logger.warn(
            'RW: listHighlights loaded',
            {
              count: rows.length,
              rows: rows.map((r) => `${r.pageNumber}:${r.id.slice(0, 4)}`).join(','),
              bookId: bookId.slice(0, 4),
            },
            'reader',
          );
          const rowIds = new Set(rows.map((r) => r.id));
          const optimistic = untrack(() => persistedHighlights).filter((h) => !rowIds.has(h.id));
          if (book.format?.toLowerCase() === 'epub' && book.filePath) {
            await spine.ensureSpineHrefs(bookId, book.filePath);
          }
          let merged: PersistedHighlight[] = rows.map((r) => {
            let pageNumber = r.pageNumber;
            if (r.cfi) {
              const cfiTrim = r.cfi.trim();
              const m = /epubcfi\(\/6\/(\d+)!/.exec(cfiTrim);
              if (m) {
                const idx = parseInt(m[1], 10) - 1;
                if (idx >= 0 && idx !== pageNumber) {
                  logger.warn(
                    'RW: fixing page mismatch',
                    { id: r.id.slice(0, 4), from: r.pageNumber, to: idx },
                    'reader',
                  );
                  pageNumber = idx;
                  void viewerPort.updateHighlight({ id: r.id, pageNumber }).catch(() => {});
                }
              } else if (cfiTrim.startsWith('readium:')) {
                const spineIdx = spine.getSpineIndexForHref(cfiTrim, spine.epubSpineHrefs);
                if (spineIdx !== null && spineIdx >= 0 && spineIdx !== pageNumber) {
                  logger.warn(
                    'RW: fixing page mismatch (readium)',
                    {
                      id: r.id.slice(0, 4),
                      from: r.pageNumber,
                      to: spineIdx,
                      href: cfiTrim.slice(0, 40),
                    },
                    'reader',
                  );
                  pageNumber = spineIdx;
                  void viewerPort.updateHighlight({ id: r.id, pageNumber }).catch(() => {});
                } else if (spineIdx === null) {
                  logger.warn(
                    'RW: readium href not in spine',
                    {
                      id: r.id.slice(0, 4),
                      href: cfiTrim.slice(0, 60),
                      spineLen: spine.epubSpineHrefs.length,
                    },
                    'reader',
                  );
                }
              }
            }
            return {
              id: r.id,
              color: r.color,
              pageNumber,
              rects: [],
              cfi: r.cfi ?? null,
              text: r.text,
              note: r.note ?? null,
            };
          });
          if (optimistic.length > 0) {
            logger.warn(
              'RW: preserving optimistic highlights not yet in DB',
              {
                count: optimistic.length,
                entries: optimistic.map((o) => `${o.pageNumber}:${o.id.slice(0, 4)}`).join(','),
              },
              'reader',
            );
            merged = [...merged, ...optimistic];
          }
          persistedHighlights = merged;
        } catch (err) {
          handleError(err, 'reader', {
            bookId: bookId,
            format: book.format ?? null,
            action: 'load_highlights',
          });
        }
      } while (highlightReloadQueued);
    } finally {
      highlightReloadInFlight = false;
    }
  }

  function cleanup(): void {
    if (highlightReloadTimer) {
      clearTimeout(highlightReloadTimer);
      highlightReloadTimer = null;
    }
  }

  async function handleColorSelect(color: string, data: SelectionData): Promise<void> {
    const dbg = getDbg();
    if (dbg) {
      dbg.epub.colorPickCount++;
      dbg.epub.lastPickedColor = color;
    }
    logger.warn(
      'RW: handleColorSelect',
      {
        pageNumber: data.pageNumber,
        cfi: data.cfi?.slice(0, 40) ?? '(null)',
        text: data.text.slice(0, 30),
      },
      'reader',
    );

    const book = untrack(getBook);
    if (data && book) {
      const highlightId = crypto.randomUUID();
      const bounds = data.bounds;
      const pageNumber = data.pageNumber ?? (book.format?.toLowerCase() === 'epub' ? 0 : 1);
      const cfi = data.cfi ?? null;
      logger.warn(
        'RW: push highlight',
        {
          pageNumber,
          cfi: cfi?.slice(0, 60) ?? '(null)',
          id: highlightId.slice(0, 4),
          text: data.text.slice(0, 30),
        },
        'reader',
      );

      persistedHighlights = [
        ...persistedHighlights,
        {
          id: highlightId,
          color,
          pageNumber,
          rects: data.rects,
          cfi,
          text: data.text,
          note: null,
        },
      ];
      // Journey crumb: ids + text LENGTH only, never highlight text.
      captureBreadcrumb('action', BREADCRUMB_LABELS.HIGHLIGHT_CREATE, {
        bookId: book.id,
        highlightId,
        pageNumber,
        textLength: data.text.length,
      });

      try {
        const dbg2 = getDbg();
        if (dbg2) dbg2.epub.saveHighlightCallCount++;
        await viewerPort.saveHighlight({
          id: highlightId,
          bookId: book.id,
          text: data.text,
          color,
          pageNumber,
          rectLeft: bounds.left,
          rectRight: bounds.right,
          rectTop: bounds.top,
          rectBottom: bounds.bottom,
          cfi,
        });
        const uid = getUserId();
        if (uid) {
          void outbox.add(
            'HIGHLIGHT',
            highlightId,
            'UPSERT',
            JSON.stringify({
              userId: uid,
              bookId: book.id,
              cfiRange: cfi ?? '',
              textContent: data.text,
              color,
              page: pageNumber,
              locatorJson: readerState.locatorJson,
              updatedAt: new Date().toISOString(),
            }),
          );
        }
      } catch (err) {
        const dbg3 = getDbg();
        if (dbg3) {
          dbg3.epub.saveHighlightLastError = String(err);
          if (!dbg3.epub.failedHighlightIds.includes(highlightId)) {
            dbg3.epub.failedHighlightIds.push(highlightId);
          }
        }
        // PII: pass text LENGTH, never the highlight text.
        handleError(err, 'reader', {
          bookId: book.id,
          highlightId,
          cfi: cfi ?? null,
          pageNumber,
          textLength: data.text.length,
          action: 'save_highlight',
        });
      }
    }

    // 220ms delay before clearing selection — preserves transition and avoids
    // selectionchange race (captured data is used, not global lastSelectionData)
    setTimeout(() => {
      window.getSelection()?.removeAllRanges();
    }, 220);
  }

  function updateHighlightColor(id: string, color: string): void {
    const bookId = untrack(getBook)?.id;
    captureBreadcrumb('action', BREADCRUMB_LABELS.HIGHLIGHT_UPDATE, {
      bookId: bookId ?? null,
      highlightId: id,
    });
    persistedHighlights = persistedHighlights.map((h) => (h.id === id ? { ...h, color } : h));
    viewerPort.updateHighlight({ id, color }).catch((err) => {
      handleError(err, 'reader', {
        bookId: bookId ?? null,
        highlightId: id,
        attemptedColor: color,
        action: 'update_color',
      });
    });
    enqueueHighlightUpdate(id, { color });
  }

  function updateHighlightNote(id: string, note: string | null): void {
    const bookId = untrack(getBook)?.id;
    // Journey crumb: ids only — never the note content.
    captureBreadcrumb('action', BREADCRUMB_LABELS.HIGHLIGHT_NOTE, {
      bookId: bookId ?? null,
      highlightId: id,
    });
    persistedHighlights = persistedHighlights.map((h) => (h.id === id ? { ...h, note } : h));
    viewerPort.updateHighlight({ id, note: note ?? undefined }).catch((err) => {
      // PII: pass note LENGTH, never the note content. The scrubber would also
      // redact a `note` key, but length is the safest shape (no embedded PII).
      handleError(err, 'reader', {
        bookId: bookId ?? null,
        highlightId: id,
        noteLength: note?.length ?? 0,
        action: 'update_note',
      });
    });
    enqueueHighlightUpdate(id, { note });
  }

  function deleteHighlightById(id: string): void {
    const highlight = persistedHighlights.find((item) => item.id === id);
    const bookId = untrack(getBook)?.id ?? null;
    captureBreadcrumb('action', BREADCRUMB_LABELS.HIGHLIGHT_DELETE, {
      bookId,
      highlightId: id,
    });
    persistedHighlights = persistedHighlights.filter((h) => h.id !== id);
    viewerPort.deleteHighlight(id).catch((err) => {
      handleError(err, 'reader', {
        bookId: bookId,
        highlightId: id,
        action: 'delete',
      });
    });
    const uidDel = getUserId();
    if (uidDel && highlight) {
      const book = untrack(getBook);
      const updatedAt = new Date().toISOString();
      void outbox.add(
        'HIGHLIGHT',
        id,
        'DELETE',
        JSON.stringify({
          userId: uidDel,
          bookId: book?.id ?? id,
          cfiRange: highlight.cfi ?? '',
          textContent: highlight.text ?? '',
          color: highlight.color,
          page: highlight.pageNumber,
          locatorJson: readerState.locatorJson,
          deletedAt: updatedAt,
          updatedAt,
        }),
      );
    }
  }

  function enqueueHighlightUpdate(
    id: string,
    changes: { color?: string; note?: string | null },
  ): void {
    const uid = getUserId();
    if (!uid) return;
    const highlight = persistedHighlights.find((item) => item.id === id);
    if (!highlight) return;
    const book = untrack(getBook);
    void outbox.add(
      'HIGHLIGHT',
      id,
      'UPSERT',
      JSON.stringify({
        userId: uid,
        bookId: book?.id ?? id,
        cfiRange: highlight.cfi ?? '',
        textContent: highlight.text ?? '',
        color: changes.color ?? highlight.color,
        note: changes.note ?? highlight.note ?? null,
        page: highlight.pageNumber,
        locatorJson: readerState.locatorJson,
        updatedAt: new Date().toISOString(),
      }),
    );
  }

  return {
    get persistedHighlights() {
      return persistedHighlights;
    },
    set persistedHighlights(v: PersistedHighlight[]) {
      persistedHighlights = v;
    },
    reloadHighlights,
    runReloadHighlights,
    handleColorSelect,
    updateHighlightColor,
    updateHighlightNote,
    deleteHighlightById,
    enqueueHighlightUpdate,
    cleanup,
    get _timer() {
      return highlightReloadTimer;
    },
    get _inFlight() {
      return highlightReloadInFlight;
    },
    get _queued() {
      return highlightReloadQueued;
    },
  };
}

export type HighlightsState = ReturnType<typeof createHighlights>;
