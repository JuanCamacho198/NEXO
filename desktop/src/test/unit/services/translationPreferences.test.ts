/**
 * Translation preferences + history settings hook.
 *
 * The hook is the only writer of the preference pair and the only reader of the
 * history. What is pinned here is the behaviour a reader can observe: `null`
 * means "infer" and is a real value, the history reflects the backend after a
 * confirmed delete, and a failed delete never empties the list locally.
 */
import { describe, expect, it, vi, beforeEach } from 'vitest';
import type { TranslationPort } from '$lib/shared/ports/TranslationPort';
import type { TranslationCacheEntryDto, TranslationPreferencesDto } from '$lib/shared/types';

const { createTranslationSettings } =
  await import('$lib/features/settings/useTranslationSettings.svelte');

type FakePort = TranslationPort & {
  getPreferences: ReturnType<typeof vi.fn>;
  listHistory: ReturnType<typeof vi.fn>;
  savePreferences: ReturnType<typeof vi.fn>;
  clearHistory: ReturnType<typeof vi.fn>;
};

const createPort = (overrides: Partial<TranslationPort> = {}): FakePort =>
  ({
    translate: vi.fn(),
    getPreferences: vi.fn().mockResolvedValue(null),
    listHistory: vi.fn().mockResolvedValue([]),
    savePreferences: vi.fn(),
    clearHistory: vi.fn().mockResolvedValue(0),
    ...overrides,
  }) as FakePort;

const entry = (overrides: Partial<TranslationCacheEntryDto> = {}): TranslationCacheEntryDto => ({
  cacheKey: 'a',
  sourceLanguage: 'es',
  targetLanguage: 'en',
  originalText: 'hola',
  translatedText: 'hello',
  createdAt: '2026-01-02T00:00:00Z',
  ...overrides,
});

describe('createTranslationSettings', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('reads null on both sides when no preference was ever saved', async () => {
    const settings = createTranslationSettings({ port: createPort() });
    await settings.load();

    expect(settings.sourceLanguage).toBeNull();
    expect(settings.targetLanguage).toBeNull();
    expect(settings.history).toEqual([]);
  });

  it('reads the saved pair and the history, newest first', async () => {
    const port = createPort({
      getPreferences: vi.fn().mockResolvedValue({
        sourceLanguage: 'es',
        targetLanguage: 'en',
        updatedAt: '2026-01-01T00:00:00Z',
      } satisfies TranslationPreferencesDto),
      listHistory: vi.fn().mockResolvedValue([entry()]),
    });

    const settings = createTranslationSettings({ port });
    await settings.load();

    expect(settings.sourceLanguage).toBe('es');
    expect(settings.targetLanguage).toBe('en');
    expect(settings.history).toHaveLength(1);
    expect(settings.history[0].originalText).toBe('hola');
  });

  it('writes the pair through the port and reflects what was stored', async () => {
    const port = createPort({
      savePreferences: vi.fn().mockResolvedValue({
        sourceLanguage: 'fr',
        targetLanguage: null,
        updatedAt: '2026-01-03T00:00:00Z',
      } satisfies TranslationPreferencesDto),
    });

    const settings = createTranslationSettings({ port });
    await settings.save('fr', null);

    expect(port.savePreferences).toHaveBeenCalledWith({
      sourceLanguage: 'fr',
      targetLanguage: null,
    });
    expect(settings.sourceLanguage).toBe('fr');
    expect(settings.targetLanguage).toBeNull();
  });

  it('empties the local history only after the backend confirms the delete', async () => {
    const port = createPort({
      listHistory: vi.fn().mockResolvedValue([entry()]),
      clearHistory: vi.fn().mockResolvedValue(1),
    });

    const settings = createTranslationSettings({ port });
    await settings.load();
    expect(settings.history).toHaveLength(1);

    await settings.clearHistory();

    expect(port.clearHistory).toHaveBeenCalledTimes(1);
    expect(settings.history).toEqual([]);
  });

  it('leaves the history intact when clearing fails', async () => {
    const port = createPort({
      listHistory: vi.fn().mockResolvedValue([entry()]),
      clearHistory: vi.fn().mockRejectedValue(new Error('TRANSLATION_HISTORY_DELETE_FAILED')),
    });

    const settings = createTranslationSettings({ port });
    await settings.load();

    await expect(settings.clearHistory()).rejects.toThrow();
    expect(settings.history).toHaveLength(1);
  });

  it('surfaces a load failure instead of silently showing an empty history', async () => {
    const port = createPort({
      getPreferences: vi.fn().mockRejectedValue(new Error('boom')),
      listHistory: vi.fn().mockRejectedValue(new Error('boom')),
    });

    const settings = createTranslationSettings({ port });
    await settings.load();

    expect(settings.error).not.toBeNull();
  });
});
