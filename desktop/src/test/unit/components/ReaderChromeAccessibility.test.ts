import { fireEvent, render, screen } from '@testing-library/svelte';
import { describe, expect, it } from 'vitest';
import ReaderTopBar from '$lib/features/reader/chrome/ReaderTopBar.svelte';
import ReaderBottomBar from '$lib/features/reader/chrome/ReaderBottomBar.svelte';
import FitControls from '$lib/features/reader/chrome/FitControls.svelte';
import ZoomDropdown from '$lib/features/reader/chrome/ZoomDropdown.svelte';
import type { MessageKey } from '$lib/shared/i18n';

const t = (key: MessageKey, _params?: Record<string, string | number>): string => key;

function renderToolbar(overrides: Record<string, unknown> = {}) {
  return render(ReaderBottomBar, {
    title: 'Book',
    bookProgress: 42,
    currentPdfPage: 3,
    totalPdfPages: 10,
    viewerKind: 'pdf' as const,
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

describe('reader toolbar accessibility (U3.1)', () => {
  it('announces the paging controls as a labelled toolbar', () => {
    renderToolbar();
    const toolbar = screen.getByRole('toolbar', { name: 'reader.toolbar' });
    expect(toolbar).toBeInTheDocument();
  });

  it('keeps exactly one paging button in the tab order', () => {
    renderToolbar();
    expect(screen.getByLabelText('reader.prev_page').getAttribute('tabindex')).toBe('0');
    expect(screen.getByLabelText('reader.next_page').getAttribute('tabindex')).toBe('-1');
  });

  it('moves focus with arrow keys and follows with the tab order', async () => {
    renderToolbar();
    const prev = screen.getByLabelText('reader.prev_page');
    const next = screen.getByLabelText('reader.next_page');
    prev.focus();
    await fireEvent.keyDown(prev, { key: 'ArrowRight' });
    expect(document.activeElement).toBe(next);
    expect(next.getAttribute('tabindex')).toBe('0');
    expect(prev.getAttribute('tabindex')).toBe('-1');
    await fireEvent.keyDown(next, { key: 'ArrowLeft' });
    expect(document.activeElement).toBe(prev);
  });

  it('skips the disabled button when roving', async () => {
    renderToolbar({ currentPage: 1 });
    const next = screen.getByLabelText('reader.next_page');
    expect(screen.getByLabelText('reader.prev_page').getAttribute('tabindex')).toBe('-1');
    expect(next.getAttribute('tabindex')).toBe('0');
    next.focus();
    await fireEvent.keyDown(next, { key: 'ArrowRight' });
    expect(document.activeElement).toBe(next);
  });

  it('leaves arrow keys alone when focus is in the page input', async () => {
    renderToolbar();
    const input = screen.getByLabelText('reader.page_input');
    const next = screen.getByLabelText('reader.next_page');
    input.focus();
    await fireEvent.keyDown(input, { key: 'ArrowRight' });
    expect(document.activeElement).toBe(input);
    expect(next.getAttribute('tabindex')).toBe('-1');
  });

  // ADR-007: the bar is the only toolbar and it is not gated by fullscreen, so
  // keyboard parity across modes is structural, not conditional.
  it('renders the same toolbar in windowed and fullscreen modes', () => {
    for (const isFullscreen of [false, true]) {
      const first = renderToolbar({ isFullscreen });
      expect(screen.getByRole('toolbar', { name: 'reader.toolbar' })).toBeInTheDocument();
      first.unmount();
    }
  });
});

describe('reader chrome target sizes (U3.2)', () => {
  it('toolbar paging buttons reach 44px', () => {
    renderToolbar();
    for (const label of ['reader.prev_page', 'reader.next_page']) {
      const button = screen.getByLabelText(label);
      expect(button.className).toContain('min-w-11');
      expect(button.className).toContain('min-h-11');
    }
  });

  it('top bar tool buttons and back control reach 44px', () => {
    const { container } = renderTopBar();
    const buttons = container.querySelectorAll('header button');
    expect(buttons.length).toBeGreaterThan(0);
    for (const button of buttons) {
      expect(button.className).toContain('min-h-11');
    }
  });

  it('page input reaches 44px height', () => {
    renderToolbar();
    expect(screen.getByLabelText('reader.page_input').className).toContain('min-h-11');
  });

  it('fit controls and zoom trigger reach 44px', () => {
    const { container } = render(FitControls, { t, onFit: () => {} });
    for (const button of container.querySelectorAll('button')) {
      expect(button.className).toContain('min-h-11');
    }
    const zoom = render(ZoomDropdown, {
      value: 100,
      onSelect: () => {},
      labelKind: 'zoom' as const,
      t,
    });
    expect(zoom.getByTestId('zoom-dropdown-trigger').className).toContain('min-h-11');
  });
});

function renderBottomBar(overrides: Record<string, unknown> = {}) {
  return renderToolbar(overrides);
}

describe('reader chrome labels and surfaces (U3.3/U3.4)', () => {
  it('announces search with the format-agnostic key in any viewer', () => {
    renderTopBar();
    expect(screen.getByLabelText('search.title')).toBeInTheDocument();
    expect(screen.queryByLabelText('epub.search')).toBeNull();
  });

  // The previous form of this test REQUIRED `bg-(--color-bg-deep)` on the bar
  // and thereby forbade the fix: the chrome wore the app's background plane
  // instead of its own. Both tokens below are now rejected on purpose.
  // `bg-deep` because it is the old broken plane; `bg-surface` because it is
  // the paper pill the chrome must never wear again. Do not "repair" this by
  // restoring either — assert against the dedicated chrome plane.
  it('paints the chrome over the dedicated chrome surface, never the app plane', () => {
    const topBar = renderTopBar().container.firstElementChild;
    expect(topBar?.className).toContain('bg-(--color-reader-chrome)');
    expect(topBar?.className).not.toContain('bg-(--color-bg-deep)');
    expect(topBar?.className).not.toContain('bg-(--color-surface)');
  });

  it('paints the bottom bar over the dedicated chrome surface, never the app plane', () => {
    const bottomBar = renderBottomBar().container.firstElementChild;
    expect(bottomBar?.className).toContain('bg-(--color-reader-chrome)');
    expect(bottomBar?.className).not.toContain('bg-(--color-bg-deep)');
    expect(bottomBar?.className).not.toContain('bg-(--color-surface)');
  });

  it('paints the progress track as a visible step past the chrome plane', () => {
    const { container } = renderBottomBar();
    const track = container.querySelector('.bg-\\(--color-chrome-track\\)');
    expect(track).not.toBeNull();
    expect(track?.className).not.toContain('bg-(--color-surface-strong)');
  });

  it('hides the decorative page total from assistive tech', () => {
    const toolbarTree = renderToolbar();
    const toolbarTotals = Array.from(toolbarTree.container.querySelectorAll('span')).filter(
      (node) => node.children.length === 0 && (node.textContent?.trim().startsWith('/') ?? false),
    );
    expect(toolbarTotals.length).toBeGreaterThan(0);
    for (const node of toolbarTotals) {
      expect(node.getAttribute('aria-hidden')).toBe('true');
    }
    const topBarTree = renderTopBar();
    expect(topBarTree.container.querySelector('header > div + div')).toBeNull();
  });

  // The count was >= 5 while the header still carried the bookmarks toggle.
  // Moving bookmarks to the bottom bar leaves four, so the minimum is anchored
  // to the exact set: a bare minimum would let a control vanish silently.
  it('every icon-only top bar control carries a matching title for the hover tooltip', () => {
    const { container } = renderTopBar();
    // Icon-only controls explain themselves nowhere else: without a title the
    // row reads as unlabelled glyphs until one is pressed. A control with
    // visible text (the Biblioteca back button) needs neither.
    const iconButtons = Array.from(container.querySelectorAll('button')).filter((button) => {
      if (!button.querySelector('svg')) return false;
      const text = button.textContent?.trim() ?? '';
      return text.length === 0;
    });
    expect(iconButtons.length).toBeGreaterThanOrEqual(4);
    for (const button of iconButtons) {
      const label = button.getAttribute('aria-label');
      const title = button.getAttribute('title');
      expect(label, 'aria-label').toBeTruthy();
      expect(title, `title on the control labelled "${label}"`).toBe(label);
    }
    expect(iconButtons.map((button) => button.getAttribute('aria-label')).sort()).toEqual([
      'pdf.fullscreenEnter',
      'reader.ajustes_texto',
      'reader.tabla_contenidos',
      'search.title',
    ]);
  });

  it('explains the bookmarks control on the bottom bar', () => {
    renderBottomBar();
    const bookmarks = screen.getByLabelText('reader.bookmark');
    expect(bookmarks.getAttribute('title')).toBe('reader.bookmark');
  });
});
