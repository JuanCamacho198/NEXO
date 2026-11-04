/**
 * The language a "Definir" result is written in (D6).
 *
 * Requirement 4 of the slice is "the definition language is USED, not only
 * stored", and this is the function the reader spends the preference through.
 * If it fell back to the locale unconditionally, every test below except the
 * first would fail — which is the point: storing a preference that the reader
 * ignores is the failure this pins.
 */
import { describe, expect, it } from 'vitest';
import {
  FALLBACK_DEFINITION_LANGUAGE,
  resolveDefinitionLanguage,
} from '$lib/shared/services/translation/definitionLanguage';

describe('resolveDefinitionLanguage', () => {
  it('uses the saved preference when there is one, over the app locale', () => {
    // The app is in Spanish and the reader chose French definitions: the
    // definition must be written in French, not Spanish.
    expect(resolveDefinitionLanguage('fr', 'es')).toBe('fr');
  });

  it('falls back to the app locale when no preference was chosen', () => {
    // `null` is "the app locale" — the behaviour that predates the preference,
    // so an account that never picked one is unaffected.
    expect(resolveDefinitionLanguage(null, 'es')).toBe('es');
  });

  it('handles an undefined preference the same as null', () => {
    expect(resolveDefinitionLanguage(undefined, 'en')).toBe('en');
  });

  it('normalises a region-shaped preference to its primary subtag', () => {
    // The backend builds `{lang}.wiktionary.org` out of this, and a region tag
    // is a different host (or a 404).
    expect(resolveDefinitionLanguage('fr-CA', 'es')).toBe('fr');
  });

  it('normalises a region-shaped locale the same way', () => {
    expect(resolveDefinitionLanguage(null, 'es-MX')).toBe('es');
  });

  it('is case-insensitive on both inputs', () => {
    expect(resolveDefinitionLanguage('FR', 'ES')).toBe('fr');
    expect(resolveDefinitionLanguage(null, 'ES')).toBe('es');
  });

  /**
   * Last resort only. Both sources unusable is the "no locale, no preference"
   * case, and `en` is the measured worst case the cascade already assumes for an
   * unlabelled book (see `commands/define.rs`) — an empty string here would make
   * the command trim to `en` anyway, so the two agree.
   */
  it('falls back to the measured default when neither side is usable', () => {
    expect(resolveDefinitionLanguage(null, null)).toBe(FALLBACK_DEFINITION_LANGUAGE);
    expect(resolveDefinitionLanguage('   ', '')).toBe(FALLBACK_DEFINITION_LANGUAGE);
  });

  it('ignores a preference that is not a usable language code', () => {
    // A stored `'1'` or `'--'` is a set-but-unusable preference: the app locale
    // is a better answer than a code no wiki can serve.
    expect(resolveDefinitionLanguage('1', 'es')).toBe('es');
  });
});
