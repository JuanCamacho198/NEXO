import { fireEvent, render, screen } from '@testing-library/svelte';
import { describe, expect, it } from 'vitest';
import ReaderControls from '$lib/features/reader/chrome/ReaderControls.svelte';
import ReaderHeader from '$lib/features/reader/chrome/ReaderHeader.svelte';
import FitControls from '$lib/features/reader/chrome/FitControls.svelte';
import ZoomDropdown from '$lib/features/reader/chrome/ZoomDropdown.svelte';
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

function renderHeader(overrides: Record<string, unknown> = {}) {
  return render(ReaderHeader, {
    title: 'Book',
    showTocPanel: false,
    searchPanelOpen: false,
    showTextSettings: false,
    showBookmarks: false,
    isFullscreen: false,
    t,
    onBackToHome: () => {},
    onToggleToc: () => {},
    onToggleSearch: () => {},
    onToggleTextSettings: () => {},
    onToggleBookmarks: () => {},
    onToggleFullscreen: () => {},
    ...overrides,
  });
}

describe('reader chrome target sizes (U3.2)', () => {
  it('toolbar paging buttons reach 44px', () => {
    renderToolbar();
    for (const label of ['reader.prev_page', 'reader.next_page']) {
      const button = screen.getByLabelText(label);
      expect(button.className).toContain('min-w-11');
      expect(button.className).toContain('min-h-11');
    }
  });

  it('header tool buttons and back control reach 44px', () => {
    const { container } = renderHeader();
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
    const zoom = render(ZoomDropdown, { value: 100, onSelect: () => {} });
    expect(zoom.getByTestId('zoom-dropdown-trigger').className).toContain('min-h-11');
  });
});

describe('reader chrome labels and surfaces (U3.3/U3.4)', () => {
  it('announces search with the format-agnostic key in any viewer', () => {
    renderHeader();
    expect(screen.getByLabelText('search.title')).toBeInTheDocument();
    expect(screen.queryByLabelText('epub.search')).toBeNull();
  });

  it('paints the toolbar over a fixed opaque surface', () => {
    const { container } = renderToolbar();
    const toolbar = container.firstElementChild;
    expect(toolbar?.className).toContain('bg-(--color-bg-deep)');
    expect(toolbar?.className).not.toContain('bg-(--color-surface)');
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
    const headerTree = renderHeader({
      isFullscreen: true,
      currentPage: 3,
      totalPages: 10,
      onGoToPage: async () => true,
    });
    const headerTotals = Array.from(headerTree.container.querySelectorAll('span')).filter(
      (node) => node.children.length === 0 && (node.textContent?.trim().startsWith('/') ?? false),
    );
    expect(headerTotals.length).toBeGreaterThan(0);
    for (const node of headerTotals) {
      expect(node.getAttribute('aria-hidden')).toBe('true');
    }
  });
});
