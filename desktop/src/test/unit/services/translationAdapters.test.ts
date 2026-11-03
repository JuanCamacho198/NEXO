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
}));

const { translateSelection } = await import('$lib/shared/api/tauriClient');
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
});
