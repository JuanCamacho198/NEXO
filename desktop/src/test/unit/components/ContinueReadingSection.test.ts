import { fireEvent, render, screen } from '@testing-library/svelte';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { axe } from 'vitest-axe';
import { toHaveNoViolations } from 'vitest-axe/dist/matchers.js';
import {
  mockAppState,
  mockLibraryState,
  mockNavigationState,
  resetContinueReadingMocks,
  setContinueReadingBooks,
  setPreviewBookId,
} from './continueReadingMocks.svelte';

expect.extend({ toHaveNoViolations });

vi.mock('$lib/shared/stores/AppState.svelte', () => ({
  appState: mockAppState,
}));
vi.mock('$lib/shared/stores/LibraryDomainState.svelte', () => ({
  libraryState: mockLibraryState,
}));
vi.mock('$lib/shared/stores/NavigationDomainState.svelte', () => ({
  navigationState: mockNavigationState,
}));

import ContinueReadingSection from '$lib/shared/ui/layout/ContinueReadingSection.svelte';

function makeBook(id: string, overrides: Record<string, unknown> = {}) {
  return {
    id,
    title: `Book ${id}`,
    author: 'Test Author',
    format: 'epub',
    filePath: `/path/${id}.epub`,
    coverPath: null,
    currentPage: 1,
    totalPages: 10,
    progressPercentage: 10,
    isFavorite: false,
    minutesRead: 0,
    updatedAt: new Date().toISOString(),
    ...overrides,
  };
}

function makeMq(matches: boolean): MediaQueryList {
  return {
    matches,
    media: '(prefers-reduced-motion: reduce)',
    onchange: null,
    addEventListener: vi.fn(),
    removeEventListener: vi.fn(),
    addListener: vi.fn(),
    removeListener: vi.fn(),
    dispatchEvent: vi.fn(),
  } as unknown as MediaQueryList;
}

const originalScrollTo = Element.prototype.scrollTo;
const originalScrollBy = Element.prototype.scrollBy;

// File-level stubs: jsdom has neither matchMedia nor element scrollTo, and the
// component calls both in effects on mount.
beforeEach(() => {
  vi.clearAllMocks();
  resetContinueReadingMocks();
  Element.prototype.scrollTo = vi.fn() as never;
  Element.prototype.scrollBy = vi.fn() as never;
  vi.stubGlobal('matchMedia', vi.fn().mockReturnValue(makeMq(false)));
});

afterEach(() => {
  vi.unstubAllGlobals();
  Element.prototype.scrollTo = originalScrollTo;
  Element.prototype.scrollBy = originalScrollBy;
});

describe('ContinueReadingSection (5.3)', () => {
  it('shows the placeholder and no carousel controls when there are no books', () => {
    render(ContinueReadingSection);

    expect(screen.getByText('No in-progress books yet')).toBeInTheDocument();
    expect(screen.queryByLabelText(/in progress/)).toBeNull();
    expect(screen.queryByRole('button', { name: 'Next' })).toBeNull();
    expect(screen.queryByRole('button', { name: 'Previous' })).toBeNull();
  });

  it('renders book card with action menu trigger for single book', () => {
    setContinueReadingBooks([makeBook('b1')]);
    render(ContinueReadingSection);

    // BookCard renders a Continue button for continue-reading variant
    expect(screen.getByText('Continue')).toBeInTheDocument();

    // ShelfActionMenu renders a trigger with aria-haspopup="menu"
    const trigger = document.querySelector('[aria-haspopup="menu"]');
    expect(trigger).toBeTruthy();
  });

  it('renders the resume action as the NEXO-blue primary CTA', () => {
    setContinueReadingBooks([makeBook('b1')]);
    render(ContinueReadingSection);

    const button = screen.getByText('Continue').closest('button');
    expect(button).not.toBeNull();
    expect(button!.className).toContain('bg-(--color-accent)');
  });

  it('renders action menu trigger for each book when 2+ books', () => {
    setContinueReadingBooks([makeBook('b1'), makeBook('b2')]);
    render(ContinueReadingSection);

    const triggers = document.querySelectorAll('[aria-haspopup="menu"]');
    expect(triggers.length).toBe(2);
  });

  it('passes correct compact variant when multiple books', () => {
    setContinueReadingBooks([makeBook('b1'), makeBook('b2')]);
    render(ContinueReadingSection);

    // With 2+ books, BookCard renders an <article> for each
    const articles = document.querySelectorAll('article');
    expect(articles.length).toBe(2);
  });

  it('renders single book without compact styling', () => {
    setContinueReadingBooks([makeBook('b1')]);
    render(ContinueReadingSection);

    // With 1 book, renders one <article>
    const articles = document.querySelectorAll('article');
    expect(articles.length).toBe(1);
  });

  it('shows the count pill and hides arrows for a single book', () => {
    setContinueReadingBooks([makeBook('b1')]);
    render(ContinueReadingSection);

    expect(screen.getByLabelText('1 in progress')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Next' })).toBeNull();
    expect(screen.queryByRole('button', { name: 'Previous' })).toBeNull();
  });

  it('shows the count pill and both arrows for 2+ books with translated labels', () => {
    setContinueReadingBooks([makeBook('b1'), makeBook('b2'), makeBook('b3')]);
    render(ContinueReadingSection);

    expect(screen.getByLabelText('3 in progress')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Next' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Previous' })).toBeInTheDocument();
  });

  it('arrows wrap at both ends', async () => {
    const user = userEvent.setup();
    setContinueReadingBooks([makeBook('b1'), makeBook('b2')]);
    const { container } = render(ContinueReadingSection);
    const ul = container.querySelector('ul')!;

    expect(ul.getAttribute('data-active-index')).toBe('0');

    await user.click(screen.getByRole('button', { name: 'Next' }));
    expect(ul.getAttribute('data-active-index')).toBe('1');

    // Next on the last card wraps to the first
    await user.click(screen.getByRole('button', { name: 'Next' }));
    expect(ul.getAttribute('data-active-index')).toBe('0');

    // Previous on the first card wraps to the last
    await user.click(screen.getByRole('button', { name: 'Previous' }));
    expect(ul.getAttribute('data-active-index')).toBe('1');
  });

  it('renders the EN CURSO badge only when readingStatus is reading', () => {
    setContinueReadingBooks([
      makeBook('reading-1', { readingStatus: 'reading', progressPercentage: 40 }),
      makeBook('plain-2', { progressPercentage: 40 }),
    ]);
    render(ContinueReadingSection);

    expect(screen.getAllByText('IN PROGRESS').length).toBe(1);
    // The other card (progress > 0 but no reading status) must NOT show it
    expect(screen.getByText('Book plain-2')).toBeInTheDocument();
    expect(screen.queryAllByText('IN PROGRESS').length).toBe(1);
  });

  it('announces the active book through an aria-live region', () => {
    setContinueReadingBooks([makeBook('b1'), makeBook('b2')]);
    const { container } = render(ContinueReadingSection);

    const liveRegion = container.querySelector('[aria-live="polite"]');
    expect(liveRegion).not.toBeNull();
    expect(liveRegion).toHaveAttribute('aria-atomic', 'true');
    expect(liveRegion?.textContent).toContain('Book b1');
  });

  it('falls back to the library.cover placeholder when no coverPath', () => {
    setContinueReadingBooks([makeBook('b1', { coverPath: null })]);
    render(ContinueReadingSection);

    expect(screen.getAllByText('Cover').length).toBeGreaterThan(0);
  });

  it('renders the real cover image when coverPath is a remote URL', () => {
    setContinueReadingBooks([makeBook('b1', { coverPath: 'https://example.com/covers/b1.jpg' })]);
    const { container } = render(ContinueReadingSection);

    const img = container.querySelector('img');
    expect(img).not.toBeNull();
    expect(img).toHaveAttribute('src', 'https://example.com/covers/b1.jpg');
  });

  it('operates the arrows from the keyboard (native buttons)', async () => {
    const user = userEvent.setup();
    setContinueReadingBooks([makeBook('b1'), makeBook('b2')]);
    const { container } = render(ContinueReadingSection);
    const ul = container.querySelector('ul')!;

    const nextButton = screen.getByRole('button', { name: 'Next' });
    nextButton.focus();
    await user.keyboard('{Enter}');
    expect(ul.getAttribute('data-active-index')).toBe('1');

    const prevButton = screen.getByRole('button', { name: 'Previous' });
    prevButton.focus();
    await user.keyboard(' ');
    expect(ul.getAttribute('data-active-index')).toBe('0');
  });

  it('has no axe violations with two books', async () => {
    setContinueReadingBooks([makeBook('b1', { readingStatus: 'reading' }), makeBook('b2')]);
    const { container } = render(ContinueReadingSection);

    const results = await axe(container);
    const assertion = toHaveNoViolations(results);
    expect(assertion.pass, assertion.message()).toBe(true);
  });
});

describe('ContinueReadingSection auto-rotation (WCAG 2.2.2)', () => {
  let matchMediaMock: ReturnType<typeof vi.fn>;

  beforeEach(() => {
    vi.useFakeTimers();
    matchMediaMock = vi.fn();
    matchMediaMock.mockReturnValue(makeMq(false));
    vi.stubGlobal('matchMedia', matchMediaMock);
  });

  afterEach(() => {
    vi.useRealTimers();
    vi.unstubAllGlobals();
  });

  it('auto-advances every 8s and wraps to the first card', async () => {
    setContinueReadingBooks([makeBook('b1'), makeBook('b2')]);
    const { container } = render(ContinueReadingSection);
    await Promise.resolve();
    const ul = container.querySelector('ul')!;

    expect(ul.getAttribute('data-active-index')).toBe('0');

    await vi.advanceTimersByTimeAsync(8000);
    expect(ul.getAttribute('data-active-index')).toBe('1');

    await vi.advanceTimersByTimeAsync(8000);
    expect(ul.getAttribute('data-active-index')).toBe('0');
  });

  it('pauses on hover and resumes without resetting the index', async () => {
    setContinueReadingBooks([makeBook('b1'), makeBook('b2')]);
    const { container } = render(ContinueReadingSection);
    await Promise.resolve();
    const carousel = container.querySelector('[data-testid="continue-carousel"]')!;
    const ul = container.querySelector('ul')!;

    await vi.advanceTimersByTimeAsync(8000);
    expect(ul.getAttribute('data-active-index')).toBe('1');

    fireEvent.mouseEnter(carousel);
    await vi.advanceTimersByTimeAsync(16000);
    expect(ul.getAttribute('data-active-index')).toBe('1');

    fireEvent.mouseLeave(carousel);
    await vi.advanceTimersByTimeAsync(8000);
    // resumes from where it paused and wraps
    expect(ul.getAttribute('data-active-index')).toBe('0');
  });

  it('pauses on focus and resumes without resetting the index', async () => {
    setContinueReadingBooks([makeBook('b1'), makeBook('b2')]);
    const { container } = render(ContinueReadingSection);
    await Promise.resolve();
    const carousel = container.querySelector('[data-testid="continue-carousel"]')!;
    const ul = container.querySelector('ul')!;

    await vi.advanceTimersByTimeAsync(8000);
    expect(ul.getAttribute('data-active-index')).toBe('1');

    fireEvent.focusIn(carousel);
    await vi.advanceTimersByTimeAsync(16000);
    expect(ul.getAttribute('data-active-index')).toBe('1');

    fireEvent.focusOut(carousel);
    await vi.advanceTimersByTimeAsync(8000);
    expect(ul.getAttribute('data-active-index')).toBe('0');
  });

  it('restarts the timer after a manual arrow press', async () => {
    setContinueReadingBooks([makeBook('b1'), makeBook('b2')]);
    const { container } = render(ContinueReadingSection);
    await Promise.resolve();
    const ul = container.querySelector('ul')!;

    fireEvent.click(screen.getByRole('button', { name: 'Next' }));
    await Promise.resolve();
    expect(ul.getAttribute('data-active-index')).toBe('1');

    // The 8s window restarted: nothing advances before the full 8s elapses
    await vi.advanceTimersByTimeAsync(7999);
    expect(ul.getAttribute('data-active-index')).toBe('1');

    await vi.advanceTimersByTimeAsync(1);
    expect(ul.getAttribute('data-active-index')).toBe('0');
  });

  it('never auto-advances when prefers-reduced-motion is reduce', async () => {
    matchMediaMock.mockReturnValue(makeMq(true));
    setContinueReadingBooks([makeBook('b1'), makeBook('b2')]);
    const { container } = render(ContinueReadingSection);
    await Promise.resolve();
    const ul = container.querySelector('ul')!;

    await vi.advanceTimersByTimeAsync(30000);
    expect(ul.getAttribute('data-active-index')).toBe('0');
  });

  it('clamps an out-of-range index when the list shrinks', async () => {
    setContinueReadingBooks([makeBook('b1'), makeBook('b2'), makeBook('b3')]);
    const { container } = render(ContinueReadingSection);
    await Promise.resolve();
    const ul = container.querySelector('ul')!;

    await vi.advanceTimersByTimeAsync(8000);
    await vi.advanceTimersByTimeAsync(8000);
    expect(ul.getAttribute('data-active-index')).toBe('2');

    setContinueReadingBooks([makeBook('b1')]);
    await Promise.resolve();
    expect(ul.getAttribute('data-active-index')).toBe('0');
  });

  it('stops rotating without index errors when the list drops to one book', async () => {
    setContinueReadingBooks([makeBook('b1'), makeBook('b2')]);
    const { container } = render(ContinueReadingSection);
    await Promise.resolve();
    const ul = container.querySelector('ul')!;

    await vi.advanceTimersByTimeAsync(8000);
    expect(ul.getAttribute('data-active-index')).toBe('1');

    setContinueReadingBooks([makeBook('b1')]);
    await Promise.resolve();
    expect(ul.getAttribute('data-active-index')).toBe('0');

    await vi.advanceTimersByTimeAsync(24000);
    expect(ul.getAttribute('data-active-index')).toBe('0');
  });

  it('re-syncs the active index after a manual scrollend', async () => {
    setContinueReadingBooks([makeBook('b1'), makeBook('b2')]);
    const { container } = render(ContinueReadingSection);
    await Promise.resolve();
    const ul = container.querySelector('ul')!;

    // Simulate a manual scroll that snapped to the second card (w-full snap)
    Object.defineProperty(ul, 'clientWidth', { value: 300, configurable: true });
    Object.defineProperty(ul, 'scrollLeft', { value: 300, configurable: true });
    fireEvent(ul, new Event('scrollend'));
    await Promise.resolve();

    expect(ul.getAttribute('data-active-index')).toBe('1');
  });

  it('clears the rotation timer on unmount', async () => {
    setContinueReadingBooks([makeBook('b1'), makeBook('b2')]);
    const { unmount } = render(ContinueReadingSection);
    await Promise.resolve();

    expect(vi.getTimerCount()).toBeGreaterThan(0);
    unmount();
    await Promise.resolve();
    expect(vi.getTimerCount()).toBe(0);
  });
});
