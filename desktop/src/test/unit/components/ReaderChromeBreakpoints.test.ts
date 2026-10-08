import { render, screen } from '@testing-library/svelte';
import { describe, expect, it } from 'vitest';
import ReaderControls from '$lib/features/reader/chrome/ReaderControls.svelte';
import ReaderHeader from '$lib/features/reader/chrome/ReaderHeader.svelte';
import type { MessageKey } from '$lib/shared/i18n';

const t = (key: MessageKey, _params?: Record<string, string | number>): string => key;

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

describe('reader chrome breakpoints (U2.3)', () => {
  it('header top row tightens padding on narrow widths', () => {
    const { container } = renderHeader();
    const topRow = container.querySelector('header > div');
    expect(topRow?.className).toContain('px-4');
    expect(topRow?.className).toContain('sm:px-8');
  });

  it('header title truncates and yields below 480px', () => {
    renderHeader();
    const title = screen.getByText('Book');
    expect(title.className).toContain('truncate');
    expect(title.className).toContain('min-w-0');
    expect(title.className).toContain('max-[480px]:hidden');
  });

  it('toolbar wraps and tightens gaps on narrow widths', () => {
    const { container } = render(ReaderControls, {
      currentPage: 1,
      totalPages: 10,
      t,
      onPrev: () => {},
      onNext: () => {},
      onGoToPage: async () => true,
    });
    const toolbar = container.firstElementChild;
    expect(toolbar?.className).toContain('flex-wrap');
    expect(toolbar?.className).toContain('gap-2');
    expect(toolbar?.className).toContain('sm:gap-3');
    expect(toolbar?.className).toContain('px-2');
    expect(toolbar?.className).toContain('sm:px-3');
  });

  it('fullscreen status strip tightens gaps on narrow widths', () => {
    const { container } = renderHeader({
      isFullscreen: true,
      currentPage: 3,
      totalPages: 10,
      onGoToPage: async () => true,
    });
    const strip = container.querySelector('header > div + div');
    expect(strip?.className).toContain('gap-2');
    expect(strip?.className).toContain('sm:gap-3');
    expect(strip?.className).toContain('px-2');
    expect(strip?.className).toContain('sm:px-4');
  });
});
