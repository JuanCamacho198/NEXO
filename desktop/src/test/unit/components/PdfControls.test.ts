import { fireEvent, render, screen } from '@testing-library/svelte';
import { describe, expect, it, vi } from 'vitest';
import PdfControls from '$lib/features/reader/viewer-pdf/PdfControls.svelte';

const t = (key: string) => key;

function makeProps(overrides: Record<string, unknown> = {}) {
  return {
    currentPage: 3,
    totalPages: 10,
    scale: 1,
    isLoading: false,
    error: null,
    t,
    onPrevPage: vi.fn(),
    onNextPage: vi.fn(),
    onGoToPage: vi.fn().mockResolvedValue(true),
    onSetScale: vi.fn(),
    onFit: vi.fn(),
    ...overrides,
  };
}

describe('PdfControls fit control (U2.2)', () => {
  it('renders fit-to-width and fit-to-page buttons in the toolbar', () => {
    render(PdfControls, makeProps());
    expect(screen.getByTestId('fit-controls')).toBeInTheDocument();
    expect(screen.getByTestId('fit-width')).toBeInTheDocument();
    expect(screen.getByTestId('fit-page')).toBeInTheDocument();
    expect(screen.getByLabelText('reader.fitWidth')).toBeInTheDocument();
    expect(screen.getByLabelText('reader.fitPage')).toBeInTheDocument();
  });

  it('uses its own fit glyphs, never the fullscreen expand/shrink icons', () => {
    const { container } = render(PdfControls, makeProps());
    expect(container.querySelector('svg.lucide-unfold-horizontal')).not.toBeNull();
    expect(container.querySelector('svg.lucide-frame')).not.toBeNull();
    expect(container.querySelector('svg.lucide-expand')).toBeNull();
    expect(container.querySelector('svg.lucide-shrink')).toBeNull();
  });

  it('routes fit-width clicks to onFit with the width mode', async () => {
    const onFit = vi.fn();
    render(PdfControls, makeProps({ onFit }));
    await fireEvent.click(screen.getByTestId('fit-width'));
    expect(onFit).toHaveBeenCalledTimes(1);
    expect(onFit).toHaveBeenCalledWith('width');
  });

  it('routes fit-page clicks to onFit with the page mode', async () => {
    const onFit = vi.fn();
    render(PdfControls, makeProps({ onFit }));
    await fireEvent.click(screen.getByTestId('fit-page'));
    expect(onFit).toHaveBeenCalledTimes(1);
    expect(onFit).toHaveBeenCalledWith('page');
  });

  it('keeps the zoom dropdown next to the fit control', async () => {
    const onSetScale = vi.fn();
    render(PdfControls, makeProps({ onSetScale }));
    expect(screen.getByTestId('zoom-dropdown-trigger')).toHaveTextContent('100%');
    await fireEvent.click(screen.getByTestId('zoom-dropdown-trigger'));
    await fireEvent.click(screen.getByTestId('zoom-option-125'));
    expect(onSetScale).toHaveBeenCalledWith(1.25);
  });
});
