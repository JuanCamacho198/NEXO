/**
 * useEpubHighlights — highlight overlay for EpubNativeViewer (PR5).
 * Extracts overlay renderKey debounce, handleEpubHighlight* handlers,
 * and the highlight $effect (25×20ms poll) while preserving byte-identical behavior.
 * Spec: renderKey debounce + stale pageNumber already guarded in bridge; here we
 * deduplicate renders and poll for bridge+overlay readiness.
 */
import { untrack } from 'svelte';
import type { ViewerPort } from '$lib/shared/ports/ViewerPort';
import { TauriViewerAdapter } from '$lib/shared/ports/adapters/tauri/TauriViewerAdapter';
import { debugState } from '$lib/shared/debug/debugState.svelte';
import { normalizeHref } from '$lib/shared/sync/LocatorCodec';
import { stripFragment } from '$lib/features/reader/viewer-epub/epubViewerHelpers';
import type { HighlightActionKind, HighlightActionOpts } from '$lib/shared/types/book';
import type { EpubChapterMeta } from '$lib/features/reader/viewer-epub/epubViewerHelpers';

export interface EpubMetadataExtract {
  title: string;
  author: string;
  language: string | null;
  publisher: string | null;
  toc: EpubChapterMeta[];
  spineHrefs: string[];
  chapters?: EpubChapterMeta[];
  spine_hrefs?: string[];
  totalChapters: number;
  total_chapters?: number;
  resourcesPath: string;
  resources_path?: string;
}

type EpubHighlightShape = {
  id: string;
  color: string;
  pageNumber: number;
  cfi?: string | null;
  text?: string | null;
};

export type EpubHighlightsDeps = {
  getMetadata: () => EpubMetadataExtract | null;
  getIframeEl: () => HTMLIFrameElement | null;
  getSpineHrefs: () => string[];
  getToc: () => EpubChapterMeta[];
  getCurrentChapterIndex: () => number;
  getCurrentSpineIndex: () => number;
  getPersistedHighlights: () => Array<EpubHighlightShape>;
  setPersistedHighlights?: (v: Array<EpubHighlightShape>) => void;
  getIsLoading: () => boolean;
  getLastRenderedChapter: () => number;
  onHighlightAction?: (action: HighlightActionKind, id: string, opts?: HighlightActionOpts) => void;
  viewerPort?: ViewerPort;
};

export function createEpubHighlights(deps: EpubHighlightsDeps): {
  lastHighlightRenderKey: string;
  handleEpubHighlightClick(msg: {
    id: string;
    x: number;
    y: number;
    color: string;
    text?: string;
    pageNumber: number;
  }): void;
  handleEpubHighlightFailed(msg: {
    id: string;
    reason: string;
    pageNumber: number;
    cfi?: string;
    color?: string;
  }): void;
  handleEpubHighlightPlaced(msg: { id: string; pageNumber: number }): void;
} {
  const viewerPort = deps.viewerPort ?? new TauriViewerAdapter();
  let lastHighlightRenderKey = $state('');

  function handleEpubHighlightClick(msg: {
    id: string;
    x: number;
    y: number;
    color: string;
    text?: string;
    pageNumber: number;
  }): void {
    if (msg.pageNumber !== deps.getCurrentSpineIndex()) return;
    const iframeEl = deps.getIframeEl();
    if (!iframeEl || !deps.onHighlightAction) return;
    const frameRect = iframeEl.getBoundingClientRect();
    deps.onHighlightAction('open', msg.id, {
      color: msg.color,
      text: msg.text,
      x: msg.x + frameRect.left,
      y: msg.y + frameRect.top,
    });
  }

  function handleEpubHighlightFailed(msg: {
    id: string;
    reason: string;
    pageNumber: number;
    cfi?: string;
    color?: string;
  }): void {
    if (msg.id && !debugState.epub.failedHighlightIds.includes(msg.id)) {
      debugState.epub.failedHighlightIds.push(msg.id);
    }
    console.warn('epub-hl: highlight failed to apply', {
      id: msg.id,
      reason: msg.reason,
      pageNumber: msg.pageNumber,
    });
  }

  function handleEpubHighlightPlaced(msg: { id: string; pageNumber: number }): void {
    if (!msg?.id || typeof msg.pageNumber !== 'number') return;
    const arr = deps.getPersistedHighlights();
    const idx = arr.findIndex((h) => h.id === msg.id);
    if (idx >= 0 && arr[idx].pageNumber !== msg.pageNumber) {
      arr[idx] = { ...arr[idx], pageNumber: msg.pageNumber };
      deps.setPersistedHighlights?.(arr);
    }
    void viewerPort.updateHighlight({ id: msg.id, pageNumber: msg.pageNumber }).catch(() => {});
  }

  // Highlight overlay effect — mirrors viewer $effect verbatim, deps injected
  $effect(() => {
    const metadata = deps.getMetadata();
    const iframeEl = deps.getIframeEl();
    const isLoading = deps.getIsLoading();
    if (!metadata || isLoading || !iframeEl) return;
    void deps.getPersistedHighlights();
    const currentIdx = deps.getCurrentChapterIndex();
    void deps.getCurrentSpineIndex();
    const spineHref = normalizeHref(deps.getSpineHrefs()[deps.getCurrentSpineIndex()] ?? '');
    const tocHrefRaw = deps.getToc()[currentIdx]?.href ?? '';
    const chapterHref = spineHref || normalizeHref(stripFragment(tocHrefRaw));
    const metaHref = normalizeHref(stripFragment(tocHrefRaw));
    const highlightsSnapshot = deps.getPersistedHighlights();
    const lastRenderedSnapshot = untrack(() => deps.getLastRenderedChapter());

    console.warn(
      'epub-hl: effect triggered with',
      highlightsSnapshot.length,
      'highlights, toc',
      currentIdx,
      'spine',
      deps.getCurrentSpineIndex(),
      'lastRendered',
      lastRenderedSnapshot,
      'highlightsMap',
      highlightsSnapshot.map((h) => `${h.pageNumber}:${h.id.slice(0, 4)}`).join(','),
      'chapterHref',
      chapterHref,
      'metaHref',
      metaHref,
    );

    const renderKey = `${currentIdx}|${deps.getCurrentSpineIndex()}|${chapterHref}|${highlightsSnapshot.map((h) => h.id + ':' + h.pageNumber).join(',')}`;
    if (renderKey === untrack(() => lastHighlightRenderKey)) {
      console.warn('epub-hl: skip duplicate render', renderKey.slice(0, 120));
      return;
    }

    const MAX_RETRIES = 25;
    const RETRY_INTERVAL = 20;
    let retries = 0;
    let timer: ReturnType<typeof setTimeout> | null = null;
    let cancelled = false;

    function attemptRender(): void {
      if (cancelled) return;
      const win = deps.getIframeEl()?.contentWindow as
        | (Window & {
            __epubHighlightOverlay?: {
              render: (h: EpubHighlightShape[], chapterHref: string, idx: number) => void;
              isReady?: () => boolean;
            };
            __cfiBridge?: { cfiToRange: (...args: unknown[]) => unknown };
          })
        | null;

      const currentLastRendered = untrack(() => deps.getLastRenderedChapter());
      if (currentLastRendered !== currentIdx) {
        if (retries++ < MAX_RETRIES) {
          timer = setTimeout(attemptRender, RETRY_INTERVAL);
          if (retries === 1) {
            console.warn(
              'epub-hl: render deferred (lastRenderedChapter',
              currentLastRendered,
              '!== current',
              currentIdx,
              ') - retrying',
            );
          }
        } else {
          console.warn(
            'epub-hl: render aborted (lastRenderedChapter',
            currentLastRendered,
            '!== current',
            currentIdx,
            ') after',
            MAX_RETRIES,
            'retries',
          );
        }
        return;
      }

      if (!win || !win.__epubHighlightOverlay) {
        if (retries++ < MAX_RETRIES) {
          timer = setTimeout(attemptRender, RETRY_INTERVAL);
          if (retries === 1) {
            console.warn(
              'epub-hl: render deferred (overlay not mounted on iframe window) - retrying',
            );
          }
        } else {
          console.warn(
            'epub-hl: render aborted (overlay not mounted on iframe window) after',
            MAX_RETRIES,
            'retries',
          );
        }
        return;
      }

      console.warn(
        'epub-hl: render called with',
        highlightsSnapshot.length,
        'highlights, toc',
        currentIdx,
        'spine',
        deps.getCurrentSpineIndex(),
        'chapterHref',
        chapterHref,
      );
      try {
        win.__epubHighlightOverlay.render(
          highlightsSnapshot,
          chapterHref,
          deps.getCurrentSpineIndex(),
        );
        lastHighlightRenderKey = renderKey;
      } catch (err) {
        console.warn('epub-hl: render failed', err);
      }
    }

    attemptRender();

    return () => {
      cancelled = true;
      if (timer) clearTimeout(timer);
    };
  });

  return {
    get lastHighlightRenderKey(): string {
      return lastHighlightRenderKey;
    },
    set lastHighlightRenderKey(v: string) {
      lastHighlightRenderKey = v;
    },
    handleEpubHighlightClick,
    handleEpubHighlightFailed,
    handleEpubHighlightPlaced,
  };
}

export type EpubHighlightsState = ReturnType<typeof createEpubHighlights>;
