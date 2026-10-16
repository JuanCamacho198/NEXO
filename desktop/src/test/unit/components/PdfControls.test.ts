import { fireEvent, render, screen } from '@testing-library/svelte';
import { afterEach, describe, expect, it, vi } from 'vitest';
import ReaderBottomBar from '$lib/features/reader/chrome/ReaderBottomBar.svelte';
import { debugState } from '$lib/shared/debug/debugState.svelte';

const t = (key: string) => key;

function makeProps(overrides: Record<string, unknown> = {}) {
  return {
    title: 'Some Book',
    bookProgress: 30,
    currentPdfPage: 3,
    totalPdfPages: 10,
    viewerKind: 'pdf' as const,
    isFullscreen: false,
    visible: true,
    currentPage: 3,
    totalPages: 10,
    zoomPercent: 100,
    bookmarkCount: 0,
    bookmarksLoading: false,
    showBookmarks: false,
    t,
    onPrev: vi.fn(),
    onNext: vi.fn(),
    onGoToPage: vi.fn().mockResolvedValue(true),
    onFit: vi.fn(),
    onZoomSelect: vi.fn(),
    onToggleBookmarks: vi.fn(),
    ...overrides,
  };
}

describe('ReaderBottomBar fit control (U3.3, was PdfControls U2.2)', () => {
  it('renders fit-to-width and fit-to-page buttons in the toolbar', () => {
    render(ReaderBottomBar, makeProps());
    expect(screen.getByTestId('fit-controls')).toBeInTheDocument();
    expect(screen.getByTestId('fit-width')).toBeInTheDocument();
    expect(screen.getByTestId('fit-page')).toBeInTheDocument();
    expect(screen.getByLabelText('reader.fitWidth')).toBeInTheDocument();
    expect(screen.getByLabelText('reader.fitPage')).toBeInTheDocument();
  });

  it('uses its own fit glyphs, never the fullscreen expand/shrink icons', () => {
    const { container } = render(ReaderBottomBar, makeProps());
    expect(container.querySelector('svg.lucide-unfold-horizontal')).not.toBeNull();
    expect(container.querySelector('svg.lucide-frame')).not.toBeNull();
    expect(container.querySelector('svg.lucide-expand')).toBeNull();
    expect(container.querySelector('svg.lucide-shrink')).toBeNull();
  });

  it('routes fit-width clicks to onFit with the width mode', async () => {
    const onFit = vi.fn();
    render(ReaderBottomBar, makeProps({ onFit }));
    await fireEvent.click(screen.getByTestId('fit-width'));
    expect(onFit).toHaveBeenCalledTimes(1);
    expect(onFit).toHaveBeenCalledWith('width');
  });

  it('routes fit-page clicks to onFit with the page mode', async () => {
    const onFit = vi.fn();
    render(ReaderBottomBar, makeProps({ onFit }));
    await fireEvent.click(screen.getByTestId('fit-page'));
    expect(onFit).toHaveBeenCalledTimes(1);
    expect(onFit).toHaveBeenCalledWith('page');
  });

  it('keeps the zoom dropdown next to the fit control and reports the percent', async () => {
    const onZoomSelect = vi.fn();
    render(ReaderBottomBar, makeProps({ onZoomSelect }));
    expect(screen.getByTestId('zoom-dropdown-trigger')).toHaveTextContent('100%');
    await fireEvent.click(screen.getByTestId('zoom-dropdown-trigger'));
    await fireEvent.click(screen.getByTestId('zoom-option-125'));
    expect(onZoomSelect).toHaveBeenCalledWith(125);
  });

  it('hides the fit controls in EPUB, where fit has no meaning', () => {
    render(ReaderBottomBar, makeProps({ viewerKind: 'epub' }));
    expect(screen.queryByTestId('fit-controls')).toBeNull();
    expect(screen.getByTestId('zoom-dropdown-trigger')).toBeInTheDocument();
  });
});

describe('ReaderBottomBar debug span (U4.2)', () => {
  afterEach(() => {
    debugState.enabled = false;
  });

  it('renders zero debug chrome by default', () => {
    debugState.enabled = false;
    const { container } = render(ReaderBottomBar, makeProps());
    expect(container.textContent).not.toContain('p3/10');
  });

  it('shows the page/scale readout only while the debug setting is on', () => {
    debugState.enabled = true;
    const { container, unmount } = render(ReaderBottomBar, makeProps());
    expect(container.textContent).toContain('p3/10');
    unmount();
    debugState.enabled = false;
    const hidden = render(ReaderBottomBar, makeProps());
    expect(hidden.container.textContent).not.toContain('p3/10');
  });
});

describe('ReaderBottomBar bookmark count and loading state (U3.15)', () => {
  it('renders the bookmark count when loaded', () => {
    render(ReaderBottomBar, makeProps({ bookmarkCount: 7, bookmarksLoading: false }));
    expect(screen.getByTestId('bookmark-count')).toHaveTextContent('7');
  });

  // A count that renders `0` while the list is still loading is a false zero:
  // it tells the reader the book has no bookmarks before that is known.
  it('renders a neutral placeholder instead of a false zero while loading', () => {
    render(ReaderBottomBar, makeProps({ bookmarkCount: 0, bookmarksLoading: true }));
    const slot = screen.getByTestId('bookmark-count');
    expect(slot).not.toHaveTextContent('0');
    expect(slot).toHaveAttribute('aria-busy', 'true');
  });
});
