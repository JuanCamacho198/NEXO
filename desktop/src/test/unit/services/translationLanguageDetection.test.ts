/**
 * Local language detection for a reader selection.
 *
 * MyMemory does NOT support `auto` as a source language: measured,
 * `langpair=auto|es` answers `403 "'AUTO' IS AN INVALID SOURCE LANGUAGE..."`.
 * Detection therefore has to be ours and local. It is a guess, and the whole
 * point of this suite is the ways it REFUSES to guess: guessing wrong
 * translates between the wrong languages and the reader does not notice.
 */
import { describe, expect, it } from 'vitest';
import { detectLanguage } from '$lib/shared/services/translation/translationLanguageDetection';

describe('detectLanguage', () => {
  it('detects a clear English sentence', () => {
    expect(detectLanguage('The wind was strong and the boat was safe at sea.')).toBe('en');
  });

  it('detects a clear Spanish sentence', () => {
    expect(detectLanguage('El hombre llegó a la casa de su madre por la tarde.')).toBe('es');
  });

  it('detects a clear French sentence', () => {
    expect(detectLanguage('Les enfants sont dans la maison des voisins pour le repas.')).toBe('fr');
  });

  it('detects a clear German sentence', () => {
    expect(detectLanguage('Der Mann ist nicht mit dem Boot auf das Meer gefahren.')).toBe('de');
  });

  it('detects a clear Italian sentence', () => {
    expect(detectLanguage('Il ragazzo è andato di casa per la strada nuova.')).toBe('it');
  });

  it('detects a clear Portuguese sentence', () => {
    expect(detectLanguage('O homem foi para a casa da sua mãe com um amigo.')).toBe('pt');
  });

  it('refuses to guess from a two-word phrasal verb', () => {
    // "give up" has no function word to count — and a two-word guess is not
    // evidence of a language under any circumstances.
    expect(detectLanguage('give up')).toBeNull();
  });

  it('refuses to guess on a tie', () => {
    // "de" and "la" are markers in BOTH Spanish and French (and Italian), so
    // this two-marker text scores the same for several languages. Not knowing
    // beats picking one of them.
    expect(detectLanguage('la de')).toBeNull();
  });

  it('refuses an empty or whitespace-only text', () => {
    expect(detectLanguage('')).toBeNull();
    expect(detectLanguage('     ')).toBeNull();
  });

  it('refuses punctuation-only text', () => {
    expect(detectLanguage('...')).toBeNull();
    expect(detectLanguage(' ,;:!? ')).toBeNull();
  });

  it('refuses numbers-only text', () => {
    expect(detectLanguage('12345')).toBeNull();
  });

  it('picks the winner with a wide margin on a long clear text', () => {
    const text =
      'The old fisherman said that the sea is not a place of rest and that the boat ' +
      'is the only home he has with the wind that pushes it to the shore of the island.';
    expect(detectLanguage(text)).toBe('en');
  });
});
