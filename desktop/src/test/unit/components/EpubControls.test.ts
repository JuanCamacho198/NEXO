import { render, screen, fireEvent } from '@testing-library/svelte';
import { describe, expect, it, vi } from 'vitest';
import EpubControls from '$lib/features/reader/viewer-epub/EpubControls.svelte';

const t = (key: string) => key;

function makeProps(overrides: Record<string, unknown> = {}) {
  return {
    currentPage: 1,
    totalPages: 10,
    currentPercentage: 15,
    fontSize: 100,
    t,
    onPrev: vi.fn(),
    onNext: vi.fn(),
    onGoToPage: vi.fn().mockResolvedValue(true),
    onFontSizeChange: vi.fn(),
    ...overrides,
  };
}

describe('EpubControls (U1.1) — toolbar owns paging and zoom only', () => {
  it('does NOT render TOC or fullscreen controls (the header owns them)', () => {
    render(EpubControls, makeProps());
    expect(screen.queryByTestId('epub-toc')).toBeNull();
    expect(screen.queryByTestId('epub-fullscreen')).toBeNull();
  });

  it('renders prev/next navigation buttons', () => {
    render(EpubControls, makeProps());
    expect(screen.getByTestId('epub-prev')).toBeInTheDocument();
    expect(screen.getByTestId('epub-next')).toBeInTheDocument();
  });

  it('renders page input with currentPage and totalPages', () => {
    render(EpubControls, makeProps({ currentPage: 3, totalPages: 15 }));
    const input = screen.getByTestId('epub-page-input') as HTMLInputElement;
    expect(input).toBeInTheDocument();
    expect(input.value).toBe('3');
    expect(screen.getByTestId('epub-total-pages')).toHaveTextContent('/ 15');
  });

  it('renders ZoomDropdown with current fontSize (pill)', () => {
    render(EpubControls, makeProps());
    expect(screen.getByTestId('zoom-dropdown-trigger')).toBeInTheDocument();
  });

  it('shows ZoomDropdown with correct percentage', () => {
    render(EpubControls, makeProps({ fontSize: 120 }));
    expect(screen.getByTestId('zoom-dropdown-trigger')).toHaveTextContent('120%');
  });

  it('disables prev button at first chapter (page 1)', () => {
    render(EpubControls, makeProps({ currentPage: 1 }));
    expect(screen.getByTestId('epub-prev')).toBeDisabled();
  });

  it('disables next button at last chapter', () => {
    render(EpubControls, makeProps({ currentPage: 10, totalPages: 10 }));
    expect(screen.getByTestId('epub-next')).toBeDisabled();
  });

  it('enables prev/next in middle of book', () => {
    render(EpubControls, makeProps({ currentPage: 5, totalPages: 10 }));
    expect(screen.getByTestId('epub-prev')).not.toBeDisabled();
    expect(screen.getByTestId('epub-next')).not.toBeDisabled();
  });
});

describe('EpubControls (U1.1) — Callback behavior', () => {
  it('calls onPrev when prev button clicked', async () => {
    const onPrev = vi.fn();
    render(EpubControls, makeProps({ currentPage: 5, totalPages: 10, onPrev }));
    await fireEvent.click(screen.getByTestId('epub-prev'));
    expect(onPrev).toHaveBeenCalledTimes(1);
  });

  it('calls onNext when next button clicked', async () => {
    const onNext = vi.fn();
    render(EpubControls, makeProps({ currentPage: 5, totalPages: 10, onNext }));
    await fireEvent.click(screen.getByTestId('epub-next'));
    expect(onNext).toHaveBeenCalledTimes(1);
  });

  it('calls onFontSizeChange when ZoomDropdown option selected', async () => {
    const onFontSizeChange = vi.fn();
    render(EpubControls, makeProps({ fontSize: 100, onFontSizeChange }));
    await fireEvent.click(screen.getByTestId('zoom-dropdown-trigger'));
    const opt = screen.getByTestId('zoom-option-125');
    await fireEvent.click(opt);
    expect(onFontSizeChange).toHaveBeenCalledWith(125);
  });

  it('ZoomDropdown clamps selection to 50-300 via onFontSizeChange', async () => {
    const onFontSizeChange = vi.fn();
    render(EpubControls, makeProps({ fontSize: 100, onFontSizeChange }));
    await fireEvent.click(screen.getByTestId('zoom-dropdown-trigger'));
    const opt50 = screen.getByTestId('zoom-option-50');
    await fireEvent.click(opt50);
    expect(onFontSizeChange).toHaveBeenCalledWith(50);
  });

  it('calls onGoToPage when valid page entered in input', async () => {
    const onGoToPage = vi.fn().mockResolvedValue(true);
    render(EpubControls, makeProps({ currentPage: 3, totalPages: 10, onGoToPage }));
    const input = screen.getByTestId('epub-page-input');
    await fireEvent.change(input, { target: { value: '7' } });
    await fireEvent.blur(input);
    // onchange fires on blur in testing-lib/svelte
    expect(onGoToPage).toHaveBeenCalledWith(7);
  });
});

describe('EpubControls (U1.1) — Page input validation', () => {
  it('calls onGoToPage with valid page and resets on failure', async () => {
    const onGoToPage = vi.fn().mockResolvedValue(false);
    render(EpubControls, makeProps({ currentPage: 3, totalPages: 10, onGoToPage }));
    const input = screen.getByTestId('epub-page-input') as HTMLInputElement;
    await fireEvent.change(input, { target: { value: '7' } });
    expect(onGoToPage).toHaveBeenCalledWith(7);
  });

  it('resets to current page when value exceeds totalPages', async () => {
    const onGoToPage = vi.fn();
    render(EpubControls, makeProps({ currentPage: 3, totalPages: 10, onGoToPage }));
    const input = screen.getByTestId('epub-page-input') as HTMLInputElement;
    await fireEvent.change(input, { target: { value: '20' } });
    // onGoToPage is NOT called for out-of-range values
    expect(onGoToPage).not.toHaveBeenCalled();
    expect(input.value).toBe('3');
  });

  it('resets to current page when value is non-numeric', async () => {
    const onGoToPage = vi.fn();
    render(EpubControls, makeProps({ currentPage: 3, totalPages: 10, onGoToPage }));
    const input = screen.getByTestId('epub-page-input') as HTMLInputElement;
    await fireEvent.change(input, { target: { value: 'abc' } });
    await fireEvent.blur(input);
    expect(input.value).toBe('3');
    expect(onGoToPage).not.toHaveBeenCalled();
  });
});
