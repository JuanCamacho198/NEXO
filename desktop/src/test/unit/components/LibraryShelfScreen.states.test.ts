import { fireEvent, render, screen } from '@testing-library/svelte';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import LibraryShelfScreen from '$lib/features/library/components/LibraryShelfScreen.svelte';
import type { ShelfBook } from '$lib/features/library/utils';

const { catalog } = vi.hoisted(() => ({
  catalog: {
    books: [] as unknown[],
    count: 0,
    error: null as string | null,
    isDownloading: new Set<string>(),
    loadAvailableFromDrive: vi.fn().mockResolvedValue(undefined),
    clearDownloadError: vi.fn(),
  },
}));

vi.mock('$lib/stores/downloadableCatalog.svelte', async (importOriginal) => {
  const actual = await importOriginal<typeof import('$lib/stores/downloadableCatalog.svelte')>();
  return { ...actual, downloadableCatalog: catalog };
});

const dictionary: Record<string, string> = {
  'library.emptyTitle': 'Your library is empty',
  'library.emptyDescription': 'Add books to start building your personal collection.',
  'library.import': 'Import Books',
  'library.searchNoResults': 'No results',
  'library.noResultsDescription': 'Try a different search or clear the filters.',
  'library.clearFilters': 'Clear filters',
  'library.searchPlaceholder': 'Search by title or author...',
};

const t = (key: string, params?: Record<string, string | number>): string => {
  const template = dictionary[key] ?? key;
  if (!params) return template;
  return template.replace(/\{\{\s*([\w.-]+)\s*\}\}/g, (_match, token) =>
    String(params[token] ?? ''),
  );
};

const book: ShelfBook = {
  id: 'b1',
  title: 'Alpha',
  author: 'Author A',
  format: 'epub',
  currentPage: 2,
  totalPages: 10,
  progressPercentage: 20,
  coverPath: null,
  minutesRead: 5,
  updatedAt: '2026-01-01T00:00:00.000Z',
  createdAt: '2026-01-01T00:00:00.000Z',
  filePath: '/library/alpha.epub',
};

describe('LibraryShelfScreen states (LIB-02)', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('shows a skeleton grid instead of the empty state while loading', () => {
    const { container } = render(LibraryShelfScreen, {
      props: { books: [], isLoading: true, t },
    });

    expect(container.querySelectorAll('.animate-pulse').length).toBeGreaterThan(0);
    expect(screen.queryByText('Your library is empty')).toBeNull();
    // The toolbar stays available while the catalogue loads.
    expect(screen.getByLabelText('shelf.gridView')).toBeInTheDocument();
  });

  it('explains an empty library and offers importing as the next action', () => {
    render(LibraryShelfScreen, { props: { books: [], isLoading: false, t } });

    expect(screen.getByText('Your library is empty')).toBeInTheDocument();
    expect(
      screen.getByText('Add books to start building your personal collection.'),
    ).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Import Books' })).toBeInTheDocument();
  });

  it('explains a filtered-out catalogue and clears the filters on demand', async () => {
    const user = userEvent.setup();
    render(LibraryShelfScreen, { props: { books: [book], isLoading: false, t } });

    expect(screen.getByText('Alpha')).toBeInTheDocument();

    await user.type(screen.getByPlaceholderText('Search by title or author...'), 'no-such-title');

    expect(await screen.findByText('No results')).toBeInTheDocument();
    expect(screen.queryByText('Alpha')).toBeNull();

    await user.click(screen.getByRole('button', { name: 'Clear filters' }));

    expect(await screen.findByText('Alpha')).toBeInTheDocument();
    expect(screen.queryByText('No results')).toBeNull();
  });

  it('focuses the search field on `/` but not while typing in another field', async () => {
    render(LibraryShelfScreen, { props: { books: [book], isLoading: false, t } });

    const search = screen.getByTestId('shelf-search');
    await fireEvent.keyDown(window, { key: '/' });
    expect(document.activeElement).toBe(search);

    const external = document.createElement('input');
    document.body.appendChild(external);
    external.focus();
    await fireEvent.keyDown(external, { key: '/' });

    expect(document.activeElement).toBe(external);
    expect(document.activeElement).not.toBe(search);
    external.remove();
  });

  it('renders the unified search field with its own container focus and `/` keycap', async () => {
    const user = userEvent.setup();
    const { container } = render(LibraryShelfScreen, {
      props: { books: [book], isLoading: false, t },
    });

    const root = container.querySelector('[role="search"]')!;
    expect(root).toHaveClass('search-field');
    expect(root).toHaveClass('focus-within:ring-2');

    const search = screen.getByTestId('shelf-search');
    expect(search).toHaveAttribute('type', 'search');

    await user.click(screen.getByRole('button', { name: 'library.searchShortcutAria' }));
    expect(document.activeElement).toBe(search);
  });

  it('gives the search its own row and splits chips left from sort/view right', () => {
    const { container } = render(LibraryShelfScreen, {
      props: { books: [book], isLoading: false, t },
    });

    const search = screen.getByTestId('shelf-search');
    const band = search.closest('section')!.firstElementChild as HTMLElement;
    expect(band.children).toHaveLength(2);

    const [searchRow, controlsRow] = Array.from(band.children) as HTMLElement[];

    // The search line carries nothing else.
    expect(searchRow.contains(search)).toBe(true);
    expect(searchRow.querySelector('[data-testid^="shelf-tab-"]')).toBeNull();
    expect(searchRow.querySelector('[data-testid="shelf-sort"]')).toBeNull();

    // The second row splits the filter chips (left) from the sort and view
    // controls (right).
    expect(controlsRow.className).toContain('justify-between');
    expect(container.querySelector('[class*="lg:justify-end"]')).toBeNull();
    expect(container.querySelector('[class*="xl:ml-auto"]')).toBeNull();

    const chips = screen.getByTestId('shelf-tab-all');
    const sort = screen.getByTestId('shelf-sort');
    const toggle = screen.getByTestId('shelf-view-toggle');

    expect(controlsRow.firstElementChild!.contains(chips)).toBe(true);
    expect(controlsRow.lastElementChild!.contains(sort)).toBe(true);
    expect(controlsRow.lastElementChild!.contains(toggle)).toBe(true);
    expect(controlsRow.lastElementChild!.className).toContain('ml-auto');
  });
});
