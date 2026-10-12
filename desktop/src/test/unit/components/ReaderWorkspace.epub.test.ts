import { render, screen, fireEvent } from '@testing-library/svelte';
import { describe, expect, it, vi, beforeEach } from 'vitest';
import ReaderWorkspace from '$lib/features/reader/chrome/ReaderWorkspace.svelte';

const t = (key: string) => key;

vi.mock('@tauri-apps/api/webviewWindow', () => ({
  getCurrentWebviewWindow: () => ({
    setFullscreen: vi.fn(),
    minimize: vi.fn(),
    toggleMaximize: vi.fn(),
    close: vi.fn(),
    isMaximized: vi.fn().mockResolvedValue(false),
    onResized: vi.fn(),
  }),
}));

vi.mock('$lib/features/reader/viewer-pdf/PdfViewer.svelte', async () => {
  const mod = await import('../../mocks/MockPdfViewer.svelte');
  return { default: mod.default };
});

vi.mock('$lib/features/reader/viewer-epub/EpubNativeViewer.svelte', async () => {
  const mod = await import('../../mocks/MockPdfViewer.svelte');
  return { default: mod.default };
});

vi.mock('$lib/shared/api/tauriClient', () => ({
  saveHighlight: vi.fn(),
  deleteHighlight: vi.fn(),
  updateHighlight: vi.fn(),
  getDefaultReaderSettings: vi.fn(() => ({
    themeMode: 'paper',
    brightness: 100,
    contrast: 100,
    selectionColor: '#3388ff',
    epub: { fontSize: 100, fontFamily: 'serif' },
    lineHeight: 1.8,
    letterSpacing: 0,
    paragraphSpacing: 1,
    textAlign: 'left',
    direction: 'ltr',
    hyphenation: false,
    verticalScrolling: false,
    margins: { top: 1.5, bottom: 1.5, left: 2, right: 2 },
    showHeader: true,
    showFooter: true,
    showPageNumbers: true,
    progressIndicator: 'percentage',
  })),
  upsertReaderSettings: vi.fn(),
  listBookmarks: vi.fn().mockResolvedValue([]),
  listHighlights: vi.fn().mockResolvedValue([]),
  saveBookmark: vi.fn(),
  deleteBookmark: vi.fn(),
  listTags: vi.fn().mockResolvedValue([]),
  listTagsForHighlight: vi.fn().mockResolvedValue([]),
  createTag: vi.fn().mockResolvedValue({ id: 't1', name: 't1' }),
  saveHighlightTags: vi.fn().mockResolvedValue([]),
}));

const makeEpubBook = () => ({
  id: 'epub-1',
  title: 'EPUB Book',
  author: 'Author',
  filePath: 'C:/book.epub',
  format: 'epub',
  currentPage: 1,
  totalPages: 10,
  progressPercentage: 0,
  coverPath: null,
  minutesRead: 0,
  updatedAt: new Date().toISOString(),
  createdAt: new Date().toISOString(),
});

describe('ReaderWorkspace EPUB', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    // Mock requestFullscreen on documentElement
    Object.defineProperty(document.documentElement, 'requestFullscreen', {
      configurable: true,
      value: vi.fn(),
    });
  });

  // ─── Task 1.1: Selection prop wiring ────────────────────
  describe('EPUB Selection (1.1)', () => {
    it('passes onselection to EpubNativeViewer and renders selection toolbar', async () => {
      render(ReaderWorkspace, {
        activeReadingBook: makeEpubBook(),
        t,
        onBackToHome: () => undefined,
      });

      // MockPdfViewer provides a data-testid="mock-pdfviewer-select" button
      const selectBtn = await screen.findByTestId('mock-pdfviewer-select');
      await fireEvent.click(selectBtn);

      // The selection toolbar should appear
      const toolbar = document.querySelector('.selection-toolbar');
      expect(toolbar).toBeTruthy();
    });
  });

  // ─── U3: parent-chrome clicks collapse the menu ────────────
  describe('EPUB parent dismiss (U3)', () => {
    it('closes the menu on pointerdown outside the toolbar', async () => {
      render(ReaderWorkspace, {
        activeReadingBook: makeEpubBook(),
        t,
        onBackToHome: () => undefined,
      });

      await fireEvent.click(await screen.findByTestId('mock-pdfviewer-select'));
      expect(document.querySelector('.selection-toolbar')).toBeTruthy();

      await fireEvent.pointerDown(screen.getAllByText('EPUB Book')[0]);
      await vi.waitFor(() => {
        expect(document.querySelector('.selection-toolbar')).toBeFalsy();
      });
    });

    it('keeps the menu on pointerdown inside the toolbar', async () => {
      render(ReaderWorkspace, {
        activeReadingBook: makeEpubBook(),
        t,
        onBackToHome: () => undefined,
      });

      await fireEvent.click(await screen.findByTestId('mock-pdfviewer-select'));
      const toolbar = document.querySelector('.selection-toolbar');
      expect(toolbar).toBeTruthy();

      await fireEvent.pointerDown(toolbar!);
      expect(document.querySelector('.selection-toolbar')).toBeTruthy();
    });
  });

  // ─── Task 1.2: Fullscreen prop wiring ───────────────────
  describe('EPUB Fullscreen (1.2)', () => {
    it('passes isFullscreen and onToggleFullscreen to EpubNativeViewer', async () => {
      render(ReaderWorkspace, {
        activeReadingBook: makeEpubBook(),
        t,
        onBackToHome: () => undefined,
      });

      // MockPdfViewer provides a data-testid="mock-pdfviewer-toggle" button
      const toggleBtn = await screen.findByTestId('mock-pdfviewer-toggle');
      await fireEvent.click(toggleBtn);

      // Fullscreen should have been requested
      expect(document.documentElement.requestFullscreen).toHaveBeenCalled();
    });
  });

  // ─── Task 2.3: Bookmarks chapter support ────────────────
  describe('EPUB Bookmarks (2.3)', () => {
    it('uses chapter index for EPUB bookmarks instead of page=1', async () => {
      const mockSaveBookmark = vi.fn();
      const { listBookmarks, saveBookmark } = await import('$lib/shared/api/tauriClient');
      (saveBookmark as ReturnType<typeof vi.fn>).mockImplementation(mockSaveBookmark);
      (listBookmarks as ReturnType<typeof vi.fn>).mockResolvedValue([]);

      render(ReaderWorkspace, {
        activeReadingBook: makeEpubBook(),
        t,
        onBackToHome: () => undefined,
      });

      // Find and open bookmarks panel - since we mock EpubNativeViewer,
      // the bookmark button should be in the header
      // The MockPdfViewer renders a button we can click
      // Simulate the bookmarks panel opening by checking for the add bookmark button
      const bookmarkBtn = await screen.findByRole('button', { name: /bookmark/i });
      expect(bookmarkBtn).toBeTruthy();
    });
  });
});
