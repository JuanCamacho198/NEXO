import type { HighlightDto, LibraryBookDto, TagDto } from '$lib/shared/types';
import { HIGHLIGHT_COLORS, PAGE_SIZE, resolveHighlightHex } from './state.svelte';
import type { HighlightsViewDeps } from './highlightsViewDeps';

type SelectedType = 'all' | 'quotes' | 'ideas' | 'passages';

function getHighlightType(h: HighlightDto): 'quote' | 'idea' | 'passage' {
  if (h.note && h.note.trim().length > 0) return 'idea';
  if (h.text.length > 180) return 'passage';
  return 'quote';
}

function matchesType(h: HighlightDto, type: SelectedType): boolean {
  if (type === 'all') return true;
  const t = getHighlightType(h);
  if (type === 'quotes') return t === 'quote';
  if (type === 'ideas') return t === 'idea';
  if (type === 'passages') return t === 'passage';
  return true;
}

export function createHighlightsFilters(opts: {
  getHighlights: () => HighlightDto[];
  getBooks: () => LibraryBookDto[];
  deps: HighlightsViewDeps;
}): {
  searchQuery: string;
  selectedColors: Set<string>;
  selectedBookId: string | null;
  selectedDateRange: string | null;
  selectedType: SelectedType;
  selectedTagId: string;
  currentPage: number;
  allTags: TagDto[];
  highlightTagMap: Map<string, string[]>;
  readonly filteredHighlights: HighlightDto[];
  readonly paginatedHighlights: HighlightDto[];
  readonly totalPages: number;
  toggleColor(key: string): void;
  clearFilters(): void;
  loadTagsAndMap(): Promise<void>;
} {
  let searchQuery = $state('');
  let selectedColors = $state<Set<string>>(new Set());
  let selectedBookId = $state<string | null>('');
  let selectedDateRange = $state<string | null>('');
  let selectedType = $state<SelectedType>('all');
  let selectedTagId = $state('');
  let currentPage = $state(1);
  let allTags = $state<TagDto[]>([]);
  let highlightTagMap = $state<Map<string, string[]>>(new Map());

  function toggleColor(key: string): void {
    const next = new Set(selectedColors);
    if (next.has(key)) next.delete(key);
    else next.add(key);
    selectedColors = next;
  }

  function clearFilters(): void {
    searchQuery = '';
    selectedColors = new Set();
    selectedBookId = '';
    selectedDateRange = '';
    selectedType = 'all';
    selectedTagId = '';
    currentPage = 1;
  }

  async function loadTagsAndMap(): Promise<void> {
    try {
      allTags = await opts.deps.listTags();
    } catch {
      allTags = [];
    }
    const highlights = opts.getHighlights();
    if (highlights.length === 0) return;
    const map = new Map<string, string[]>();
    const slice = highlights.slice(0, 50);
    await Promise.all(
      slice.map(async (h) => {
        try {
          const tags = await opts.deps.listTagsForHighlight(h.id);
          map.set(
            h.id,
            tags.map((t) => t.id),
          );
        } catch {
          map.set(h.id, []);
        }
      }),
    );
    for (const h of highlights) if (!map.has(h.id)) map.set(h.id, []);
    highlightTagMap = map;
  }

  const bookMap = $derived(new Map(opts.getBooks().map((b) => [b.id, b])));

  const filteredHighlights = $derived.by(() => {
    let result = opts.getHighlights();
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
    if (selectedColors.size > 0) {
      result = result.filter((h) => {
        const normalized = h.color.trim().toLowerCase();
        if (selectedColors.has(normalized)) return true;
        const keyForHighlight =
          HIGHLIGHT_COLORS.find(
            (c) => c.hex.toLowerCase() === resolveHighlightHex(h.color).toLowerCase(),
          )?.key ?? HIGHLIGHT_COLORS.find((c) => c.hex.toLowerCase() === normalized)?.key;
        return keyForHighlight ? selectedColors.has(keyForHighlight) : false;
      });
    }
    if (selectedBookId) result = result.filter((h) => h.bookId === selectedBookId);
    if (selectedDateRange) {
      const now = new Date();
      let cutoff: Date;
      if (selectedDateRange === '7d') cutoff = new Date(now.getTime() - 7 * 86400000);
      else if (selectedDateRange === '30d') cutoff = new Date(now.getTime() - 30 * 86400000);
      else if (selectedDateRange === '90d') cutoff = new Date(now.getTime() - 90 * 86400000);
      else cutoff = new Date(0);
      result = result.filter((h) => new Date(h.createdAt) >= cutoff);
    }
    if (selectedType !== 'all') result = result.filter((h) => matchesType(h, selectedType));
    if (selectedTagId)
      result = result.filter((h) => (highlightTagMap.get(h.id) ?? []).includes(selectedTagId!));
    return result;
  });

  const totalPages = $derived(Math.max(1, Math.ceil(filteredHighlights.length / PAGE_SIZE)));
  const paginatedHighlights = $derived.by(() => {
    const start = (currentPage - 1) * PAGE_SIZE;
    return filteredHighlights.slice(start, start + PAGE_SIZE);
  });

  // reset page when filters change
  $effect(() => {
    void [
      searchQuery,
      selectedColors,
      selectedBookId,
      selectedDateRange,
      selectedType,
      selectedTagId,
    ];
    currentPage = 1;
  });

  return {
    get searchQuery() {
      return searchQuery;
    },
    set searchQuery(v: string) {
      searchQuery = v;
    },
    get selectedColors() {
      return selectedColors;
    },
    set selectedColors(v: Set<string>) {
      selectedColors = v;
    },
    get selectedBookId() {
      return selectedBookId;
    },
    set selectedBookId(v: string | null) {
      selectedBookId = v;
    },
    get selectedDateRange() {
      return selectedDateRange;
    },
    set selectedDateRange(v: string | null) {
      selectedDateRange = v;
    },
    get selectedType() {
      return selectedType;
    },
    set selectedType(v: SelectedType) {
      selectedType = v;
    },
    get selectedTagId() {
      return selectedTagId;
    },
    set selectedTagId(v: string) {
      selectedTagId = v;
    },
    get currentPage() {
      return currentPage;
    },
    set currentPage(v: number) {
      currentPage = v;
    },
    get allTags() {
      return allTags;
    },
    set allTags(v: TagDto[]) {
      allTags = v;
    },
    get highlightTagMap() {
      return highlightTagMap;
    },
    set highlightTagMap(v: Map<string, string[]>) {
      highlightTagMap = v;
    },
    get filteredHighlights() {
      return filteredHighlights;
    },
    get paginatedHighlights() {
      return paginatedHighlights;
    },
    get totalPages() {
      return totalPages;
    },
    toggleColor,
    clearFilters,
    loadTagsAndMap,
  };
}

export type HighlightsFiltersState = ReturnType<typeof createHighlightsFilters>;

// pure helpers for vitest without Svelte runtime
export { getHighlightType, matchesType };
