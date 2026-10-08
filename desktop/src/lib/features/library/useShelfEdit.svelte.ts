import { readFile } from '@tauri-apps/plugin-fs';
import { pickImage } from '$lib/shared/services/FilePicker';
import type { LibraryBookDto } from '$lib/shared/types';
import type { MessageKey } from '$lib/shared/i18n';
import type { LibraryPort } from '$lib/shared/ports/LibraryPort';
import { TauriLibraryAdapter } from '$lib/shared/ports/adapters/tauri/TauriLibraryAdapter';
import { logger } from '$lib/shared/logger/Logger';

export const MAX_GENRE_LENGTH = 80;
export const CONTROL_CHAR_REGEX = /[\u0000-\u001f\u007f]/;

export const KNOWN_GENRES = [
  'Novela',
  'Ficción',
  'No ficción',
  'Ciencia ficción',
  'Fantasía',
  'Terror',
  'Misterio',
  'Romance',
  'Thriller',
  'Biografía / Memorias',
  'Historia',
  'Ciencia / Tecnología',
  'Autoayuda',
  'Filosofía',
  'Ensayo',
  'Poesía',
  'Aventura',
  'Clásicos',
  'Sin clasificar',
] as const;

export const GENRE_LABEL_KEYS: Record<string, string> = {
  Novela: 'shelf.genreNovel',
  Ficción: 'shelf.genreFiction',
  'No ficción': 'shelf.genreNonFiction',
  'Ciencia ficción': 'shelf.genreSciFi',
  Fantasía: 'shelf.genreFantasy',
  Terror: 'shelf.genreHorror',
  Misterio: 'shelf.genreMystery',
  Romance: 'shelf.genreRomance',
  Thriller: 'shelf.genreThriller',
  'Biografía / Memorias': 'shelf.genreBiography',
  Historia: 'shelf.genreHistory',
  'Ciencia / Tecnología': 'shelf.genreScience',
  Autoayuda: 'shelf.genreSelfHelp',
  Filosofía: 'shelf.genrePhilosophy',
  Ensayo: 'shelf.genreEssay',
  Poesía: 'shelf.genrePoetry',
  Aventura: 'shelf.genreAdventure',
  Clásicos: 'shelf.genreClassics',
  'Sin clasificar': 'shelf.genreUnclassified',
};

export function getMimeTypeFromExtension(fileName: string): string {
  const ext = fileName.split('.').pop()?.toLowerCase() ?? '';
  const map: Record<string, string> = {
    png: 'image/png',
    jpg: 'image/jpeg',
    jpeg: 'image/jpeg',
    webp: 'image/webp',
    gif: 'image/gif',
  };
  return map[ext] ?? 'image/png';
}

export function resolveGenre(selectedGenre: string | null, customGenre: string): string {
  if (selectedGenre === '__other__') return customGenre.trim();
  return selectedGenre ?? '';
}

export function validateGenre(trimmedGenre: string): string | null {
  if (trimmedGenre.length > MAX_GENRE_LENGTH) return 'shelf.genreTooLong';
  if (trimmedGenre.length > 0 && CONTROL_CHAR_REGEX.test(trimmedGenre))
    return 'shelf.genreInvalidChars';
  return null;
}

export type UseShelfEditOptions = {
  getSelectedBook: () => LibraryBookDto | null;
  onSaveEdit: (dto: Partial<LibraryBookDto>) => Promise<void>;
  t: (key: MessageKey, params?: Record<string, string | number>) => string;
  onCoverUpdated?: (bookId: string, path: string) => void;
  libraryPort?: LibraryPort;
};

export function useShelfEdit(options: UseShelfEditOptions): {
  isEditing: boolean;
  editTitle: string;
  editAuthor: string;
  selectedGenre: string | null;
  customGenre: string;
  editError: string | null;
  readonly isSaving: boolean;
  readonly genreOptions: { value: string; label: string }[];
  readonly maxGenreLength: number;
  startEditing(book: LibraryBookDto): void;
  cancelEditing(): void;
  resolveGenre(): string;
  saveEditing(): Promise<void>;
  handleCoverImport(book: LibraryBookDto): Promise<void>;
  resetOnClose(isOpen: boolean): void;
} {
  const libraryPort: LibraryPort = options.libraryPort ?? new TauriLibraryAdapter();
  let isEditing = $state(false);
  let editTitle = $state('');
  let editAuthor = $state('');
  let selectedGenre = $state<string | null>(null);
  let customGenre = $state('');
  let editError = $state<string | null>(null);
  let isSaving = $state(false);

  const genreOptions = $derived([
    ...KNOWN_GENRES.map((g) => ({ value: g, label: options.t(GENRE_LABEL_KEYS[g] as MessageKey) })),
    { value: '__other__', label: options.t('shelf.otherGenre' as MessageKey) },
  ]);

  function startEditing(book: LibraryBookDto): void {
    editTitle = book.title;
    editAuthor = book.author || '';
    const stored = (book.genre ?? '').trim();
    const known = KNOWN_GENRES.find((g) => g.toLowerCase() === stored.toLowerCase());
    if (known) {
      selectedGenre = known;
      customGenre = '';
    } else {
      selectedGenre = '__other__';
      customGenre = stored;
    }
    editError = null;
    isEditing = true;
  }

  function cancelEditing(): void {
    isEditing = false;
    editError = null;
  }

  function resolveGenreLocal(): string {
    return resolveGenre(selectedGenre, customGenre);
  }

  async function saveEditing(): Promise<void> {
    const book = options.getSelectedBook();
    if (!book) return;
    if (!editTitle.trim()) {
      editError = options.t('shelf.titleRequired' as MessageKey);
      return;
    }
    const trimmedGenre = resolveGenreLocal();
    const validationKey = validateGenre(trimmedGenre);
    if (validationKey) {
      editError = options.t(validationKey as MessageKey);
      return;
    }

    isSaving = true;
    editError = null;
    try {
      await options.onSaveEdit({
        ...book,
        title: editTitle.trim(),
        author: editAuthor.trim(),
        genre: trimmedGenre.length > 0 ? trimmedGenre : null,
      });
      isEditing = false;
    } catch (e) {
      editError = e instanceof Error ? e.message : options.t('shelf.saveError' as MessageKey);
    } finally {
      isSaving = false;
    }
  }

  async function handleCoverImport(book: LibraryBookDto): Promise<void> {
    const result = await pickImage();
    if (!result) return;
    try {
      const bytes = await readFile(result.path);
      const mimeType = getMimeTypeFromExtension(result.name);
      await libraryPort.upsertBookCover({
        bookId: book.id,
        data: Array.from(bytes),
        mimeType,
      });
      if (options.onCoverUpdated) {
        options.onCoverUpdated(book.id, result.path);
      } else {
        // fallback: mutate the book reference for immediate UI feedback
        (book as unknown as { coverPath: string | null }).coverPath = result.path;
      }
    } catch (e) {
      logger.error(
        'Failed to import cover:',
        { error: e instanceof Error ? e.message : String(e) },
        'library',
      );
    }
  }

  function resetOnClose(isOpen: boolean): void {
    if (!isOpen) {
      isEditing = false;
      editError = null;
    }
  }

  return {
    get isEditing(): boolean {
      return isEditing;
    },
    set isEditing(v: boolean) {
      isEditing = v;
    },
    get editTitle(): string {
      return editTitle;
    },
    set editTitle(v: string) {
      editTitle = v;
    },
    get editAuthor(): string {
      return editAuthor;
    },
    set editAuthor(v: string) {
      editAuthor = v;
    },
    get selectedGenre(): string | null {
      return selectedGenre;
    },
    set selectedGenre(v: string | null) {
      selectedGenre = v;
    },
    get customGenre(): string {
      return customGenre;
    },
    set customGenre(v: string) {
      customGenre = v;
    },
    get editError(): string | null {
      return editError;
    },
    set editError(v: string | null) {
      editError = v;
    },
    get isSaving(): boolean {
      return isSaving;
    },
    get genreOptions(): { value: string; label: string }[] {
      return genreOptions;
    },
    get maxGenreLength(): number {
      return MAX_GENRE_LENGTH;
    },
    startEditing,
    cancelEditing,
    resolveGenre: resolveGenreLocal,
    saveEditing,
    handleCoverImport,
    resetOnClose,
  };
}

export type ShelfEditState = ReturnType<typeof useShelfEdit>;
