import { describe, expect, it } from 'vitest';
import {
  MAX_DEFINITION_WORDS,
  countWords,
  isDefinableSelection,
} from '$lib/shared/services/definition/definitionSelectionGuard';

// The C+A limit. The predicate decides the "Definir" button's `disabled` state,
// so every case here is a case the reader can reach by dragging a selection.
// The set is deliberately adversarial: each rejection has a REASON, and the
// reason is the test's name.

describe('isDefinableSelection — accepted selections', () => {
  it('accepts a single word', () => {
    expect(isDefinableSelection('house')).toBe(true);
  });

  it('accepts a phrasal verb', () => {
    // The case that justifies MAX_DEFINITION_WORDS > 1: a regex detector would
    // have to know this is a phrasal verb; the count does not care.
    expect(isDefinableSelection('give up')).toBe(true);
  });

  it('accepts a four-word collocation', () => {
    expect(isDefinableSelection('make up your mind')).toBe(true);
  });

  it('accepts a five-word idiom — the boundary itself', () => {
    expect(isDefinableSelection('a piece of cake here')).toBe(true);
  });

  it('accepts a word with an apostrophe', () => {
    expect(isDefinableSelection("don't")).toBe(true);
  });

  it('accepts a hyphenated word', () => {
    expect(isDefinableSelection('well-being')).toBe(true);
  });

  it('accepts a comma-bearing phrase — a parenthetical is still worth looking up', () => {
    expect(isDefinableSelection('bread, stale')).toBe(true);
  });

  it('accepts non-Latin scripts', () => {
    expect(isDefinableSelection('casa')).toBe(true);
    expect(isDefinableSelection('家')).toBe(true);
    expect(isDefinableSelection('дом')).toBe(true);
  });

  it('trims before measuring, so a padded selection is still accepted', () => {
    expect(isDefinableSelection('  give up  ')).toBe(true);
  });
});

describe('isDefinableSelection — rejections, each for its own reason', () => {
  it('rejects an empty or whitespace-only selection', () => {
    expect(isDefinableSelection('')).toBe(false);
    expect(isDefinableSelection('   ')).toBe(false);
    expect(isDefinableSelection('\t\n')).toBe(false);
  });

  it('rejects a selection with no letters — there is nothing to look up', () => {
    // DECISION, as required by the task: `"123"` is REJECTED. Wiktionary has no
    // numeric page, so the cascade would spend three requests to answer "no
    // definition". Refusing here is one comparison instead.
    expect(isDefinableSelection('123')).toBe(false);
    expect(isDefinableSelection('...')).toBe(false);
    expect(isDefinableSelection('—')).toBe(false);
    expect(isDefinableSelection('42.5')).toBe(false);
  });

  it('rejects a single punctuation mark — the literal "." case', () => {
    expect(isDefinableSelection('.')).toBe(false);
  });

  it('rejects an eight-word phrase — too long to be a lexical unit', () => {
    expect(isDefinableSelection('this is a much longer phrase of eight words')).toBe(false);
  });

  it('rejects at the first word past the boundary', () => {
    expect(isDefinableSelection('one two three four five six')).toBe(false);
  });

  it('rejects a sentence ending in a full stop', () => {
    expect(isDefinableSelection('the house is big.')).toBe(false);
  });

  it('rejects an interrogative', () => {
    expect(isDefinableSelection('what does this mean?')).toBe(false);
  });

  it('rejects an exclamation', () => {
    expect(isDefinableSelection('give up!')).toBe(false);
  });

  it('rejects a Spanish interrogative — inverted marks count', () => {
    expect(isDefinableSelection('¿qué significa?')).toBe(false);
  });

  it('rejects a clause separated by a semicolon or colon', () => {
    expect(isDefinableSelection('first; second')).toBe(false);
    expect(isDefinableSelection('note: this')).toBe(false);
  });

  it('rejects a multi-line selection', () => {
    expect(isDefinableSelection('give\nup')).toBe(false);
  });

  it('rejects a real paragraph, whatever its punctuation', () => {
    expect(
      isDefinableSelection(
        'The quick brown fox jumps over the lazy dog and keeps running well into the night',
      ),
    ).toBe(false);
  });

  it('rejects null and undefined without throwing', () => {
    expect(isDefinableSelection(null)).toBe(false);
    expect(isDefinableSelection(undefined)).toBe(false);
  });
});

describe('countWords', () => {
  it('counts whitespace-separated words', () => {
    expect(countWords('give up')).toBe(2);
    expect(countWords('  give   up  ')).toBe(2);
  });

  it('counts a whitespace-only string as zero', () => {
    expect(countWords('   ')).toBe(0);
  });

  it('counts an unspaced CJK sentence as one, which is the useful answer here', () => {
    expect(countWords('家')).toBe(1);
  });
});

describe('MAX_DEFINITION_WORDS', () => {
  it('is five: phrasal verbs, collocations and idioms in; paragraphs out', () => {
    expect(MAX_DEFINITION_WORDS).toBe(5);
  });

  it('agrees with the predicate at every word count around the boundary', () => {
    for (let n = 1; n <= 8; n += 1) {
      const phrase = Array.from({ length: n }, (_, i) => `w${i}`).join(' ');
      expect(isDefinableSelection(phrase)).toBe(n <= MAX_DEFINITION_WORDS);
    }
  });
});
