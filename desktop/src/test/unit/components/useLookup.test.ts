import { describe, expect, it, vi } from 'vitest';
import { createLookup } from '$lib/features/reader/highlight/useLookup.svelte';
import type { TranslationOutcome } from '$lib/shared/ports/TranslationPort';
import type { DefinitionResult } from '$lib/shared/ports/DictionaryLookupPort';

const translated: TranslationOutcome = {
  status: 'ok',
  text: 'hola',
  source: 'en',
  target: 'es',
  sourceOrigin: 'book',
};
const defined: DefinitionResult = {
  word: 'hello',
  definitions: [{ text: 'a greeting', partOfSpeech: null }],
  source: 'native',
  attribution: 'Wiktionary',
};

function setup(overrides: { canDefine?: boolean; canTranslate?: boolean } = {}) {
  const translate = vi.fn(async () => translated);
  const define = vi.fn(async () => defined);
  const lookup = createLookup({
    translate,
    define,
    changeLanguages: vi.fn(async () => undefined),
    canTranslate: () => overrides.canTranslate ?? true,
    canDefine: () => overrides.canDefine ?? true,
  });
  return { lookup, translate, define };
}

describe('createLookup — translate and define as one connected panel', () => {
  it('opens on a tab, fetching that side', async () => {
    const { lookup, translate, define } = setup();
    lookup.toggle('translate');
    expect(lookup.tab).toBe('translate');
    await vi.waitFor(() => expect(lookup.translation).toEqual(translated));
    expect(translate).toHaveBeenCalledOnce();
    expect(define).not.toHaveBeenCalled();
  });

  it('moves between the tabs, fetching each side once and keeping both', async () => {
    const { lookup, translate, define } = setup();
    lookup.toggle('translate');
    await vi.waitFor(() => expect(lookup.translation).not.toBeNull());

    lookup.select('define');
    expect(lookup.tab).toBe('define');
    await vi.waitFor(() => expect(lookup.definition).toEqual(defined));

    lookup.select('translate');
    lookup.select('define');
    expect(translate).toHaveBeenCalledOnce();
    expect(define).toHaveBeenCalledOnce();
    expect(lookup.translation).toEqual(translated);
  });

  it('closes when the shown tab is toggled again, and the other button switches', async () => {
    const { lookup } = setup();
    lookup.toggle('translate');
    lookup.toggle('define');
    expect(lookup.tab).toBe('define');
    lookup.toggle('define');
    expect(lookup.open).toBe(false);
    expect(lookup.translation).toBeNull();
    expect(lookup.definition).toBeNull();
  });

  it('drops an answer that lands after the panel was closed', async () => {
    let resolve!: (value: TranslationOutcome) => void;
    const lookup = createLookup({
      translate: () => new Promise((r) => (resolve = r)),
      define: async () => defined,
      changeLanguages: async () => undefined,
      canTranslate: () => true,
      canDefine: () => true,
    });
    lookup.toggle('translate');
    lookup.close();
    resolve(translated);
    await Promise.resolve();
    expect(lookup.translation).toBeNull();
    expect(lookup.translating).toBe(false);
  });

  it('never opens Define outside its range, nor anything without text', () => {
    const noDefine = setup({ canDefine: false });
    noDefine.lookup.toggle('define');
    expect(noDefine.lookup.open).toBe(false);

    const noText = setup({ canTranslate: false });
    noText.lookup.toggle('translate');
    expect(noText.lookup.open).toBe(false);
  });
});
