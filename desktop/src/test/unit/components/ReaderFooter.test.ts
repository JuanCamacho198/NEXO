import { render, screen } from '@testing-library/svelte';
import { describe, expect, it } from 'vitest';
import ReaderBottomBar from '$lib/features/reader/chrome/ReaderBottomBar.svelte';

const t = (key: string) => key;

function makeProps(overrides: Record<string, unknown> = {}) {
  return {
    title: 'Some Book',
    bookProgress: 0,
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
    onPrev: () => {},
    onNext: () => {},
    onGoToPage: async () => true,
    onFit: () => {},
    onZoomSelect: () => {},
    onToggleBookmarks: () => {},
    ...overrides,
  };
}

describe('ReaderBottomBar — EPUB progress', () => {
  it('renders the bookProgress value as text and bar width', () => {
    render(ReaderBottomBar, makeProps({ viewerKind: 'epub', bookProgress: 42 }));
    expect(screen.getByText('42%')).toBeInTheDocument();
    expect(screen.getByTestId('footer-progress-bar')).toHaveStyle({ width: '42%' });
  });

  it('renders zero at the boundary', () => {
    render(ReaderBottomBar, makeProps({ viewerKind: 'epub', bookProgress: 0 }));
    expect(screen.getByText('0%')).toBeInTheDocument();
    expect(screen.getByTestId('footer-progress-bar')).toHaveStyle({ width: '0%' });
  });

  // The change-1 test id must survive fullscreen. Fullscreen no longer
  // suppresses the footer: it only slides the bar with the chrome.
  it('keeps the progress bar under isFullscreen: true', () => {
    render(
      ReaderBottomBar,
      makeProps({ viewerKind: 'epub', bookProgress: 42, isFullscreen: true }),
    );
    expect(screen.getByTestId('footer-progress-bar')).toHaveStyle({ width: '42%' });
  });
});

describe('ReaderBottomBar — PDF branch is unchanged', () => {
  it('renders pages-left text and the inline page fraction, ignoring bookProgress', () => {
    render(
      ReaderBottomBar,
      makeProps({
        viewerKind: 'pdf',
        currentPdfPage: 2,
        totalPdfPages: 10,
        bookProgress: 99,
      }),
    );
    expect(screen.getByText(/8/)).toHaveTextContent('8 pdf.pagesLeft');
    expect(screen.getByText('20%')).toBeInTheDocument();
    expect(screen.queryByText('99%')).toBeNull();
    expect(screen.queryByTestId('footer-progress-bar')).toBeNull();
  });
});

// The bar is shared by EPUB scrolling, EPUB paginated and PDF, so its geometry
// is one contract: a minimum height whose contents fit, and a control row that
// never wraps. Measured before this: the row was 60px (44px controls plus
// `py-2`) inside a fixed 48px bar, so the page field painted over the reading
// area above and below it, and `flex-wrap` dropped the whole row to a second
// line under any squeeze.
describe('ReaderBottomBar — geometry', () => {
  it('is a minimum height, not a fixed one', () => {
    const { container } = render(ReaderBottomBar, makeProps());
    const footer = container.querySelector('footer');
    expect(footer?.className).toContain('min-h-12');
    expect(footer?.className).not.toMatch(/(^|\s)h-12(\s|$)/);
  });

  it('keeps the control row on one line', () => {
    render(ReaderBottomBar, makeProps());
    const row = screen.getByTestId('bottom-bar-controls');
    expect(row.className).toContain('flex-nowrap');
    expect(row.className).not.toContain('flex-wrap');
    // No vertical padding on the row: the bar's 48px minimum already contains
    // the 44px controls, and padding on top of them is what overflowed it.
    expect(row.className).not.toContain('py-2');
  });

  it('keeps "/ M" on one line beside the page field', () => {
    render(ReaderBottomBar, makeProps({ currentPage: 12, totalPages: 43 }));
    const total = screen.getByText('/ 43');
    expect(total.className).toContain('whitespace-nowrap');
    expect(total.className).toContain('shrink-0');
  });

  // A long chapter title must ellipsize, never grow the bar. `truncate` alone
  // was not enough: `justify-self-start` sizes a grid item to its max-content,
  // so the title's box always matched its text and there was nothing to
  // ellipsize — measured 912px wide inside a 444px track, running under the
  // paging controls. `w-full` makes it fill (and therefore be clipped by) the
  // track.
  it('gives the location label the track width so it can truncate', () => {
    render(ReaderBottomBar, makeProps({ chapterTitle: 'Capítulo XXIV' }));
    const label = screen.getByText('Capítulo XXIV');
    expect(label.className).toContain('truncate');
    expect(label.className).toContain('min-w-0');
    expect(label.className).toContain('w-full');
  });
});
