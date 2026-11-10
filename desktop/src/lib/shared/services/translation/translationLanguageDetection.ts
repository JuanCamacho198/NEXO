/**
 * Local language detection for a reader selection.
 *
 * The source language of a translation has one reliable source — the book's
 * `language` metadata — and in practice that field is often missing (`books.
 * language` is nullable, `sql_text_opt`). Asking the service to infer it is not
 * an option: MyMemory answers `langpair=auto|es` with
 * `403 "'AUTO' IS AN INVALID SOURCE LANGUAGE..."`, measured twice. So the
 * fallback is a local, offline guess.
 *
 * The guess is deliberately weak, and that is the design: below four word tokens
 * it returns `null`. Guessing WRONG is worse than not guessing: a wrong
 * source translates between the wrong two languages and the reader cannot tell,
 * because the answer is still fluent text. There is no user-visible signal to
 * correct, so the honest behaviour is to refuse and let the panel ask.
 */

/**
 * Function-word markers per supported language.
 *
 * Function words (articles, prepositions, conjunctions, common verb forms) are
 * the cheapest honest signal: they are high-frequency, they are
 * language-specific, and a longer text hits several of them.
 *
 * INVARIANT — every marker belongs to EXACTLY ONE language. A word that is
 * shared between two lists (`la`, `de`, `un`, `o`, `que`, `para`) scores for
 * both, so real selections can MATCH and the detector refuses by design. That
 * is what made the previous lists unusable: a Spanish sentence scored the same
 * as its Italian twin and the answer was always `null`. A marker that cannot
 * separate the supported languages carries no information, so it is not a
 * marker — it is noise. Keep every word exclusive to one list.
 *
 * The lists are deliberately SHORT and roughly the same size, so no language
 * collects a systematic advantage from list length. Genuinely ambiguous text —
 * no marker hits at all, or an exact tie between the best two languages — still
 * refuses: not guessing beats guessing wrong.
 */
const LANGUAGE_MARKERS: ReadonlyArray<readonly [string, readonly string[]]> = [
  ['en', ['the', 'and', 'of', 'with', 'this', 'there']],
  ['es', ['un', 'la', 'y', 'con', 'del', 'pero', 'puede']],
  ['fr', ['et', 'les', 'dans', 'avec', 'pour', 'cette']],
  ['de', ['und', 'ist', 'nicht', 'mit', 'der', 'die', 'das']],
  ['it', ['il', 'di', 'per', 'che', 'questo', 'sono']],
  ['pt', ['voce', 'nao', 'com', 'para', 'muito', 'onde']],
];

/**
 * Minimum number of word tokens before a guess is allowed.
 *
 * Four `is` below the length of the shortest text that reliably carries two or
 * three distinct function-word hits in one language. Below it the sample is a
 * short selection — a highlighted phrase, a phrasal verb — where a single
 * coincidental marker would decide the answer.
 */
const MIN_WORDS = 4;

/**
 * Infers the ISO 639-1 code of `text`, or `null` when the evidence is too thin
 * to be trusted.
 *
 * Returns `null` for: fewer than {@link MIN_WORDS} word tokens, no marker hits
 * at all, a tie between the two best-scoring languages, and any text with no
 * word tokens (empty, whitespace, punctuation-only, numbers-only).
 *
 * @returns A language code from {@link LANGUAGE_MARKERS}, or `null`.
 */
export function detectLanguage(text: string): string | null {
  const words = tokenize(text);
  if (words.length < MIN_WORDS) return null;

  const scores = LANGUAGE_MARKERS.map(([code, markers]) => ({
    code,
    score: countMarkerHits(words, markers),
  })).sort((a, b) => b.score - a.score);

  const best = scores[0];
  const second = scores[1];
  if (!best || best.score === 0) return null;
  if (second && second.score === best.score) return null;
  return best.code;
}

function tokenize(text: string): string[] {
  return text
    .toLowerCase()
    .normalize('NFD')
    .replace(/[\u0300-\u036f]/g, '')
    .split(/[^a-z]+/)
    .filter((word) => word.length > 0);
}

function countMarkerHits(words: readonly string[], markers: readonly string[]): number {
  const markerSet = new Set(markers);
  let hits = 0;
  for (const word of words) {
    if (markerSet.has(word)) hits += 1;
  }
  return hits;
}
