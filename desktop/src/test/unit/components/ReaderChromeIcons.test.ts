import { fireEvent, render, screen } from '@testing-library/svelte';
import { describe, expect, it, vi } from 'vitest';
import ReaderTopBar from '$lib/features/reader/chrome/ReaderTopBar.svelte';
import ReaderBottomBar from '$lib/features/reader/chrome/ReaderBottomBar.svelte';
import ReaderTextSettings from '$lib/features/reader/chrome/ReaderTextSettings.svelte';
import type { MessageKey } from '$lib/shared/i18n';
import type { ReaderSettings } from '$lib/shared/types';

/**
 * Reader-chrome icon migration (slice 11), retargeted by the bar unification.
 * The bookmarks glyph left the header for the bottom bar, so the header holds
 * four icon-only controls instead of five and the bottom bar owns its own
 * ordered glyph list. The arrays stay exact-equality: the glyphs are laid out
 * in source order and a reorder is a UI change. Do NOT switch these to
 * `.sort()` or `arrayContaining` — that keeps the test green while destroying
 * the guarantee it exists for.
 */

const t = (key: MessageKey, _params?: Record<string, string | number>): string => key;

/** The glyph class lucide emits: `lucide-icon lucide-<name> ...`. */
const glyphOf = (icon: Element): string | undefined =>
  (icon.getAttribute('class') ?? '')
    .split(/\s+/)
    .find((cls) => cls.startsWith('lucide-') && cls !== 'lucide-icon');

const SVG_CONTRACT = {
  viewBox: '0 0 24 24',
  stroke: 'currentColor',
  'aria-hidden': 'true',
} as const;

/** Chrome glyphs were bumped from 1.8 with the contrast pass: a hairline on
 * navy read as decoration rather than as a control. Panel glyphs keep 1.8. */
const CHROME_STROKE_WIDTH = '2';
const PANEL_STROKE_WIDTH = '1.8';

const glyphs = (root: HTMLElement): (string | undefined)[] =>
  Array.from(root.querySelectorAll('svg.lucide-icon')).map(glyphOf);

function expectGlyphContract(icon: Element, strokeWidth: string = CHROME_STROKE_WIDTH): void {
  expect(icon.getAttribute('class')).toContain('lucide-icon');
  for (const [attr, value] of Object.entries(SVG_CONTRACT)) {
    expect(icon.getAttribute(attr), attr).toBe(value);
  }
  expect(icon.getAttribute('stroke-width')).toBe(strokeWidth);
  // The control glyphs are 16px in the chrome; a glyph nested inside another
  // control (the zoom trigger's disclosure chevron) is drawn at 12px so it
  // reads as part of that control rather than as a second one beside it.
  const width = icon.getAttribute('width');
  if (strokeWidth === CHROME_STROKE_WIDTH) {
    expect(['16', '12']).toContain(width);
  } else {
    expect(width).toBe('14');
  }
}

const readerSettings: ReaderSettings = {
  themeMode: 'paper',
  brightness: 1,
  contrast: 1,
  selectionColor: '#fde68a',
  epub: { fontSize: 100, fontFamily: '' },
  lineHeight: 1.8,
  letterSpacing: 0,
  paragraphSpacing: 1,
  textAlign: 'left',
  direction: 'ltr',
  hyphenation: false,
  verticalScrolling: false,
  paginated: false,
  margins: { top: 1, bottom: 1, left: 1, right: 1 },
  showHeader: true,
  showFooter: true,
  showPageNumbers: true,
  progressIndicator: 'percentage',
};

function renderTopBar(overrides: Record<string, unknown> = {}) {
  return render(ReaderTopBar, {
    title: 'Book',
    showTocPanel: false,
    searchPanelOpen: false,
    showTextSettings: false,
    isFullscreen: false,
    visible: true,
    t,
    onBackToHome: () => {},
    onToggleToc: () => {},
    onToggleSearch: () => {},
    onToggleTextSettings: () => {},
    onToggleFullscreen: () => {},
    ...overrides,
  });
}

function renderBottomBar(overrides: Record<string, unknown> = {}) {
  return render(ReaderBottomBar, {
    title: 'Book',
    bookProgress: 42,
    currentPdfPage: 3,
    totalPdfPages: 10,
    viewerKind: 'epub' as 'pdf' | 'epub' | null,
    isFullscreen: false,
    visible: true,
    currentPage: 5,
    totalPages: 10,
    zoomPercent: 100,
    bookmarkCount: 0,
    bookmarksLoading: false,
    showBookmarks: false,
    t,
    onPrev: () => {},
    onNext: () => {},
    onGoToPage: async () => true,
    onFit: () => {},
    onZoomSelect: () => {},
    onToggleBookmarks: () => {},
    ...overrides,
  });
}

describe('reader chrome icon migration', () => {
  it('renders the bottom bar paging glyphs (the top bar owns TOC/fullscreen)', () => {
    const { container } = renderBottomBar({ viewerKind: 'epub' });
    for (const icon of container.querySelectorAll('svg.lucide-icon')) expectGlyphContract(icon);
    expect(glyphs(container)).toContain('lucide-chevron-left');
    expect(glyphs(container)).toContain('lucide-chevron-right');
  });

  it('does not render the TOC or fullscreen controls in the bottom bar', () => {
    renderBottomBar();
    expect(screen.queryByLabelText('reader.tabla_contenidos')).toBeNull();
    expect(screen.queryByLabelText('pdf.fullscreenEnter')).toBeNull();
    expect(screen.queryByLabelText('pdf.fullscreenExit')).toBeNull();
    expect(screen.getByLabelText('reader.prev_page')).toBeInTheDocument();
    expect(screen.getByLabelText('reader.next_page')).toBeInTheDocument();
  });

  it('renders the four top bar icons as components on the closed-tool branch', () => {
    const { container } = renderTopBar();

    expect(glyphs(container)).toEqual([
      'lucide-chevron-left',
      'lucide-menu',
      'lucide-search',
      'lucide-settings',
      'lucide-expand',
    ]);
    for (const icon of container.querySelectorAll('svg.lucide-icon')) expectGlyphContract(icon);
  });

  it('swaps all four top bar ternaries to the close glyph when their panel is open', () => {
    const { container } = renderTopBar({
      showTocPanel: true,
      searchPanelOpen: true,
      showTextSettings: true,
      showBookmarks: true,
      isFullscreen: true,
    });

    expect(glyphs(container)).toEqual([
      'lucide-chevron-left',
      'lucide-x',
      'lucide-x',
      'lucide-x',
      'lucide-shrink',
    ]);
  });

  // There is no separate status strip anymore: fullscreen renders the same
  // single top bar, so only the window-mode glyph differs.
  it('renders the single top bar in fullscreen, without its own paging chevrons', () => {
    const { container } = renderTopBar({
      isFullscreen: true,
      currentPage: 3,
      totalPages: 10,
      onGoToPage: async () => true,
    });

    expect(glyphs(container)).toEqual([
      'lucide-chevron-left',
      'lucide-menu',
      'lucide-search',
      'lucide-settings',
      'lucide-shrink',
    ]);
  });

  it('renders the bottom bar EPUB glyphs in source order', () => {
    const { container } = renderBottomBar({ viewerKind: 'epub', showBookmarks: false });
    // paging chevrons, the zoom trigger's disclosure chevron, then the
    // (unselected) bookmarks glyph.
    expect(glyphs(container)).toEqual([
      'lucide-chevron-left',
      'lucide-chevron-right',
      'lucide-chevron-down',
      'lucide-bookmark',
    ]);
    for (const icon of container.querySelectorAll('svg.lucide-icon')) expectGlyphContract(icon);
  });

  it('renders the bottom bar PDF glyphs in source order, with the fit glyphs', () => {
    const { container } = renderBottomBar({ viewerKind: 'pdf', showBookmarks: false });
    // Fit is PDF-only, so the ordering guarantee is asserted per format.
    expect(glyphs(container)).toEqual([
      'lucide-chevron-left',
      'lucide-chevron-right',
      'lucide-unfold-horizontal',
      'lucide-frame',
      'lucide-chevron-down',
      'lucide-bookmark',
    ]);
  });

  it('swaps the bottom bar bookmarks glyph when the panel is open', () => {
    const { container } = renderBottomBar({ viewerKind: 'epub', showBookmarks: true });
    expect(glyphs(container)).toEqual([
      'lucide-chevron-left',
      'lucide-chevron-right',
      'lucide-chevron-down',
      'lucide-x',
    ]);
  });

  it('renders the saved-toast glyph through the direct lucide component', async () => {
    const { container } = render(ReaderTextSettings, {
      open: true,
      format: 'epub',
      readerSettings,
      onSettingsChange: () => {},
      t,
      onClose: () => {},
    });

    expect(container.querySelector('svg.lucide-check')).toBeNull();

    await fireEvent.click(screen.getByTestId('font-size-inc'));

    const toastIcon = container.querySelector('svg.lucide-check');
    expect(toastIcon).not.toBeNull();
    expect(toastIcon?.getAttribute('class')).toContain('shrink-0');
    if (!toastIcon) throw new Error('the saved toast did not render its check glyph');
    expectGlyphContract(toastIcon, PANEL_STROKE_WIDTH);
  });
});

describe('one page input implementation (U1.5)', () => {
  it('the single page field commits through onGoToPage', async () => {
    const onGoToPage = vi.fn().mockResolvedValue(true);
    renderBottomBar({ currentPage: 3, totalPages: 10, onGoToPage });
    const input = screen.getByLabelText('reader.page_input');
    await fireEvent.change(input, { target: { value: '7' } });
    expect(onGoToPage).toHaveBeenCalledWith(7);
  });

  it('the single page field reverts out-of-range values to the current page', async () => {
    const onGoToPage = vi.fn();
    renderBottomBar({ currentPage: 3, totalPages: 10, onGoToPage });
    const input = screen.getByLabelText('reader.page_input') as HTMLInputElement;
    await fireEvent.change(input, { target: { value: '20' } });
    expect(onGoToPage).not.toHaveBeenCalled();
    expect(input.value).toBe('3');
  });
});
