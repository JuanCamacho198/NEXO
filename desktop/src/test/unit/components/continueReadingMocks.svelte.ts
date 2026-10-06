// Rune-backed mock state for ContinueReadingSection tests.
//
// ContinueReadingSection reads `libraryState.continueReadingBooks` through a
// $derived/$effect chain, so the mock MUST expose signal-backed getters —
// plain objects are not reactive and mid-test mutations (OOB clamp, list
// shrinking to one book) would never propagate. `.svelte.ts` files may use
// runes, matching the store pattern used across the app.
import { vi } from 'vitest';

type BookLike = Record<string, unknown>;

const dictionary: Record<string, string> = {
  'home.continueReading': 'Continue Reading',
  'home.continueReadingPlaceholder': 'No in-progress books yet',
  'home.continue.progress': 'Progress',
  'home.continue.position': 'p. {{current}} / {{total}}',
  'home.continue.liveBadge': 'IN PROGRESS',
  'home.continue.countAria': '{{count}} in progress',
  'home.continue.nextBook': 'Next',
  'home.continue.prevBook': 'Previous',
  'app.continue': 'Continue',
  'app.read': 'Read',
  'app.unknownAuthor': 'Unknown author',
  'library.cover': 'Cover',
  'library.optionsFor': 'Options for {{title}}',
  'library.editMetadata.title': 'Edit Metadata',
  'library.removeFromShelf': 'Remove from shelf',
  'library.favoriteAdd': 'Add to favorites',
  'library.favoriteRemove': 'Remove from favorites',
  'shelf.viewDetails': 'View details',
};

const translate = (key: string, params?: Record<string, string | number>): string => {
  const template = dictionary[key] ?? key;
  if (!params) {
    return template;
  }
  return template
    .replace(/\{\{\s*([\w.-]+)\s*\}\}/g, (_match, token: string) => String(params[token] ?? ''))
    .replace(/\{\s*([\w.-]+)\s*\}/g, (_match, token: string) => String(params[token] ?? ''));
};

function createMocks() {
  let books = $state<BookLike[]>([]);
  let previewBookId = $state<string | null>(null);

  const mockAppState = {
    t: translate,
    openDetails: vi.fn(),
    startReading: vi.fn().mockResolvedValue(undefined),
    handleEditBook: vi.fn(),
    handleHideBook: vi.fn().mockResolvedValue(undefined),
    handleToggleFavorite: vi.fn().mockResolvedValue(undefined),
    getBookById: vi.fn(() => null),
  };

  const mockLibraryState = {
    get continueReadingBooks(): BookLike[] {
      return books;
    },
    getBookById: vi.fn(() => null),
    handleEditBook: vi.fn(),
    handleToggleFavorite: vi.fn().mockResolvedValue(undefined),
  };

  const mockNavigationState = {
    get previewBookId(): string | null {
      return previewBookId;
    },
    openShelfDetails: vi.fn(),
  };

  return {
    mockAppState,
    mockLibraryState,
    mockNavigationState,
    setBooks: (value: BookLike[]): void => {
      books = value;
    },
    setPreviewBookId: (value: string | null): void => {
      previewBookId = value;
    },
    reset: (): void => {
      books = [];
      previewBookId = null;
    },
  };
}

export const mocks = createMocks();
export const { mockAppState, mockLibraryState, mockNavigationState } = mocks;
export const setContinueReadingBooks = mocks.setBooks;
export const setPreviewBookId = mocks.setPreviewBookId;
export const resetContinueReadingMocks = mocks.reset;
