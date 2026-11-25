/**
 * REQ-DRE-003 / 004 / 005 / 006 / 008 — the reader capture glue.
 *
 * `ReaderWorkspace` is the only production caller of `captureFromSelection`.
 * These tests drive a whole selection through the toolbar (viewer double ->
 * `handleViewerSelection` -> `SelectionToolbar` -> `handleAddToDictionary`) and
 * assert what reaches the store: which write happened, with which evidence, and
 * which localized feedback the toolbar shows.
 *
 * `invokeWrapper` is mocked so `dictionaryState` (the default
 * `dictionaryActions`) runs for real; one test injects a fake actions object to
 * prove the prop is honoured and that the resolver is fed
 * `dictionaryActions.words`.
 */
import { render, screen, fireEvent, waitFor } from '@testing-library/svelte';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import type { LibraryBookDto } from '$lib/shared/types/library';
import type { DictionaryWordDto } from '$lib/shared/types';
import type {
  DictionaryActions,
  DictionaryEvidence,
} from '$lib/shared/dictionary/captureFromSelection';

const t = (key: string) => key;

vi.mock('@tauri-apps/api/webviewWindow', () => ({
  getCurrentWebviewWindow: () => ({
    setFullscreen: vi.fn(),
    minimize: vi.fn(),
    toggleMaximize: vi.fn(),
    close: vi.fn(),
    isMaximized: vi.fn().mockResolvedValue(false),
    onResized: vi.fn(),
  }),
}));

let invokeMock = vi.fn();

vi.mock('$lib/shared/api/invokeWrapper', () => ({
  invoke: (...args: unknown[]) => invokeMock(...args),
}));

vi.mock('$lib/features/reader/viewer-pdf/PdfViewer.svelte', async () => {
  const mod = await import('../../mocks/MockCaptureViewer.svelte');
  return { default: mod.default };
});

vi.mock('$lib/features/reader/viewer-epub/EpubNativeViewer.svelte', async () => {
  const mod = await import('../../mocks/MockCaptureViewer.svelte');
  return { default: mod.default };
});

vi.mock('$lib/shared/api/tauriClient', () => ({
  saveHighlight: vi.fn(),
  deleteHighlight: vi.fn(),
  updateHighlight: vi.fn(),
  getDefaultReaderSettings: vi.fn(() => ({
    themeMode: 'paper',
    brightness: 100,
    contrast: 100,
    selectionColor: '#3388ff',
    epub: { fontSize: 100, fontFamily: 'serif' },
    lineHeight: 1.8,
    letterSpacing: 0,
    paragraphSpacing: 1,
    textAlign: 'left',
    direction: 'ltr',
    hyphenation: false,
    verticalScrolling: false,
    margins: { top: 1.5, bottom: 1.5, left: 2, right: 2 },
    showHeader: true,
    showFooter: true,
    showPageNumbers: true,
    progressIndicator: 'percentage',
  })),
  upsertReaderSettings: vi.fn(),
  listBookmarks: vi.fn().mockResolvedValue([]),
  listHighlights: vi.fn().mockResolvedValue([]),
  saveBookmark: vi.fn(),
  deleteBookmark: vi.fn(),
  listTags: vi.fn().mockResolvedValue([]),
  listTagsForHighlight: vi.fn().mockResolvedValue([]),
  createTag: vi.fn().mockResolvedValue({ id: 't1', name: 't1' }),
  saveHighlightTags: vi.fn().mockResolvedValue([]),
}));

import ReaderWorkspace from '$lib/features/reader/chrome/ReaderWorkspace.svelte';
import { dictionaryState } from '$lib/shared/stores/DictionaryState.svelte';
import { toastQueue } from '$lib/shared/stores/ToastQueue.svelte';
import { resetCaptureSelection, setCaptureSelection } from '../../mocks/MockCaptureViewer.svelte';

const USER_FIELDS = {
  definition: 'A deep void',
  partOfSpeech: 'noun',
  phonetic: '/əˈbɪs/',
  example: 'The abyss stared back.',
};

/** The paragraph and reference the default mock selection carries. */
const DEFAULT_EVIDENCE: DictionaryEvidence = {
  quote: 'The abyss stared back at the diver.',
  sourceBookId: 'epub-1',
  sourceBookTitle: 'EPUB Book',
  sourceBookAuthor: 'Author',
  sourceChapter: 'Chapter 3',
  sourceLocator: 'epubcfi(/6/4!/4/2/1:0)',
};

function dto(overrides: Partial<DictionaryWordDto> = {}): DictionaryWordDto {
  return {
    id: 'local-1',
    word: 'Abyss',
    createdAt: '2025-01-01T00:00:00Z',
    updatedAt: '2025-01-01T00:00:00Z',
    normalizedWord: 'abyss',
    tags: [],
    srsStage: 0,
    ...USER_FIELDS,
    quote: null,
    sourceBookId: null,
    sourceBookTitle: null,
    sourceBookAuthor: null,
    sourceChapter: null,
    sourceLocator: null,
    ...overrides,
  } as DictionaryWordDto;
}

/** The rows `listDictionaryWords` returns for the next `load()`. */
let currentList: DictionaryWordDto[] = [];

invokeMock.mockImplementation((command: string, args?: Record<string, unknown>) => {
  if (command === 'listDictionaryWords') {
    return Promise.resolve(currentList.map((entry) => ({ ...entry })));
  }
  if (command === 'addDictionaryWord') {
    const payload = (args as { payload: Record<string, unknown> }).payload;
    return Promise.resolve(
      dto({
        id: 'local-2',
        word: String(payload.word),
        quote: (payload.quote as string | null) ?? null,
        sourceBookId: (payload.sourceBookId as string | null) ?? null,
        sourceBookTitle: (payload.sourceBookTitle as string | null) ?? null,
        sourceBookAuthor: (payload.sourceBookAuthor as string | null) ?? null,
        sourceChapter: (payload.sourceChapter as string | null) ?? null,
        sourceLocator: (payload.sourceLocator as string | null) ?? null,
      }),
    );
  }
  if (command === 'updateDictionaryEvidence') {
    const payload = (args as { payload: Record<string, unknown> }).payload;
    return Promise.resolve(
      dto({
        id: String(payload.id),
        quote: (payload.quote as string | null) ?? null,
        sourceBookId: (payload.sourceBookId as string | null) ?? null,
        sourceBookTitle: (payload.sourceBookTitle as string | null) ?? null,
        sourceBookAuthor: (payload.sourceBookAuthor as string | null) ?? null,
        sourceChapter: (payload.sourceChapter as string | null) ?? null,
        sourceLocator: (payload.sourceLocator as string | null) ?? null,
      }),
    );
  }
  return Promise.resolve(undefined);
});

type InvokeCall = [command: string, args?: Record<string, unknown>];

/** Payloads the store sent for one IPC command, in call order. */
function payloadsFor(command: string): Array<Record<string, unknown>> {
  return (invokeMock.mock.calls as InvokeCall[])
    .filter(([cmd]) => cmd === command)
    .map(([, args]) => (args?.payload ?? {}) as Record<string, unknown>);
}

type BookStub = LibraryBookDto & { filePath: string };

const makeEpubBook = (overrides: Partial<BookStub> = {}): BookStub => ({
  id: 'epub-1',
  title: 'EPUB Book',
  author: 'Author',
  filePath: 'C:/book.epub',
  format: 'epub',
  currentPage: 1,
  totalPages: 10,
  progressPercentage: 0,
  coverPath: null,
  minutesRead: 0,
  updatedAt: '2025-01-01T00:00:00Z',
  createdAt: '2025-01-01T00:00:00Z',
  ...overrides,
});

const makePdfBook = (overrides: Partial<BookStub> = {}): BookStub =>
  makeEpubBook({
    id: 'pdf-1',
    title: 'PDF Book',
    filePath: 'C:/book.pdf',
    format: 'pdf',
    ...overrides,
  });

async function seedStore(words: DictionaryWordDto[]): Promise<void> {
  currentList = words;
  await dictionaryState.load();
}

function renderReader(book: BookStub, extraProps: { dictionaryActions?: DictionaryActions } = {}) {
  return render(ReaderWorkspace, {
    activeReadingBook: book,
    t,
    onBackToHome: () => undefined,
    ...extraProps,
  });
}

/**
 * Select, then click "Add to Dictionary", and return the feedback the reader
 * sees. The feedback is a toast at the bottom of the window now (not a line
 * under the toolbar), so this waits for the NEW toast and hands back its text
 * in the shape the assertions read.
 */
async function selectAndCapture(): Promise<{ textContent: string }> {
  const before = toastQueue.items.length;
  const emit = await screen.findByTestId('mock-capture-select');
  await fireEvent.click(emit);

  const dictionaryButton = await screen.findByRole('button', {
    name: 'reader.addToDictionary',
  });
  await fireEvent.click(dictionaryButton);

  await waitFor(() => expect(toastQueue.items.length).toBeGreaterThan(before));
  return { textContent: toastQueue.items[toastQueue.items.length - 1].message };
}

const EVIDENCE_KEYS = [
  'quote',
  'sourceBookId',
  'sourceBookTitle',
  'sourceBookAuthor',
  'sourceChapter',
  'sourceLocator',
] as const;

describe('reader capture — EPUB evidence (REQ-DRE-003/005)', () => {
  beforeEach(async () => {
    vi.clearAllMocks();
    resetCaptureSelection();
    await seedStore([]);
  });

  it('creates one entry from a single-word EPUB selection and writes the full evidence set', async () => {
    renderReader(makeEpubBook());
    const feedback = await selectAndCapture();

    const calls = payloadsFor('addDictionaryWord');
    expect(calls).toHaveLength(1);
    expect(calls[0]).toEqual(
      expect.objectContaining({
        word: 'Abyss',
        ...DEFAULT_EVIDENCE,
      }),
    );
    // No evidence key is dropped: the store sends all six explicitly.
    for (const key of EVIDENCE_KEYS) expect(calls[0]).toHaveProperty(key);
    expect(payloadsFor('updateDictionaryEvidence')).toHaveLength(0);
    expect(feedback.textContent?.trim()).toBe('reader.addedToDictionary');
  });

  it('keeps the captured title and author as a snapshot taken at click time', async () => {
    const book = makeEpubBook();
    renderReader(book);
    await selectAndCapture();

    // A second, different word so this is a second create rather than a
    // re-capture; the book object is then edited before the second click.
    setCaptureSelection({ text: 'Solitude' });
    book.title = 'Renamed Book';
    book.author = 'Someone Else';
    await selectAndCapture();

    const calls = payloadsFor('addDictionaryWord');
    expect(calls).toHaveLength(2);
    expect(calls[0].sourceBookTitle).toBe('EPUB Book');
    expect(calls[0].sourceBookAuthor).toBe('Author');
    expect(calls[1].sourceBookTitle).toBe('Renamed Book');
    expect(calls[1].sourceBookAuthor).toBe('Someone Else');
  });

  it('re-captures onto an existing entry and leaves the user-authored fields untouched', async () => {
    await seedStore([
      dto({
        id: 'local-1',
        quote: 'An older paragraph.',
        sourceBookId: 'old-book',
        sourceBookTitle: 'Old Book',
        sourceBookAuthor: 'Old Author',
        sourceChapter: 'Chapter 1',
        sourceLocator: 'epubcfi(/6/2!/4/2/1:0)',
      }),
    ]);

    renderReader(makeEpubBook());
    const feedback = await selectAndCapture();

    const calls = payloadsFor('updateDictionaryEvidence');
    expect(calls).toHaveLength(1);
    expect(calls[0]).toEqual({ id: 'local-1', ...DEFAULT_EVIDENCE });
    expect(payloadsFor('addDictionaryWord')).toHaveLength(0);
    expect(feedback.textContent?.trim()).toBe('reader.dictionaryEvidenceUpdated');

    const stored = dictionaryState.words[0];
    expect(stored.quote).toBe(DEFAULT_EVIDENCE.quote);
    expect(stored.sourceBookId).toBe('epub-1');
    expect(stored.definition).toBe(USER_FIELDS.definition);
    expect(stored.partOfSpeech).toBe(USER_FIELDS.partOfSpeech);
    expect(stored.phonetic).toBe(USER_FIELDS.phonetic);
    expect(stored.example).toBe(USER_FIELDS.example);
  });

  it('attaches evidence to an existing evidence-less entry without creating a second one', async () => {
    await seedStore([dto({ id: 'local-1', quote: null })]);

    renderReader(makeEpubBook());
    const feedback = await selectAndCapture();

    expect(payloadsFor('addDictionaryWord')).toHaveLength(0);
    expect(payloadsFor('updateDictionaryEvidence')).toHaveLength(1);
    expect(dictionaryState.words).toHaveLength(1);
    expect(feedback.textContent?.trim()).toBe('reader.addedToDictionary');
  });
});

describe('reader capture — PDF is evidence-free (REQ-DRE-006)', () => {
  beforeEach(async () => {
    vi.clearAllMocks();
    resetCaptureSelection();
    await seedStore([]);
  });

  it('creates the entry with all six evidence fields null and no capture error', async () => {
    setCaptureSelection({ text: 'Solitude', quote: null, chapterTitle: null, cfi: null });
    renderReader(makePdfBook());
    const feedback = await selectAndCapture();

    const calls = payloadsFor('addDictionaryWord');
    expect(calls).toHaveLength(1);
    expect(calls[0].word).toBe('Solitude');
    for (const key of EVIDENCE_KEYS) expect(calls[0]).toHaveProperty(key, null);
    expect(payloadsFor('updateDictionaryEvidence')).toHaveLength(0);
    expect(feedback.textContent?.trim()).toBe('reader.addedToDictionary');
    expect(feedback.textContent?.trim()).not.toBe('error.somethingWrong');
  });
});

describe('reader capture — selection resolution (REQ-DRE-004)', () => {
  beforeEach(async () => {
    vi.clearAllMocks();
    resetCaptureSelection();
    await seedStore([]);
  });

  it('writes nothing when a multi-word selection matches no entry', async () => {
    setCaptureSelection({ text: 'the abyss' });
    renderReader(makeEpubBook());
    const feedback = await selectAndCapture();

    expect(payloadsFor('addDictionaryWord')).toHaveLength(0);
    expect(payloadsFor('updateDictionaryEvidence')).toHaveLength(0);
    expect(feedback.textContent?.trim()).toBe('reader.dictionaryNoMatch');
  });

  it('writes nothing when a multi-word selection matches several entries', async () => {
    await seedStore([dto({ id: 'local-1', word: 'Abyss' }), dto({ id: 'local-2', word: 'The' })]);
    setCaptureSelection({ text: 'the abyss' });
    renderReader(makeEpubBook());
    const feedback = await selectAndCapture();

    expect(payloadsFor('addDictionaryWord')).toHaveLength(0);
    expect(payloadsFor('updateDictionaryEvidence')).toHaveLength(0);
    expect(feedback.textContent?.trim()).toBe('reader.dictionarySeveralMatches');
  });

  it('attaches to the single matching entry of a multi-word selection', async () => {
    await seedStore([dto({ id: 'local-1', word: 'abyss', quote: null })]);
    setCaptureSelection({ text: 'the abyss' });
    renderReader(makeEpubBook());
    const feedback = await selectAndCapture();

    const calls = payloadsFor('updateDictionaryEvidence');
    expect(calls).toHaveLength(1);
    expect(calls[0].id).toBe('local-1');
    expect(calls[0].quote).toBe(DEFAULT_EVIDENCE.quote);
    expect(payloadsFor('addDictionaryWord')).toHaveLength(0);
    expect(feedback.textContent?.trim()).toBe('reader.addedToDictionary');
  });

  it('treats a punctuation-only selection as a no-op and writes nothing', async () => {
    setCaptureSelection({ text: '...', quote: null, chapterTitle: null });
    renderReader(makeEpubBook());
    const feedback = await selectAndCapture();

    expect(payloadsFor('addDictionaryWord')).toHaveLength(0);
    expect(payloadsFor('updateDictionaryEvidence')).toHaveLength(0);
    expect(feedback.textContent?.trim()).toBe('reader.dictionaryNoMatch');
  });

  it('resolves without writing when the entry exists and the selection has no evidence', async () => {
    await seedStore([dto({ id: 'local-1', quote: 'Already captured.' })]);
    setCaptureSelection({ text: 'Abyss', quote: null, chapterTitle: null });
    renderReader(makeEpubBook());
    const feedback = await selectAndCapture();

    expect(payloadsFor('addDictionaryWord')).toHaveLength(0);
    expect(payloadsFor('updateDictionaryEvidence')).toHaveLength(0);
    expect(feedback.textContent?.trim()).toBe('reader.dictionaryAlreadyInDictionary');
  });
});

describe('reader capture — injected dictionaryActions', () => {
  beforeEach(async () => {
    vi.clearAllMocks();
    resetCaptureSelection();
    await seedStore([]);
  });

  it('feeds the resolver from actions.words and writes through the injected actions', async () => {
    const actions: DictionaryActions = {
      words: [dto({ id: 'fake-1', word: 'Abyss' })],
      add: vi.fn(() => Promise.resolve(dto({ id: 'fake-2' }))),
      capture: vi.fn(() => Promise.resolve(dto({ id: 'fake-1' }))),
    };

    renderReader(makeEpubBook(), { dictionaryActions: actions });
    const feedback = await selectAndCapture();

    // `entries` came from `actions.words`: the existing word resolved to attach.
    expect(actions.capture).toHaveBeenCalledTimes(1);
    expect(actions.capture).toHaveBeenCalledWith('fake-1', DEFAULT_EVIDENCE);
    expect(actions.add).not.toHaveBeenCalled();
    // The store was replaced wholesale, so no IPC reached it.
    expect(payloadsFor('addDictionaryWord')).toHaveLength(0);
    expect(payloadsFor('updateDictionaryEvidence')).toHaveLength(0);
    expect(feedback.textContent?.trim()).toBe('reader.addedToDictionary');
  });
});
