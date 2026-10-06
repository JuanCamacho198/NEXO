import type { HighlightDto, LibraryBookDto } from '$lib/shared/types';
import type { MessageKey } from '$lib/shared/i18n';
import type { ViewerPort } from '$lib/shared/ports';
import type { HighlightsViewDeps } from './highlightsViewDeps';
import {
  HIGHLIGHT_COLORS as CANONICAL_HIGHLIGHT_COLORS,
  nearestHighlightHex,
  type HighlightColorId,
} from '$lib/features/reader/highlight/highlightColors';

export type Props = {
  books: LibraryBookDto[];
  t: (key: MessageKey, params?: Record<string, string | number>) => string;
  viewerPort?: ViewerPort;
  deps?: HighlightsViewDeps;
};

export const PAGE_SIZE = 6;

/**
 * Filter-chip palette for the library screen, derived from the canonical
 * reader palette so both menus always offer exactly the same five
 * Android-canonical colors (spec HPU-1).
 */
export const HIGHLIGHT_COLORS: ReadonlyArray<{ key: HighlightColorId; hex: string }> =
  CANONICAL_HIGHLIGHT_COLORS.map((color) => ({ key: color.label, hex: color.hex.toLowerCase() }));

export type HighlightColorKey = HighlightColorId;

/**
 * Resolve a stored highlight color (hex like `#FACC15`, a legacy hex like
 * `#60A5FA`, or a legacy key like `yellow`) to its display hex. The reader
 * persists hex values, while older rows may hold keys, so both forms are
 * matched case-insensitively first; anything else goes through the shared
 * nearest-RGB resolver, which pins legacy hexes to their canonical targets
 * and falls back to canonical yellow for unknown values (spec HPU-2).
 */
export function resolveHighlightHex(color: string): string {
  const normalized = color.trim().toLowerCase();
  const keyMatch = HIGHLIGHT_COLORS.find((c) => c.key === normalized);
  if (keyMatch) return keyMatch.hex;
  return nearestHighlightHex(normalized).toLowerCase();
}

export function formatDate(iso: string): string {
  const d = new Date(iso);
  return (
    d.toLocaleDateString('es-ES', {
      day: 'numeric',
      month: 'short',
      year: 'numeric',
    }) +
    ' — ' +
    d.toLocaleTimeString('es-ES', {
      hour: 'numeric',
      minute: '2-digit',
      hour12: true,
    })
  );
}

function getDateCutoff(selectedDateRange: string | null): Date {
  const now = new Date();
  if (selectedDateRange === '7d') return new Date(now.getTime() - 7 * 86400000);
  if (selectedDateRange === '30d') return new Date(now.getTime() - 30 * 86400000);
  if (selectedDateRange === '90d') return new Date(now.getTime() - 90 * 86400000);
  return new Date(0);
}

export function filterHighlights(
  highlights: HighlightDto[],
  searchQuery: string,
  selectedColor: string | null,
  selectedBookId: string | null,
  selectedDateRange: string | null,
  bookMap: Map<string, LibraryBookDto>,
): HighlightDto[] {
  let result = highlights;

  if (searchQuery.trim().length > 0) {
    const q = searchQuery.toLowerCase();
    result = result.filter((h) => {
      const book = bookMap.get(h.bookId);
      return (
        h.text.toLowerCase().includes(q) ||
        (h.note && h.note.toLowerCase().includes(q)) ||
        (book && book.title.toLowerCase().includes(q)) ||
        (book && book.author.toLowerCase().includes(q))
      );
    });
  }

  if (selectedColor) {
    // Stored colors are canonical or legacy hexes (reader/Android/Supabase);
    // filter chips use keys. Resolve each stored color through the shared
    // nearest-RGB resolver so legacy rows keep matching their pinned
    // canonical chip, and match legacy key rows directly.
    const selectedHex = HIGHLIGHT_COLORS.find((c) => c.key === selectedColor)?.hex;
    result = result.filter(
      (h) =>
        h.color.trim().toLowerCase() === selectedColor ||
        (selectedHex !== undefined && resolveHighlightHex(h.color) === selectedHex),
    );
  }

  if (selectedBookId) {
    result = result.filter((h) => h.bookId === selectedBookId);
  }

  if (selectedDateRange) {
    const cutoff = getDateCutoff(selectedDateRange);
    result = result.filter((h) => new Date(h.createdAt) >= cutoff);
  }

  return result;
}
