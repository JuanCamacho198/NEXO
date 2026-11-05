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
 * Function words (articles, prepositions, conjunctions, common verbs) are the
 * cheapest honest signal: they are high-frequency, they are language-specific,
 * and a longer text hits several of them. The lists are deliberately SHORT —
 * every marker that is shared between languages (`la`, `de`, `un`, `o` in
 * Spanish/Italian/Portuguese) makes a tie more likely, and a tie is a refusal.
 */
const LANGUAGE_MARKERS: ReadonlyArray<readonly [string, readonly string[]]> = [
  ['en', ['the', 'and', 'of', 'to', 'is', 'that', 'with']],
  ['es', ['el', 'la', 'de', 'que', 'los', 'una', 'por', 'para']],
  ['fr', ['le', 'la', 'les', 'des', 'une', 'est', 'pour']],
  ['de', ['der', 'die', 'das', 'und', 'ist', 'nicht', 'mit']],
  ['it', ['il', 'la', 'di', 'che', 'un', 'per']],
  ['pt', ['o', 'a', 'de', 'que', 'um', 'para']],
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
