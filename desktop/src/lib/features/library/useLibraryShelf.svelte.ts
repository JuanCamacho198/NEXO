import {
  FAVORITES_COLLECTION_ID,
  getSafeProgressPercentage,
  getTimestamp,
  type ShelfBook,
  type ShelfFilter,
  type ShelfSort,
  type ShelfView,
} from './utils';
import {
  parseShelfSmartQuery,
  normalizeSearchValue,
  type ParsedShelfSmartQuery,
  type ShelfQueryInvalidToken,
  type ShelfSortKey,
  type ShelfTabCode,
} from '$lib/shared/stores/HomeState';

/**
 * Single filter + sort engine for Estantería. The Estantería search field
 * accepts the same smart-query grammar as the retired Home bar (parsed by
 * `parseShelfSmartQuery`), so a token and the visible controls can target the
 * same dimension. Precedence, per dimension:
 *
 *   - `status:` tokens (all|favorites|to_read|completed) override
 *     `activeFilter` while present; deleting the token restores the control.
 *   - `sort:` tokens (progress|date|last_read|author|title|file_size) override
 *     `activeSort` while present; deleting the token restores the control.
 *   - `author:` / `title:` tokens filter that field and compose with the free
 *     text left over after the tokens are stripped.
 *   - Free text keeps today's behavior: a case/accent-insensitive substring
 *     match against title or author.
 */
const TOKEN_STATUS_TO_FILTER: Record<ShelfTabCode, ShelfFilter> = {
  all: 'all',
  favorites: 'favorites',
  // Estantería names this bucket "Pendientes"; the token reuses its `to_read`.
  to_read: 'pending',
  completed: 'completed',
};

const TOKEN_SORT_TO_SORT: Record<ShelfSortKey, ShelfSort> = {
  progress: 'progress',
  date: 'date_added',
  last_read: 'last_read',
  author: 'author',
  title: 'title',
  file_size: 'file_size',
};

function matchesFilter(book: ShelfBook, filter: ShelfFilter): boolean {
  const progress = getSafeProgressPercentage(book);
  if (filter === 'all') return true;
  if (filter === 'favorites') return Boolean(book.collectionIds?.includes(FAVORITES_COLLECTION_ID));
  if (filter === 'reading') return progress > 0 && progress < 100;
  if (filter === 'completed') return book.readingStatus === 'completed' || progress >= 100;
  return progress === 0;
}

function resolveStatusFilters(
  parsed: ParsedShelfSmartQuery,
  activeFilter: ShelfFilter,
): ShelfFilter[] {
  const filters: ShelfFilter[] = [];
  for (const token of parsed.tokens) {
    if (token.field !== 'status') continue;
    const mapped = TOKEN_STATUS_TO_FILTER[token.normalizedValue as ShelfTabCode];
    if (mapped && !filters.includes(mapped)) filters.push(mapped);
  }
  return filters.length > 0 ? filters : [activeFilter];
}

function resolveSort(parsed: ParsedShelfSmartQuery, activeSort: ShelfSort): ShelfSort {
  for (let index = parsed.tokens.length - 1; index >= 0; index -= 1) {
    const token = parsed.tokens[index];
    if (token.field !== 'sort') continue;
    const mapped = TOKEN_SORT_TO_SORT[token.normalizedValue as ShelfSortKey];
    if (mapped) return mapped;
  }
  return activeSort;
}

function getFileSizeBytes(book: ShelfBook): number {
  const size = (book as ShelfBook & { fileSizeBytes?: number | null }).fileSizeBytes;
  return typeof size === 'number' && Number.isFinite(size) ? size : 0;
}

export function filterAndSortShelfBooks(
  books: ShelfBook[],
  searchQuery: string,
  activeFilter: ShelfFilter,
  activeSort: ShelfSort,
): ShelfBook[] {
  const parsed = parseShelfSmartQuery(searchQuery);
  const statusFilters = resolveStatusFilters(parsed, activeFilter);
  const sortKey = resolveSort(parsed, activeSort);

  const freeText = normalizeSearchValue(parsed.freeText);
  const authorTerms = parsed.tokens
    .filter((token) => token.field === 'author')
    .map((token) => token.normalizedValue)
    .filter((term) => term.length > 0);
  const titleTerms = parsed.tokens
    .filter((token) => token.field === 'title')
    .map((token) => token.normalizedValue)
    .filter((term) => term.length > 0);

  const visible = books.filter((book) => {
    if (!statusFilters.some((filter) => matchesFilter(book, filter))) return false;

    if (
      authorTerms.some((term) => !normalizeSearchValue(book.author ?? '').includes(term)) ||
      titleTerms.some((term) => !normalizeSearchValue(book.title).includes(term))
    ) {
      return false;
    }

    if (freeText.length === 0) return true;
    return (
      normalizeSearchValue(book.title).includes(freeText) ||
      normalizeSearchValue(book.author ?? '').includes(freeText)
    );
  });

  return [...visible].sort((left, right) => {
    if (sortKey === 'title') return left.title.localeCompare(right.title, 'es');
    if (sortKey === 'author') {
      const byAuthor = (left.author ?? '').localeCompare(right.author ?? '', 'es');
      return byAuthor !== 0 ? byAuthor : left.title.localeCompare(right.title, 'es');
    }
    if (sortKey === 'progress')
      return getSafeProgressPercentage(right) - getSafeProgressPercentage(left);
    if (sortKey === 'file_size') return getFileSizeBytes(right) - getFileSizeBytes(left);
    // date_added and last_read share the same timestamp source (updatedAt).
    return getTimestamp(right) - getTimestamp(left);
  });
}

export type UseLibraryShelfReturn = {
  searchQuery: string;
  activeFilter: ShelfFilter;
  activeSort: ShelfSort;
  activeView: ShelfView;
  invalidTokens: ShelfQueryInvalidToken[];
  filteredBooks: ShelfBook[];
};

export function useLibraryShelf(getBooks: () => ShelfBook[]): UseLibraryShelfReturn {
  let searchQuery = $state('');
  let activeFilter = $state<ShelfFilter>('all');
  let activeSort = $state<ShelfSort>('date_added');
  let activeView = $state<ShelfView>('grid');

  const parsedQuery = $derived(parseShelfSmartQuery(searchQuery));
  const filteredBooks = $derived.by(() =>
    filterAndSortShelfBooks(getBooks(), searchQuery, activeFilter, activeSort),
  );

  return {
    get searchQuery(): string {
      return searchQuery;
    },
    set searchQuery(v: string) {
      searchQuery = v;
    },
    get activeFilter(): ShelfFilter {
      return activeFilter;
    },
    set activeFilter(v: ShelfFilter) {
      activeFilter = v;
    },
    get activeSort(): ShelfSort {
      return activeSort;
    },
    set activeSort(v: ShelfSort) {
      activeSort = v;
    },
    get activeView(): ShelfView {
      return activeView;
    },
    set activeView(v: ShelfView) {
      activeView = v;
    },
    get invalidTokens(): ShelfQueryInvalidToken[] {
      return parsedQuery.invalidTokens;
    },
    get filteredBooks(): ShelfBook[] {
      return filteredBooks;
    },
  };
}
