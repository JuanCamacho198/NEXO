import { render, screen } from '@testing-library/svelte';
import { describe, expect, it } from 'vitest';
import ReaderFooter from '$lib/features/reader/chrome/ReaderFooter.svelte';

const t = (key: string) => key;

function makeProps(overrides: Record<string, unknown> = {}) {
  return {
    title: 'Some Book',
    bookProgress: 0,
    currentPdfPage: 1,
    totalPdfPages: 0,
    viewerKind: 'epub' as 'pdf' | 'epub' | null,
    isFullscreen: false,
    t,
    ...overrides,
  };
}

describe('ReaderFooter — EPUB progress', () => {
  it('renders the bookProgress value as text and bar width', () => {
    render(ReaderFooter, makeProps({ viewerKind: 'epub', bookProgress: 42 }));
    expect(screen.getByText('42%')).toBeInTheDocument();
    expect(screen.getByTestId('footer-progress-bar')).toHaveStyle({ width: '42%' });
  });

  it('renders zero at the boundary', () => {
    render(ReaderFooter, makeProps({ viewerKind: 'epub', bookProgress: 0 }));
    expect(screen.getByText('0%')).toBeInTheDocument();
    expect(screen.getByTestId('footer-progress-bar')).toHaveStyle({ width: '0%' });
  });
});

describe('ReaderFooter — PDF branch is unchanged', () => {
  it('renders pages-left text and the inline page fraction, ignoring bookProgress', () => {
    render(
      ReaderFooter,
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
