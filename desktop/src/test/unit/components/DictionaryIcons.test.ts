import { render } from '@testing-library/svelte';
import { describe, expect, it, vi } from 'vitest';
import DictionaryDetailPanel from '$lib/features/dictionary/components/DictionaryDetailPanel.svelte';
import DictionaryKpiRow from '$lib/features/dictionary/components/DictionaryKpiRow.svelte';
import DictionaryView from '$lib/features/dictionary/components/DictionaryView.svelte';
import type { MessageKey } from '$lib/shared/i18n';
import type { DictionaryStateApi } from '$lib/shared/stores/DictionaryState.svelte';
import type { DictionaryWordDto } from '$lib/shared/types';

/**
 * Dictionary icon migration (slice 12). The three dictionary files held ten
 * `<Icon` call sites: seven in the detail panel, the `card.icon` producer in the
 * KPI row and two in the view. The shim resolved a name to a lucide component;
 * these call sites resolve the same components directly, so the assertion is
 * per-glyph identity against the shim contract (lucide marker class, 24x24
 * viewBox, `currentColor`, stroke width 1.8, `aria-hidden`, numeric size).
 */

const t = (key: MessageKey, _params?: Record<string, string | number>): string => key;

/** The glyph class lucide emits: `lucide-icon lucide-<name> ...`. */
const glyphOf = (icon: Element): string | undefined =>
  (icon.getAttribute('class') ?? '')
    .split(/\s+/)
    .find((cls) => cls.startsWith('lucide-') && cls !== 'lucide-icon');

const glyphs = (root: HTMLElement): (string | undefined)[] =>
  Array.from(root.querySelectorAll('svg.lucide-icon')).map(glyphOf);

function expectGlyphContract(icon: Element, width: string): void {
  expect(icon.getAttribute('class')).toContain('lucide-icon');
  expect(icon.getAttribute('viewBox')).toBe('0 0 24 24');
  expect(icon.getAttribute('stroke')).toBe('currentColor');
  expect(icon.getAttribute('stroke-width')).toBe('1.8');
  expect(icon.getAttribute('aria-hidden')).toBe('true');
  expect(icon.getAttribute('width')).toBe(width);
}

const FULL_ENTRY: DictionaryWordDto = {
  id: '1',
  word: 'Efímero',
  createdAt: '2026-09-19T12:00:00.000Z',
  definition: 'Que tiene una duración muy breve.',
  partOfSpeech: 'Adjetivo',
  phonetic: '/eˈfimeɾo/',
  example: 'La belleza de un atardecer es efímera.',
  quote: 'Los pequeños momentos de éxito crean la base para cambios duraderos.',
  sourceBookId: 'b1',
  sourceBookTitle: 'Hábitos Atómicos',
  sourceBookAuthor: 'James Clear',
  sourceChapter: 'Capítulo 1',
};

function makeDictionary(words: DictionaryWordDto[]): DictionaryStateApi {
  return {
    get words() {
      return words;
    },
    isLoading: false,
    load: vi.fn(async () => {}),
    search: vi.fn((query: string, limit = 20) =>
      words
        .filter((entry) => (entry.word ?? '').trim().toLowerCase().includes(query.toLowerCase()))
        .slice(0, limit),
    ),
    add: vi.fn(),
    remove: vi.fn(),
    update: vi.fn(),
    capture: vi.fn(),
    subscribeToRemoteChanges: vi.fn(),
    unsubscribe: vi.fn(),
  } as unknown as DictionaryStateApi;
}

describe('dictionary icon migration', () => {
  it('renders the three KPI glyphs from the component-valued card field', () => {
    const { container } = render(DictionaryKpiRow, {
      t,
      kpis: { total: 12, thisWeek: 3, referencedBooks: 5 },
    });

    expect(glyphs(container)).toEqual([
      'lucide-book-open',
      'lucide-calendar-plus',
      'lucide-library',
    ]);
    for (const icon of container.querySelectorAll('svg.lucide-icon')) {
      expectGlyphContract(icon, '16');
    }
  });

  it('renders the seven detail-panel glyphs from direct lucide components', () => {
    const { container } = render(DictionaryDetailPanel, { word: FULL_ENTRY, t });

    expect(glyphs(container)).toEqual([
      'lucide-book-open',
      'lucide-square-pen',
      'lucide-trash-2',
      'lucide-book-text',
      'lucide-quote',
      'lucide-book-open',
      'lucide-info',
    ]);

    const icons = [...container.querySelectorAll('svg.lucide-icon')];
    const widths = icons.map((icon) => icon.getAttribute('width'));
    // Only the delete action is `md`; every other detail glyph is `sm`.
    expect(widths).toEqual(['14', '14', '16', '14', '14', '14', '14']);
    icons.forEach((icon, index) => expectGlyphContract(icon, widths[index] as string));
  });

  it('drops the shim wrapper element at every migrated detail-panel site', () => {
    const { container } = render(DictionaryDetailPanel, { word: FULL_ENTRY, t });

    // The shim rendered `div.group.relative.inline-flex` around each glyph. The
    // positive control is the shim itself, rendered in its own contract test.
    expect(container.querySelectorAll('div.group.relative.inline-flex')).toHaveLength(0);
    expect(container.querySelectorAll('svg.lucide-icon')).toHaveLength(7);
  });

  it('keeps the three KPI glyphs decorative and free of accessible names', () => {
    const { container } = render(DictionaryKpiRow, {
      t,
      kpis: { total: 12, thisWeek: 3, referencedBooks: 5 },
    });

    for (const icon of container.querySelectorAll('svg.lucide-icon')) {
      expect(icon.getAttribute('aria-label')).toBeNull();
      expect(icon.querySelector('title')).toBeNull();
    }
  });

  it('renders the view header and search glyphs from direct lucide components', () => {
    const { container } = render(DictionaryView, {
      t,
      dictionary: makeDictionary([FULL_ENTRY]),
    });

    // The view composes the KPI row, so the three KPI glyphs sit between the
    // header action and the search field.
    expect(glyphs(container)).toEqual([
      'lucide-plus',
      'lucide-book-open',
      'lucide-calendar-plus',
      'lucide-library',
    ]);
    expectGlyphContract(container.querySelector('svg.lucide-plus') as Element, '16');

    // The magnifier now belongs to the unified SearchBar, which draws its own
    // inline glyph instead of a lucide component.
    const searchField = container.querySelector('[role="search"]');
    expect(searchField).not.toBeNull();
    expect(searchField?.querySelector('svg.lucide-icon')).toBeNull();
  });
});
