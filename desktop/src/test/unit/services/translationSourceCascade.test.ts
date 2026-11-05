/**
 * Source-language resolution cascade with local detection as the fallback.
 *
 * The book's `language` is optional in practice, and when it is missing the
 * translation used to be refused outright — the reader saw "This book has no
 * language. Choose a source language in Settings › Translation." even for a
 * selection whose language is obvious. The fallback is a LOCAL detection over
 * the selected text (`detectLanguage`), because MyMemory rejects `auto` as a
 * source language with a 403.
 *
 * The order is: book metadata, then detection, then `unknown-source`. The BOOK
 * WINS when it exists, even when it contradicts detection — its metadata is
 * declared, detection is a guess.
 */
import { describe, expect, it } from 'vitest';
import { resolveTranslationSourceWithDetection } from '$lib/shared/services/translation/translationLanguageResolution';

describe('resolveTranslationSourceWithDetection', () => {
  it('uses the book language when the book declares one', () => {
    expect(
      resolveTranslationSourceWithDetection(
        null,
        'fr',
        'The man is in the house with the dog and the cat of the neighbour.',
        'es',
      ),
    ).toEqual({ kind: 'translate', source: 'fr', target: 'es' });
  });

  it('falls back to local detection when the book carries no language', () => {
    expect(
      resolveTranslationSourceWithDetection(
        null,
        null,
        'The man is in the house with the dog and the cat of the neighbour.',
        'es',
      ),
    ).toEqual({ kind: 'translate', source: 'en', target: 'es' });
  });

  it('lets the BOOK win over a detection that contradicts it', () => {
    // The text is English, the metadata says Spanish. The metadata is declared,
    // the heuristic is a guess; a wrong guess is silently fluent, so the
    // declared value is the honest one to trust and the detection is ignored.
    expect(
      resolveTranslationSourceWithDetection(
        null,
        'es',
        'The man is in the house with the dog and the cat of the neighbour.',
        'en',
      ),
    ).toEqual({ kind: 'translate', source: 'es', target: 'en' });
  });

  it('refuses when neither the book nor detection can name the source', () => {
    expect(resolveTranslationSourceWithDetection(null, null, 'give up', 'en')).toEqual({
      kind: 'no-translation',
      reason: 'unknown-source',
    });
  });

  it('falls back to detection when the book language is garbage', () => {
    // `'  '` and `'1'` normalise to null; that is "the book said nothing", not a
    // hard refusal, so the detection step still gets its chance.
    const text = 'The man is in the house with the dog and the cat of the neighbour.';
    expect(resolveTranslationSourceWithDetection(null, '  ', text, 'es')).toEqual({
      kind: 'translate',
      source: 'en',
      target: 'es',
    });
    expect(resolveTranslationSourceWithDetection(null, '1', text, 'es')).toEqual({
      kind: 'translate',
      source: 'en',
      target: 'es',
    });
  });

  it('still refuses when source and target resolve to the same language', () => {
    // The detection must not bypass the same-language guard: detected `en`,
    // target `en` is still the 403 trap.
    expect(
      resolveTranslationSourceWithDetection(
        null,
        null,
        'The man is in the house with the dog and the cat of the neighbour.',
        'en',
      ),
    ).toEqual({ kind: 'no-translation', reason: 'same-language' });
  });
});
