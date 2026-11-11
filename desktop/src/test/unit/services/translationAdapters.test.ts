/**
 * Translation port adapters.
 *
 * The Tauri adapter must forward the payload to the Rust command unchanged
 * (the command takes `text`, `sourceLang`, `targetLang` as separate IPC args,
 * not a nested object). The mock adapter mirrors the command's rejection
 * contract so a caller cannot pass a test here and fail in the app.
 */
import { beforeEach, describe, expect, it, vi } from 'vitest';

vi.mock('$lib/shared/api/tauriClient', () => ({
  translateSelection: vi.fn(),
  getBookTranslationSource: vi.fn(),
  getBookTranslationSourceChoice: vi.fn(),
  saveBookTranslationSource: vi.fn(),
}));

const {
  translateSelection,
  getBookTranslationSource,
  getBookTranslationSourceChoice,
  saveBookTranslationSource,
} = await import('$lib/shared/api/tauriClient');
const { TauriTranslationAdapter } =
  await import('$lib/shared/ports/adapters/tauri/TauriTranslationAdapter');
const { MockTranslationAdapter } =
  await import('$lib/shared/ports/adapters/mock/MockTranslationAdapter');

describe('TauriTranslationAdapter', () => {
  beforeEach(() => {
    vi.mocked(translateSelection).mockReset();
  });

  it('forwards text and both languages to the translateSelection command', async () => {
    vi.mocked(translateSelection).mockResolvedValue('hello');
    const adapter = new TauriTranslationAdapter();

    const result = await adapter.translate({
      text: 'hola',
      sourceLang: 'es',
      targetLang: 'en',
    });

    expect(translateSelection).toHaveBeenCalledWith({
      text: 'hola',
      sourceLang: 'es',
      targetLang: 'en',
    });
    expect(result).toBe('hello');
  });

  it('propagates a rejection (untranslatable input, echo, quota)', async () => {
    vi.mocked(translateSelection).mockRejectedValue(
      new Error('TRANSLATE_QUOTA_EXCEEDED: quota exceeded'),
    );
    const adapter = new TauriTranslationAdapter();

    await expect(
      adapter.translate({ text: 'hola', sourceLang: 'es', targetLang: 'en' }),
    ).rejects.toThrow('TRANSLATE_QUOTA_EXCEEDED');
  });

  it('forwards the book id to the effective-source and own-choice reads', async () => {
    vi.mocked(getBookTranslationSource).mockResolvedValue('es');
    vi.mocked(getBookTranslationSourceChoice).mockResolvedValue({
      sourceLanguage: 'es',
      updatedAt: '1970-01-01T00:00:00Z',
    });
    const adapter = new TauriTranslationAdapter();

    await expect(adapter.getBookSourceLanguage('book-1')).resolves.toBe('es');
    await expect(adapter.getBookSourceChoice('book-1')).resolves.toEqual({
      sourceLanguage: 'es',
      updatedAt: '1970-01-01T00:00:00Z',
    });

    expect(getBookTranslationSource).toHaveBeenCalledWith('book-1');
    expect(getBookTranslationSourceChoice).toHaveBeenCalledWith('book-1');
  });

  it('forwards the book id and a null source to the save command', async () => {
    // `null` is a REAL value here: it clears the book's choice so the resolution
    // falls through to the account preference. Dropping the field instead would
    // send a different request than the one the reader asked for.
    vi.mocked(saveBookTranslationSource).mockResolvedValue({
      sourceLanguage: null,
      updatedAt: '1970-01-01T00:00:00Z',
    });
    const adapter = new TauriTranslationAdapter();

    await adapter.saveBookSourceLanguage({ bookId: 'book-1', sourceLanguage: null });

    expect(saveBookTranslationSource).toHaveBeenCalledWith({
      bookId: 'book-1',
      sourceLanguage: null,
    });
  });
});

describe('MockTranslationAdapter', () => {
  it('records calls and returns a deterministic answer by default', async () => {
    const adapter = new MockTranslationAdapter();

    const result = await adapter.translate({
      text: '  hola  ',
      sourceLang: 'es',
      targetLang: 'en',
    });

    expect(result).toBe('[en] hola');
    expect(adapter.calls).toHaveLength(1);
    expect(adapter.calls[0]).toEqual({ text: '  hola  ', sourceLang: 'es', targetLang: 'en' });
  });

  it('honours a pinned response and a pinned rejection', async () => {
    const adapter = new MockTranslationAdapter();
    adapter.setResponse('hola', 'hello');
    adapter.setResponse('bonjour', new Error('TRANSLATE_NO_TRANSLATION'));

    await expect(
      adapter.translate({ text: 'hola', sourceLang: 'es', targetLang: 'en' }),
    ).resolves.toBe('hello');
    await expect(
      adapter.translate({ text: 'bonjour', sourceLang: 'fr', targetLang: 'fr' }),
    ).rejects.toThrow('TRANSLATE_NO_TRANSLATION');
  });

  it('keeps the book choice and the account fallback as two separate answers', async () => {
    // The double must not collapse the two levels: "the reader chose this for
    // the book" and "the book inherited the account preference" are different
    // facts, and the UI reads exactly that difference to know whether a choice
    // is clearable for the book.
    const adapter = new MockTranslationAdapter();
    await adapter.savePreferences({ sourceLanguage: 'de', targetLanguage: 'en' });

    // No choice for this book -> the account fallback answers, but the book has
    // no OWN choice to report.
    await expect(adapter.getBookSourceLanguage('book-1')).resolves.toBe('de');
    await expect(adapter.getBookSourceChoice('book-1')).resolves.toBeNull();

    await adapter.saveBookSourceLanguage({ bookId: 'book-1', sourceLanguage: 'es' });
    await expect(adapter.getBookSourceLanguage('book-1')).resolves.toBe('es');
    await expect(adapter.getBookSourceChoice('book-1')).resolves.toMatchObject({
      sourceLanguage: 'es',
    });

    // Another book is unaffected: the choice is per book, not per account.
    await expect(adapter.getBookSourceLanguage('book-2')).resolves.toBe('de');
  });

  it('clears the book choice with a null write, falling back to the account', async () => {
    const adapter = new MockTranslationAdapter();
    await adapter.savePreferences({ sourceLanguage: 'de', targetLanguage: 'en' });
    await adapter.saveBookSourceLanguage({ bookId: 'book-1', sourceLanguage: 'es' });

    const cleared = await adapter.saveBookSourceLanguage({
      bookId: 'book-1',
      sourceLanguage: null,
    });

    expect(cleared.sourceLanguage).toBeNull();
    await expect(adapter.getBookSourceLanguage('book-1')).resolves.toBe('de');
    // The clear is a real write, not a deletion: the choice is now explicitly
    // absent rather than never having been asked about, and either way the
    // cascade falls through.
    await expect(adapter.getBookSourceChoice('book-1')).resolves.toMatchObject({
      sourceLanguage: null,
    });
  });
});
