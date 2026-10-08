import { logger } from '$lib/shared/logger/Logger';

type DebugReaderInfo = {
  format: 'pdf' | 'epub' | null;
  isTocOpen: boolean;
  isSearchOpen: boolean;
  isFullscreen: boolean;
  pageInfo: string;
  scale: number;
} | null;

type DebugRect = {
  top: number;
  left: number;
  width: number;
  height: number;
};

type DebugSelectionInfo = {
  text: string;
  source: 'pdf' | 'epub';
  rectCount: number;
  firstRect: DebugRect;
} | null;

const BREAKPOINTS = [
  { name: 'sm', min: 0, max: 639 },
  { name: 'md', min: 640, max: 767 },
  { name: 'lg', min: 768, max: 1023 },
  { name: 'xl', min: 1024, max: 1279 },
  { name: '2xl', min: 1280, max: Infinity },
] as const;

type EpubSelectionDebug = {
  iframeRect: { left: number; top: number; width: number; height: number } | null;
  lastRawMessage: {
    type: string;
    pageNumber: number | null;
    hasText: boolean;
    textPreview: string;
    hasBounds: boolean;
    hasContainer: boolean;
    hasCfi: boolean;
    cfiPreview: string;
  } | null;
  postMessageCount: number;
  emptyTextMessageCount: number;
  guardResult:
    | 'pass'
    | 'drop-chapter-mismatch'
    | 'drop-empty-text'
    | 'drop-no-handler'
    | 'drop-unknown-type'
    | 'none';
  currentChapterIndex: number | null;
  onselectionCalled: number;
  onselectionclearCalled: number;
  parentState: {
    showToolbar: boolean;
    selectedText: string;
    selectionBounds: { left: number; top: number; right: number; bottom: number } | null;
    selectionContainer: { left: number; top: number; width: number; height: number } | null;
  };
  computedToolbarX: number | null;
  computedToolbarY: number | null;
  rectCount: number;
  // Issue 2: dismiss
  dismissToolbarCallCount: number;
  lastDismissTrigger: string;
  // Issue 3: color picker
  colorPickCount: number;
  lastPickedColor: string;
  saveHighlightCallCount: number;
  saveHighlightLastError: string;
  persistedHighlightsCount: number;
  // epub-highlight-bugfix: append-only deduped list of highlight IDs that
  // failed to apply. Populated by `epub-hl-failed` postMessage from the
  // iframe overlay (cfi-unresolved, invalid-color) and from
  // `saveHighlight` reject in ReaderWorkspace. Bounded by Set-like dedup.
  failedHighlightIds: string[];
};

class DebugState {
  enabled = $state(false);
  currentRoute = $state('');
  viewportWidth = $state(0);
  viewportHeight = $state(0);
  readerInfo: DebugReaderInfo = $state(null);
  selection: DebugSelectionInfo = $state(null);
  epub: EpubSelectionDebug = $state({
    iframeRect: null,
    lastRawMessage: null,
    postMessageCount: 0,
    emptyTextMessageCount: 0,
    guardResult: 'none',
    currentChapterIndex: null,
    onselectionCalled: 0,
    onselectionclearCalled: 0,
    parentState: {
      showToolbar: false,
      selectedText: '',
      selectionBounds: null,
      selectionContainer: null,
    },
    computedToolbarX: null,
    computedToolbarY: null,
    rectCount: 0,
    dismissToolbarCallCount: 0,
    lastDismissTrigger: '—',
    colorPickCount: 0,
    lastPickedColor: '—',
    saveHighlightCallCount: 0,
    saveHighlightLastError: '',
    persistedHighlightsCount: 0,
    failedHighlightIds: [],
  });

  get breakpoint(): string {
    const bp = BREAKPOINTS.find((b) => this.viewportWidth >= b.min && this.viewportWidth <= b.max);
    return bp?.name ?? 'lg';
  }

  updateViewport(): void {
    this.viewportWidth = window.innerWidth;
    this.viewportHeight = window.innerHeight;
  }

  getSnapshot(): string {
    const lines: string[] = [
      `Route: ${this.currentRoute || '—'}`,
      `Viewport: ${this.viewportWidth}x${this.viewportHeight} (${this.breakpoint})`,
    ];
    if (this.readerInfo) {
      lines.push(
        `Reader: ${this.readerInfo.format ?? '—'} | ${this.readerInfo.pageInfo} | fullscreen:${this.readerInfo.isFullscreen}`,
      );
    }
    if (this.selection) {
      lines.push(`Selection: ${this.selection.source} | "${this.selection.text.slice(0, 80)}"`);
    }
    return lines.join('\n');
  }

  async copySnapshot(): Promise<void> {
    const text = this.getSnapshot();
    try {
      await navigator.clipboard.writeText(text);
    } catch {
      logger.warn('Failed to copy debug snapshot', undefined, 'app_shell');
    }
  }

  resetSelection(): void {
    this.selection = null;
  }
}

export const debugState = new DebugState();
export type { DebugReaderInfo, DebugSelectionInfo, EpubSelectionDebug };
