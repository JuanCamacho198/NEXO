/**
 * Dictionary lookup port — the "Definir" transport.
 *
 * The Rust `defineSelection` command runs a three-step cascade (native
 * Wiktionary → en.wiktionary → machine translation) behind a per-account cache.
 * This port is the frontend's only door to it. Nothing is cached here: the
 * cache lives in Rust, keyed on the active account, and it is deliberately NOT
 * mirrored in the frontend — a second copy would be a second truth about
 * whether a definition is still valid.
 *
 * The port is transport-only. It does not decide WHETHER a selection is worth
 * looking up (that is `definitionSelectionGuard`, a pure predicate the caller
 * applies first) nor which language the word is in (that comes from the book).
 * It carries the three inputs the command needs and returns what it said.
 */

/** Where a definition came from, which decides how much the reader may trust it. */
export type DefinitionSource = 'native' | 'translated';

/** One definition line, with its part of speech when the source carried one. */
export interface DefinitionItem {
  text: string;
  partOfSpeech: string | null;
}

/** The whole result of a "Definir" call, mirroring `DefinitionResult` in Rust. */
export interface DefinitionResult {
  word: string;
  /**
   * Empty ONLY when every source missed. That is not an error — it is the
   * honest "no definition", and the caller renders it as such.
   */
  definitions: DefinitionItem[];
  /**
   * `null` when nothing was found, otherwise `native` (a human wrote the gloss
   * in the reader's language) or `translated` (a machine translated an English
   * gloss). The reader MUST be told when this is `translated`: a machine
   * translation is usually right and wrong in ways the reader cannot see.
   */
  source: DefinitionSource | null;
  /**
   * CC BY-SA 4.0 attribution. Always present, including on an empty result,
   * because the license requires it accompany the reused text and the UI
   * cannot be trusted to reconstruct it.
   */
  attribution: string;
}

export interface DictionaryLookupPort {
  /**
   * Looks up a definition for `text`.
   *
   * `readerLang` is the language the definition should be written in (the UI
   * locale). `wordLang` is the language the selected word is in — the backend
   * uses it to pick the right Wiktionary section, and `null` when the book
   * carries no language (the backend then skips the native step and assumes
   * `en`; see `SourceLanguage` in `commands/define.rs`).
   *
   * Resolves with an empty `definitions` array when no source has anything.
   * Rejects only on a transport/storage failure.
   */
  define(input: {
    text: string;
    readerLang: string;
    wordLang: string | null;
  }): Promise<DefinitionResult>;
}
