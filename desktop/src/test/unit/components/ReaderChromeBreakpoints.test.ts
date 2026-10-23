import { render, screen } from '@testing-library/svelte';
import { describe, expect, it } from 'vitest';
import ReaderTopBar from '$lib/features/reader/chrome/ReaderTopBar.svelte';
import ReaderBottomBar from '$lib/features/reader/chrome/ReaderBottomBar.svelte';
import type { MessageKey } from '$lib/shared/i18n';

const t = (key: MessageKey, _params?: Record<string, string | number>): string => key;

function renderTopBar(overrides: Record<string, unknown> = {}) {
  return render(ReaderTopBar, {
    title: 'Book',
    showTocPanel: false,
    searchPanelOpen: false,
    showTextSettings: false,
    isFullscreen: false,
    visible: true,
    t,
    onBackToHome: () => {},
    onToggleToc: () => {},
    onToggleSearch: () => {},
    onToggleTextSettings: () => {},
    onToggleFullscreen: () => {},
    ...overrides,
  });
}

export function renderBottomBar(overrides: Record<string, unknown> = {}) {
  return render(ReaderBottomBar, {
    title: 'Book',
    bookProgress: 42,
    currentPdfPage: 3,
    totalPdfPages: 10,
    viewerKind: 'pdf' as const,
    isFullscreen: false,
    visible: true,
    currentPage: 5,
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
  });
}

describe('reader chrome breakpoints (U3.3)', () => {
  it('top bar tightens padding on narrow widths', () => {
    const { container } = renderTopBar();
    const bar = container.firstElementChild;
    expect(bar?.className).toContain('px-4');
    expect(bar?.className).toContain('sm:px-8');
  });

  it('top bar title truncates and yields below 480px', () => {
    renderTopBar();
    const title = screen.getByText('Book');
    expect(title.className).toContain('truncate');
    expect(title.className).toContain('min-w-0');
    expect(title.className).toContain('max-[480px]:hidden');
  });

  // The cluster used to WRAP at narrow widths, which is what put the page field
  // on a second line inside a 48px bar. Wrapping is no longer the narrow-width
  // answer: the row stays one line and tightens its gaps instead, because paging
  // and the page field are one instrument.
  it('bottom bar control cluster never wraps and tightens gaps on narrow widths', () => {
    const { container } = renderBottomBar();
    const toolbar = container.querySelector('[data-testid="bottom-bar-controls"]');
    expect(toolbar?.className).toContain('flex-nowrap');
    expect(toolbar?.className).not.toContain('flex-wrap');
    expect(toolbar?.className).toContain('gap-2');
    expect(toolbar?.className).toContain('sm:gap-3');
    expect(toolbar?.className).toContain('px-2');
    expect(toolbar?.className).toContain('sm:px-4');
  });

  // The previous form of this test selected `header > div + div` — the second
  // header row — and became null when that row was deleted. The contract is not
  // "a second header div exists": it is "the bottom bar's control cluster stays
  // responsive", asserted here against its own test id. That no second header
  // row exists is a composition property and is asserted structurally in
  // ReaderWorkspace.test.ts, NEVER by sibling position — asserting the absence
  // here would re-encode row position as the contract this test guards.
  it('bottom bar control cluster is the responsive surface, selected by test id', () => {
    const { container } = renderBottomBar();
    const cluster = container.querySelector('[data-testid="bottom-bar-controls"]');
    expect(cluster).not.toBeNull();
    expect(cluster?.className).toContain('gap-2');
    expect(cluster?.className).toContain('sm:gap-3');
    expect(cluster?.className).toContain('px-2');
    expect(cluster?.className).toContain('sm:px-4');
  });
});
