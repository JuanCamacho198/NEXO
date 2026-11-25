/**
 * How a table-of-contents title is laid out. Books write their TOC however
 * they like — "1. En la línea de salida", "Primera parte. DEL EDÉN A
 * CAJAMARCA", "AGRADECIMIENTOS" — and printed as-is the list reads as noise.
 * This splits off what is structure (a number, a "Part one" label) so it can
 * be drawn as structure, and flags shouting capitals so they can be drawn as
 * small caps instead of rewritten (lowercasing would lose proper nouns).
 */
export type TocTitleParts = {
  /** "1", "IV": drawn in its own column. */
  number: string | null;
  /** "Primera parte", "Prólogo": drawn small above the title. */
  label: string | null;
  title: string;
  /** Mostly capitals: drawn as small caps. */
  shouting: boolean;
};

const NUMBERED = /^(\d{1,3}|[IVXLC]{1,6})[.)]\s+(.+)$/;
// A short label before a full stop. At least four characters, so an
// abbreviation ("Dr. No", "St. Ives") is never taken for one.
const LABELLED = /^([^.]{4,24})\.\s+(.+)$/;

export function isShouting(text: string): boolean {
  const letters = text.replace(/[^\p{L}]/gu, '');
  if (letters.length < 4) return false;
  const upper = letters.replace(/[^\p{Lu}]/gu, '').length;
  return upper / letters.length >= 0.8;
}

export function splitTocTitle(raw: string): TocTitleParts {
  const text = raw.trim();
  const numbered = NUMBERED.exec(text);
  if (numbered) {
    const title = numbered[2]!.trim();
    return { number: numbered[1]!, label: null, title, shouting: isShouting(title) };
  }
  const labelled = LABELLED.exec(text);
  if (labelled && labelled[1]!.trim().split(/\s+/).length <= 3) {
    const title = labelled[2]!.trim();
    return { number: null, label: labelled[1]!.trim(), title, shouting: isShouting(title) };
  }
  return { number: null, label: null, title: text, shouting: isShouting(text) };
}
