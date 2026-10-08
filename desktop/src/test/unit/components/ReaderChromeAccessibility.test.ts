import { fireEvent, render, screen } from '@testing-library/svelte';
import { describe, expect, it } from 'vitest';
import ReaderControls from '$lib/features/reader/chrome/ReaderControls.svelte';
import type { MessageKey } from '$lib/shared/i18n';

const t = (key: MessageKey, _params?: Record<string, string | number>): string => key;

function renderToolbar(overrides: Record<string, unknown> = {}) {
  return render(ReaderControls, {
    currentPage: 5,
    totalPages: 10,
    t,
    onPrev: () => {},
    onNext: () => {},
    onGoToPage: async () => true,
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
});
