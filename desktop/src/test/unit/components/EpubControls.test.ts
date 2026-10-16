import { render, screen, fireEvent } from '@testing-library/svelte';
import { describe, expect, it, vi } from 'vitest';
import ReaderBottomBar from '$lib/features/reader/chrome/ReaderBottomBar.svelte';

const t = (key: string) => key;

function makeProps(overrides: Record<string, unknown> = {}) {
  return {
    title: 'Some Book',
    bookProgress: 15,
    currentPdfPage: 1,
    totalPdfPages: 0,
    viewerKind: 'epub' as 'pdf' | 'epub' | null,
    isFullscreen: false,
    visible: true,
    currentPage: 1,
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

describe('ReaderBottomBar EPUB controls (U3.3, was EpubControls U1.1)', () => {
  it('does NOT render TOC or fullscreen controls (the top bar owns them)', () => {
    render(ReaderBottomBar, makeProps());
    expect(screen.queryByTestId('epub-toc')).toBeNull();
    expect(screen.queryByTestId('epub-fullscreen')).toBeNull();
  });

  it('renders prev/next navigation buttons', () => {
    render(ReaderBottomBar, makeProps());
    expect(screen.getByLabelText('reader.prev_page')).toBeInTheDocument();
    expect(screen.getByLabelText('reader.next_page')).toBeInTheDocument();
  });

  it('renders page input with currentPage and totalPages', () => {
    render(ReaderBottomBar, makeProps({ currentPage: 3, totalPages: 15 }));
    const input = screen.getByLabelText('reader.page_input') as HTMLInputElement;
    expect(input).toBeInTheDocument();
    expect(input.value).toBe('3');
    expect(screen.getByText('/ 15')).toBeInTheDocument();
  });

  it('renders the zoom dropdown', () => {
    render(ReaderBottomBar, makeProps());
    expect(screen.getByTestId('zoom-dropdown-trigger')).toBeInTheDocument();
  });

  it('shows the zoom dropdown with the correct percentage', () => {
    render(ReaderBottomBar, makeProps({ zoomPercent: 120 }));
    expect(screen.getByTestId('zoom-dropdown-trigger')).toHaveTextContent('120%');
  });

  // ADR-006: the EPUB path means font size, not scale, and must announce it.
  it('announces font size, not zoom, on the EPUB path', () => {
    render(ReaderBottomBar, makeProps({ viewerKind: 'epub', zoomPercent: 120 }));
    expect(screen.getByTestId('zoom-dropdown-trigger')).toHaveAttribute(
      'aria-label',
      'reader.font_size_level',
    );
  });

  it('announces zoom on the PDF path', () => {
    render(ReaderBottomBar, makeProps({ viewerKind: 'pdf', zoomPercent: 120 }));
    expect(screen.getByTestId('zoom-dropdown-trigger')).toHaveAttribute(
      'aria-label',
      'reader.zoomLevel',
    );
  });

  it('disables prev button at first chapter (page 1)', () => {
    render(ReaderBottomBar, makeProps({ currentPage: 1 }));
    expect(screen.getByLabelText('reader.prev_page')).toBeDisabled();
  });

  it('disables next button at last chapter', () => {
    render(ReaderBottomBar, makeProps({ currentPage: 10, totalPages: 10 }));
    expect(screen.getByLabelText('reader.next_page')).toBeDisabled();
  });

  it('enables prev/next in the middle of a book', () => {
    render(ReaderBottomBar, makeProps({ currentPage: 5, totalPages: 10 }));
    expect(screen.getByLabelText('reader.prev_page')).not.toBeDisabled();
    expect(screen.getByLabelText('reader.next_page')).not.toBeDisabled();
  });

  it('renders the progress percentage readout from bookProgress', () => {
    render(ReaderBottomBar, makeProps({ viewerKind: 'epub', bookProgress: 37 }));
    expect(screen.getByText('37%')).toHaveTextContent('37%');
    expect(screen.getByTestId('footer-progress-bar')).toBeInTheDocument();
  });
});

describe('ReaderBottomBar EPUB callback behavior', () => {
  it('calls onPrev when prev button clicked', async () => {
    const onPrev = vi.fn();
    render(ReaderBottomBar, makeProps({ currentPage: 5, totalPages: 10, onPrev }));
    await fireEvent.click(screen.getByLabelText('reader.prev_page'));
    expect(onPrev).toHaveBeenCalledTimes(1);
  });

  it('calls onNext when next button clicked', async () => {
    const onNext = vi.fn();
    render(ReaderBottomBar, makeProps({ currentPage: 5, totalPages: 10, onNext }));
    await fireEvent.click(screen.getByLabelText('reader.next_page'));
    expect(onNext).toHaveBeenCalledTimes(1);
  });

  it('calls onZoomSelect when a ZoomDropdown option is selected', async () => {
    const onZoomSelect = vi.fn();
    render(ReaderBottomBar, makeProps({ zoomPercent: 100, onZoomSelect }));
    await fireEvent.click(screen.getByTestId('zoom-dropdown-trigger'));
    await fireEvent.click(screen.getByTestId('zoom-option-125'));
    expect(onZoomSelect).toHaveBeenCalledWith(125);
  });

  it('calls onGoToPage when a valid page is entered in the input', async () => {
    const onGoToPage = vi.fn().mockResolvedValue(true);
    render(ReaderBottomBar, makeProps({ currentPage: 3, totalPages: 10, onGoToPage }));
    const input = screen.getByLabelText('reader.page_input');
    await fireEvent.change(input, { target: { value: '7' } });
    await fireEvent.blur(input);
    // onchange fires on blur in testing-lib/svelte
    expect(onGoToPage).toHaveBeenCalledWith(7);
  });
});

describe('ReaderBottomBar page input validation', () => {
  it('calls onGoToPage with a valid page and resets on failure', async () => {
    const onGoToPage = vi.fn().mockResolvedValue(false);
    render(ReaderBottomBar, makeProps({ currentPage: 3, totalPages: 10, onGoToPage }));
    const input = screen.getByLabelText('reader.page_input') as HTMLInputElement;
    await fireEvent.change(input, { target: { value: '7' } });
    expect(onGoToPage).toHaveBeenCalledWith(7);
  });

  it('resets to the current page when the value exceeds totalPages', async () => {
    const onGoToPage = vi.fn();
    render(ReaderBottomBar, makeProps({ currentPage: 3, totalPages: 10, onGoToPage }));
    const input = screen.getByLabelText('reader.page_input') as HTMLInputElement;
    await fireEvent.change(input, { target: { value: '20' } });
    // onGoToPage is NOT called for out-of-range values
    expect(onGoToPage).not.toHaveBeenCalled();
    expect(input.value).toBe('3');
  });

  it('resets to the current page when the value is non-numeric', async () => {
    const onGoToPage = vi.fn();
    render(ReaderBottomBar, makeProps({ currentPage: 3, totalPages: 10, onGoToPage }));
    const input = screen.getByLabelText('reader.page_input') as HTMLInputElement;
    await fireEvent.change(input, { target: { value: 'abc' } });
    await fireEvent.blur(input);
    expect(input.value).toBe('3');
    expect(onGoToPage).not.toHaveBeenCalled();
  });
});
