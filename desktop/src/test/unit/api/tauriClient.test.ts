import { beforeEach, describe, expect, it, vi } from 'vitest';

const invokeMock = vi.fn();

vi.mock('@tauri-apps/api/core', () => ({
  invoke: (...args: unknown[]) => invokeMock(...args) as Promise<unknown>,
}));

import {
  addDictionaryWord,
  createTag,
  getDefaultReaderSettings,
  getReaderSettings,
  getReadingActivity,
  getReadingStatsForRange,
  getReadingStreak,
  listDictionaryWords,
  listTags,
  listTagsForHighlight,
  removeDictionaryWord,
  resetReaderSettingsToDefaults,
  sanitizeReaderSettings,
  saveHighlightTags,
  updateDictionaryEvidence,
  updateHighlight,
  upsertReaderSettings,
  upsertRemoteReadingSessions,
  upsertRemoteHighlights,
} from '$lib/shared/api/tauriClient';

describe('tauriClient reader settings', () => {
  beforeEach(() => {
    invokeMock.mockReset();
  });

  it('sanitizes invalid reader settings with defaults and clamps', () => {
    const settings = sanitizeReaderSettings({
      themeMode: 'invalid' as never,
      brightness: 999,
      contrast: 5,
      epub: {
        fontSize: 74,
        fontFamily: '   ',
      },
    });

    expect(settings).toEqual({
      themeMode: 'paper',
      brightness: 150,
      contrast: 50,
      selectionColor: '#3388ff',
      epub: {
        fontSize: 75,
        fontFamily: 'serif',
      },
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
    });
  });

  it('reads and sanitizes persisted reader settings', async () => {
    invokeMock.mockResolvedValueOnce([
      {
        key: 'reader.themeMode',
        valueJson: JSON.stringify('night'),
        updatedAt: '2026-01-01T00:00:00.000Z',
      },
      {
        key: 'reader.brightness',
        valueJson: JSON.stringify(49),
        updatedAt: '2026-01-01T00:00:00.000Z',
      },
      {
        key: 'reader.contrast',
        valueJson: JSON.stringify(151),
        updatedAt: '2026-01-01T00:00:00.000Z',
      },
      {
        key: 'reader.selectionColor',
        valueJson: JSON.stringify('#33bbff'),
        updatedAt: '2026-01-01T00:00:00.000Z',
      },
      {
        key: 'reader.epub.fontSize',
        valueJson: JSON.stringify(150),
        updatedAt: '2026-01-01T00:00:00.000Z',
      },
      {
        key: 'reader.epub.fontFamily',
        valueJson: JSON.stringify('Literata'),
        updatedAt: '2026-01-01T00:00:00.000Z',
      },
      // New layout fields — not provided, so defaults apply
    ]);

    const settings = await getReaderSettings();

    expect(invokeMock).toHaveBeenCalledWith('getSettings', undefined);
    expect(settings).toEqual({
      themeMode: 'night',
      brightness: 50,
      contrast: 150,
      selectionColor: '#33bbff',
      epub: {
        fontSize: 150,
        fontFamily: 'Literata',
      },
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
    });
  });

  it('persists sanitized reader settings values', async () => {
    invokeMock.mockResolvedValue(undefined);

    const settings = await upsertReaderSettings({
      themeMode: 'sepia',
      brightness: 88.6,
      contrast: 112.4,
      epub: {
        fontSize: 205,
        fontFamily: '   Merriweather   ',
      },
    });

    expect(settings).toEqual({
      themeMode: 'sepia',
      brightness: 89,
      contrast: 112,
      selectionColor: '#3388ff',
      epub: {
        fontSize: 200,
        fontFamily: 'Merriweather',
      },
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
    });

    expect(invokeMock).toHaveBeenCalledTimes(1);
    const [command, args] = invokeMock.mock.calls[0] as [
      string,
      { settings: Array<{ key: string; valueJson: string }> },
    ];
    expect(command).toBe('upsertSettings');
    expect(args.settings).toHaveLength(21);
    expect(args.settings.find((entry) => entry.key === 'reader.themeMode')?.valueJson).toBe(
      JSON.stringify('sepia'),
    );
    expect(args.settings.find((entry) => entry.key === 'reader.brightness')?.valueJson).toBe(
      JSON.stringify(89),
    );
    expect(args.settings.find((entry) => entry.key === 'reader.contrast')?.valueJson).toBe(
      JSON.stringify(112),
    );
    expect(args.settings.find((entry) => entry.key === 'reader.epub.fontSize')?.valueJson).toBe(
      JSON.stringify(200),
    );
    expect(args.settings.find((entry) => entry.key === 'reader.epub.fontFamily')?.valueJson).toBe(
      JSON.stringify('Merriweather'),
    );
  });

  it('resets reader settings to defaults', async () => {
    invokeMock.mockResolvedValue(undefined);

    const settings = await resetReaderSettingsToDefaults();

    expect(settings).toEqual(getDefaultReaderSettings());
    const [command, args] = invokeMock.mock.calls[0] as [
      string,
      { settings: Array<{ key: string; valueJson: string }> },
    ];
    expect(command).toBe('upsertSettings');
    expect(args.settings.find((entry) => entry.key === 'reader.themeMode')?.valueJson).toBe(
      JSON.stringify('paper'),
    );
    expect(args.settings.find((entry) => entry.key === 'reader.brightness')?.valueJson).toBe(
      JSON.stringify(100),
    );
    expect(args.settings.find((entry) => entry.key === 'reader.contrast')?.valueJson).toBe(
      JSON.stringify(100),
    );
    expect(args.settings.find((entry) => entry.key === 'reader.selectionColor')?.valueJson).toBe(
      JSON.stringify('#3388ff'),
    );
    expect(args.settings.find((entry) => entry.key === 'reader.epub.fontSize')?.valueJson).toBe(
      JSON.stringify(100),
    );
    expect(args.settings.find((entry) => entry.key === 'reader.epub.fontFamily')?.valueJson).toBe(
      JSON.stringify('serif'),
    );
    // Verify new layout fields are persisted
    expect(args.settings.find((entry) => entry.key === 'reader.lineHeight')?.valueJson).toBe(
      JSON.stringify(1.8),
    );
    expect(args.settings.find((entry) => entry.key === 'reader.textAlign')?.valueJson).toBe(
      JSON.stringify('left'),
    );
  });
});

describe('tauriClient highlight menu commands', () => {
  beforeEach(() => {
    invokeMock.mockReset();
  });

  it('updates highlight color and note', async () => {
    invokeMock.mockResolvedValueOnce({
      id: 'hl-1',
      bookId: 'book-1',
      text: 'sample',
      color: '#4ADE80',
      pageNumber: 1,
      note: 'updated note',
      createdAt: '2024-01-01T00:00:00Z',
    });

    const result = await updateHighlight({ id: 'hl-1', color: '#4ADE80', note: 'updated note' });

    expect(invokeMock).toHaveBeenCalledWith('updateHighlight', {
      payload: { id: 'hl-1', color: '#4ADE80', note: 'updated note' },
    });
    expect(result.color).toBe('#4ADE80');
    expect(result.note).toBe('updated note');
  });

  it('saves highlight tags', async () => {
    invokeMock.mockResolvedValueOnce([
      { id: 'tag-1', name: 'Review', createdAt: '2024-01-01T00:00:00Z' },
    ]);

    const result = await saveHighlightTags({ highlightId: 'hl-1', tagIds: ['tag-1'] });

    expect(invokeMock).toHaveBeenCalledWith('saveHighlightTags', {
      payload: { highlightId: 'hl-1', tagIds: ['tag-1'] },
    });
    expect(result).toHaveLength(1);
    expect(result[0].name).toBe('Review');
  });

  it('lists tags', async () => {
    invokeMock.mockResolvedValueOnce([
      { id: 'tag-1', name: 'Review', createdAt: '2024-01-01T00:00:00Z' },
    ]);

    const result = await listTags();

    expect(invokeMock).toHaveBeenCalledWith('listTags', undefined);
    expect(result).toHaveLength(1);
  });

  it('lists tags for a highlight', async () => {
    invokeMock.mockResolvedValueOnce([
      { id: 'tag-1', name: 'Review', createdAt: '2024-01-01T00:00:00Z' },
    ]);

    const result = await listTagsForHighlight('hl-1');

    expect(invokeMock).toHaveBeenCalledWith('listTagsForHighlight', { highlightId: 'hl-1' });
    expect(result).toHaveLength(1);
  });

  it('creates a tag', async () => {
    invokeMock.mockResolvedValueOnce({
      id: 'tag-1',
      name: 'Review',
      color: '#ff0000',
      createdAt: '2024-01-01T00:00:00Z',
    });

    const result = await createTag({ name: 'Review', color: '#ff0000' });

    expect(invokeMock).toHaveBeenCalledWith('createTag', {
      payload: { name: 'Review', color: '#ff0000' },
    });
    expect(result.color).toBe('#ff0000');
  });

  it('adds and lists dictionary words', async () => {
    invokeMock.mockResolvedValueOnce({
      id: 'word-1',
      word: 'Serendipity',
      createdAt: '2024-01-01T00:00:00Z',
    });

    const added = await addDictionaryWord({ word: 'Serendipity' });

    expect(invokeMock).toHaveBeenCalledWith('addDictionaryWord', {
      payload: { word: 'Serendipity' },
    });
    expect(added.word).toBe('Serendipity');

    invokeMock.mockResolvedValueOnce([
      { id: 'word-1', word: 'Serendipity', createdAt: '2024-01-01T00:00:00Z' },
    ]);

    const words = await listDictionaryWords();
    expect(invokeMock).toHaveBeenCalledWith('listDictionaryWords', undefined);
    expect(words).toHaveLength(1);
  });

  it('removes a dictionary word', async () => {
    invokeMock.mockResolvedValueOnce(undefined);

    await removeDictionaryWord('word-1');

    expect(invokeMock).toHaveBeenCalledWith('removeDictionaryWord', { id: 'word-1' });
  });
});

describe('tauriClient dictionary evidence commands', () => {
  beforeEach(() => {
    invokeMock.mockReset();
  });

  it('forwards the ten rich-entry fields on addDictionaryWord', async () => {
    invokeMock.mockResolvedValueOnce({
      id: 'word-1',
      word: 'Abyss',
      createdAt: '2024-01-01T00:00:00Z',
    });
    const payload = {
      word: 'Abyss',
      definition: 'a deep hole',
      partOfSpeech: 'noun',
      phonetic: '/əˈbɪs/',
      example: 'the abyss stared back',
      quote: 'He gazed into the abyss.',
      sourceBookId: 'book-1',
      sourceBookTitle: 'Meditations',
      sourceBookAuthor: 'Marcus Aurelius',
      sourceChapter: 'Book IV',
      sourceLocator: 'epubcfi(/6/14!/4/2/2)',
    };

    await addDictionaryWord(payload);

    expect(invokeMock).toHaveBeenCalledWith('addDictionaryWord', { payload });
  });

  it('invokes updateDictionaryEvidence with the re-capture payload', async () => {
    invokeMock.mockResolvedValueOnce({
      id: 'word-1',
      word: 'Abyss',
      createdAt: '2024-01-01T00:00:00Z',
    });
    const payload = {
      id: 'word-1',
      quote: 'A new passage.',
      sourceBookId: 'book-2',
      sourceBookTitle: 'Letters',
      sourceBookAuthor: 'Seneca',
      sourceChapter: 'Letter I',
      sourceLocator: 'epubcfi(/6/8!/4/2)',
    };

    const result = await updateDictionaryEvidence(payload);

    expect(invokeMock).toHaveBeenCalledWith('updateDictionaryEvidence', { payload });
    expect(result.id).toBe('word-1');
  });

  it('surfaces a rejected updateDictionaryEvidence as a command error', async () => {
    invokeMock.mockRejectedValueOnce(new Error('not found'));

    await expect(updateDictionaryEvidence({ id: 'missing' })).rejects.toThrow();
  });
});

describe('tauriClient reading stats commands', () => {
  beforeEach(() => {
    invokeMock.mockReset();
  });

  it('getReadingActivity invokes the command with period, granularity, and bookId', async () => {
    const expected: Array<{ bucket: string; minutes: number }> = [
      { bucket: '2026-06-24', minutes: 10 },
      { bucket: '2026-06-25', minutes: 5 },
    ];
    invokeMock.mockResolvedValueOnce(expected);

    const result = await getReadingActivity('week', 'day', undefined);

    expect(invokeMock).toHaveBeenCalledWith('getReadingActivity', {
      period: 'week',
      granularity: 'day',
      bookId: undefined,
      zoneOffsetMinutes: expect.any(Number),
    });
    expect(result).toEqual(expected);
  });

  it('getReadingActivity with bookId passes it through', async () => {
    invokeMock.mockResolvedValueOnce([]);

    await getReadingActivity('month', 'day', 'book-1');

    expect(invokeMock).toHaveBeenCalledWith('getReadingActivity', {
      period: 'month',
      granularity: 'day',
      bookId: 'book-1',
      zoneOffsetMinutes: expect.any(Number),
    });
  });

  it('getReadingActivity throws on rejection', async () => {
    invokeMock.mockRejectedValueOnce(new Error('command failed'));

    await expect(getReadingActivity('week', 'day')).rejects.toThrow();
  });

  it('getReadingStatsForRange invokes the command with from, to, and bookId', async () => {
    const expected = {
      totalMinutesRead: 120,
      totalSessions: 3,
      booksStarted: 1,
      booksCompleted: 0,
      avgProgressPercentage: 15.5,
    };
    invokeMock.mockResolvedValueOnce(expected);

    const result = await getReadingStatsForRange('2026-06-01T00:00:00Z', '2026-06-30T23:59:59Z');

    expect(invokeMock).toHaveBeenCalledWith('getReadingStatsForRange', {
      from: '2026-06-01T00:00:00Z',
      to: '2026-06-30T23:59:59Z',
      bookId: undefined,
    });
    expect(result).toEqual(expected);
  });

  it('getReadingStatsForRange throws on rejection', async () => {
    invokeMock.mockRejectedValueOnce(new Error('stats unavailable'));

    await expect(
      getReadingStatsForRange('2026-01-01T00:00:00Z', '2026-12-31T23:59:59Z'),
    ).rejects.toThrow();
  });

  it('getReadingStreak invokes the command with bookId and userId', async () => {
    invokeMock.mockResolvedValueOnce(5);

    const result = await getReadingStreak('book-1', 'user-1');

    expect(invokeMock).toHaveBeenCalledWith('getReadingStreak', {
      bookId: 'book-1',
      userId: 'user-1',
      zoneOffsetMinutes: expect.any(Number),
    });
    expect(result).toBe(5);
  });

  it('getReadingStreak defaults userId to empty string (legacy rows)', async () => {
    invokeMock.mockResolvedValueOnce(0);

    const result = await getReadingStreak('book-1');

    expect(invokeMock).toHaveBeenCalledWith('getReadingStreak', {
      bookId: 'book-1',
      userId: '',
      zoneOffsetMinutes: expect.any(Number),
    });
    expect(result).toBe(0);
  });

  it('getReadingStreak without bookId passes undefined', async () => {
    invokeMock.mockResolvedValueOnce(0);

    const result = await getReadingStreak();

    expect(invokeMock).toHaveBeenCalledWith('getReadingStreak', {
      bookId: undefined,
      userId: '',
      zoneOffsetMinutes: expect.any(Number),
    });
    expect(result).toBe(0);
  });

  it('getReadingStreak throws on rejection', async () => {
    invokeMock.mockRejectedValueOnce(new Error('streak unavailable'));

    await expect(getReadingStreak()).rejects.toThrow();
  });

  it('upsertRemoteReadingSessions invokes the command with the rows array', async () => {
    invokeMock.mockResolvedValueOnce(2);
    const rows = [
      {
        id: 'sess_1',
        userId: 'u1',
        bookId: 'b1',
        startedAt: '2026-08-13T10:00:00.000Z',
        durationMinutes: 25,
        date: '2026-08-13T00:00:00.000Z',
        updatedAtEpochMillis: 1786615200000,
        startPercentage: null,
        endPercentage: null,
      },
    ];

    const result = await upsertRemoteReadingSessions(rows);

    expect(invokeMock).toHaveBeenCalledWith('upsertRemoteReadingSessions', { rows });
    expect(result).toBe(2);
  });

  it('upsertRemoteHighlights invokes the command with the rows array and returns summary', async () => {
    invokeMock.mockResolvedValueOnce({ applied: 1, skippedUnknownBook: 0, skippedInvalid: 0 });
    const rows = [
      {
        id: 'hl_1',
        userId: 'u1',
        bookId: 'b1',
        cfiRange: 'epubcfi(/6/2)',
        textContent: 'highlight',
        note: null,
        color: '#FACC15',
        page: 1,
        updatedAtEpochMillis: 1786615200000,
        deletedAtEpochMillis: null,
      },
    ];

    const result = await upsertRemoteHighlights(rows);

    expect(invokeMock).toHaveBeenCalledWith('upsertRemoteHighlights', { rows });
    expect(result).toEqual({ applied: 1, skippedUnknownBook: 0, skippedInvalid: 0 });
  });
});
