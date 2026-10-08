import { render, screen, waitFor } from '@testing-library/svelte';
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
  'home.shelfWarningsLabel': 'Query warnings',
  'home.shelfSearchInvalid': 'Ignored tokens: {{value}}',
  'library.searchPlaceholder': 'Search by title or author...',
  'library.searchAriaLabel': 'Search books',
};

const t = (key: string, params?: Record<string, string | number>): string => {
  const template = dictionary[key] ?? key;
  if (!params) return template;
  return template.replace(/\{\{\s*([\w.-]+)\s*\}\}/g, (_match, token) =>
    String(params[token] ?? ''),
  );
};

function makeBook(overrides: Partial<ShelfBook>): ShelfBook {
  return {
    id: 'book',
    title: 'Untitled',
    author: 'Unknown',
    format: 'epub',
    currentPage: 0,
    totalPages: 100,
    progressPercentage: 0,
    coverPath: null,
    minutesRead: 0,
    updatedAt: '2026-01-01T00:00:00.000Z',
    createdAt: '2026-01-01T00:00:00.000Z',
    filePath: '/library/book.epub',
    ...overrides,
  };
}

const favorite = makeBook({
  id: 'fav',
  title: 'Dune',
  author: 'Frank Herbert',
  collectionIds: [1],
});
const completed = makeBook({
  id: 'done',
  title: 'Alpha',
  author: 'Isaac Asimov',
  progressPercentage: 100,
  readingStatus: 'completed',
});
const reading = makeBook({
  id: 'read',
  title: 'Beta',
  author: 'Ursula Le Guin',
  progressPercentage: 40,
});

const BOOKS = [favorite, completed, reading];

function renderScreen() {
  return render(LibraryShelfScreen, { props: { books: BOOKS, t } });
}

function visibleTitles(): (string | null)[] {
  return Array.from(document.querySelectorAll('article h2')).map((node) => node.textContent);
}

describe('LibraryShelfScreen smart-query tokens', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('filters by free text against title and author as before', async () => {
    const user = userEvent.setup();
    renderScreen();

    await user.type(screen.getByTestId('shelf-search'), 'beta');

    await waitFor(() => {
      expect(screen.getByText('Beta')).toBeInTheDocument();
      expect(screen.queryByText('Dune')).toBeNull();
      expect(screen.queryByText('Alpha')).toBeNull();
    });
  });

  it('applies a status token and lets it override the visible filter', async () => {
    const user = userEvent.setup();
    renderScreen();

    await user.click(screen.getByTestId('shelf-tab-completed'));
    await waitFor(() => {
      expect(screen.getByText('Alpha')).toBeInTheDocument();
      expect(screen.queryByText('Dune')).toBeNull();
    });

    await user.type(screen.getByTestId('shelf-search'), 'status:favoritos');
    await waitFor(() => {
      expect(screen.getByText('Dune')).toBeInTheDocument();
      expect(screen.queryByText('Alpha')).toBeNull();
    });

    await user.clear(screen.getByTestId('shelf-search'));
    await waitFor(() => {
      expect(screen.getByText('Alpha')).toBeInTheDocument();
      expect(screen.queryByText('Dune')).toBeNull();
    });
  });

  it('applies a sort token to the visible results', async () => {
    const user = userEvent.setup();
    renderScreen();

    await waitFor(() => {
      expect(visibleTitles().length).toBe(3);
    });

    await user.type(screen.getByTestId('shelf-search'), 'sort:titulo');

    await waitFor(() => {
      expect(visibleTitles()).toEqual(['Alpha', 'Beta', 'Dune']);
    });
  });

  it('filters by author: and title: tokens on their own field', async () => {
    const user = userEvent.setup();
    renderScreen();

    const search = screen.getByTestId('shelf-search');
    await user.type(search, 'author:asimov');
    await waitFor(() => {
      expect(screen.getByText('Alpha')).toBeInTheDocument();
      expect(screen.queryByText('Dune')).toBeNull();
      expect(screen.queryByText('Beta')).toBeNull();
    });

    await user.clear(search);
    await user.type(search, 'title:dune');
    await waitFor(() => {
      expect(screen.getByText('Dune')).toBeInTheDocument();
      expect(screen.queryByText('Alpha')).toBeNull();
      expect(screen.queryByText('Beta')).toBeNull();
    });
  });

  it('shows a warnings block for invalid tokens while keeping valid results', async () => {
    const user = userEvent.setup();
    renderScreen();

    await user.type(screen.getByTestId('shelf-search'), 'author:asimov foo:bar');

    await waitFor(() => {
      const warnings = screen.getByTestId('shelf-warnings');
      expect(warnings).toBeInTheDocument();
      expect(warnings).toHaveTextContent('Query warnings');
      expect(warnings).toHaveTextContent('Ignored tokens: foo:bar');
      expect(screen.getByText('Alpha')).toBeInTheDocument();
    });
  });
});
