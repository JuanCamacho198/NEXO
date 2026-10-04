import { fireEvent, render, screen, waitFor } from '@testing-library/svelte';
import { describe, expect, it, vi } from 'vitest';
import ShelfDetailModal from '$lib/features/library/ShelfDetailModal.svelte';
import LibraryShelfScreen from '$lib/features/library/components/LibraryShelfScreen.svelte';
import HighlightsView from '$lib/features/highlights/components/HighlightsView.svelte';
import type { HighlightDto, LibraryBookDto } from '$lib/shared/types';
import type { ViewerPort } from '$lib/shared/ports';
import type { HighlightsViewDeps } from '$lib/features/highlights/highlightsViewDeps';

/**
 * Library and highlights icon migration (slice 13, closed by the slice-14
 * batch). These three files hold the remaining static `<Icon` call sites
 * outside the home/welcome/sidebar group: `LibraryShelfScreen` (2),
 * `HighlightsView` (7) and `ShelfDetailModal` (6). `ShelfSection`'s toggle
 * glyphs moved to `LibraryShelfScreen` when Home's toolbar was retired
 * (single Estantería toolbar), so this suite no longer renders `ShelfSection`.
 * The last file was migrated only after the constraint-8/2 exception was
 * authorized: the compact shape costs a measured **+5 lines** (386 -> 391 by
 * newline count), attributed to +3 deep lucide imports and +2 for the one
 * glyph whose trailing `{t('shelf.added' as MessageKey)}` exceeds the
 * 100-column budget at indent 16.
 *
 * The shim resolved a name to a lucide component; these call sites resolve the
 * same components directly, so the assertions are per-glyph identity against
 * the shim contract (lucide marker class, 24x24 viewBox, `currentColor`, stroke
 * width 1.8, `aria-hidden` from the lucide default, numeric size).
 */

const t = (key: string): string => key;

/** The glyph class lucide emits: `lucide-icon lucide-<name> ...`. */
const glyphOf = (icon: Element): string | undefined =>
  (icon.getAttribute('class') ?? '')
    .split(/\s+/)
    .find((cls) => cls.startsWith('lucide-') && cls !== 'lucide-icon');

const glyphs = (root: ParentNode): (string | undefined)[] =>
  Array.from(root.querySelectorAll('svg.lucide-icon')).map(glyphOf);

function expectGlyphContract(icon: Element, width: string): void {
  expect(icon.getAttribute('class')).toContain('lucide-icon');
  expect(icon.getAttribute('viewBox')).toBe('0 0 24 24');
  expect(icon.getAttribute('stroke')).toBe('currentColor');
  expect(icon.getAttribute('stroke-width')).toBe('1.8');
  // Not passed explicitly by the migrated call sites: lucide-svelte 1.0.1 emits
  // the attribute itself, which is what keeps the compact shape decorative.
  expect(icon.getAttribute('aria-hidden')).toBe('true');
  expect(icon.getAttribute('width')).toBe(width);
}

const SHELF_BOOK: LibraryBookDto = {
  id: 'b1',
  title: 'Hábitos Atómicos',
  author: 'James Clear',
  format: 'epub',
  currentPage: 12,
  totalPages: 300,
  progressPercentage: 4,
  coverPath: null,
  minutesRead: 42,
  updatedAt: '2026-09-19T12:00:00.000Z',
  createdAt: '2026-09-01T12:00:00.000Z',
};

const HIGHLIGHT: HighlightDto = {
  id: 'h1',
  bookId: 'b1',
  text: 'Los pequeños momentos de éxito crean la base para cambios duraderos.',
  color: 'yellow',
  pageNumber: 12,
  note: 'Una nota',
  createdAt: '2026-09-19T12:00:00.000Z',
  updatedAt: '2026-09-19T12:00:00.000Z',
};

const noop = vi.fn();

function highlightsDeps(): HighlightsViewDeps {
  return {
    listHighlights: async () => [HIGHLIGHT],
    deleteHighlight: async () => {},
    upsertRemoteHighlights: async () => {},
    listTags: async () => [],
    listTagsForHighlight: async () => [],
  } as unknown as HighlightsViewDeps;
}

async function renderHighlights() {
  const result = render(HighlightsView, {
    books: [SHELF_BOOK],
    t,
    deps: highlightsDeps(),
    viewerPort: {} as ViewerPort,
  });
  await waitFor(() => expect(screen.getByText(HIGHLIGHT.text)).toBeTruthy());
  return result;
}

describe('library and highlights icon migration', () => {
  it('renders the LibraryShelfScreen toggle glyphs without the shim tooltip wrapper', () => {
    const { container } = render(LibraryShelfScreen, { books: [], t });

    expect(glyphs(container)).toEqual(['lucide-layout-grid', 'lucide-list']);
    for (const icon of container.querySelectorAll('svg.lucide-icon')) {
      expectGlyphContract(icon, '14');
    }
    // The shim rendered `div.group.relative.inline-flex` plus an inline
    // `role="tooltip"` span per titled glyph. The positive control is the shim
    // itself: `Icon.test.ts` still asserts that tooltip contract.
    expect(container.querySelectorAll('div.group.relative.inline-flex')).toHaveLength(0);
    expect(container.querySelector('[role="tooltip"]')).toBeNull();
    // The accessible name the tooltip used to carry is still on the button.
    expect(screen.getByLabelText('shelf.gridView')).toBeTruthy();
    expect(screen.getByLabelText('shelf.listView')).toBeTruthy();
  });

  it('renders the HighlightsView note, placeholder-cover and trigger glyphs', async () => {
    const { container } = await renderHighlights();

    // `note` and `edit` both resolved to `square-pen` in the shim map; the
    // trailing glyph is the action-menu trigger (`more-dot`).
    expect(glyphs(container)).toEqual([
      'lucide-square-pen',
      'lucide-book',
      'lucide-ellipsis-vertical',
    ]);
    expectGlyphContract(container.querySelector('svg.lucide-square-pen') as Element, '14');
    expectGlyphContract(container.querySelector('svg.lucide-book') as Element, '20');
    expectGlyphContract(container.querySelector('svg.lucide-ellipsis-vertical') as Element, '14');
  });

  it('renders the ShelfDetailModal glyphs from direct lucide components', async () => {
    // The modal is a bits-ui Dialog, so its content is portalled out of the
    // render target and read through the a11y tree by role.
    render(ShelfDetailModal, {
      open: true,
      book: { ...SHELF_BOOK, publicationDate: '2020-05-01' },
      collections: [],
      t,
      onClose: noop,
      onStartReading: noop,
      onDeleteCover: noop,
      onStatusChange: noop,
      onToggleFavorite: noop,
      onSaveEdit: async () => {},
    });

    const dialog = await waitFor(() => screen.getByRole('dialog'));
    // Two `clock` sites (reading label + minutes read), `info`, the two
    // `calendar` sites (published + added) and the footer `edit` glyph.
    expect(glyphs(dialog)).toEqual([
      'lucide-clock',
      'lucide-clock',
      'lucide-info',
      'lucide-calendar',
      'lucide-calendar',
      'lucide-square-pen',
    ]);
    for (const icon of dialog.querySelectorAll('svg.lucide-icon')) {
      expectGlyphContract(icon, '14');
    }
  });

  it('renders the HighlightsView action-menu glyphs from direct lucide components', async () => {
    await renderHighlights();

    await fireEvent.click(screen.getByLabelText('Opciones'));
    await waitFor(() => expect(screen.getByRole('menu')).toBeTruthy());

    const menu = screen.getByRole('menu');
    expect(glyphs(menu)).toEqual([
      'lucide-copy',
      'lucide-book',
      'lucide-square-pen',
      'lucide-trash-2',
    ]);
    for (const icon of menu.querySelectorAll('svg.lucide-icon')) {
      expectGlyphContract(icon, '14');
      expect(icon.getAttribute('aria-label')).toBeNull();
    }
  });
});
