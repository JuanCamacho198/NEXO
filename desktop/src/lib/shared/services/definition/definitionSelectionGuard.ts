/**
 * Decides whether a selection is a "Definir" candidate.
 *
 * This is the C+A limit: a selection is definable when it is short enough to be
 * a lexical unit and contains no sentence punctuation. It is a PURE predicate —
 * no I/O, no state, no locale — so the toolbar can derive the button's
 * `disabled` state from it and the tests can pin every boundary case without
 * rendering anything.
 *
 * WHY A WORD COUNT AND NOT A REGEX FOR PHRASAL VERBS
 * --------------------------------------------------
 * The obvious design is "detect a phrasal verb / collocation and enable the
 * button only for those". It is the wrong one:
 *
 *   1. It is language-specific by construction. `give up` is a phrasal verb in
 *      English; Spanish separates `darse por vencido` with arbitrary words in
 *      between, and the regexes that try to cover both end up as a pile of
 *      exceptions that fail on the next language.
 *   2. The user already KNOWS what they selected. Re-deriving their intent from
 *      the string means the button disagrees with the reader about their own
 *      selection — the worst kind of false negative, because the reader cannot
 *      see why it is disabled.
 *
 * So the limit is a generous upper bound on "this is a phrase, not a paragraph"
 * and nothing more. It ADMITS the things a regex would try to detect, and
 * rejects only the things that are unambiguously not a lexical lookup.
 *
 * WHY THE REJECTED THINGS ARE REJECTED
 * ------------------------------------
 * - No letters (`"123"`, `"..."`, `"—"`): Wiktionary has no entry to look up.
 *   Sending it is a guaranteed wasted network round-trip, so it is refused
 *   here rather than by the backend.
 * - Too many words: a paragraph is not a definition candidate. The cascade
 *   would send the whole paragraph as a page title, get a miss, walk all three
 *   steps and return nothing — three requests to answer "no".
 * - Strong punctuation (`. ! ? ; :` terminator/interrogative/separator marks,
 *   plus a paragraph break): the selection is a SENTENCE. A sentence has no
 *   single definition, and the backend would look up the literal sentence as a
 *   Wiktionary page title and miss.
 *
 * Note what is deliberately NOT rejected: hyphens and apostrophes (`well-being`,
 * `don't`), which are part of a single word; commas and parentheses, which a
 * careful selection of a parenthetical gloss often carries; and CJK text, which
 * has no spaces at all and is therefore a single "word" under the counting rule
 * below — exactly the right answer for a language where one character is a word.
 */

/**
 * The maximum number of whitespace-separated words a selection may have.
 *
 * Five, chosen to cover the longest lexical unit a reader plausibly selects:
 *
 * - a single word (`house`) — 1
 * - a phrasal verb (`give up`) — 2
 * - a collocation (`make up your mind`, `take into account`) — 3-4
 * - a full idiom (`a piece of cake`, `when pigs fly`) — 4-5
 *
 * and to exclude a sentence fragment (6+) and a paragraph. It is a soft
 * boundary on purpose: 5 is not a linguistic fact, it is the point past which
 * "the reader meant a phrase" stops being the safe assumption. A selection of
 * six words that IS an idiom is refused, and that is an accepted cost — the
 * alternative is admitting paragraphs and paying three network requests to
 * learn there is no definition.
 */
export const MAX_DEFINITION_WORDS = 5;

/**
 * Punctuation that marks the selection as a sentence rather than a lexical unit.
 *
 * Sentence terminators (`. ! ?`), the Spanish inverted marks (`¡ ¿`), clause
 * separators (`; :`), and explicit line breaks. Deliberately excluded: `,`
 * (a selected parenthetical or appositive is still worth looking up), `-` and
 * `'` (word-internal), `"` and `(` `)` (a quoted or glossed word is still a
 * word — the lookup just misses, which is a cheap, honest outcome).
 */
const STRONG_PUNCTUATION = /[.!?;:¡¿\n\r\u2026]/;

/** `\p{L}` covers every script's letters, not just Latin: `casa`, `家`, `дом`. */
const HAS_LETTER = /\p{L}/u;

/**
 * True when `raw` is a selection worth defining.
 *
 * Pure and total: every input returns a boolean, no input throws.
 */
export function isDefinableSelection(raw: string | null | undefined): boolean {
  if (typeof raw !== 'string') return false;

  const trimmed = raw.trim();
  if (trimmed.length === 0) return false;
  if (!HAS_LETTER.test(trimmed)) return false;
  if (STRONG_PUNCTUATION.test(trimmed)) return false;

  return countWords(trimmed) <= MAX_DEFINITION_WORDS;
}

/**
 * Counts whitespace-separated words.
 *
 * Whitespace-only input counts as zero. CJK text without spaces counts as one,
 * which is correct for purposes of this limit (a CJK sentence is as short to
 * type as a single English word, and Wiktionary does carry CJK entries).
 */
export function countWords(raw: string): number {
  const trimmed = raw.trim();
  if (trimmed.length === 0) return 0;
  return trimmed.split(/\s+/).length;
}
