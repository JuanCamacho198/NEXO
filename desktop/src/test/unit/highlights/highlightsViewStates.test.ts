import { fireEvent, render, screen, waitFor } from '@testing-library/svelte';
import { describe, expect, it } from 'vitest';
import HighlightsView from '$lib/features/highlights/components/HighlightsView.svelte';
import type { HighlightDto, LibraryBookDto } from '$lib/shared/types';
import type { ViewerPort } from '$lib/shared/ports';
import type { HighlightsViewDeps } from '$lib/features/highlights/highlightsViewDeps';

const messages: Record<string, string> = {
  'home.highlightsTitle': 'Highlights',
  'home.highlightsSubtitle': 'All your important ideas, always at hand.',
  'home.highlightsSearchPlaceholder': 'Search in highlights...',
  'home.highlightsFiltersHeading': 'Filters',
  'home.highlightsClearFilters': 'Clear filters',
  'home.highlightsShowingCount': 'Showing {{count}} highlights',
  'home.highlightsResultsHeading': 'Highlight results',
  'home.highlightsEmptyTitle': 'No highlights',
  'home.highlightsEmptyDescription': 'No highlights found with current filters.',
  'home.highlightsEmptyNoDataTitle': 'No highlights yet',
  'home.highlightsEmptyNoDataDescription': 'Select text while reading a book to save a highlight.',
  'home.highlightsRefresh': 'Refresh highlights',
  'home.highlightsSyncing': 'Synchronizing…',
  'home.highlightsSynced': 'Synchronized',
};

function t(key: string, params?: Record<string, string | number>): string {
  const template = messages[key] ?? key;
  return template.replace(/\{\{\s*([\w.-]+)\s*\}\}/g, (_match, name: string) =>
    String(params?.[name] ?? ''),
  );
}

const BOOK: LibraryBookDto = {
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
  note: null,
  createdAt: '2026-09-19T12:00:00.000Z',
  updatedAt: '2026-09-19T12:00:00.000Z',
};

function makeDeps(highlights: HighlightDto[]): HighlightsViewDeps {
  return {
    listHighlights: async () => highlights,
    deleteHighlight: async () => {},
    upsertRemoteHighlights: async () => {},
    listTags: async () => [],
    listTagsForHighlight: async () => [],
  } as unknown as HighlightsViewDeps;
}

function renderView(highlights: HighlightDto[]): ReturnType<typeof render> {
  return render(HighlightsView, {
    books: [BOOK],
    t,
    deps: makeDeps(highlights),
    viewerPort: {} as ViewerPort,
  });
}

describe('HighlightsView empty states', () => {
  it('shows the no-data copy (and how to create one) when there are no highlights at all', async () => {
    renderView([]);

    await waitFor(() => expect(screen.getByText('No highlights yet')).toBeTruthy());

    expect(screen.getByText('Select text while reading a book to save a highlight.')).toBeTruthy();
    // The filter message belongs to the "no matches" case, never here.
    expect(screen.queryByText('No highlights found with current filters.')).toBeNull();
    // Count line is hidden when the total is 0.
    expect(screen.queryByText(/Showing/)).toBeNull();
    // The empty state itself offers no "clear filters" action; only the
    // persistent filter card button remains.
    expect(screen.getAllByRole('button', { name: 'Clear filters' })).toHaveLength(1);
  });

  it('shows the filter copy and a clear action when highlights exist but no filter matches', async () => {
    renderView([HIGHLIGHT]);

    const input = await screen.findByPlaceholderText('Search in highlights...');
    await fireEvent.input(input, { target: { value: 'zzz-no-match' } });

    await waitFor(() =>
      expect(screen.getByText('No highlights found with current filters.')).toBeTruthy(),
    );
    expect(screen.queryByText('No highlights yet')).toBeNull();
    // Filter-card action + the empty state's own action.
    expect(screen.getAllByRole('button', { name: 'Clear filters' })).toHaveLength(2);
    // The count line still reflects the total when highlights exist.
    expect(screen.getByText('Showing 0 highlights')).toBeTruthy();
  });

  it('keeps headings in a valid descending order (h1 → h2 → h3)', async () => {
    const { container } = renderView([]);
    await waitFor(() => expect(screen.getByText('No highlights yet')).toBeTruthy());

    const levels = Array.from(container.querySelectorAll('h1, h2, h3, h4, h5, h6')).map((node) =>
      Number(node.tagName.slice(1)),
    );
    expect(levels[0]).toBe(1);
    for (let i = 1; i < levels.length; i += 1) {
      expect(levels[i] - levels[i - 1]).toBeLessThanOrEqual(1);
    }
  });

  it('clears the filters from the no-match empty state action', async () => {
    renderView([HIGHLIGHT]);

    const input = await screen.findByPlaceholderText('Search in highlights...');
    await fireEvent.input(input, { target: { value: 'zzz-no-match' } });
    await waitFor(() =>
      expect(screen.getByText('No highlights found with current filters.')).toBeTruthy(),
    );

    const [, emptyAction] = screen.getAllByRole('button', { name: 'Clear filters' });
    await fireEvent.click(emptyAction!);

    await waitFor(() => expect(screen.getByText(HIGHLIGHT.text)).toBeTruthy());
  });
});
