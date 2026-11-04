import { describe, expect, it, vi } from 'vitest';
import { MockDictionaryLookupAdapter } from '$lib/shared/ports/adapters/mock/MockDictionaryLookupAdapter';
import type { DefinitionResult } from '$lib/shared/ports/DictionaryLookupPort';

// The double must mirror the Rust command's CONTRACT, not the network. The
// contract that matters most: a miss is an empty result with `source: null`,
// never a rejection. A test that passes on a rejection would pass on a shape
// the app never produces.

const miss: DefinitionResult = {
  word: 'qqqzzzz',
  definitions: [],
  source: null,
  attribution: 'Wiktionary, CC BY-SA 4.0 (https://www.wiktionary.org/)',
};

describe('MockDictionaryLookupAdapter', () => {
  it('returns an empty result — not a rejection — on a miss', async () => {
    const adapter = new MockDictionaryLookupAdapter();
    adapter.setMiss('qqqzzzz');

    const result = await adapter.define({
      text: 'qqqzzzz',
      readerLang: 'es',
      wordLang: 'en',
    });

    expect(result.definitions).toEqual([]);
    expect(result.source).toBeNull();
    expect(result.attribution).toContain('CC BY-SA');
  });

  it('pins an explicit source for the UI warning contract', async () => {
    const adapter = new MockDictionaryLookupAdapter();
    adapter.setResult('juxtaposition', 'translated', [
      { text: 'La acción de yuxtaponer.', partOfSpeech: 'sustantivo' },
    ]);

    const result = await adapter.define({
      text: 'juxtaposition',
      readerLang: 'es',
      wordLang: 'en',
    });

    expect(result.source).toBe('translated');
    expect(result.definitions[0].partOfSpeech).toBe('sustantivo');
  });

  it('records every call so wiring is assertable', async () => {
    const adapter = new MockDictionaryLookupAdapter();

    await adapter.define({ text: 'house', readerLang: 'es', wordLang: 'en' });

    expect(adapter.calls).toEqual([{ text: 'house', readerLang: 'es', wordLang: 'en' }]);
  });

  it('forwards a null wordLang rather than inventing one', async () => {
    const adapter = new MockDictionaryLookupAdapter();

    await adapter.define({ text: 'house', readerLang: 'es', wordLang: null });

    expect(adapter.calls[0].wordLang).toBeNull();
  });

  it('rejects when a rejection was pinned, so a failure path is testable', async () => {
    const adapter = new MockDictionaryLookupAdapter();
    adapter.setResponse('house', new Error('DEFINE_NETWORK: wiktionary request failed'));

    await expect(
      adapter.define({ text: 'house', readerLang: 'es', wordLang: 'en' }),
    ).rejects.toThrow('DEFINE_NETWORK');
  });

  it('always carries the real attribution string, not a placeholder', async () => {
    const adapter = new MockDictionaryLookupAdapter();

    const result = await adapter.define({ text: 'house', readerLang: 'es', wordLang: 'en' });

    expect(result.attribution).toBe('Wiktionary, CC BY-SA 4.0 (https://www.wiktionary.org/)');
  });
});

describe('TauriDictionaryLookupAdapter', () => {
  it('forwards the payload verbatim to the defineSelection command', async () => {
    const defineSelection = vi.fn(async () => miss);
    vi.doMock('$lib/shared/api/tauriClient', () => ({ defineSelection }));
    vi.resetModules();

    const { TauriDictionaryLookupAdapter } =
      await import('$lib/shared/ports/adapters/tauri/TauriDictionaryLookupAdapter');
    const result = await new TauriDictionaryLookupAdapter().define({
      text: 'give up',
      readerLang: 'es',
      wordLang: 'en',
    });

    expect(defineSelection).toHaveBeenCalledWith({
      text: 'give up',
      readerLang: 'es',
      wordLang: 'en',
    });
    expect(result.source).toBeNull();

    vi.doUnmock('$lib/shared/api/tauriClient');
  });
});
