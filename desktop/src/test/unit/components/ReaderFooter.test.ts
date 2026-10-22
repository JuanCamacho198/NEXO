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

describe('ReaderBottomBar — book page numbers', () => {
  it('replaces the percentage with N / M when a position is supplied', () => {
    render(
      ReaderBottomBar,
      makeProps({ viewerKind: 'epub', bookProgress: 7, bookPages: { page: 5, total: 345 } }),
    );
    expect(screen.getByTestId('footer-progress-label')).toHaveTextContent('5 / 345');
    expect(screen.queryByText('7%')).toBeNull();
  });

  it('reads the label from the progress span, never from the zoom chip', () => {
    // The `%` labels live in a sibling div of `bottom-bar-controls`, and the zoom
    // chip next to it also prints a percentage. The one label the footer owns is
    // `footer-progress-label`; the chapter's page field must stay untouched.
    render(
      ReaderBottomBar,
      makeProps({
        viewerKind: 'epub',
        bookProgress: 7,
        bookPages: { page: 5, total: 345 },
        currentPage: 1,
        totalPages: 3,
        zoomPercent: 150,
      }),
    );
    expect(screen.getByTestId('footer-progress-label')).toHaveTextContent('5 / 345');
    // The page field still counts the CHAPTER — the two answer different
    // questions and both survive.
    expect(screen.getByDisplayValue('1')).toBeInTheDocument();
    expect(screen.getByText('/ 3')).toBeInTheDocument();
  });

  it('keeps the percentage when no page position exists yet', () => {
    render(ReaderBottomBar, makeProps({ viewerKind: 'epub', bookProgress: 7, bookPages: null }));
    expect(screen.getByTestId('footer-progress-label')).toHaveTextContent('7%');
  });

  it('never renders "N / 0"', () => {
    render(
      ReaderBottomBar,
      makeProps({ viewerKind: 'epub', bookProgress: 7, bookPages: { page: 1, total: 0 } }),
    );
    expect(screen.getByTestId('footer-progress-label')).toHaveTextContent('7%');
    expect(screen.queryByText(/\/ 0/)).toBeNull();
  });

  it('never renders "0 / M"', () => {
    render(
      ReaderBottomBar,
      makeProps({ viewerKind: 'epub', bookProgress: 7, bookPages: { page: 0, total: 12 } }),
    );
    expect(screen.getByTestId('footer-progress-label')).toHaveTextContent('7%');
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
