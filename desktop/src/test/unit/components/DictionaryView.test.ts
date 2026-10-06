import { fireEvent, render, screen, waitFor } from '@testing-library/svelte';
import { afterEach, describe, expect, it, vi } from 'vitest';
import DictionaryView from '$lib/features/dictionary/components/DictionaryView.svelte';
import { deriveDictionaryKpis } from '$lib/features/dictionary/dictionaryKpis';
import {
  avatarColorVariable,
  bookInitials,
  bookReferenceLine,
  EMPTY_USER_FIELD_DRAFT,
  entryInitial,
  formatEntryDate,
  formatPhonetic,
  hasAnyDetail,
  hasBookReference,
  hasQuote,
  isComplete,
  USER_FIELD_KEYS,
  userFieldDraft,
  userFieldPatchFrom,
} from '$lib/features/dictionary/dictionaryEntry';
import type { DictionaryStateApi } from '$lib/shared/stores/DictionaryState.svelte';
import {
  openDictionaryBook,
  resolveBookTarget,
  type DictionaryBookNavigationDeps,
} from '$lib/features/dictionary/dictionaryBookNavigation';
import { messagesEn } from '$lib/shared/i18n/messages.en';
import { messagesEs } from '$lib/shared/i18n/messages.es';
import type { DictionaryWordDto, ReaderBook } from '$lib/shared/types';

const HEX_PATTERN = /#[0-9a-fA-F]{3,8}/;
const NOW = new Date('2026-09-19T12:00:00.000Z');
const DAY_MS = 24 * 60 * 60 * 1000;

const dictEs = messagesEs as unknown as Record<string, string>;
const dictEn = messagesEn as unknown as Record<string, string>;

const interpolate = (template: string, params?: Record<string, string | number>): string =>
  params
    ? template.replace(/\{\{\s*([\w.-]+)\s*\}\}/g, (_match, key: string) =>
        String(params[key] ?? ''),
      )
    : template;

const tEs = (key: string, params?: Record<string, string | number>): string =>
  interpolate(dictEs[key] ?? key, params);
const tEn = (key: string, params?: Record<string, string | number>): string =>
  interpolate(dictEn[key] ?? key, params);

function word(
  overrides: Partial<DictionaryWordDto> & { id: string; word: string },
): DictionaryWordDto {
  return { createdAt: NOW.toISOString(), ...overrides };
}

const COMPLETE_FIELDS = {
  definition: 'Que tiene una duración muy breve o pasajera.',
  partOfSpeech: 'Adjetivo',
  phonetic: '/eˈfimeɾo/',
  example: 'La belleza de un atardecer es efímera.',
  quote: 'Los pequeños momentos de éxito crean la base para cambios duraderos.',
} as const;

const TERM = '[data-testid="dictionary-row-term"]';

function termsOf(container: HTMLElement): string[] {
  return [...container.querySelectorAll(TERM)].map((node) => node.textContent?.trim() ?? '');
}

function rowFor(container: HTMLElement, term: string): HTMLElement {
  const rows = [...container.querySelectorAll<HTMLElement>('[data-testid="dictionary-row"]')];
  const row = rows.find((node) => node.querySelector(TERM)?.textContent?.trim() === term);
  if (!row) throw new Error(`No row rendered for term "${term}"`);
  return row;
}

function makeState(
  words: DictionaryWordDto[],
  overrides: { error?: string | null; isLoading?: boolean } = {},
): DictionaryStateApi {
  const state = {
    get words() {
      return words;
    },
    isLoading: overrides.isLoading ?? false,
    error: overrides.error ?? null,
    load: vi.fn(async () => {}),
    search: vi.fn((query: string, limit = 20) =>
      words
        .filter((entry) => (entry.word ?? '').trim().toLowerCase().includes(query.toLowerCase()))
        .slice(0, limit),
    ),
    add: vi.fn(),
    remove: vi.fn(),
    update: vi.fn(),
    capture: vi.fn(),
    subscribeToRemoteChanges: vi.fn(),
    unsubscribe: vi.fn(),
  };
  return state as unknown as DictionaryStateApi;
}

function makeBook(id: string, format = 'epub'): ReaderBook {
  return {
    id,
    title: 'Hábitos Atómicos',
    author: 'James Clear',
    format,
    currentPage: 0,
    totalPages: 0,
    progressPercentage: 0,
    coverPath: null,
    minutesRead: 0,
    updatedAt: NOW.toISOString(),
    createdAt: NOW.toISOString(),
    filePath: `/books/${id}.${format}`,
  };
}

/**
 * A fake reader-navigation surface that records the step order, so a test can
 * assert the Highlights sequence without touching the domain singletons.
 */
function makeNavigationDeps(book: ReaderBook | null = makeBook('book-a')): {
  deps: DictionaryBookNavigationDeps;
  calls: string[];
} {
  const calls: string[] = [];
  const deps: DictionaryBookNavigationDeps = {
    getBookById: vi.fn((id: string) => {
      calls.push(`getBookById:${id}`);
      return book && book.id === id ? book : null;
    }),
    promoteBookForReading: vi.fn((id: string) => calls.push(`promote:${id}`)),
    setActiveReadingBookId: vi.fn((id: string) => calls.push(`active:${id}`)),
    clearShelfDetails: vi.fn(() => calls.push('clearShelfDetails')),
    openReader: vi.fn(() => calls.push('openReader')),
    resetSearch: vi.fn(() => calls.push('resetSearch')),
    recordReaderOpenMetric: vi.fn((format: string) => calls.push(`metric:${format}`)),
    startReading: vi.fn(async () => {
      calls.push('startReading');
    }),
    loadStats: vi.fn((id: string) => calls.push(`loadStats:${id}`)),
    setSearchTargetLocator: vi.fn((locator: string | null) =>
      calls.push(`locator:${locator ?? 'null'}`),
    ),
  };
  return { deps, calls };
}

afterEach(() => {
  vi.useRealTimers();
});

describe('DictionaryView shell (4A)', () => {
  it('renders the three frame KPI labels verbatim in Spanish', () => {
    const state = makeState([
      word({ id: '1', word: 'Efímero', sourceBookId: 'book-a' }),
      word({ id: '2', word: 'Sísifo' }),
    ]);

    render(DictionaryView, { props: { t: tEs, dictionary: state } });

    expect(screen.getByTestId('dictionary-kpi-row')).toBeInTheDocument();
    expect(screen.getByText('Palabras guardadas')).toBeInTheDocument();
    expect(screen.getByText('Esta semana')).toBeInTheDocument();
    expect(screen.getByText('Libros referenciados')).toBeInTheDocument();

    // The labels are the frame's own wording, not a paraphrase.
    expect(messagesEs['dictionary.kpiTotal']).toBe('Palabras guardadas');
    expect(messagesEs['dictionary.kpiThisWeek']).toBe('Esta semana');
    expect(messagesEs['dictionary.kpiReferencedBooks']).toBe('Libros referenciados');
  });

  it('renders faithful English equivalents and the header/search/tabs copy', () => {
    render(DictionaryView, {
      props: { t: tEn, dictionary: makeState([word({ id: '1', word: 'Efímero' })]) },
    });

    expect(screen.getByText('Saved words')).toBeInTheDocument();
    expect(screen.getByText('This week')).toBeInTheDocument();
    expect(screen.getByText('Books referenced')).toBeInTheDocument();
    expect(screen.getByRole('heading', { name: 'Dictionary' })).toBeInTheDocument();
    expect(screen.getByText('New word')).toBeInTheDocument();
    expect(screen.getByPlaceholderText('Search word...')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'All' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Recent' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'A-Z' })).toBeInTheDocument();
  });

  it('hides the zeroed KPI row when the dictionary is empty and keeps it with data (P2-G)', () => {
    const empty = render(DictionaryView, { props: { t: tEs, dictionary: makeState([]) } });
    expect(screen.queryByTestId('dictionary-kpi-row')).not.toBeInTheDocument();
    empty.unmount();

    render(DictionaryView, {
      props: { t: tEs, dictionary: makeState([word({ id: '1', word: 'Efímero' })]) },
    });
    expect(screen.getByTestId('dictionary-kpi-row')).toBeInTheDocument();
  });

  it('gives the search field an accessible label beyond its placeholder (P3-J)', () => {
    render(DictionaryView, {
      props: { t: tEs, dictionary: makeState([word({ id: '1', word: 'Efímero' })]) },
    });

    const input = screen.getByLabelText(messagesEs['dictionary.searchLabel']);
    expect(input).toHaveAttribute('id', 'dictionary-search');
  });

  it('shows the `/` keycap and focuses the search from the shortcut', async () => {
    render(DictionaryView, {
      props: { t: tEs, dictionary: makeState([word({ id: '1', word: 'Efímero' })]) },
    });

    expect(
      screen.getByRole('button', { name: messagesEs['dictionary.searchShortcutAria'] }),
    ).toBeInTheDocument();

    const input = screen.getByLabelText(messagesEs['dictionary.searchLabel']);
    expect(document.activeElement).not.toBe(input);

    await fireEvent.keyDown(window, { key: '/' });
    expect(document.activeElement).toBe(input);
  });

  it('exposes a polite live region for add/save/delete announcements (P3-J)', () => {
    render(DictionaryView, {
      props: { t: tEs, dictionary: makeState([word({ id: '1', word: 'Efímero' })]) },
    });

    const region = screen.getByRole('status');
    expect(region).toHaveAttribute('aria-live', 'polite');
  });

  it('contains no hardcoded hex colors', () => {
    const { container } = render(DictionaryView, {
      props: { t: tEs, dictionary: makeState([word({ id: '1', word: 'Efímero' })]) },
    });

    expect(container.innerHTML).not.toMatch(HEX_PATTERN);
  });

  it('carries no import/export controls and one primary action', () => {
    const { container } = render(DictionaryView, {
      props: { t: tEs, dictionary: makeState([word({ id: '1', word: 'Efímero' })]) },
    });

    // The transfer moved to Settings > Data; the screen keeps no file input
    // and no control whose label offers import/export.
    expect(container.querySelector('input[type="file"]')).toBeNull();
    const labels = [...container.querySelectorAll('button')].map(
      (b) => b.textContent?.trim() ?? '',
    );
    expect(labels.some((label) => /import|export|json|csv/i.test(label))).toBe(false);

    expect(screen.getByRole('button', { name: 'Nueva palabra' })).toBeInTheDocument();
  });

  it('filters by tab: store order, newest first, then alphabetical', async () => {
    const state = makeState([
      word({
        id: '1',
        word: 'Paradigma',
        createdAt: new Date(NOW.getTime() - 3 * DAY_MS).toISOString(),
      }),
      word({ id: '2', word: 'Efímero', createdAt: NOW.toISOString() }),
      word({
        id: '3',
        word: 'Kafkiano',
        createdAt: new Date(NOW.getTime() - DAY_MS).toISOString(),
      }),
    ]);

    const { container } = render(DictionaryView, { props: { t: tEs, dictionary: state } });
    const listed = (): string[] => termsOf(container);

    expect(listed()).toEqual(['Paradigma', 'Efímero', 'Kafkiano']);

    await fireEvent.click(screen.getByRole('button', { name: 'Recientes' }));
    expect(listed()).toEqual(['Efímero', 'Kafkiano', 'Paradigma']);

    await fireEvent.click(screen.getByRole('button', { name: 'A-Z' }));
    expect(listed()).toEqual(['Efímero', 'Kafkiano', 'Paradigma']);
  });

  it('filters by the search field and shows the empty result state', async () => {
    vi.useFakeTimers();
    const state = makeState([
      word({ id: '1', word: 'Efímero' }),
      word({ id: '2', word: 'Sísifo' }),
    ]);

    render(DictionaryView, { props: { t: tEs, dictionary: state } });
    const input = screen.getByPlaceholderText('Buscar palabra...');

    await fireEvent.input(input, { target: { value: 'Efím' } });
    await vi.advanceTimersByTimeAsync(300);

    expect(screen.getByText('Efímero')).toBeInTheDocument();
    expect(screen.queryByText('Sísifo')).not.toBeInTheDocument();
    expect(state.search).toHaveBeenCalledWith('Efím', 50);

    await fireEvent.input(input, { target: { value: 'zzz' } });
    await vi.advanceTimersByTimeAsync(300);

    expect(screen.queryByTestId('dictionary-list')).not.toBeInTheDocument();
    expect(screen.getByText(dictEs['dictionary.noResultsTitle'])).toBeInTheDocument();
    expect(screen.queryByText(dictEs['home.highlightsEmptyTitle'])).not.toBeInTheDocument();
  });
});

describe('DictionaryView load error and delete confirmation (P1-A / P1-B)', () => {
  it('shows the error state with a retry that calls load when the load failed (P1-A)', async () => {
    const state = makeState([], { error: 'boom' });
    render(DictionaryView, { props: { t: tEs, dictionary: state } });

    expect(screen.getByTestId('dictionary-load-error')).toBeInTheDocument();
    expect(screen.getByText(messagesEs['dictionary.loadErrorTitle'])).toBeInTheDocument();
    // Never the false empty state: a user with words must not think they lost them.
    expect(screen.queryByText(messagesEs['dictionary.emptyTitle'])).not.toBeInTheDocument();

    const callsBefore = vi.mocked(state.load).mock.calls.length;
    await fireEvent.click(screen.getByRole('button', { name: messagesEs['dictionary.retry'] }));
    expect(vi.mocked(state.load).mock.calls.length).toBe(callsBefore + 1);
  });

  it('keeps the real empty state when the load succeeded with no words (P1-A)', () => {
    render(DictionaryView, { props: { t: tEs, dictionary: makeState([]) } });

    expect(screen.queryByTestId('dictionary-load-error')).not.toBeInTheDocument();
    expect(screen.getByText(messagesEs['dictionary.emptyTitle'])).toBeInTheDocument();
  });

  it('names the destructive control as an action, not a question (P1-B)', async () => {
    const { container } = render(DictionaryView, {
      props: { t: tEs, dictionary: makeState([word({ id: '1', word: 'Efímero' })]) },
    });

    await fireEvent.click(rowFor(container, 'Efímero'));

    expect(screen.getByTestId('dictionary-detail-delete')).toHaveAttribute(
      'aria-label',
      messagesEs['dictionary.delete'],
    );
    expect(messagesEs['dictionary.delete']).toBe('Eliminar');
    expect(messagesEs['dictionary.deleteConfirm']).toContain('{{word}}');
  });

  it('does not delete when the confirmation is dismissed, and deletes when accepted (P1-B)', async () => {
    const confirmSpy = vi.spyOn(window, 'confirm').mockReturnValue(false);
    const state = makeState([word({ id: '1', word: 'Efímero' })]);
    const { container } = render(DictionaryView, { props: { t: tEs, dictionary: state } });

    await fireEvent.click(rowFor(container, 'Efímero'));
    await fireEvent.click(screen.getByTestId('dictionary-detail-delete'));

    expect(confirmSpy).toHaveBeenCalledWith('¿Eliminar "Efímero" de tu diccionario?');
    expect(state.remove).not.toHaveBeenCalled();

    confirmSpy.mockReturnValue(true);
    await fireEvent.click(screen.getByTestId('dictionary-detail-delete'));
    expect(state.remove).toHaveBeenCalledWith('1');
    confirmSpy.mockRestore();
  });
});

describe('DictionaryView heading order (P3-J)', () => {
  it('keeps a valid outline: one h1, then the selected term as h2, no skipped level', async () => {
    const entry = word({
      id: '1',
      word: 'Efímero',
      definition: 'Breve.',
      partOfSpeech: 'Adjetivo',
      phonetic: 'efimero',
      example: 'Fue efímero.',
      quote: 'Una cita.',
    });
    const { container } = render(DictionaryView, {
      props: { t: tEs, dictionary: makeState([entry]) },
    });

    await fireEvent.click(rowFor(container, 'Efímero'));

    const headings = [...container.querySelectorAll('h1, h2, h3, h4, h5, h6')].map((node) =>
      Number(node.tagName.slice(1)),
    );
    expect(headings).toEqual([1, 2]);

    let previous = 0;
    for (const level of headings) {
      expect(level).toBeLessThanOrEqual(previous + 1);
      previous = level;
    }
  });
});

describe('DictionaryView list row anatomy (4B)', () => {
  const TODAY = new Date().toISOString();

  it('draws the frame anatomy: letter badge, term, book and relative date', () => {
    const state = makeState([
      word({
        id: '1',
        word: 'Efímero',
        createdAt: TODAY,
        sourceBookId: 'book-a',
        sourceBookTitle: 'Hábitos Atómicos',
        ...COMPLETE_FIELDS,
      }),
    ]);

    const { container } = render(DictionaryView, { props: { t: tEs, dictionary: state } });
    const row = rowFor(container, 'Efímero');

    expect(row.querySelector('[data-testid="dictionary-row-initial"]')).toHaveTextContent('E');
    expect(row.querySelector('[data-testid="dictionary-row-book"]')).toHaveTextContent(
      'Hábitos Atómicos',
    );
    expect(row.querySelector('[data-testid="dictionary-row-date"]')).toHaveTextContent(
      messagesEs['dictionary.dateToday'],
    );
  });

  it('shows the book title only when evidence exists', () => {
    const state = makeState([
      word({
        id: '1',
        word: 'Efímero',
        sourceBookId: 'book-a',
        sourceBookTitle: 'Hábitos Atómicos',
      }),
      word({ id: '2', word: 'Sísifo' }),
    ]);

    const { container } = render(DictionaryView, { props: { t: tEs, dictionary: state } });

    expect(
      rowFor(container, 'Efímero').querySelector('[data-testid="dictionary-row-book"]'),
    ).not.toBeNull();
    expect(
      rowFor(container, 'Sísifo').querySelector('[data-testid="dictionary-row-book"]'),
    ).toBeNull();
  });

  it('derives the letter badge from the term first letter', () => {
    const state = makeState([
      word({ id: '1', word: 'efímero' }),
      word({ id: '2', word: 'Sísifo' }),
      word({ id: '3', word: '  Kafkiano' }),
    ]);

    const { container } = render(DictionaryView, { props: { t: tEs, dictionary: state } });

    expect(
      [...container.querySelectorAll('[data-testid="dictionary-row-initial"]')].map((node) =>
        node.textContent?.trim(),
      ),
    ).toEqual(['E', 'S', 'K']);
  });

  it('rotates the avatar colour per row position and keeps rows selectable', async () => {
    const state = makeState([
      word({ id: '1', word: 'Efímero' }),
      word({ id: '2', word: 'Sísifo' }),
    ]);

    const { container } = render(DictionaryView, { props: { t: tEs, dictionary: state } });
    const rows = [...container.querySelectorAll<HTMLElement>('[data-testid="dictionary-row"]')];

    expect(rows[0].querySelector('[data-testid="dictionary-row-initial"]')).toHaveAttribute(
      'style',
      expect.stringContaining('--color-avatar-1'),
    );
    expect(rows[1].querySelector('[data-testid="dictionary-row-initial"]')).toHaveAttribute(
      'style',
      expect.stringContaining('--color-avatar-2'),
    );

    await fireEvent.click(rows[1]);
    expect(rows[1]).toHaveAttribute('aria-current', 'true');
    expect(rows[0]).not.toHaveAttribute('aria-current');
  });

  it('marks an entry without evidence incomplete and leaves a complete entry unmarked', () => {
    const state = makeState([
      word({ id: 'complete', word: 'Efímero', sourceBookId: 'book-a', ...COMPLETE_FIELDS }),
      word({
        id: 'no-evidence',
        word: 'Sísifo',
        definition: 'Un rey.',
        partOfSpeech: 'Sustantivo',
      }),
    ]);

    const { container } = render(DictionaryView, { props: { t: tEs, dictionary: state } });

    expect(
      rowFor(container, 'Efímero').querySelector('[data-testid="dictionary-row-incomplete"]'),
    ).toBeNull();
    expect(
      rowFor(container, 'Sísifo').querySelector('[data-testid="dictionary-row-incomplete"]'),
    ).toHaveTextContent(messagesEs['dictionary.incomplete']);
  });

  it('drives the badge from exactly the exported isComplete predicate', () => {
    const entries = [
      word({ id: '1', word: 'Completa', ...COMPLETE_FIELDS }),
      word({ id: '2', word: 'SinCita', ...COMPLETE_FIELDS, quote: null }),
      word({ id: '3', word: 'SinDefinicion', ...COMPLETE_FIELDS, definition: '   ' }),
      word({ id: '4', word: 'SinEjemplo', ...COMPLETE_FIELDS, example: null }),
    ];
    const { container } = render(DictionaryView, {
      props: { t: tEs, dictionary: makeState(entries) },
    });

    const marked = termsOf(container).filter(
      (term) =>
        rowFor(container, term).querySelector('[data-testid="dictionary-row-incomplete"]') !== null,
    );
    const expected = entries.filter((entry) => !isComplete(entry)).map((entry) => entry.word);

    expect(marked).toEqual(expected);
    expect(marked.length).toBeGreaterThan(0);
    expect(marked.length).toBeLessThan(entries.length);
  });
});

describe('dictionary completeness predicate (Decision 16)', () => {
  it('requires all four user fields non-blank and a non-null quote', () => {
    expect(isComplete(word({ id: '1', word: 'A', ...COMPLETE_FIELDS }))).toBe(true);
    expect(isComplete(word({ id: '1', word: 'A', ...COMPLETE_FIELDS, quote: null }))).toBe(false);
    expect(isComplete(word({ id: '1', word: 'A', ...COMPLETE_FIELDS, definition: '' }))).toBe(
      false,
    );
    expect(isComplete(word({ id: '1', word: 'A', ...COMPLETE_FIELDS, partOfSpeech: '   ' }))).toBe(
      false,
    );
    expect(isComplete(word({ id: '1', word: 'A', ...COMPLETE_FIELDS, phonetic: null }))).toBe(
      false,
    );
    expect(isComplete(word({ id: '1', word: 'A', ...COMPLETE_FIELDS, example: '' }))).toBe(false);
    expect(isComplete(word({ id: '1', word: 'A' }))).toBe(false);
  });
});

describe('dictionary row formatting helpers', () => {
  it('uses the frame relative-date vocabulary with an injected clock', () => {
    const iso = (daysAgo: number): string =>
      new Date(NOW.getTime() - daysAgo * DAY_MS).toISOString();

    expect(formatEntryDate(iso(0), NOW, tEs)).toBe('Hoy');
    expect(formatEntryDate(iso(1), NOW, tEs)).toBe('Ayer');
    expect(formatEntryDate(iso(3), NOW, tEs)).toBe('Hace 3 días');
    expect(formatEntryDate(iso(10), NOW, tEs)).toBe('Hace 1 sem.');
    expect(formatEntryDate(iso(60), NOW, tEs)).toBe('Hace 2 meses');
    expect(formatEntryDate(iso(400), NOW, tEs)).toBe('Hace 1 años');
    expect(formatEntryDate(iso(-1), NOW, tEs)).toBe('Hoy');
    expect(formatEntryDate('not-a-date', NOW, tEs)).toBe('');
  });

  it('uppercases the term first letter and tolerates blanks', () => {
    expect(entryInitial('efímero')).toBe('E');
    expect(entryInitial('  Sísifo')).toBe('S');
    expect(entryInitial('')).toBe('');
  });

  it('rotates the seven measured avatar colours and wraps by index', () => {
    expect(avatarColorVariable(0)).toBe('var(--color-avatar-1)');
    expect(avatarColorVariable(6)).toBe('var(--color-avatar-7)');
    expect(avatarColorVariable(7)).toBe('var(--color-avatar-1)');
    expect(avatarColorVariable(9)).toBe('var(--color-avatar-3)');
  });
});

describe('dictionary KPI derivations (Decision 15)', () => {
  it('counts the total regardless of the entry shape', () => {
    expect(deriveDictionaryKpis([], NOW).total).toBe(0);
    expect(
      deriveDictionaryKpis([word({ id: '1', word: 'A' }), word({ id: '2', word: 'B' })], NOW).total,
    ).toBe(2);
  });

  it('counts only entries created in the last 7 days, inclusive of the boundary', () => {
    const entries = [
      word({ id: 'now', word: 'A', createdAt: NOW.toISOString() }),
      word({ id: '6d', word: 'B', createdAt: new Date(NOW.getTime() - 6 * DAY_MS).toISOString() }),
      word({ id: '7d', word: 'C', createdAt: new Date(NOW.getTime() - 7 * DAY_MS).toISOString() }),
      word({
        id: '7d+1ms',
        word: 'D',
        createdAt: new Date(NOW.getTime() - 7 * DAY_MS - 1).toISOString(),
      }),
      word({
        id: '30d',
        word: 'E',
        createdAt: new Date(NOW.getTime() - 30 * DAY_MS).toISOString(),
      }),
    ];

    expect(deriveDictionaryKpis(entries, NOW).thisWeek).toBe(3);
  });

  it('counts distinct referenced books and excludes entries without a source book', () => {
    const entries = [
      word({ id: '1', word: 'A', sourceBookId: 'book-a' }),
      word({ id: '2', word: 'B', sourceBookId: 'book-a' }),
      word({ id: '3', word: 'C', sourceBookId: 'book-b' }),
      word({ id: '4', word: 'D', sourceBookId: null }),
      word({ id: '5', word: 'E' }),
    ];

    expect(deriveDictionaryKpis(entries, NOW).referencedBooks).toBe(2);
  });
});

describe('DictionaryView detail panel (4C)', () => {
  const EVIDENCE = {
    sourceBookId: 'book-a',
    sourceBookTitle: 'Hábitos Atómicos',
    sourceBookAuthor: 'James Clear',
    sourceChapter: 'Capítulo 3',
  } as const;

  const FULL_ENTRY = word({
    id: 'efimero',
    word: 'Efímero',
    ...EVIDENCE,
    definition: 'Que tiene una duración muy breve o que pasa rápidamente.',
    partOfSpeech: 'Adjetivo',
    phonetic: 'eˈfimeɾo',
    example: 'La belleza de un atardecer es efímera.',
    quote: 'Los pequeños momentos de éxito son casi invisibles.',
  });

  async function select(container: HTMLElement, term: string): Promise<void> {
    await fireEvent.click(rowFor(container, term));
  }

  it('shows the no-selection state until a row is selected', async () => {
    const { container } = render(DictionaryView, {
      props: { t: tEs, dictionary: makeState([FULL_ENTRY]) },
    });

    expect(screen.getByTestId('dictionary-detail-no-selection')).toBeInTheDocument();
    expect(screen.queryByTestId('dictionary-detail-header')).not.toBeInTheDocument();
    expect(screen.getByText(messagesEs['dictionary.selectWordTitle'])).toBeInTheDocument();

    await select(container, 'Efímero');

    expect(screen.queryByTestId('dictionary-detail-no-selection')).not.toBeInTheDocument();
    expect(screen.getByTestId('dictionary-detail-term')).toHaveTextContent('Efímero');
  });

  it('renders every populated field from real data (REQ-DRE-009 scenario 1)', async () => {
    const { container } = render(DictionaryView, {
      props: { t: tEs, dictionary: makeState([FULL_ENTRY]) },
    });

    await select(container, 'Efímero');

    expect(screen.getByTestId('dictionary-detail-term')).toHaveTextContent('Efímero');
    expect(screen.getByTestId('dictionary-detail-tag')).toHaveTextContent('Adjetivo');
    expect(screen.getByTestId('dictionary-detail-definition')).toHaveTextContent(
      'Que tiene una duración muy breve o que pasa rápidamente.',
    );
    expect(screen.getByTestId('dictionary-detail-example')).toHaveTextContent(
      'La belleza de un atardecer es efímera.',
    );
    expect(screen.getByTestId('dictionary-quote')).toHaveTextContent(
      'Los pequeños momentos de éxito son casi invisibles.',
    );

    // The frame's copy, verbatim.
    expect(screen.getByTestId('dictionary-detail-definition-label')).toHaveTextContent(
      messagesEs['dictionary.description'],
    );
    expect(screen.getByTestId('dictionary-detail-example-label')).toHaveTextContent(
      messagesEs['dictionary.personalExample'],
    );
    expect(screen.getByTestId('dictionary-reference-label')).toHaveTextContent(
      messagesEs['dictionary.bookReference'],
    );
    expect(messagesEs['dictionary.description']).toBe('Descripción');
    expect(messagesEs['dictionary.personalExample']).toBe('Ejemplo personal');
    expect(messagesEs['dictionary.bookReference']).toBe('Referencia del libro');
  });

  it('draws the frame section order: description, example, citation', async () => {
    const { container } = render(DictionaryView, {
      props: { t: tEs, dictionary: makeState([FULL_ENTRY]) },
    });

    await select(container, 'Efímero');
    const panel = screen.getByTestId('dictionary-detail');

    expect(
      [...panel.querySelectorAll('[data-testid="dictionary-detail-section"]')].map((node) => {
        if (node.querySelector('[data-testid="dictionary-detail-definition-label"]')) {
          return 'definition';
        }
        if (node.querySelector('[data-testid="dictionary-detail-example-label"]')) return 'example';
        if (node.querySelector('[data-testid="dictionary-citation"]')) return 'citation';
        return 'unknown';
      }),
    ).toEqual(['definition', 'example', 'citation']);

    // One 1px divider between each adjacent pair of present sections.
    expect(panel.querySelectorAll('[data-testid="dictionary-detail-divider"]')).toHaveLength(2);
  });

  it('shows the reference card with cover initials, title and author - chapter', async () => {
    const { container } = render(DictionaryView, {
      props: { t: tEs, dictionary: makeState([FULL_ENTRY]) },
    });

    await select(container, 'Efímero');

    expect(screen.getByTestId('dictionary-reference-initials')).toHaveTextContent('HA');
    expect(screen.getByTestId('dictionary-reference-title')).toHaveTextContent('Hábitos Atómicos');
    expect(screen.getByTestId('dictionary-reference-author')).toHaveTextContent(
      'James Clear · Capítulo 3',
    );
  });

  it('renders the quote as text and never interprets it as HTML', async () => {
    const hostile = 'Un <b>rey</b> que &nbsp;<script>alert(1)</script>';
    const { container } = render(DictionaryView, {
      props: { t: tEs, dictionary: makeState([word({ ...FULL_ENTRY, quote: hostile })]) },
    });

    await select(container, 'Efímero');
    const quote = screen.getByTestId('dictionary-quote');

    expect(quote.children).toHaveLength(0);
    expect(quote.querySelector('b')).toBeNull();
    expect(quote.querySelector('script')).toBeNull();
    expect(quote.textContent).toContain('<b>rey</b>');
    expect(container.querySelector('script')).toBeNull();
  });

  it('renders no quote and no reference card when the evidence is null', async () => {
    const { container } = render(DictionaryView, {
      props: {
        t: tEs,
        dictionary: makeState([
          word({
            id: 'sin-evidencia',
            word: 'Sísifo',
            definition: 'Un rey condenado a empujar una roca.',
            partOfSpeech: 'Sustantivo',
            phonetic: 'sísifo',
            example: 'Sísifo nunca termina.',
            quote: null,
            sourceBookTitle: null,
            sourceBookAuthor: null,
            sourceChapter: null,
            sourceBookId: null,
          }),
        ]),
      },
    });

    await select(container, 'Sísifo');

    expect(screen.queryByTestId('dictionary-citation')).not.toBeInTheDocument();
    expect(screen.queryByTestId('dictionary-quote')).not.toBeInTheDocument();
    expect(screen.queryByTestId('dictionary-reference')).not.toBeInTheDocument();

    // The user-authored sections still render, with a single divider now.
    expect(screen.getByTestId('dictionary-detail-definition')).toBeInTheDocument();
    expect(screen.getByTestId('dictionary-detail-example')).toBeInTheDocument();
    expect(
      screen
        .getByTestId('dictionary-detail')
        .querySelectorAll('[data-testid="dictionary-detail-divider"]'),
    ).toHaveLength(1);
  });

  it('adds the phonetic slashes as presentation over a stored value without them', async () => {
    const { container } = render(DictionaryView, {
      props: { t: tEs, dictionary: makeState([FULL_ENTRY]) },
    });

    await select(container, 'Efímero');

    expect(FULL_ENTRY.phonetic).toBe('eˈfimeɾo');
    expect(FULL_ENTRY.phonetic).not.toContain('/');
    expect(screen.getByTestId('dictionary-detail-phonetic')).toHaveTextContent('/eˈfimeɾo/');
  });

  it('offers no control that writes or clears the captured evidence', async () => {
    const { container } = render(DictionaryView, {
      props: { t: tEs, dictionary: makeState([FULL_ENTRY]) },
    });

    await select(container, 'Efímero');
    const panel = screen.getByTestId('dictionary-detail');

    expect(panel.querySelector('input, textarea, select, [contenteditable]')).toBeNull();

    // The panel's only controls are the frame's three actions.
    const actions = screen.getByTestId('dictionary-detail-actions');
    expect([...actions.querySelectorAll('button')].map((b) => b.dataset.testid)).toEqual([
      'dictionary-detail-view-book',
      'dictionary-detail-edit',
      'dictionary-detail-delete',
    ]);
  });

  it('closes the selection through the store when the destructive action is used', async () => {
    const confirmSpy = vi.spyOn(window, 'confirm').mockReturnValue(true);
    const state = makeState([FULL_ENTRY]);
    const { container } = render(DictionaryView, { props: { t: tEs, dictionary: state } });

    await select(container, 'Efímero');
    await fireEvent.click(screen.getByTestId('dictionary-detail-delete'));

    expect(state.remove).toHaveBeenCalledWith('efimero');
    expect(screen.queryByTestId('dictionary-detail-header')).not.toBeInTheDocument();
    confirmSpy.mockRestore();
  });

  it('opens the source book at the captured locator through the navigation deps', async () => {
    const onEdit = vi.fn();
    const { deps, calls } = makeNavigationDeps();
    const entry = word({ ...FULL_ENTRY, sourceLocator: 'epubcfi(/6/4!/4/2/2)' });
    const { container } = render(DictionaryView, {
      props: { t: tEs, dictionary: makeState([entry]), bookNavigation: deps, onEdit },
    });

    await select(container, 'Efímero');
    await fireEvent.click(screen.getByTestId('dictionary-detail-view-book'));
    await fireEvent.click(screen.getByTestId('dictionary-detail-edit'));

    await waitFor(() => expect(calls).toContain('locator:epubcfi(/6/4!/4/2/2)'));
    expect(calls).toEqual([
      'getBookById:book-a',
      'promote:book-a',
      'active:book-a',
      'clearShelfDetails',
      'openReader',
      'resetSearch',
      'metric:epub',
      'startReading',
      'loadStats:book-a',
      'locator:epubcfi(/6/4!/4/2/2)',
    ]);
    expect(onEdit).toHaveBeenCalledWith('efimero');
  });

  it('passes a null locator when the entry captured none', async () => {
    const { deps, calls } = makeNavigationDeps();
    const { container } = render(DictionaryView, {
      props: { t: tEs, dictionary: makeState([FULL_ENTRY]), bookNavigation: deps },
    });

    await select(container, 'Efímero');
    await fireEvent.click(screen.getByTestId('dictionary-detail-view-book'));

    await waitFor(() => expect(calls).toContain('locator:null'));
    expect(deps.setSearchTargetLocator).toHaveBeenCalledWith(null);
  });

  it('does not navigate when the source book is no longer in the library', async () => {
    const { deps, calls } = makeNavigationDeps(null);
    const { container } = render(DictionaryView, {
      props: { t: tEs, dictionary: makeState([FULL_ENTRY]), bookNavigation: deps },
    });

    await select(container, 'Efímero');
    await fireEvent.click(screen.getByTestId('dictionary-detail-view-book'));

    expect(calls).toEqual(['getBookById:book-a']);
    expect(deps.startReading).not.toHaveBeenCalled();
    expect(deps.setSearchTargetLocator).not.toHaveBeenCalled();
  });

  it('shows the empty state for a selected entry with no fields at all', async () => {
    const { container } = render(DictionaryView, {
      props: { t: tEs, dictionary: makeState([word({ id: 'bare', word: 'Paradigma' })]) },
    });

    await select(container, 'Paradigma');

    expect(screen.getByTestId('dictionary-detail-empty')).toBeInTheDocument();
    expect(screen.getByText(messagesEs['dictionary.noDetailTitle'])).toBeInTheDocument();
    expect(screen.queryByTestId('dictionary-detail-section')).not.toBeInTheDocument();
    expect(screen.getByTestId('dictionary-detail-actions')).toBeInTheDocument();
  });
});

describe('DictionaryView edit mode (4D)', () => {
  const EVIDENCE = {
    sourceBookId: 'book-a',
    sourceBookTitle: 'Hábitos Atómicos',
    sourceBookAuthor: 'James Clear',
    sourceChapter: 'Capítulo 3',
  } as const;

  const USER_FIELDS = {
    definition: 'Que tiene una duración muy breve o que pasa rápidamente.',
    partOfSpeech: 'Adjetivo',
    phonetic: 'eˈfimeɾo',
    example: 'La belleza de un atardecer es efímera.',
  } as const;

  const ENTRY = word({
    id: 'efimero',
    word: 'Efímero',
    ...EVIDENCE,
    ...USER_FIELDS,
    quote: 'Los pequeños momentos de éxito son casi invisibles.',
  });

  async function select(container: HTMLElement, term: string): Promise<void> {
    await fireEvent.click(rowFor(container, term));
  }

  async function openEdit(container: HTMLElement, term = 'Efímero'): Promise<void> {
    await select(container, term);
    await fireEvent.click(screen.getByTestId('dictionary-detail-edit'));
  }

  it('opens an edit session carrying the four user fields and nothing else', async () => {
    const { container } = render(DictionaryView, {
      props: { t: tEs, dictionary: makeState([ENTRY]) },
    });

    await openEdit(container);

    const form = screen.getByTestId('dictionary-edit-form');
    expect(form).toBeInTheDocument();

    // The four controls carry the stored values, ready to be changed.
    const controls = [
      ...form.querySelectorAll<HTMLInputElement | HTMLTextAreaElement>('input, textarea, select'),
    ];
    expect(screen.getByTestId('dictionary-edit-definition')).toHaveValue(USER_FIELDS.definition);
    expect(screen.getByTestId('dictionary-edit-partOfSpeech')).toHaveValue(
      USER_FIELDS.partOfSpeech,
    );
    expect(screen.getByTestId('dictionary-edit-phonetic')).toHaveValue(USER_FIELDS.phonetic);
    expect(screen.getByTestId('dictionary-edit-example')).toHaveValue(USER_FIELDS.example);

    // Exactly four controls, and none of them can name an evidence field.
    expect(controls).toHaveLength(4);
    expect(
      [...form.querySelectorAll<HTMLElement>('[data-testid]')].map((n) => n.dataset.testid),
    ).toEqual([
      'dictionary-edit-definition',
      'dictionary-edit-partOfSpeech',
      'dictionary-edit-phonetic',
      'dictionary-edit-example',
      'dictionary-edit-cancel',
      'dictionary-edit-save',
    ]);

    // The term itself is never editable.
    expect(screen.getByTestId('dictionary-detail-term')).toHaveTextContent('Efímero');
    expect(controls.some((node) => node.value === 'Efímero')).toBe(false);
  });

  it('saves the four fields through DictionaryState.update and never writes evidence', async () => {
    const state = makeState([ENTRY]);
    const { container } = render(DictionaryView, { props: { t: tEs, dictionary: state } });

    await openEdit(container);
    await fireEvent.input(screen.getByTestId('dictionary-edit-definition'), {
      target: { value: 'Nueva definición.' },
    });
    await fireEvent.input(screen.getByTestId('dictionary-edit-partOfSpeech'), {
      target: { value: 'Sustantivo' },
    });
    await fireEvent.input(screen.getByTestId('dictionary-edit-phonetic'), {
      target: { value: 'nuevo' },
    });
    await fireEvent.input(screen.getByTestId('dictionary-edit-example'), {
      target: { value: 'Nuevo ejemplo.' },
    });
    await fireEvent.click(screen.getByTestId('dictionary-edit-save'));

    expect(state.update).toHaveBeenCalledTimes(1);
    const update = vi.mocked(state.update);
    const [savedId, patch] = update.mock.calls[0];
    expect(savedId).toBe('efimero');
    expect(patch).toEqual({
      definition: 'Nueva definición.',
      partOfSpeech: 'Sustantivo',
      phonetic: 'nuevo',
      example: 'Nuevo ejemplo.',
    });
    expect(Object.keys(patch).sort()).toEqual([...USER_FIELD_KEYS].sort());
    expect(Object.keys(patch).some((key) => /quote|source/i.test(key))).toBe(false);
    expect(state.capture).not.toHaveBeenCalled();

    // Saving closes the session.
    expect(screen.queryByTestId('dictionary-edit-form')).not.toBeInTheDocument();
  });

  it('keeps the captured evidence read-only inside edit mode (REQ-DRE-007 scenario 2)', async () => {
    const { container } = render(DictionaryView, {
      props: { t: tEs, dictionary: makeState([ENTRY]) },
    });

    await openEdit(container);

    // The evidence is still visible while editing...
    expect(screen.getByTestId('dictionary-quote')).toHaveTextContent(
      'Los pequeños momentos de éxito son casi invisibles.',
    );
    expect(screen.getByTestId('dictionary-reference-title')).toHaveTextContent('Hábitos Atómicos');
    expect(screen.getByTestId('dictionary-reference-author')).toHaveTextContent(
      'James Clear · Capítulo 3',
    );

    // ...and offers no control that could edit or clear it.
    const citation = screen.getByTestId('dictionary-citation');
    expect(
      citation.querySelectorAll('input, textarea, select, button, a, [contenteditable]'),
    ).toHaveLength(0);
    expect(screen.getByTestId('dictionary-quote').children).toHaveLength(0);

    // The edit-mode header drops the read-only tag/phonetic and the frame
    // actions; the four inputs are the only way in.
    expect(screen.queryByTestId('dictionary-detail-actions')).not.toBeInTheDocument();
    expect(screen.queryByTestId('dictionary-detail-tag')).not.toBeInTheDocument();
  });

  it('discards the draft on cancel without writing anything', async () => {
    const state = makeState([ENTRY]);
    const { container } = render(DictionaryView, { props: { t: tEs, dictionary: state } });

    await openEdit(container);
    await fireEvent.input(screen.getByTestId('dictionary-edit-definition'), {
      target: { value: 'Borrador descartado.' },
    });
    await fireEvent.click(screen.getByTestId('dictionary-edit-cancel'));

    expect(state.update).not.toHaveBeenCalled();
    expect(state.capture).not.toHaveBeenCalled();
    expect(screen.queryByTestId('dictionary-edit-form')).not.toBeInTheDocument();
    expect(screen.getByTestId('dictionary-detail-definition')).toHaveTextContent(
      USER_FIELDS.definition,
    );
  });

  it('closes the edit session when another row is selected', async () => {
    const state = makeState([ENTRY, word({ id: 'sisifo', word: 'Sísifo', definition: 'Un rey.' })]);
    const { container } = render(DictionaryView, { props: { t: tEs, dictionary: state } });

    await openEdit(container);
    expect(screen.getByTestId('dictionary-edit-form')).toBeInTheDocument();

    await select(container, 'Sísifo');

    expect(screen.queryByTestId('dictionary-edit-form')).not.toBeInTheDocument();
    expect(screen.getByTestId('dictionary-detail-term')).toHaveTextContent('Sísifo');
    expect(state.update).not.toHaveBeenCalled();
  });

  it('shows the re-captured evidence with the user fields unchanged (REQ-DRE-008 scenario 2)', async () => {
    const state = makeState([ENTRY]);
    const { container, rerender } = render(DictionaryView, {
      props: { t: tEs, dictionary: state },
    });

    await select(container, 'Efímero');
    expect(screen.getByTestId('dictionary-quote')).toHaveTextContent(
      'Los pequeños momentos de éxito son casi invisibles.',
    );

    // A re-capture overwrites the six evidence columns and leaves the four
    // user-authored fields byte-identical (Decision 9).
    await rerender({
      t: tEs,
      dictionary: makeState([
        word({
          ...ENTRY,
          quote: 'Otra frase, de otro capítulo.',
          sourceBookTitle: 'Mindset',
          sourceBookAuthor: 'Carol Dweck',
          sourceChapter: 'Capítulo 7',
          sourceBookId: 'book-b',
        }),
      ]),
    });

    expect(screen.getByTestId('dictionary-quote')).toHaveTextContent(
      'Otra frase, de otro capítulo.',
    );
    expect(screen.getByTestId('dictionary-reference-title')).toHaveTextContent('Mindset');
    expect(screen.getByTestId('dictionary-reference-author')).toHaveTextContent(
      'Carol Dweck · Capítulo 7',
    );

    expect(screen.getByTestId('dictionary-detail-definition')).toHaveTextContent(
      USER_FIELDS.definition,
    );
    expect(screen.getByTestId('dictionary-detail-tag')).toHaveTextContent(USER_FIELDS.partOfSpeech);
    expect(screen.getByTestId('dictionary-detail-phonetic')).toHaveTextContent(
      `/${USER_FIELDS.phonetic}/`,
    );
    expect(screen.getByTestId('dictionary-detail-example')).toHaveTextContent(USER_FIELDS.example);

    // Display alone changed; nothing wrote.
    expect(state.update).not.toHaveBeenCalled();
    expect(state.capture).not.toHaveBeenCalled();
  });

  it('renders the re-capture affordance as explanatory copy, never as a writer', async () => {
    const state = makeState([ENTRY]);
    const { container } = render(DictionaryView, { props: { t: tEs, dictionary: state } });

    await select(container, 'Efímero');

    const note = screen.getByTestId('dictionary-evidence-note');
    expect(note).toHaveTextContent(messagesEs['dictionary.evidenceNote']);
    expect(note.closest('button, a, input, textarea, select, form')).toBeNull();
    expect(note.querySelectorAll('button, a, input, textarea, select')).toHaveLength(0);

    // The citation area as a whole stays control-free in read mode.
    const citation = screen.getByTestId('dictionary-citation');
    expect(citation.querySelectorAll('button, a, input, textarea, select')).toHaveLength(0);
    expect(state.capture).not.toHaveBeenCalled();

    // The note also explains the evidence while the session is open.
    await fireEvent.click(screen.getByTestId('dictionary-detail-edit'));
    expect(screen.getByTestId('dictionary-evidence-note')).toHaveTextContent(
      messagesEs['dictionary.evidenceNote'],
    );
  });

  it('disables Ver libro for an entry with no source book (4E)', async () => {
    const state = makeState([word({ id: 'manual', word: 'Efímero', definition: 'Sin libro.' })]);
    const { deps, calls } = makeNavigationDeps();
    const { container } = render(DictionaryView, {
      props: { t: tEs, dictionary: state, bookNavigation: deps },
    });

    await select(container, 'Efímero');
    const button = screen.getByTestId('dictionary-detail-view-book');

    // The frame's geometry stays; the action cannot fire without a book.
    expect(button).toBeDisabled();
    await fireEvent.click(button);

    expect(calls).toEqual([]);
    expect(state.update).not.toHaveBeenCalled();
    expect(state.capture).not.toHaveBeenCalled();
    expect(state.remove).not.toHaveBeenCalled();
    expect(messagesEs['dictionary.viewBook']).toBe('Ver libro');
  });

  it('enables Ver libro as soon as the entry names a source book (4E)', async () => {
    const { deps } = makeNavigationDeps();
    const { container } = render(DictionaryView, {
      props: { t: tEs, dictionary: makeState([ENTRY]), bookNavigation: deps },
    });

    await select(container, 'Efímero');

    expect(screen.getByTestId('dictionary-detail-view-book')).not.toBeDisabled();
  });

  it('offers no evidence control even while the session is open', async () => {
    const state = makeState([ENTRY]);
    const { container } = render(DictionaryView, { props: { t: tEs, dictionary: state } });

    await openEdit(container);

    // No control anywhere in the panel can write or clear the evidence: the
    // edit form holds the four user fields and the citation holds no control.
    const panel = screen.getByTestId('dictionary-detail');
    const controls = [...panel.querySelectorAll<HTMLElement>('input, textarea')];
    expect(controls.map((node) => node.dataset.testid)).toEqual([
      'dictionary-edit-definition',
      'dictionary-edit-partOfSpeech',
      'dictionary-edit-phonetic',
      'dictionary-edit-example',
    ]);
  });
});

describe('dictionary user-field edit helpers (4D)', () => {
  it('seeds a draft from the entry, blank-safe', () => {
    expect(userFieldDraft({})).toEqual({
      definition: '',
      partOfSpeech: '',
      phonetic: '',
      example: '',
    });
    expect(
      userFieldDraft({
        definition: 'Algo',
        partOfSpeech: null,
        phonetic: 'a',
        example: undefined,
      }),
    ).toEqual({ definition: 'Algo', partOfSpeech: '', phonetic: 'a', example: '' });
  });

  it('builds the update payload from exactly the four user fields', () => {
    const draft = {
      definition: 'Algo',
      partOfSpeech: 'Sustantivo',
      phonetic: 'a',
      example: 'Una frase.',
    };

    expect(userFieldPatchFrom(draft)).toEqual(draft);
    expect(Object.keys(userFieldPatchFrom(draft))).toEqual([...USER_FIELD_KEYS]);
    expect(Object.keys(userFieldPatchFrom(draft)).some((key) => /quote|source/i.test(key))).toBe(
      false,
    );
  });

  it('names the four user fields in the frame section order', () => {
    expect(USER_FIELD_KEYS).toEqual(['definition', 'partOfSpeech', 'phonetic', 'example']);
    expect(EMPTY_USER_FIELD_DRAFT).toEqual(userFieldPatchFrom(EMPTY_USER_FIELD_DRAFT));
  });
});

describe('dictionary detail formatting helpers', () => {
  it('wraps the stored phonetic once and tolerates blanks', () => {
    expect(formatPhonetic('eˈfimeɾo')).toBe('/eˈfimeɾo/');
    expect(formatPhonetic('  /eˈfimeɾo/  ')).toBe('/eˈfimeɾo/');
    expect(formatPhonetic('/')).toBe('');
    expect(formatPhonetic('   ')).toBe('');
  });

  it('derives the cover initials from the first two words', () => {
    expect(bookInitials('Hábitos Atómicos')).toBe('HA');
    expect(bookInitials('Mindset')).toBe('M');
    expect(bookInitials('  el poder de la resiliencia ')).toBe('EP');
    expect(bookInitials('')).toBe('');
  });

  it('joins author and chapter without a dangling separator', () => {
    expect(bookReferenceLine('James Clear', 'Capítulo 3')).toBe('James Clear · Capítulo 3');
    expect(bookReferenceLine('James Clear', null)).toBe('James Clear');
    expect(bookReferenceLine(null, 'Capítulo 3')).toBe('Capítulo 3');
    expect(bookReferenceLine('  ', '  ')).toBe('');
    expect(bookReferenceLine(null, null)).toBe('');
  });

  it('detects evidence presence for the detail panel', () => {
    expect(hasQuote(word({ id: '1', word: 'A', quote: 'Una frase.' }))).toBe(true);
    expect(hasQuote(word({ id: '1', word: 'A', quote: '   ' }))).toBe(false);
    expect(hasQuote(word({ id: '1', word: 'A' }))).toBe(false);

    expect(hasBookReference(word({ id: '1', word: 'A', sourceBookTitle: 'Un libro' }))).toBe(true);
    expect(hasBookReference(word({ id: '1', word: 'A', sourceBookAuthor: 'Alguien' }))).toBe(true);
    expect(hasBookReference(word({ id: '1', word: 'A', sourceChapter: 'Capítulo 1' }))).toBe(true);
    expect(hasBookReference(word({ id: '1', word: 'A' }))).toBe(false);
    expect(hasBookReference(word({ id: '1', word: 'A', sourceBookTitle: '  ' }))).toBe(false);
  });

  it('reports whether the panel has anything past the term', () => {
    expect(hasAnyDetail(word({ id: '1', word: 'A' }))).toBe(false);
    expect(hasAnyDetail(word({ id: '1', word: 'A', definition: 'Algo' }))).toBe(true);
    expect(hasAnyDetail(word({ id: '1', word: 'A', partOfSpeech: 'Sustantivo' }))).toBe(true);
    expect(hasAnyDetail(word({ id: '1', word: 'A', phonetic: 'a' }))).toBe(true);
    expect(hasAnyDetail(word({ id: '1', word: 'A', example: 'Una frase.' }))).toBe(true);
    expect(hasAnyDetail(word({ id: '1', word: 'A', quote: 'Una cita.' }))).toBe(true);
    expect(hasAnyDetail(word({ id: '1', word: 'A', sourceBookTitle: 'Un libro' }))).toBe(true);
    expect(hasAnyDetail(word({ id: '1', word: 'A', definition: '   ' }))).toBe(false);
  });
});

describe('dictionary screen i18n parity', () => {
  const screenKeys = [
    'dictionary.title',
    'dictionary.subtitle',
    'dictionary.searchPlaceholder',
    'dictionary.newWord',
    'dictionary.tabAll',
    'dictionary.tabRecent',
    'dictionary.tabAz',
    'dictionary.kpiTotal',
    'dictionary.kpiThisWeek',
    'dictionary.kpiReferencedBooks',
    'dictionary.incomplete',
    'dictionary.dateToday',
    'dictionary.dateYesterday',
    'dictionary.dateDaysAgo',
    'dictionary.dateWeeksAgo',
    'dictionary.dateMonthsAgo',
    'dictionary.dateYearsAgo',
    'dictionary.description',
    'dictionary.personalExample',
    'dictionary.bookReference',
    'dictionary.viewBook',
    'dictionary.edit',
    'dictionary.selectWordTitle',
    'dictionary.selectWordDescription',
    'dictionary.noDetailTitle',
    'dictionary.noDetailDescription',
    'dictionary.delete',
    'dictionary.deleteConfirm',
    'dictionary.deleted',
    'dictionary.saved',
    'dictionary.searchLabel',
    'dictionary.tagsPlaceholder',
    'dictionary.noResultsTitle',
    'dictionary.noResultsDescription',
    'dictionary.loadErrorTitle',
    'dictionary.loadErrorDescription',
    'dictionary.retry',
  ] as const;

  it('defines every screen key in both locales with an English KPI equivalent', () => {
    for (const key of screenKeys) {
      expect(messagesEn[key]).toBeDefined();
      expect(messagesEs[key]).toBeDefined();
    }

    expect(messagesEn['dictionary.kpiTotal']).toBe('Saved words');
    expect(messagesEn['dictionary.kpiThisWeek']).toBe('This week');
    expect(messagesEn['dictionary.kpiReferencedBooks']).toBe('Books referenced');
  });
});
