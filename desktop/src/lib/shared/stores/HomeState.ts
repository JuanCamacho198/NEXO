export type ProgressLike = {
  id: string;
  progressPercentage?: number | null;
  readingStatus?: ReadingState | null;
};

export type ReadingState = 'to_read' | 'reading' | 'completed';

export const resolveReadingState = (
  explicitState: ReadingState | null | undefined,
  progressPercentage: number | null | undefined,
): ReadingState => {
  const progress =
    typeof progressPercentage === 'number' && Number.isFinite(progressPercentage)
      ? Math.min(100, Math.max(0, progressPercentage))
      : 0;
  if (explicitState === 'completed' || progress >= 100) return 'completed';
  if (explicitState === 'reading' || progress > 0) return 'reading';
  return 'to_read';
};

export type AppRoute =
  | 'home'
  | 'library'
  | 'discover'
  | 'addons'
  | 'stats'
  | 'reader'
  | 'highlights'
  | 'settings'
  | 'dictionary'
  | 'storage'
  | 'sync'
  | 'welcome';

export const SHELF_TAB_CODES = ['all', 'favorites', 'to_read', 'completed'] as const;
export type ShelfTabCode = (typeof SHELF_TAB_CODES)[number];

export const SHELF_SORT_KEYS = [
  'progress',
  'date',
  'last_read',
  'author',
  'title',
  'file_size',
] as const;
export type ShelfSortKey = (typeof SHELF_SORT_KEYS)[number];

export const SHELF_VIEW_MODES = ['grid', 'list'] as const;
export type ShelfViewMode = (typeof SHELF_VIEW_MODES)[number];

export const DEFAULT_SHELF_TAB: ShelfTabCode = 'all';
export const DEFAULT_SHELF_SORT_KEY: ShelfSortKey = 'date';
export const DEFAULT_SHELF_VIEW_MODE: ShelfViewMode = 'grid';

export type SmartQueryField = 'status' | 'sort' | 'author' | 'title';

export type ShelfQueryToken = {
  field: SmartQueryField;
  value: string;
  normalizedValue: string;
  raw: string;
};

export type ShelfQueryInvalidTokenReason =
  'unknown_field' | 'missing_value' | 'invalid_value' | 'malformed';

export type ShelfQueryInvalidToken = {
  raw: string;
  field: string | null;
  reason: ShelfQueryInvalidTokenReason;
};

export type ParsedShelfSmartQuery = {
  freeText: string;
  tokens: ShelfQueryToken[];
  invalidTokens: ShelfQueryInvalidToken[];
};

export type ShelfQueryState = {
  tab: ShelfTabCode;
  sortKey: ShelfSortKey;
  searchText: string;
  rawQuery: string;
  smartTokens: ShelfQueryToken[];
  invalidTokens: ShelfQueryInvalidToken[];
  viewMode: ShelfViewMode;
};

export type ShelfBookLike = ProgressLike & {
  id: string;
  title?: string | null;
  author?: string | null;
  createdAt?: string | null;
  updatedAt?: string | null;
  lastReadAt?: string | null;
  fileSizeBytes?: number | null;
  collectionIds?: number[] | null;
  readingStatus?: 'to_read' | 'reading' | 'completed' | null;
};

const removeAccents = (value: string): string =>
  value.normalize('NFD').replace(/[\u0300-\u036f]/g, '');

export const normalizeSearchValue = (value: string): string =>
  removeAccents(value).toLowerCase().trim();

const getSearchTerms = (value: string): string[] =>
  normalizeSearchValue(value)
    .split(/\s+/)
    .filter((term) => term.length > 0);

const parseDateAsMillis = (value: string | null | undefined): number => {
  if (typeof value !== 'string') {
    return Number.NEGATIVE_INFINITY;
  }

  const parsed = Date.parse(value);
  return Number.isFinite(parsed) ? parsed : Number.NEGATIVE_INFINITY;
};

const toNonNegativeNumber = (value: number | null | undefined): number => {
  if (typeof value !== 'number' || !Number.isFinite(value)) {
    return 0;
  }

  return value >= 0 ? value : 0;
};

const normalizeStatusAlias = (statusValue: string): ShelfTabCode | null => {
  const normalized = normalizeSearchValue(statusValue).replace(/[-\s]/g, '_');

  if (normalized === 'all' || normalized === 'todos') {
    return 'all';
  }

  if (
    normalized === 'favorites' ||
    normalized === 'favorite' ||
    normalized === 'favoritos' ||
    normalized === 'favorito'
  ) {
    return 'favorites';
  }

  if (
    normalized === 'to_read' ||
    normalized === 'toread' ||
    normalized === 'planeo_leer' ||
    normalized === 'plan_to_read'
  ) {
    return 'to_read';
  }

  if (
    normalized === 'completed' ||
    normalized === 'complete' ||
    normalized === 'completado' ||
    normalized === 'completados'
  ) {
    return 'completed';
  }

  return null;
};

const normalizeSortAlias = (sortValue: string): ShelfSortKey | null => {
  const normalized = normalizeSearchValue(sortValue).replace(/[-\s]/g, '_');

  if (normalized === 'progress' || normalized === 'progreso') {
    return 'progress';
  }

  if (normalized === 'date' || normalized === 'fecha') {
    return 'date';
  }

  if (
    normalized === 'last_read' ||
    normalized === 'ultimoleido' ||
    normalized === 'ultimo_leido' ||
    normalized === 'lastread'
  ) {
    return 'last_read';
  }

  if (normalized === 'author' || normalized === 'autor') {
    return 'author';
  }

  if (normalized === 'title' || normalized === 'titulo') {
    return 'title';
  }

  if (
    normalized === 'file_size' ||
    normalized === 'tamano_de_archivo' ||
    normalized === 'tamanodearchivo' ||
    normalized === 'size'
  ) {
    return 'file_size';
  }

  return null;
};

export const parseShelfSmartQuery = (rawQuery: string): ParsedShelfSmartQuery => {
  const query = typeof rawQuery === 'string' ? rawQuery.trim() : '';
  if (query.length === 0) {
    return {
      freeText: '',
      tokens: [],
      invalidTokens: [],
    };
  }

  const rawParts = query.split(/\s+/).filter((part) => part.length > 0);
  const freeTextParts: string[] = [];
  const tokens: ShelfQueryToken[] = [];
  const invalidTokens: ShelfQueryInvalidToken[] = [];

  for (const part of rawParts) {
    const separatorIndex = part.indexOf(':');
    if (separatorIndex < 0) {
      freeTextParts.push(part);
      continue;
    }

    const rawField = part.slice(0, separatorIndex);
    const rawValue = part.slice(separatorIndex + 1);
    const field = normalizeSearchValue(rawField);
    const value = rawValue.trim();

    if (field.length === 0) {
      invalidTokens.push({
        raw: part,
        field: null,
        reason: 'malformed',
      });
      continue;
    }

    if (value.length === 0) {
      invalidTokens.push({
        raw: part,
        field,
        reason: 'missing_value',
      });
      continue;
    }

    if (field === 'status') {
      const normalizedStatus = normalizeStatusAlias(value);
      if (!normalizedStatus) {
        invalidTokens.push({
          raw: part,
          field,
          reason: 'invalid_value',
        });
        continue;
      }

      tokens.push({
        raw: part,
        field: 'status',
        value,
        normalizedValue: normalizedStatus,
      });
      continue;
    }

    if (field === 'sort') {
      const normalizedSort = normalizeSortAlias(value);
      if (!normalizedSort) {
        invalidTokens.push({
          raw: part,
          field,
          reason: 'invalid_value',
        });
        continue;
      }

      tokens.push({
        raw: part,
        field: 'sort',
        value,
        normalizedValue: normalizedSort,
      });
      continue;
    }

    if (field === 'author' || field === 'title') {
      tokens.push({
        raw: part,
        field,
        value,
        normalizedValue: normalizeSearchValue(value),
      });
      continue;
    }

    invalidTokens.push({
      raw: part,
      field,
      reason: 'unknown_field',
    });
  }

  return {
    freeText: freeTextParts.join(' '),
    tokens,
    invalidTokens,
  };
};

export const createShelfQueryState = (
  rawQuery = '',
  overrides: Partial<
    Omit<ShelfQueryState, 'rawQuery' | 'searchText' | 'smartTokens' | 'invalidTokens'>
  > = {},
): ShelfQueryState => {
  const parsed = parseShelfSmartQuery(rawQuery);

  return {
    tab: overrides.tab ?? DEFAULT_SHELF_TAB,
    sortKey: overrides.sortKey ?? DEFAULT_SHELF_SORT_KEY,
    viewMode: overrides.viewMode ?? DEFAULT_SHELF_VIEW_MODE,
    rawQuery,
    searchText: parsed.freeText,
    smartTokens: parsed.tokens,
    invalidTokens: parsed.invalidTokens,
  };
};

export const updateShelfQueryState = (
  state: ShelfQueryState,
  updates: Partial<Omit<ShelfQueryState, 'searchText' | 'smartTokens' | 'invalidTokens'>>,
): ShelfQueryState => {
  const nextRawQuery = updates.rawQuery ?? state.rawQuery;
  const parsed = parseShelfSmartQuery(nextRawQuery);

  return {
    ...state,
    ...updates,
    rawQuery: nextRawQuery,
    searchText: parsed.freeText,
    smartTokens: parsed.tokens,
    invalidTokens: parsed.invalidTokens,
  };
};

export const getShelfQueryWarnings = (state: Pick<ShelfQueryState, 'invalidTokens'>): string[] => {
  return state.invalidTokens.map((token) => token.raw);
};

const getBookStatus = (book: ShelfBookLike): ShelfTabCode => {
  if (book.readingStatus === 'completed' || getSafeProgressPercentage(book) >= 100) {
    return 'completed';
  }

  if (book.collectionIds?.includes(1)) {
    return 'favorites';
  }

  if (book.readingStatus === 'to_read') {
    return 'to_read';
  }

  return 'all';
};

const matchesStatusCode = (book: ShelfBookLike, statusCode: ShelfTabCode): boolean => {
  if (statusCode === 'all') {
    return true;
  }

  const bookStatus = getBookStatus(book);
  return bookStatus === statusCode;
};

const matchesSearchTerms = (haystackValue: string, terms: string[]): boolean => {
  if (terms.length === 0) {
    return true;
  }

  const haystack = normalizeSearchValue(haystackValue);
  return terms.every((term) => haystack.includes(term));
};

const compareWithTieBreakers = <TBook extends ShelfBookLike>(
  left: TBook,
  right: TBook,
  primary: number,
  leftIndex: number,
  rightIndex: number,
): number => {
  if (primary !== 0) {
    return primary;
  }

  const titleCompare = normalizeSearchValue(left.title ?? '').localeCompare(
    normalizeSearchValue(right.title ?? ''),
  );
  if (titleCompare !== 0) {
    return titleCompare;
  }

  const authorCompare = normalizeSearchValue(left.author ?? '').localeCompare(
    normalizeSearchValue(right.author ?? ''),
  );
  if (authorCompare !== 0) {
    return authorCompare;
  }

  const idCompare = left.id.localeCompare(right.id);
  if (idCompare !== 0) {
    return idCompare;
  }

  return leftIndex - rightIndex;
};

const getSortFromTokens = (tokens: ShelfQueryToken[]): ShelfSortKey | null => {
  for (let index = tokens.length - 1; index >= 0; index -= 1) {
    const token = tokens[index];
    if (token.field === 'sort') {
      return token.normalizedValue as ShelfSortKey;
    }
  }

  return null;
};

const getStatusFiltersFromTokens = (tokens: ShelfQueryToken[]): ShelfTabCode[] => {
  const statuses = new Set<ShelfTabCode>();
  for (const token of tokens) {
    if (token.field === 'status') {
      statuses.add(token.normalizedValue as ShelfTabCode);
    }
  }

  return Array.from(statuses);
};

export const selectShelfBooks = <TBook extends ShelfBookLike>(
  books: TBook[],
  queryState: ShelfQueryState,
): TBook[] => {
  const statusFiltersFromTokens = getStatusFiltersFromTokens(queryState.smartTokens);
  const activeSortKey = getSortFromTokens(queryState.smartTokens) ?? queryState.sortKey;

  const freeTextTerms = getSearchTerms(queryState.searchText);
  const authorTokenTerms = queryState.smartTokens
    .filter((token) => token.field === 'author')
    .map((token) => token.normalizedValue)
    .filter((token) => token.length > 0);
  const titleTokenTerms = queryState.smartTokens
    .filter((token) => token.field === 'title')
    .map((token) => token.normalizedValue)
    .filter((token) => token.length > 0);

  const filtered = books.filter((book) => {
    if (!matchesStatusCode(book, queryState.tab)) {
      return false;
    }

    if (
      statusFiltersFromTokens.length > 0 &&
      !statusFiltersFromTokens.some((status) => matchesStatusCode(book, status))
    ) {
      return false;
    }

    if (
      authorTokenTerms.length > 0 &&
      !authorTokenTerms.every((term) => matchesSearchTerms(book.author ?? '', [term]))
    ) {
      return false;
    }

    if (
      titleTokenTerms.length > 0 &&
      !titleTokenTerms.every((term) => matchesSearchTerms(book.title ?? '', [term]))
    ) {
      return false;
    }

    if (freeTextTerms.length === 0) {
      return true;
    }

    return matchesSearchTerms(`${book.title ?? ''} ${book.author ?? ''} ${book.id}`, freeTextTerms);
  });

  const indexed = filtered.map((book, index) => ({
    book,
    index,
  }));

  indexed.sort((left, right) => {
    if (activeSortKey === 'progress') {
      const primary = getSafeProgressPercentage(right.book) - getSafeProgressPercentage(left.book);
      return compareWithTieBreakers(left.book, right.book, primary, left.index, right.index);
    }

    if (activeSortKey === 'date') {
      const primary =
        parseDateAsMillis(right.book.updatedAt ?? right.book.createdAt) -
        parseDateAsMillis(left.book.updatedAt ?? left.book.createdAt);
      return compareWithTieBreakers(left.book, right.book, primary, left.index, right.index);
    }

    if (activeSortKey === 'last_read') {
      const primary =
        parseDateAsMillis(right.book.lastReadAt ?? right.book.updatedAt) -
        parseDateAsMillis(left.book.lastReadAt ?? left.book.updatedAt);
      return compareWithTieBreakers(left.book, right.book, primary, left.index, right.index);
    }

    if (activeSortKey === 'author') {
      const primary = normalizeSearchValue(left.book.author ?? '').localeCompare(
        normalizeSearchValue(right.book.author ?? ''),
      );
      return compareWithTieBreakers(left.book, right.book, primary, left.index, right.index);
    }

    if (activeSortKey === 'title') {
      const primary = normalizeSearchValue(left.book.title ?? '').localeCompare(
        normalizeSearchValue(right.book.title ?? ''),
      );
      return compareWithTieBreakers(left.book, right.book, primary, left.index, right.index);
    }

    const primary =
      toNonNegativeNumber(right.book.fileSizeBytes) - toNonNegativeNumber(left.book.fileSizeBytes);
    return compareWithTieBreakers(left.book, right.book, primary, left.index, right.index);
  });

  return indexed.map((item) => item.book);
};

export type HomeStateSnapshot = {
  route: AppRoute;
  previewBookId: string | null;
  activeReadingBookId: string | null;
  shelfDetailsBookId: string | null;
};

const clamp = (value: number, min: number, max: number): number => {
  if (value < min) {
    return min;
  }

  if (value > max) {
    return max;
  }

  return value;
};

export const getSafeProgressPercentage = (
  book: Pick<ProgressLike, 'progressPercentage'>,
): number => {
  const raw = book.progressPercentage;
  if (typeof raw !== 'number' || !Number.isFinite(raw)) {
    return 0;
  }

  return clamp(raw, 0, 100);
};

export const isBookInProgress = (
  book: Pick<ProgressLike, 'progressPercentage' | 'readingStatus'>,
): boolean => {
  const pct = getSafeProgressPercentage(book);
  if (pct <= 0 || pct >= 100) return false;
  return resolveReadingState(book.readingStatus, book.progressPercentage) === 'reading';
};

export const partitionHomeBooks = <TBook extends ProgressLike>(
  books: TBook[],
): { continueReadingBooks: TBook[]; myShelfBooks: TBook[] } => {
  const continueReadingBooks: TBook[] = [];
  const myShelfBooks: TBook[] = [];
  const seenIds = new Set<string>();

  for (const book of books) {
    if (seenIds.has(book.id)) {
      continue;
    }

    seenIds.add(book.id);
    if (isBookInProgress(book)) {
      continueReadingBooks.push(book);
    }

    myShelfBooks.push(book);
  }

  return {
    continueReadingBooks,
    myShelfBooks,
  };
};

export const promoteBookForReading = <TBook extends ProgressLike>(
  books: TBook[],
  bookId: string,
): TBook[] => {
  let changed = false;
  const promoted = books.map((book) => {
    if (book.id !== bookId) {
      return book;
    }

    const progress = getSafeProgressPercentage(book);
    if (progress > 0 || progress >= 100) {
      return book;
    }

    changed = true;
    return {
      ...book,
      progressPercentage: 1,
    };
  });

  return changed ? promoted : books;
};

export const reconcileHomeState = <TBook extends ProgressLike>(
  books: TBook[],
  snapshot: HomeStateSnapshot,
): HomeStateSnapshot => {
  const bookIds = new Set(books.map((book) => book.id));
  const { myShelfBooks } = partitionHomeBooks(books);
  const shelfIds = new Set(myShelfBooks.map((book) => book.id));

  const firstBookId = books[0]?.id ?? null;
  const previewBookId =
    snapshot.previewBookId && bookIds.has(snapshot.previewBookId)
      ? snapshot.previewBookId
      : firstBookId;

  const activeReadingBookId =
    snapshot.activeReadingBookId && bookIds.has(snapshot.activeReadingBookId)
      ? snapshot.activeReadingBookId
      : null;

  const route = activeReadingBookId
    ? snapshot.route
    : snapshot.route === 'reader'
      ? 'home'
      : snapshot.route;

  const canKeepShelfDetails =
    route === 'home' &&
    snapshot.shelfDetailsBookId !== null &&
    shelfIds.has(snapshot.shelfDetailsBookId);

  const shelfDetailsBookId = canKeepShelfDetails ? snapshot.shelfDetailsBookId : null;

  return {
    route,
    previewBookId,
    activeReadingBookId,
    shelfDetailsBookId,
  };
};
