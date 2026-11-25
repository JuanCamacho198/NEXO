/**
 * The chapter a book was last left on, as the reader's bottom bar named it.
 * Nothing else stores it (progress keeps a CFI, not a title), so the reader
 * records it here and the Continue Reading hero reads it back. Per device, in
 * browser storage: losing it only hides one line on Home.
 */
// v2: every v1 value was written while the reader mapped a chapter's later
// documents to the TOC's first entry, so books read mid-chapter were stored as
// "Portada". Those titles are wrong and unrecoverable; a new key drops them and
// the hero shows no chapter until the book is opened again.
const STORAGE_KEY = 'nexo.lastChapter.v2';
const MAX_TITLE_LENGTH = 120;

function readAll(): Record<string, string> {
  try {
    const raw = localStorage.getItem(STORAGE_KEY);
    const parsed: unknown = raw ? JSON.parse(raw) : null;
    return parsed && typeof parsed === 'object' ? (parsed as Record<string, string>) : {};
  } catch {
    return {};
  }
}

export function saveLastChapter(bookId: string, title: string): void {
  const trimmed = title.trim().slice(0, MAX_TITLE_LENGTH);
  if (!bookId || !trimmed) return;
  try {
    const all = readAll();
    if (all[bookId] === trimmed) return;
    all[bookId] = trimmed;
    localStorage.setItem(STORAGE_KEY, JSON.stringify(all));
  } catch {
    // Storage unavailable: the hero simply shows no chapter line.
  }
}

export function getLastChapter(bookId: string): string | null {
  const value = readAll()[bookId];
  return typeof value === 'string' && value.trim() ? value : null;
}
