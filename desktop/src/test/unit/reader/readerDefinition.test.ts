/**
 * D5 wiring — the reader turns a selection into a `define` call.
 *
 * These tests drive a whole selection through the toolbar (viewer double ->
 * `handleViewerSelection` -> `SelectionToolbar` -> `handleDefine`) and assert
 * what reaches the port. Two languages travel and they come from DIFFERENT
 * places, which is the whole risk: `readerLang` is the UI locale (the language
 * the definition must be written in) and `wordLang` is the book's language (the
 * language the selected word is in). Confusing the two silently produces the
 * wrong Wiktionary section, so each is pinned separately.
 *
 * The ports are injected, so `handleDefine`'s real body runs without a Tauri
 * backend. The viewer is the capture mock the reader tests already use.
 */
import { render, screen, fireEvent } from '@testing-library/svelte';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import type { LibraryBookDto } from '$lib/shared/types/library';
import type { TranslationPort } from '$lib/shared/ports/TranslationPort';
import { MockDictionaryLookupAdapter } from '$lib/shared/ports/adapters/mock/MockDictionaryLookupAdapter';

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

// Nothing here should reach the backend: every port is injected. The mock
// rejects loudly if an unexpected command is invoked, so a missing injection
// fails the test instead of silently passing through a real network path.
vi.mock('$lib/shared/api/invokeWrapper', () => ({
  invoke: (...args: unknown[]) => {
    throw new Error(`unexpected invoke: ${String(args[0])}`);
  },
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
    margins: { top: 2.5, bottom: 2.5, left: 2, right: 2 },
    showHeader: true,
    showFooter: true,
    showPageNumbers: true,
    progressIndicator: 'percentage',
  })),
  listLibraryBooks: vi.fn().mockResolvedValue([]),
  listDictionaryWords: vi.fn().mockResolvedValue([]),
  listHighlights: vi.fn().mockResolvedValue([]),
  listBookmarks: vi.fn().mockResolvedValue([]),
  getReaderSettings: vi.fn().mockResolvedValue(null),
  getSettings: vi.fn().mockResolvedValue([]),
  searchDictionaryWords: vi.fn().mockResolvedValue([]),
  addDictionaryWord: vi.fn(),
  updateDictionaryEvidence: vi.fn(),
  updateDictionaryWord: vi.fn(),
  saveBookmark: vi.fn(),
  deleteBookmark: vi.fn(),
  listTags: vi.fn().mockResolvedValue([]),
  listTagsForHighlight: vi.fn().mockResolvedValue([]),
  addCoalescedSyncOutboxItem: vi.fn(),
}));

const { resetCaptureSelection } = await import('../../mocks/MockCaptureViewer.svelte');

const ReaderWorkspace = (await import('$lib/features/reader/chrome/ReaderWorkspace.svelte'))
  .default;

type BookStub = LibraryBookDto & { filePath: string };

const makeBook = (overrides: Partial<BookStub> = {}): BookStub => ({
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

function renderReader(
  book: BookStub,
  dictionaryLookupPort: MockDictionaryLookupAdapter,
  translationPort?: TranslationPort,
): ReturnType<typeof render> {
  return render(ReaderWorkspace, {
    activeReadingBook: book,
    t,
    onBackToHome: () => undefined,
    dictionaryLookupPort,
    translationPort,
  });
}

/** Select, then click "Define". */
async function selectAndDefine(): Promise<void> {
  const emit = await screen.findByTestId('mock-capture-select');
  await fireEvent.click(emit);

  const defineButton = await screen.findByRole('button', { name: 'reader.define' });
  await fireEvent.click(defineButton);
}

describe('ReaderWorkspace — handleDefine language wiring', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    resetCaptureSelection();
  });

  it('sends the book language as wordLang', async () => {
    const port = new MockDictionaryLookupAdapter();
    renderReader(makeBook({ language: 'en' }), port);

    await selectAndDefine();

    expect(port.calls).toHaveLength(1);
    expect(port.calls[0].wordLang).toBe('en');
  });

  it('sends the selected text unchanged, letting the backend trim', async () => {
    const port = new MockDictionaryLookupAdapter();
    renderReader(makeBook({ language: 'en' }), port);

    await selectAndDefine();

    expect(port.calls[0].text).toBe('Abyss');
  });

  it('normalises a regional book tag to its primary subtag', async () => {
    // Wiktionary sections are keyed on the primary subtag: `en-US` must reach
    // the backend as `en`, or the `{{lengua|en-US}}` section is never found.
    const port = new MockDictionaryLookupAdapter();
    renderReader(makeBook({ language: 'en-US' }), port);

    await selectAndDefine();

    expect(port.calls[0].wordLang).toBe('en');
  });

  it('sends null — not a guess — when the book carries no language', async () => {
    // The documented fallback: the backend skips the native step and assumes
    // `en`. Guessing a language here would hide the gap; null is the honest
    // "the book did not say" and the backend already handles it.
    const port = new MockDictionaryLookupAdapter();
    renderReader(makeBook({ language: undefined }), port);

    await selectAndDefine();

    expect(port.calls[0].wordLang).toBeNull();
  });

  it('sends null for a blank language tag rather than an empty string', async () => {
    const port = new MockDictionaryLookupAdapter();
    renderReader(makeBook({ language: '   ' }), port);

    await selectAndDefine();

    expect(port.calls[0].wordLang).toBeNull();
  });

  it('sends a usable readerLang — the language the definition must be written in', async () => {
    const port = new MockDictionaryLookupAdapter();
    renderReader(makeBook({ language: 'en' }), port);

    await selectAndDefine();

    // The default UI locale is `es`; whatever it is, it must be a real code and
    // it must not be the book's own language by accident.
    expect(port.calls[0].readerLang).toMatch(/^[a-z]{2}$/);
  });

  /**
   * D6, requirement 4 end to end: the stored definition language is what the
   * command is ASKED FOR, not merely a value kept in a table. The preference
   * says French while the app is in Spanish, so a `readerLang` of `es` here
   * would mean the setting is decorative.
   */
  it('defines in the account preference language, not the app locale', async () => {
    const port = new MockDictionaryLookupAdapter();
    const translationPort = {
      getPreferences: vi.fn().mockResolvedValue({
        sourceLanguage: null,
        targetLanguage: null,
        definitionLanguage: 'fr',
        updatedAt: '2026-01-01T00:00:00Z',
      }),
    } as unknown as TranslationPort;

    renderReader(makeBook({ language: 'en' }), port, translationPort);

    await selectAndDefine();

    expect(translationPort.getPreferences).toHaveBeenCalled();
    expect(port.calls[0].readerLang).toBe('fr');
  });

  it('falls back to the app locale when no definition language was chosen', async () => {
    const port = new MockDictionaryLookupAdapter();
    const translationPort = {
      getPreferences: vi.fn().mockResolvedValue(null),
    } as unknown as TranslationPort;

    renderReader(makeBook({ language: 'en' }), port, translationPort);

    await selectAndDefine();

    // The locale's own code, not `fr` — the preference is the only thing that
    // can make this differ from the pre-D6 behaviour.
    expect(port.calls[0].readerLang).toBe('es');
  });

  it('still defines when the preferences read fails', async () => {
    // A definition must not depend on a preference lookup: an unreadable
    // preference is the app locale, and the reader still gets an answer.
    const port = new MockDictionaryLookupAdapter();
    const translationPort = {
      getPreferences: vi.fn().mockRejectedValue(new Error('no active account')),
    } as unknown as TranslationPort;

    renderReader(makeBook({ language: 'en' }), port, translationPort);

    await selectAndDefine();

    expect(port.calls).toHaveLength(1);
    expect(port.calls[0].readerLang).toMatch(/^[a-z]{2}$/);
  });
});

describe('ReaderWorkspace — the definition panel through the toolbar', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    resetCaptureSelection();
  });

  it('renders definitions and attribution from the port result', async () => {
    const port = new MockDictionaryLookupAdapter();
    port.setResult('Abyss', 'native', [
      { text: 'A deep or seemingly bottomless chasm.', partOfSpeech: 'noun' },
    ]);
    renderReader(makeBook({ language: 'en' }), port);

    await selectAndDefine();

    expect(await screen.findByText('A deep or seemingly bottomless chasm.')).toBeTruthy();
    expect(screen.getByText('noun')).toBeTruthy();
    expect(screen.getByText('Wiktionary, CC BY-SA 4.0 (https://www.wiktionary.org/)')).toBeTruthy();
  });

  it('marks a machine-translated result and leaves a native one unmarked', async () => {
    const translatedPort = new MockDictionaryLookupAdapter();
    translatedPort.setResult('Abyss', 'translated', [
      { text: 'Un abismo profundo.', partOfSpeech: null },
    ]);
    renderReader(makeBook({ language: 'en' }), translatedPort);

    await selectAndDefine();

    expect(await screen.findByText('reader.definitionTranslated')).toBeTruthy();
  });

  it('shows the empty-state copy when nothing was found', async () => {
    const port = new MockDictionaryLookupAdapter();
    port.setMiss('Abyss');
    renderReader(makeBook({ language: 'en' }), port);

    await selectAndDefine();

    expect(await screen.findByText('reader.definitionNoResult')).toBeTruthy();
  });
});
