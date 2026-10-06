/**
 * usePdfSelection — buildSelectionOverlayRects + toolbar placement (PR-P2-3).
 * Extracts selection geometry, placement and highlight-click handling from
 * PdfViewer. Re-exports `buildSelectionOverlayRects` for unit testing.
 */
import { buildSelectionOverlayRects as pureBuildSelectionOverlayRects } from '$lib/features/reader/viewer-pdf/pdfSelection';
import type { SelectionOverlayRect } from '$lib/features/reader/viewer-pdf/pdfState.svelte';
import {
  TOOLBAR_OFFSET,
  TOOLBAR_WIDTH_ESTIMATE,
  TOOLBAR_EDGE_PADDING,
  clampSelectionPoint,
} from '$lib/features/reader/viewer-pdf/pdfState.svelte';
import { debugState } from '$lib/shared/debug/debugState.svelte';
import { handleError } from '$lib/shared/utils/errors';

export { pureBuildSelectionOverlayRects as buildSelectionOverlayRects };
export type { SelectionOverlayRect };

export type PdfSelectionDeps = {
  getTextLayer: () => HTMLDivElement | undefined;
  getScale: () => number;
  getCurrentPage: () => number;
  onselection?: (event: {
    text: string;
    bounds: { left: number; top: number; right: number; bottom: number };
    container: { left: number; top: number; width: number; height: number };
    placement: string;
    rects: Array<{ left: number; top: number; width: number; height: number }>;
    pageNumber: number;
  }) => void;
  onselectionclear?: () => void;
  onHighlightAction?: (
    action: import('$lib/shared/types/book').HighlightActionKind,
    id: string,
    opts?: import('$lib/shared/types/book').HighlightActionOpts,
  ) => void;
};

export function createPdfSelectionState(deps: PdfSelectionDeps): {
  selectionPlacement: 'above' | 'below';
  activeHighlightId: string | null;
  clearSelectionUi(): void;
  hideToolbar(): void;
  dismissHighlightManager(): void;
  handleHighlightClick(hl: { id: string; color: string; text?: string }, event: MouseEvent): void;
  handleTextSelection(): void;
  updateSelectionState(): void;
  buildSelectionOverlayRects: typeof pureBuildSelectionOverlayRects;
} {
  let selectionPlacement = $state<'above' | 'below'>('above');
  let activeHighlightId = $state<string | null>(null);

  const clearSelectionUi = (): void => {
    deps.onselectionclear?.();
  };

  function hideToolbar(): void {
    clearSelectionUi();
    window.getSelection()?.removeAllRanges();
  }

  function dismissHighlightManager(): void {
    activeHighlightId = null;
  }

  function handleHighlightClick(
    hl: { id: string; color: string; text?: string },
    event: MouseEvent,
  ): void {
    event.stopPropagation();
    if (activeHighlightId === hl.id) {
      dismissHighlightManager();
      return;
    }
    activeHighlightId = hl.id;
    deps.onHighlightAction?.('open', hl.id, {
      color: hl.color,
      text: hl.text ?? '',
      x: event.clientX,
      y: event.clientY,
    });
  }

  function handleTextSelection(): void {
    window.setTimeout(updateSelectionState, 10);
  }

  function updateSelectionState(): void {
    const selection = window.getSelection();
    if (debugState.enabled) {
      console.debug('PDF Selection Update:', selection?.toString().trim());
    }

    if (!selection || selection.rangeCount === 0) {
      clearSelectionUi();
      return;
    }
    const text = selection.toString().trim();
    if (!text) return;

    const textLayer = deps.getTextLayer();
    const containerRect = textLayer?.getBoundingClientRect();
    const scale = deps.getScale();

    let nextPosition: { x: number; y: number } | null = null;
    let selectionBounds = { left: 0, top: 0, right: 0, bottom: 0 };
    let overlayRects: SelectionOverlayRect[] = [];
    const unscaledWidth = containerRect ? containerRect.width : 0;

    try {
      const range = selection.getRangeAt(0);
      if (containerRect) overlayRects = pureBuildSelectionOverlayRects(range, containerRect);

      if (overlayRects.length > 0 && containerRect) {
        const left = Math.min(...overlayRects.map((r) => r.left));
        const top = Math.min(...overlayRects.map((r) => r.top));
        const right = Math.max(...overlayRects.map((r) => r.left + r.width));
        const bottom = Math.max(...overlayRects.map((r) => r.top + r.height));

        const selectionCenter = left + (right - left) / 2;
        const anchorX = clampSelectionPoint(
          selectionCenter,
          TOOLBAR_EDGE_PADDING + TOOLBAR_WIDTH_ESTIMATE / 2,
          unscaledWidth - TOOLBAR_EDGE_PADDING - TOOLBAR_WIDTH_ESTIMATE / 2,
        );
        const canPlaceAbove = top > 100;

        selectionPlacement = canPlaceAbove ? 'above' : 'below';
        nextPosition = {
          x: anchorX,
          y: canPlaceAbove ? top - TOOLBAR_OFFSET : bottom + TOOLBAR_OFFSET,
        };
        selectionBounds = { left, top, right, bottom };
      }
    } catch (e) {
      handleError(e, 'reader', {
        format: 'pdf',
        pageNumber: deps.getCurrentPage(),
        action: 'selection_state_update',
      });
      overlayRects = [];
    }

    if (!nextPosition && containerRect) {
      selectionPlacement = 'below';
      nextPosition = { x: unscaledWidth / 2, y: 20 };
    }

    if (nextPosition && containerRect) {
      let viewLeft = containerRect.left + selectionBounds.left * scale;
      let viewTop = containerRect.top + selectionBounds.top * scale;
      let viewRight = containerRect.left + selectionBounds.right * scale;
      let viewBottom = containerRect.top + selectionBounds.bottom * scale;

      try {
        const range = selection.getRangeAt(0);
        const rawRects = Array.from(range.getClientRects()).filter(
          (r) => r.width > 0 && r.height > 0,
        );
        if (rawRects.length === 0) {
          const fallbackRect = range.getBoundingClientRect();
          if (fallbackRect.width > 0 && fallbackRect.height > 0) rawRects.push(fallbackRect);
        }
        if (rawRects.length > 0) {
          viewLeft = Math.min(...rawRects.map((r) => r.left));
          viewTop = Math.min(...rawRects.map((r) => r.top));
          viewRight = Math.max(...rawRects.map((r) => r.right));
          viewBottom = Math.max(...rawRects.map((r) => r.bottom));
        }
      } catch (err) {
        handleError(err, 'reader', {
          format: 'pdf',
          pageNumber: deps.getCurrentPage(),
          action: 'read_client_rects',
        });
      }

      const normalizedRects = overlayRects.map((r) => ({
        left: r.left / scale,
        top: r.top / scale,
        width: r.width / scale,
        height: r.height / scale,
      }));

      deps.onselection?.({
        text,
        bounds: {
          left: viewLeft - containerRect.left,
          top: viewTop - containerRect.top,
          right: viewRight - containerRect.left,
          bottom: viewBottom - containerRect.top,
        },
        container: {
          left: containerRect.left,
          top: containerRect.top,
          width: containerRect.width,
          height: containerRect.height,
        },
        placement: selectionPlacement,
        rects: normalizedRects,
        pageNumber: deps.getCurrentPage(),
      });

      if (debugState.enabled) {
        if (text && overlayRects.length > 0) {
          const r = overlayRects[0];
          debugState.selection = {
            text,
            source: 'pdf',
            rectCount: overlayRects.length,
            firstRect: { top: r.top, left: r.left, width: r.width, height: r.height },
          };
        } else {
          debugState.selection = null;
        }
      }
    } else {
      if (debugState.enabled) debugState.selection = null;
      clearSelectionUi();
    }
  }

  return {
    get selectionPlacement(): 'above' | 'below' {
      return selectionPlacement;
    },
    set selectionPlacement(v: 'above' | 'below') {
      selectionPlacement = v;
    },
    get activeHighlightId(): string | null {
      return activeHighlightId;
    },
    set activeHighlightId(v: string | null) {
      activeHighlightId = v;
    },
    clearSelectionUi,
    hideToolbar,
    dismissHighlightManager,
    handleHighlightClick,
    handleTextSelection,
    updateSelectionState,
    buildSelectionOverlayRects: pureBuildSelectionOverlayRects,
  };
}

export type PdfSelectionState = ReturnType<typeof createPdfSelectionState>;
