import { render, screen } from '@testing-library/svelte';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';
import ReaderTocPanel from '$lib/features/reader/chrome/ReaderTocPanel.svelte';

const t = (key: string, _params?: Record<string, string | number>) => {
  const dictionary: Record<string, string> = {
    'reader.tabla_contenidos': 'Table of Contents',
    'reader.toc_empty': 'No chapters yet',
    'settings.close': 'Close',
  };

  return dictionary[key] ?? key;
};

describe('ReaderTocPanel', () => {
  const defaultEntries = [
    { id: 'ch1', title: 'Chapter 1', depth: 0 },
    { id: 'ch1-1', title: 'Section 1.1', depth: 1 },
    { id: 'ch2', title: 'Chapter 2', depth: 0 },
    { id: 'ch2-1', title: 'Section 2.1', depth: 1, active: true },
  ];

  const defaultProps = {
    open: true,
    entries: defaultEntries,
    t,
    onNavigate: vi.fn(),
    onClose: vi.fn(),
  };

  it('renders TOC heading when open', () => {
    render(ReaderTocPanel, defaultProps);
    expect(screen.getByText('Table of Contents')).toBeInTheDocument();
  });

  it('renders all TOC entries', () => {
    render(ReaderTocPanel, defaultProps);
    expect(screen.getByText('Chapter 1')).toBeInTheDocument();
    expect(screen.getByText('Chapter 2')).toBeInTheDocument();
    expect(screen.getByText('Section 1.1')).toBeInTheDocument();
    expect(screen.getByText('Section 2.1')).toBeInTheDocument();
  });

  it('shows empty state when no entries', () => {
    render(ReaderTocPanel, { ...defaultProps, entries: [] });
    expect(screen.getByText('No chapters yet')).toBeInTheDocument();
  });

  it('does not render when closed', () => {
    render(ReaderTocPanel, { ...defaultProps, open: false });
    expect(screen.queryByText('Table of Contents')).not.toBeInTheDocument();
  });

  it('renders entries with duplicate ids without crashing', () => {
    // Regression: EPUB TOCs can contain multiple nav points that resolve to
    // the same spine index (e.g. a "Part" heading + its first chapter), so
    // ids may not be unique. The keyed each block must not throw
    // each_key_duplicate and must still render every entry.
    const dupEntries = [
      { id: '5', title: 'Part Two', depth: 0 },
      { id: '5', title: 'Chapter 5', depth: 0 },
      { id: '6', title: 'Chapter 6', depth: 0 },
    ];
    render(ReaderTocPanel, { ...defaultProps, entries: dupEntries });
    expect(screen.getByText('Part Two')).toBeInTheDocument();
    expect(screen.getByText('Chapter 5')).toBeInTheDocument();
    expect(screen.getByText('Chapter 6')).toBeInTheDocument();
  });

  it('calls onNavigate when a chapter is clicked', async () => {
    const onNavigate = vi.fn();
    const user = userEvent.setup();
    render(ReaderTocPanel, { ...defaultProps, onNavigate });
    await user.click(screen.getByText('Chapter 2'));
    expect(onNavigate).toHaveBeenCalledTimes(1);
    expect(onNavigate).toHaveBeenCalledWith(
      expect.objectContaining({ id: 'ch2', title: 'Chapter 2' }),
    );
  });

  it('calls onClose when backdrop is clicked', async () => {
    const onClose = vi.fn();
    const user = userEvent.setup();
    render(ReaderTocPanel, { ...defaultProps, onClose });
    const backdrop = screen.getByRole('presentation');
    await user.click(backdrop);
    expect(onClose).toHaveBeenCalledTimes(1);
  });

  it('closes from a control inside the panel, not only the backdrop', async () => {
    const onClose = vi.fn();
    const user = userEvent.setup();
    render(ReaderTocPanel, { ...defaultProps, onClose });
    await user.click(screen.getByRole('button', { name: 'Close' }));
    expect(onClose).toHaveBeenCalledTimes(1);
  });

  it('renders depth as a real hierarchy: indent, weight and colour differ', () => {
    render(ReaderTocPanel, { ...defaultProps });

    const top = screen.getByText('Chapter 1');
    const sub = screen.getByText('Section 1.1');

    // The indent separates levels by a full step (16px), not a hairline.
    const topPad = parseFloat(getComputedStyle(top.closest('button')!).paddingLeft);
    const subPad = parseFloat(getComputedStyle(sub.closest('button')!).paddingLeft);
    expect(subPad - topPad).toBe(16);

    // A top-level entry is the emphasised one; a sub-entry is de-emphasised.
    expect(top.classList.contains('text-sm')).toBe(true);
    expect(top.style.opacity).toBe('1');
    expect(sub.classList.contains('text-xs')).toBe(true);
    expect(Number(sub.style.opacity)).toBeLessThan(1);
  });
});
