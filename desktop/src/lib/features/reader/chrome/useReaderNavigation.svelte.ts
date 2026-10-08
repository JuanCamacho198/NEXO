import type { TocEntry } from './ReaderTocPanel.svelte';
import type PdfViewer from '../viewer-pdf/PdfViewer.svelte';
import type EpubNativeViewer from '../viewer-epub/EpubNativeViewer.svelte';
import type { LibraryBookDto } from '$lib/shared/types/library';
import type { ViewerHandle } from '../viewer-shared/Viewer';
import { captureBreadcrumb } from '$lib/shared/logger/BreadcrumbsStore';
import { BREADCRUMB_LABELS } from '$lib/shared/logger/breadcrumbTypes';

type ActiveBook = LibraryBookDto & { filePath: string };

export type ReaderNavigationDeps = {
  getViewer?: () => ViewerHandle;
  getRefs?: () => { pdf: PdfViewer | null; epub: EpubNativeViewer | null };
  getActiveBook?: () => ActiveBook | null;
  onPdfPageChange?: (page: number, total: number) => void;
  onEpubLocationChange?: (cfi: string, pct: number) => void;
  getDebugState?: () => { readerInfo: unknown; enabled: boolean } | null;
};

export function createReaderNavigation(deps: ReaderNavigationDeps): {
  currentPdfPage: number;
  totalPdfPages: number;
  currentEpubChapter: number;
  tocEntries: TocEntry[];
  tocNavigate: TocEntry | null;
  showTocPanel: boolean;
  readonly bookProgress: number;
  readonly headerCurrentPage: number;
  readonly headerTotalPages: number;
  readonly showHeaderReadingControls: boolean;
  readonly prevDisabled: boolean;
  readonly nextDisabled: boolean;
  handlePdfPageChange(page: number, total: number): void;
  handleEpubLocationChange(cfi: string, pct: number): void;
  handleTocReady(entries: TocEntry[]): void;
  handleTocNavigate(entry: TocEntry): void;
  toggleTocPanel(): void;
  goPrev(): boolean;
  goNext(): boolean;
  handleHeaderGoToPage(page: number): Promise<boolean>;
  cleanup(): void;
} {
  let currentPdfPage = $state(0);
  let totalPdfPages = $state(0);
  let currentEpubChapter = $state(0);
  let tocEntries = $state<TocEntry[]>([]);
  let tocNavigate = $state<TocEntry | null>(null);
  let showTocPanel = $state(false);

  const resolveViewer = (): ViewerHandle => {
    if (deps.getViewer) return deps.getViewer();
    // fallback for legacy tests: build minimal viewer from getRefs/getActiveBook without polluting chrome with format branches
    const refs = deps.getRefs?.() ?? { pdf: null, epub: null };
    const book = deps.getActiveBook?.() ?? null;
    const fmtRaw = (book as unknown as { format?: unknown })?.format;
    const fmt = typeof fmtRaw === 'string' ? String(fmtRaw).toLowerCase() : '';
    const kind = fmt === 'epub' ? 'epub' : 'pdf';
    const pdf = refs.pdf as unknown as {
      navigateToPage?: (n: number) => Promise<boolean>;
      getCurrentPage?: () => number;
      getTotalPages?: () => number;
    } | null;
    const epub = refs.epub as unknown as {
      goToPrev?: () => void;
      goToNext?: () => void;
      handleGoToPage?: (n: number) => Promise<boolean>;
      getCurrentPage?: () => number;
      getTotalForHeader?: () => number;
    } | null;
    return {
      get kind() {
        return kind as ViewerHandle['kind'];
      },
      navigatePrev() {
        if (kind === 'pdf') {
          if (!pdf?.navigateToPage) return false;
          if (currentPdfPage <= 1) return false;
          return (pdf.navigateToPage(currentPdfPage - 1) ?? false) as unknown as boolean;
        }
        if (!epub?.goToPrev) return false;
        if (currentEpubChapter <= 0) return false;
        epub.goToPrev();
        return true;
      },
      navigateNext() {
        if (kind === 'pdf') {
          if (!pdf?.navigateToPage) return false;
          if (totalPdfPages > 0 && currentPdfPage >= totalPdfPages) return false;
          return (pdf.navigateToPage(currentPdfPage + 1) ?? false) as unknown as boolean;
        }
        if (!epub?.goToNext) return false;
        epub.goToNext();
        return true;
      },
      goToPage(n: number) {
        if (kind === 'pdf') return pdf?.navigateToPage?.(n) ?? Promise.resolve(false);
        return epub?.handleGoToPage?.(n) ?? Promise.resolve(false);
      },
      setScaleOrZoom(_pct: number) {},
      getScaleOrZoom() {
        return 100;
      },
      getCurrentPage() {
        if (kind === 'pdf') return pdf?.getCurrentPage?.() ?? (currentPdfPage || 1);
        return epub?.getCurrentPage?.() ?? (currentEpubChapter + 1 || 1);
      },
      getTotalForHeader() {
        if (kind === 'pdf') return pdf?.getTotalPages?.() ?? (totalPdfPages || 0);
        return ((epub?.getTotalForHeader?.() ?? tocEntries.length) || 0) as number;
      },
    } as ViewerHandle;
  };

  const bookProgress = $derived.by(() => {
    const v = resolveViewer();
    const cur = v.getCurrentPage();
    const total = v.getTotalForHeader();
    if (v.kind === 'pdf' && cur > 0 && total > 0) {
      return Math.round((cur / total) * 100);
    }
    return 0;
  });

  const headerCurrentPage = $derived.by(() => {
    const v = resolveViewer();
    if (v.kind === 'pdf') return currentPdfPage || v.getCurrentPage();
    if (v.kind === 'epub') return currentEpubChapter + 1 || v.getCurrentPage();
    return 1;
  });
  const headerTotalPages = $derived.by(() => {
    const v = resolveViewer();
    if (v.kind === 'pdf') return totalPdfPages || v.getTotalForHeader();
    if (v.kind === 'epub') return tocEntries.length > 0 ? tocEntries.length : v.getTotalForHeader();
    return 0;
  });
  const showHeaderReadingControls = $derived(headerTotalPages > 0);

  const prevDisabled = $derived.by(() => {
    const v = resolveViewer();
    if (v.kind === 'pdf') return currentPdfPage <= 1;
    if (v.kind === 'epub') return currentEpubChapter <= 0;
    return false;
  });

  const nextDisabled = $derived.by(() => {
    const v = resolveViewer();
    if (v.kind === 'pdf') return totalPdfPages > 0 && currentPdfPage >= totalPdfPages;
    return false;
  });

  function handlePdfPageChange(page: number, total: number): void {
    currentPdfPage = page;
    totalPdfPages = total;
    // Journey crumb: ids/counters only.
    captureBreadcrumb('navigation', BREADCRUMB_LABELS.CHAPTER_CHANGE, {
      bookId: deps.getActiveBook?.()?.id ?? null,
      pageNumber: page,
    });
    deps.onPdfPageChange?.(page, total);
  }

  function handleEpubLocationChange(cfi: string, pct: number): void {
    const match = cfi.match(/chapter:(\d+)/);
    if (match) currentEpubChapter = parseInt(match[1], 10);
    captureBreadcrumb('navigation', BREADCRUMB_LABELS.CHAPTER_CHANGE, {
      bookId: deps.getActiveBook?.()?.id ?? null,
      chapterIndex: currentEpubChapter,
    });
    deps.onEpubLocationChange?.(cfi, pct);
  }

  function handleTocReady(entries: TocEntry[]): void {
    tocEntries = entries;
  }

  function handleTocNavigate(entry: TocEntry): void {
    tocNavigate = entry;
    showTocPanel = false;
  }

  function toggleTocPanel(): void {
    showTocPanel = !showTocPanel;
  }

  function goPrev(): boolean {
    const viewer = resolveViewer();
    const result = viewer.navigatePrev();
    if (result instanceof Promise) {
      void result;
      return true;
    }
    return result;
  }

  function goNext(): boolean {
    const viewer = resolveViewer();
    const result = viewer.navigateNext();
    if (result instanceof Promise) {
      void result;
      return true;
    }
    return result;
  }

  async function handleHeaderGoToPage(page: number): Promise<boolean> {
    return resolveViewer().goToPage(page);
  }

  function cleanup(): void {}

  return {
    get currentPdfPage() {
      return currentPdfPage;
    },
    set currentPdfPage(v: number) {
      currentPdfPage = v;
    },
    get totalPdfPages() {
      return totalPdfPages;
    },
    set totalPdfPages(v: number) {
      totalPdfPages = v;
    },
    get currentEpubChapter() {
      return currentEpubChapter;
    },
    set currentEpubChapter(v: number) {
      currentEpubChapter = v;
    },
    get tocEntries() {
      return tocEntries;
    },
    set tocEntries(v: TocEntry[]) {
      tocEntries = v;
    },
    get tocNavigate() {
      return tocNavigate;
    },
    set tocNavigate(v: TocEntry | null) {
      tocNavigate = v;
    },
    get showTocPanel() {
      return showTocPanel;
    },
    set showTocPanel(v: boolean) {
      showTocPanel = v;
    },
    get bookProgress() {
      return bookProgress;
    },
    get headerCurrentPage() {
      return headerCurrentPage;
    },
    get headerTotalPages() {
      return headerTotalPages;
    },
    get showHeaderReadingControls() {
      return showHeaderReadingControls;
    },
    get prevDisabled() {
      return prevDisabled;
    },
    get nextDisabled() {
      return nextDisabled;
    },
    handlePdfPageChange,
    handleEpubLocationChange,
    handleTocReady,
    handleTocNavigate,
    toggleTocPanel,
    goPrev,
    goNext,
    handleHeaderGoToPage,
    cleanup,
  };
}

export type ReaderNavigation = ReturnType<typeof createReaderNavigation>;
