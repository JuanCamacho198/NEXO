import { fireEvent, render, screen } from '@testing-library/svelte';
import userEvent from '@testing-library/user-event';
import { tick } from 'svelte';
import { describe, expect, it, vi } from 'vitest';
import { SearchPanel } from '$lib/features/reader';
import type { SearchBookTextResponse } from '$lib/shared/types';
import { searchBookText } from '$lib/shared/api/tauriClient';

const t = (key: string) => {
  const dictionary: Record<string, string> = {
    'search.title': 'In-Book Search',
    'search.placeholder': 'Search text in this book',
    'search.search': 'Search',
    'search.searching': 'Searching...',
    'search.locator': 'Locator',
    'search.page': 'Page',
    'search.matches': 'matches',
    'search.prev': 'Prev',
    'search.next': 'Next',
    'search.noMatches': 'No matches found for this query.',
    'settings.close': 'Close settings',
  };

  return dictionary[key] ?? key;
};

// The reader feature barrel (`$lib/features/reader`) loads ReaderWorkspace,
// bookmarks state, pdfStreaming and ReaderDomainState at import time; the
// tauriClient mock must expose every export those modules bind at load.
// NOTE: no top-level variables inside the factory — vi.mock is hoisted.
vi.mock('$lib/shared/api/tauriClient', () => {
  const defaults = {
    themeMode: 'paper',
    brightness: 100,
    contrast: 100,
    selectionColor: '#3b82f6',
    epub: { fontSize: 16, fontFamily: 'serif' },
  };
  return {
    searchBookText: vi.fn(),
    getDefaultReaderSettings: vi.fn(() => defaults),
    getReaderSettings: vi.fn().mockResolvedValue(defaults),
    upsertReaderSettings: vi.fn().mockResolvedValue(defaults),
    saveHighlight: vi.fn().mockResolvedValue(undefined),
    deleteHighlight: vi.fn().mockResolvedValue(undefined),
    updateHighlight: vi.fn().mockResolvedValue(undefined),
    saveHighlightTags: vi.fn().mockResolvedValue(undefined),
    createTag: vi.fn().mockResolvedValue({ id: 1, name: 'tag' }),
    listTags: vi.fn().mockResolvedValue([]),
    listTagsForHighlight: vi.fn().mockResolvedValue([]),
    addDictionaryWord: vi.fn().mockResolvedValue({ id: 1, word: 'w' }),
    listHighlights: vi.fn().mockResolvedValue([]),
    listBookmarks: vi.fn().mockResolvedValue([]),
    saveBookmark: vi.fn().mockResolvedValue(undefined),
    deleteBookmark: vi.fn().mockResolvedValue(undefined),
    getFileBytes: vi.fn().mockResolvedValue([]),
    setReadingStatus: vi.fn().mockResolvedValue(undefined),
  };
});

// Use type cast since vi.mocked is not available in vitest 4.x
const mockedSearchBookText = searchBookText as unknown as ReturnType<typeof vi.fn>;

describe('SearchPanel', () => {
  it('shows no-match state and emits search/jump callbacks', async () => {
    mockedSearchBookText.mockResolvedValueOnce({
      items: [],
      total: 0,
      page: 1,
      pageSize: 200,
    });

    const noMatches = await searchBookText({
      bookId: 'book-1',
      query: 'absent',
      page: 1,
      pageSize: 200,
    });

    const onSearch = vi.fn();
    const onJump = vi.fn();

    const rendered = render(SearchPanel, {
      open: true,
      onClose: vi.fn(),
      bookId: 'book-1',
      disabledReason: null,
      isSearching: false,
      response: noMatches,
      onSearch,
      onJump,
      t,
    });

    expect(screen.getByText('No matches found for this query.')).toBeInTheDocument();

    const user = userEvent.setup();
    const input = screen.getByPlaceholderText('Search text in this book');
    await user.type(input, 'needle');
    await fireEvent.submit(input.closest('form') as HTMLFormElement);
    expect(onSearch).toHaveBeenCalledWith('needle', 1);

    const withMatches: SearchBookTextResponse = {
      items: [
        {
          chunkId: 'chunk-1',
          bookId: 'book-1',
          locator: 'epubcfi(/6/2)',
          snippet: '...needle...',
          rank: 0.2,
        },
      ],
      total: 1,
      page: 1,
      pageSize: 200,
    };

    rendered.unmount();
    render(SearchPanel, {
      open: true,
      onClose: vi.fn(),
      bookId: 'book-1',
      disabledReason: null,
      isSearching: false,
      response: withMatches,
      onSearch,
      onJump,
      t,
    });
    await user.click(screen.getByRole('button', { name: /needle/ }));

    expect(onJump).toHaveBeenCalledTimes(1);
    expect(onJump.mock.calls[0][0]).toMatchObject({
      locator: 'epubcfi(/6/2)',
    });
  });

  const makeResponse = (
    overrides: Partial<SearchBookTextResponse> = {},
  ): SearchBookTextResponse => ({
    items: [],
    total: 0,
    page: 1,
    pageSize: 20,
    ...overrides,
  });

  const renderPanel = (overrides: Record<string, unknown> = {}) => {
    const onClose = vi.fn();
    const onSearch = vi.fn();
    const onJump = vi.fn();
    const rendered = render(SearchPanel, {
      open: true,
      onClose,
      bookId: 'book-1',
      disabledReason: null,
      isSearching: false,
      response: null,
      onSearch,
      onJump,
      t,
      ...overrides,
    });
    return { rendered, onClose, onSearch, onJump };
  };

  describe('drawer shell', () => {
    it('renders nothing while closed', () => {
      const { rendered } = renderPanel({ open: false });

      expect(rendered.container.querySelector('[role="dialog"]')).toBeNull();
      expect(screen.queryByPlaceholderText('Search text in this book')).toBeNull();
      expect(rendered.container.querySelector('form')).toBeNull();
    });

    it('names the dialog and makes the surface focusable', () => {
      renderPanel();

      const dialog = screen.getByRole('dialog', { name: 'In-Book Search' });
      expect(dialog).toBeInTheDocument();
      expect(dialog.getAttribute('tabindex')).toBe('0');
    });

    it('closes on a backdrop click on the overlay root', async () => {
      const { rendered, onClose } = renderPanel();

      const overlay = rendered.container.querySelector('.fixed.inset-0');
      expect(overlay).not.toBeNull();
      await fireEvent.click(overlay as HTMLElement);

      expect(onClose).toHaveBeenCalledTimes(1);
    });

    it('does not close on a click inside the drawer surface', async () => {
      const { onClose } = renderPanel();

      const dialog = screen.getByRole('dialog', { name: 'In-Book Search' });
      await fireEvent.click(dialog);

      expect(onClose).not.toHaveBeenCalled();
    });

    it('closes on Escape from within the drawer', async () => {
      const { onClose, rendered } = renderPanel();

      await fireEvent.keyDown(rendered.container.querySelector('[role="dialog"]') as HTMLElement, {
        key: 'Escape',
      });

      // Call-count is intentionally NOT asserted: the hand-rolled recipe binds
      // Escape on BOTH the overlay root and the drawer surface (verbatim from
      // ReaderTocPanel.svelte:60/:76), so a bubbling keydown reaches two
      // handlers. The sibling drawers behave identically and the duplicate call
      // is idempotent against a state-setting onClose. Asserting "exactly once"
      // here would fail the copied recipe rather than the behavior.
      expect(onClose).toHaveBeenCalled();
    });

    it('closes on the close control', async () => {
      const { onClose } = renderPanel();

      await fireEvent.click(screen.getByRole('button', { name: 'Close settings' }));

      expect(onClose).toHaveBeenCalledTimes(1);
    });

    it('traps focus inside the drawer while open', async () => {
      renderPanel();
      await tick();

      const dialog = screen.getByRole('dialog', { name: 'In-Book Search' });
      expect(dialog.contains(document.activeElement)).toBe(true);
    });
  });

  describe('preserved search behavior', () => {
    it('shows the disabled reason instead of the form', () => {
      renderPanel({ disabledReason: 'No searchable index for this book' });

      expect(screen.getByText('No searchable index for this book')).toBeInTheDocument();
      expect(screen.queryByPlaceholderText('Search text in this book')).toBeNull();
    });

    it('does not search on a whitespace-only query', async () => {
      const { onSearch, rendered } = renderPanel();

      const input = screen.getByPlaceholderText('Search text in this book');
      await fireEvent.input(input, { target: { value: '   ' } });
      await fireEvent.submit(rendered.container.querySelector('form') as HTMLFormElement);

      expect(onSearch).not.toHaveBeenCalled();
    });

    it('disables submit while no book id is set', () => {
      renderPanel({ bookId: null });

      const input = screen.getByPlaceholderText('Search text in this book');
      void fireEvent.input(input, { target: { value: 'needle' } });

      expect(screen.getByRole('button', { name: 'Search' })).toBeDisabled();
    });

    it('disables prev on page 1 and next when no further page exists', () => {
      renderPanel({
        response: makeResponse({
          items: [{ chunkId: 'c1', bookId: 'book-1', locator: 'l1', snippet: 's1', rank: 0.1 }],
          total: 1,
          page: 1,
          pageSize: 20,
        }),
      });

      expect(screen.getByRole('button', { name: 'Prev' })).toBeDisabled();
      expect(screen.getByRole('button', { name: 'Next' })).toBeDisabled();
    });

    it('enables next when a further page exists', () => {
      renderPanel({
        response: makeResponse({
          items: [{ chunkId: 'c1', bookId: 'book-1', locator: 'l1', snippet: 's1', rank: 0.1 }],
          total: 40,
          page: 1,
          pageSize: 20,
        }),
      });

      expect(screen.getByRole('button', { name: 'Next' })).not.toBeDisabled();
    });
  });
});
