import { render, screen } from '@testing-library/svelte';
import { describe, expect, it, vi, beforeEach } from 'vitest';
import EpubNativeViewer from '$lib/features/reader/viewer-epub/EpubNativeViewer.svelte';

// Mock Tauri invoke
const mockInvoke = vi.fn();
vi.mock('@tauri-apps/api/core', () => ({
  invoke: (...args: unknown[]) => mockInvoke(...args),
  convertFileSrc: vi.fn(
    (path: string) => `tauri://asset.localhost/${String(path).replace(/\\/g, '/')}`,
  ),
}));

const t = (key: string) => key;

describe('EpubNativeViewer', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    // Make parse_epub return minimal metadata quickly for loading state testing
    mockInvoke.mockImplementation((cmd: string) => {
      if (cmd === 'parse_epub') {
        return Promise.resolve({
          title: 'Test Book',
          author: 'Test Author',
          language: 'en',
          publisher: null,
          chapters: [{ index: 0, id: '0', label: 'Chapter 1', href: 'chap1.xhtml' }],
          totalChapters: 1,
          resourcesPath: '/tmp/resources',
        });
      }
      if (cmd === 'get_epub_chapter') {
        return Promise.resolve({
          index: 0,
          html: `<!DOCTYPE html><html><head>
            <link rel="stylesheet" href="../css/book.css">
            <style>p.title { font-weight: 700; }</style>
          </head><body><p class="title">Hello</p><p>World</p></body></html>`,
          mime: 'application/xhtml+xml',
          chapterBasePath: 'OEBPS/Text',
          chapterPath: 'OEBPS/Text/chapter1.xhtml',
        });
      }
      if (cmd === 'index_epub_text') {
        return Promise.resolve();
      }
      return Promise.reject(new Error(`Unknown command: ${cmd}`));
    });
  });

  // ─── Task 1.3: Loading progress indicator ────────────────
  describe('Loading Progress (1.3)', () => {
    it('shows animated progress bar during loading state', async () => {
      // Delay invoke to keep loading state active
      mockInvoke.mockImplementation(
        () =>
          new Promise(() => {
            /* never resolves - keeps loading */
          }),
      );

      render(EpubNativeViewer, {
        filePath: '/test/book.epub',
        bookId: 'test-book',
        t,
      });

      // Should show the progress bar container div while loading
      const progressContainer = document.querySelector('.h-1.w-full');
      expect(progressContainer).toBeTruthy();
      expect(progressContainer?.classList.contains('bg-(--color-border)/20')).toBe(true);
      expect(progressContainer?.classList.contains('overflow-hidden')).toBe(true);
    });

    it('replaces progress bar with iframe after loading completes', async () => {
      render(EpubNativeViewer, {
        filePath: '/test/book.epub',
        bookId: 'test-book',
        t,
      });

      // After loading, the iframe should appear
      const iframe = await screen.findByTitle('chapter');
      expect(iframe).toBeInTheDocument();
      // Progress bar should be gone
      const progressBar = document.querySelector('.h-1.w-full');
      expect(progressBar).toBeFalsy();
    });
  });

  // ─── Task 1.2: Fullscreen props ──────────────────────────
  describe('Fullscreen Support (1.2)', () => {
    it('renders container with fullscreen classes when isFullscreen is true', async () => {
      render(EpubNativeViewer, {
        filePath: '/test/book.epub',
        bookId: 'test-book',
        isFullscreen: true,
        t,
      });

      // Wait for loading to complete
      await screen.findByTitle('chapter');

      // The iframe container div should exist
      const chapterDiv = document.querySelector('.flex-1');
      expect(chapterDiv).toBeTruthy();
    });
  });

  // ─── Task 1.1: Text Selection via postMessage ────────────
  describe('Text Selection (1.1)', () => {
    it('fires onselection when epub-selection postMessage arrives', async () => {
      const onselection = vi.fn();

      render(EpubNativeViewer, {
        filePath: '/test/book.epub',
        bookId: 'test-book',
        t,
        onselection,
      });

      // Wait for component to mount
      await screen.findByTitle('chapter');

      // Simulate postMessage from iframe
      const selectionEvent = {
        type: 'epub-selection' as const,
        text: 'selected text',
        bounds: { left: 10, top: 20, right: 100, bottom: 40 },
        container: { left: 0, top: 0, width: 800, height: 600 },
        rects: [{ left: 10, top: 20, width: 90, height: 20 }],
        pageNumber: 0,
        cfi: null as string | null,
      };

      window.dispatchEvent(
        new MessageEvent('message', {
          data: selectionEvent,
          origin: window.origin,
        }),
      );

      expect(onselection).toHaveBeenCalledTimes(1);
      expect(onselection).toHaveBeenCalledWith({
        text: 'selected text',
        bounds: { left: 10, top: 20, right: 100, bottom: 40 },
        container: { left: 0, top: 0, width: 800, height: 600 },
        placement: 'epub-chapter',
        rects: [{ left: 10, top: 20, width: 90, height: 20 }],
        pageNumber: 0,
        cfi: null,
        quote: null,
        chapterTitle: null,
      });
    });

    it('ignores non-selection postMessage events', async () => {
      const onselection = vi.fn();

      render(EpubNativeViewer, {
        filePath: '/test/book.epub',
        bookId: 'test-book',
        t,
        onselection,
      });

      await screen.findByTitle('chapter');

      // Dispatch a non-selection message
      window.dispatchEvent(
        new MessageEvent('message', {
          data: { type: 'some-other-event', payload: 'irrelevant' },
          origin: window.origin,
        }),
      );

      expect(onselection).not.toHaveBeenCalled();
    });

    it('ignores messages with empty text selection', async () => {
      const onselection = vi.fn();

      render(EpubNativeViewer, {
        filePath: '/test/book.epub',
        bookId: 'test-book',
        t,
        onselection,
      });

      await screen.findByTitle('chapter');

      // Dispatch selection message with empty text
      window.dispatchEvent(
        new MessageEvent('message', {
          data: { type: 'epub-selection', text: '', bounds: {} },
          origin: window.origin,
        }),
      );

      expect(onselection).not.toHaveBeenCalled();
    });
  });

  // ─── Selection script injection ──────────────────────────
  describe('Selection Script Injection (1.1)', () => {
    it('injects selection detection script into srcdoc', async () => {
      render(EpubNativeViewer, {
        filePath: '/test/book.epub',
        bookId: 'test-book',
        t,
      });

      await screen.findByTitle('chapter');

      // The iframe should have srcdoc with the selection script
      const iframe = document.querySelector('iframe');
      expect(iframe).toBeTruthy();

      const srcdoc = iframe?.getAttribute('srcdoc') || '';
      expect(srcdoc).toContain('mouseup');
      expect(srcdoc).toContain('window.parent.postMessage');
      expect(srcdoc).toContain('epub-selection');
      expect(srcdoc).toContain('epub-resize');
      expect(srcdoc).toContain('nexo-reader-overrides');
      expect(srcdoc).toContain('OEBPS/css/book.css');
      expect(srcdoc).toContain('font-weight: 700');
    });

    it('pins the ::highlight() style element in srcdoc, separate from nexo-reader-overrides', async () => {
      render(EpubNativeViewer, {
        filePath: '/test/book.epub',
        bookId: 'test-book',
        t,
      });

      await screen.findByTitle('chapter');

      const iframe = document.querySelector('iframe');
      expect(iframe).toBeTruthy();
      const srcdoc = iframe?.getAttribute('srcdoc') || '';

      // REQ-STYLING / design D4: a dedicated style element carries the
      // ::highlight() rules. The default (yellow) canonical color must
      // render at alpha 0.4.
      expect(srcdoc).toContain('<style id="nexo-highlight-styles">');
      expect(srcdoc).toContain(
        '::highlight(epub-hl-yellow) { background-color: rgba(250, 204, 21, 0.4); }',
      );

      // The rules must NOT live inside nexo-reader-overrides:
      // refreshReaderStyles() rewrites that element's textContent on
      // settings change and would wipe them. Parse the srcdoc to check
      // element-level containment instead of relying on string order.
      const parsed = new DOMParser().parseFromString(srcdoc, 'text/html');
      const highlightStyle = parsed.getElementById('nexo-highlight-styles');
      expect(highlightStyle).toBeTruthy();
      expect(highlightStyle?.textContent ?? '').toContain('::highlight(epub-hl-yellow)');
      const overrides = parsed.getElementById('nexo-reader-overrides');
      expect(overrides?.textContent ?? '').not.toContain('::highlight(');
    });
  });

  // ─── Task 5.2: Floating pill removed, EpubControls present ─────
  describe('EpubControls Integration (5.2)', () => {
    it('does NOT render floating pill overlay div', async () => {
      render(EpubNativeViewer, {
        filePath: '/test/book.epub',
        bookId: 'test-book',
        t,
      });

      await screen.findByTitle('chapter');

      // The old floating pill was an overlay div at absolute bottom-4
      const pillContainer = document.querySelector('.absolute.bottom-4');
      expect(pillContainer).toBeFalsy();
    });

    it('renders EpubControls with TOC button in the viewer', async () => {
      render(EpubNativeViewer, {
        filePath: '/test/book.epub',
        bookId: 'test-book',
        t,
      });

      await screen.findByTitle('chapter');

      // EpubControls renders a TOC button with data-testid="epub-toc"
      const tocBtn = await screen.findByTestId('epub-toc');
      expect(tocBtn).toBeInTheDocument();
    });

    it('renders EpubControls with prev/next/font-size controls', async () => {
      render(EpubNativeViewer, {
        filePath: '/test/book.epub',
        bookId: 'test-book',
        t,
      });

      await screen.findByTitle('chapter');

      expect(screen.getByTestId('epub-prev')).toBeInTheDocument();
      expect(screen.getByTestId('epub-next')).toBeInTheDocument();
      expect(screen.getByTestId('zoom-dropdown-trigger')).toBeInTheDocument();
    });

    it('renders EpubControls with fullscreen toggle', async () => {
      render(EpubNativeViewer, {
        filePath: '/test/book.epub',
        bookId: 'test-book',
        t,
        isFullscreen: false,
        onToggleFullscreen: vi.fn(),
      });

      await screen.findByTitle('chapter');

      const fsBtn = screen.getByTestId('epub-fullscreen');
      expect(fsBtn).toBeInTheDocument();
    });
  });

  // ─── Menu 2: highlight click postMessage ──────────────
  describe('Menu 2 (highlight click) postMessage', () => {
    it('translates epub-highlight-click coords to parent-viewport and calls onHighlightAction', async () => {
      const onHighlightAction = vi.fn();

      render(EpubNativeViewer, {
        filePath: '/test/book.epub',
        bookId: 'test-book',
        t,
        onHighlightAction,
      });

      const iframe = (await screen.findByTitle('chapter')) as HTMLIFrameElement;
      expect(iframe).toBeTruthy();

      // The test book has chapter index 0 (0-based). We dispatch a
      // click from the iframe at iframe-local (50, 80) and verify the
      // parent translates it to parent-viewport by adding the iframe
      // element's bounding rect.
      const frameRect = iframe.getBoundingClientRect();
      const iframeLocalX = 50;
      const iframeLocalY = 80;

      window.dispatchEvent(
        new MessageEvent('message', {
          data: {
            type: 'epub-highlight-click',
            id: 'hl-123',
            x: iframeLocalX,
            y: iframeLocalY,
            color: '#4ADE80',
            pageNumber: 0,
          },
          origin: window.origin,
        }),
      );

      expect(onHighlightAction).toHaveBeenCalledTimes(1);
      expect(onHighlightAction).toHaveBeenCalledWith('open', 'hl-123', {
        color: '#4ADE80',
        x: iframeLocalX + frameRect.left,
        y: iframeLocalY + frameRect.top,
      });
    });
  });

  // ─── SEL-4: chapter guard drops stale postMessages ─────────
  describe('SEL-4 (stale chapter guard)', () => {
    it('drops an epub-selection postMessage whose pageNumber is not the current chapter', async () => {
      const onselection = vi.fn();

      render(EpubNativeViewer, {
        filePath: '/test/book.epub',
        bookId: 'test-book',
        t,
        onselection,
      });

      await screen.findByTitle('chapter');

      // The test book has 1 chapter so currentChapterIndex is 0.
      // Send a selection with pageNumber=2 -- must be dropped.
      window.dispatchEvent(
        new MessageEvent('message', {
          data: {
            type: 'epub-selection',
            text: 'some text',
            bounds: { left: 0, top: 0, right: 100, bottom: 20 },
            container: { left: 0, top: 0, width: 800, height: 600 },
            rects: [{ left: 0, top: 0, width: 100, height: 20 }],
            pageNumber: 2,
            cfi: null,
          },
          origin: window.origin,
        }),
      );

      expect(onselection).not.toHaveBeenCalled();
    });

    it('drops an epub-highlight-click postMessage whose pageNumber is not the current chapter', async () => {
      const onHighlightAction = vi.fn();

      render(EpubNativeViewer, {
        filePath: '/test/book.epub',
        bookId: 'test-book',
        t,
        onHighlightAction,
      });

      await screen.findByTitle('chapter');

      window.dispatchEvent(
        new MessageEvent('message', {
          data: {
            type: 'epub-highlight-click',
            id: 'hl-stale',
            x: 10,
            y: 20,
            color: '#FACC15',
            pageNumber: 99,
          },
          origin: window.origin,
        }),
      );

      expect(onHighlightAction).not.toHaveBeenCalled();
    });
  });

  // ─── NFR-2: smoke test for iframe + frameElement ────────────
  describe('NFR-2 (iframe smoke)', () => {
    // jsdom does not fully implement srcdoc-loaded iframes: the
    // `frameElement` property on the iframe's contentWindow is not
    // populated synchronously after `srcdoc` is set, and the `load`
    // event does not fire from srcdoc-initialized documents. The
    // `EpubHighlightOverlay` render path is exercised in production by
    // Tauri's WebView2 / WKWebView (where srcdoc + frameElement work
    // as expected). The unit test below asserts the parent side:
    // the iframe element is rendered, has a non-empty srcdoc, and is
    // queryable. This is a defensive smoke test for the parent wiring
    // only; the iframe-internal CFI bridge round-trip is covered
    // exhaustively by `cfiBridge.test.ts`.
    it('renders the iframe with a non-empty srcdoc (parent-side smoke)', async () => {
      render(EpubNativeViewer, {
        filePath: '/test/book.epub',
        bookId: 'test-book',
        t,
      });

      const iframe = (await screen.findByTitle('chapter')) as HTMLIFrameElement;
      expect(iframe).toBeTruthy();
      expect(iframe.tagName).toBe('IFRAME');
      const srcdoc = iframe.getAttribute('srcdoc');
      expect(srcdoc).toBeTruthy();
      expect(srcdoc!.length).toBeGreaterThan(0);
      // The srcdoc must include the reader override style id, the
      // chapter content, and the base element pointing to the
      // resources path. (We do NOT assert on the inlined script
      // content here: `buildChapterSrcdoc` strips the <script> tags
      // it appends to the doc before serialising, so the inlined JS
      // does not appear in the srcdoc attribute. The scripts run from
      // the iframe's parsed document in production.)
      expect(srcdoc).toContain('nexo-reader-overrides');
      expect(srcdoc).toContain('Hello');
    });

    it('rewrites SVG image xlink:href cover paths into asset URLs (fixes 403 cover)', async () => {
      mockInvoke.mockImplementation((cmd: string) => {
        if (cmd === 'parse_epub') {
          return Promise.resolve({
            title: 'Cover Book',
            author: 'Author',
            language: 'es',
            publisher: null,
            chapters: [{ index: 0, id: '0', label: 'Cubierta', href: 'OEBPS/Text/cubierta.xhtml' }],
            totalChapters: 1,
            resourcesPath: '/tmp/resources',
          });
        }
        if (cmd === 'get_epub_chapter') {
          return Promise.resolve({
            index: 0,
            html: `<!DOCTYPE html><html><head><title>Cubierta</title></head>
              <body><div class="cubierta"><svg xmlns="http://www.w3.org/2000/svg" xmlns:xlink="http://www.w3.org/1999/xlink"><image height="900" width="600" xlink:href="../Images/cover.jpg"/></svg></div></body></html>`,
            mime: 'application/xhtml+xml',
            chapterBasePath: 'OEBPS/Text',
            chapterPath: 'OEBPS/Text/cubierta.xhtml',
          });
        }
        if (cmd === 'index_epub_text') return Promise.resolve();
        return Promise.reject(new Error(`Unknown: ${cmd}`));
      });

      render(EpubNativeViewer, {
        filePath: '/test/cover.epub',
        bookId: 'cover-book',
        t,
      });

      const iframe = (await screen.findByTitle('chapter')) as HTMLIFrameElement;
      const srcdoc = iframe.getAttribute('srcdoc');
      expect(srcdoc).toBeTruthy();
      // chapterPath dir 'OEBPS/Text/' + '../Images/cover.jpg' resolves to
      // 'OEBPS/Images/cover.jpg', then convertFileSrc maps it to a tauri URL.
      expect(srcdoc).toContain('OEBPS/Images/cover.jpg');
      // The xlink:href must have been rewritten to the asset URL (not left
      // as the relative ../Images/cover.jpg which 403s).
      expect(srcdoc).not.toContain('xlink:href="../Images/cover.jpg"');
    });

    it.todo(
      'iframe.contentWindow.frameElement is the iframe element and getBoundingClientRect returns finite numbers (Tauri webview smoke)',
    );
  });
});
