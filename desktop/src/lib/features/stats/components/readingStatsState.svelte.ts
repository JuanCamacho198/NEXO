import type { LibraryBookDto } from '$lib/shared/types';
import { UNCLASSIFIED_GENRE, type CanonicalGenre } from '$lib/shared/services/genreHeuristic';
import type { AppState } from '$lib/shared/stores/AppState.svelte';
import type { MessageKey } from '$lib/shared/i18n';

export type StatsBook = LibraryBookDto;

export type PeriodKey = 'week' | 'month' | 'year' | 'all';
export type Granularity = 'day' | 'week' | 'month';
export type GenreKey = CanonicalGenre;

export type Props = {
  appState: AppState;
};

export const periodLabels: Record<PeriodKey, string> = {
  week: 'Esta semana',
  month: 'Este mes',
  year: 'Este año',
  all: 'Todo el tiempo',
};

// Token-backed ramp (see tokens.css). Theme-aware by construction, so the same
// series is legible in light and dark without a hardcoded hex in the component.
const GENRE_COLORS = [
  'var(--color-chart-1)',
  'var(--color-chart-2)',
  'var(--color-chart-3)',
  'var(--color-chart-4)',
  'var(--color-chart-5)',
  'var(--color-chart-6)',
] as const;

const resolveGenre = (book: StatsBook): string => {
  const value = book.genre;
  if (typeof value === 'string' && value.trim().length > 0) {
    return value.trim();
  }
  return UNCLASSIFIED_GENRE;
};

function groupBooksByGenre(books: StatsBook[]): Map<string, number> {
  const groups = new Map<string, number>();
  for (const book of books) {
    const minutes = Math.max(book.minutesRead, 10);
    const key = resolveGenre(book);
    groups.set(key, (groups.get(key) ?? 0) + minutes);
  }
  return groups;
}

export function periodWindow(period: PeriodKey): { from: string; to: string } {
  const now = new Date();
  const to = now.toISOString();
  let from: Date;
  switch (period) {
    case 'week':
      from = new Date(now.getTime() - 7 * 24 * 60 * 60 * 1000);
      break;
    case 'month':
      from = new Date(now.getTime() - 30 * 24 * 60 * 60 * 1000);
      break;
    case 'year':
      from = new Date(now.getTime() - 365 * 24 * 60 * 60 * 1000);
      break;
    case 'all':
      return { from: '1970-01-01T00:00:00Z', to };
  }
  return { from: from.toISOString(), to };
}

export function previousWindow(period: PeriodKey): { from: string; to: string } {
  const now = new Date();
  let length: number;
  switch (period) {
    case 'week':
      length = 7;
      break;
    case 'month':
      length = 30;
      break;
    case 'year':
      length = 365;
      break;
    case 'all':
      return { from: '1970-01-01T00:00:00Z', to: now.toISOString() };
  }
  const windowMs = length * 24 * 60 * 60 * 1000;
  const to = new Date(now.getTime() - windowMs);
  const from = new Date(to.getTime() - windowMs);
  return { from: from.toISOString(), to: to.toISOString() };
}

export function computeDelta(current: number, previous: number): number | null {
  if (previous <= 0) return null;
  return Math.round(((current - previous) / previous) * 100 * 10) / 10;
}

export type DeltaTone = 'positive' | 'negative' | 'neutral' | 'none';

export type DeltaResult = { text: string; tone: DeltaTone };

const DELTA_LABEL_KEYS: Record<PeriodKey, MessageKey> = {
  week: 'stats.deltaWeek',
  month: 'stats.deltaMonth',
  year: 'stats.deltaYear',
  all: 'stats.deltaAll',
};

/**
 * Builds an honest delta label.
 *
 * - No current and no previous activity → nothing to compare, empty result.
 * - Previous period with real data (previous > 0) → signed delta; a zero
 *   change is neutral (no sign, no success colour) and a drop to zero is a
 *   real -100%, never hidden.
 * - First period with activity but no previous baseline → an explicit
 *   "first period" note instead of an invented percentage.
 */
export function formatDelta(
  current: number | undefined,
  previous: number | undefined,
  period: PeriodKey,
  t: (key: MessageKey) => string,
): DeltaResult {
  const cur = current ?? 0;
  const prev = previous ?? 0;
  if (cur === 0 && prev === 0) return { text: '', tone: 'none' };

  const delta = computeDelta(cur, prev);
  if (delta === null) {
    return cur > 0 ? { text: t('stats.firstPeriod'), tone: 'none' } : { text: '', tone: 'none' };
  }

  const label = t(DELTA_LABEL_KEYS[period]);
  if (delta === 0) return { text: `${t('stats.noChange')} ${label}`, tone: 'neutral' };

  const sign = delta > 0 ? '+' : '';
  return {
    text: `${sign}${delta}% ${label}`,
    tone: delta > 0 ? 'positive' : 'negative',
  };
}

export const periodDeltaLabels: Record<PeriodKey, string> = {
  week: 'vs. semana anterior',
  month: 'vs. mes anterior',
  year: 'vs. año anterior',
  all: 'vs. periodo anterior',
};

export function calculateGenreDistribution(
  books: StatsBook[],
): Array<{ genre: string; minutes: number; percent: number; color: string }> {
  const groups = groupBooksByGenre(books);
  const total = Array.from(groups.values()).reduce((sum, value) => sum + value, 0);

  return Array.from(groups.entries())
    .sort(([, leftMin], [, rightMin]) => rightMin - leftMin)
    .map(([genre, minutes], index) => ({
      genre,
      minutes,
      percent: total > 0 ? Math.round((minutes / total) * 100) : 0,
      color: GENRE_COLORS[index % GENRE_COLORS.length],
    }));
}

// ─── Chart helpers (PR-P4-1) ─────────────────────────────────────────
const CHART_WIDTH = 800;
const CHART_HEIGHT = 240;
const MIN_LABEL_SPACING = 46;

export type ChartPoint = { label: string; value: number; x: number; y: number };

export type ChartMeta = {
  max: number;
  min: number;
  width: number;
  height: number;
  points: ChartPoint[];
  line: string;
  area: string;
  labelInterval: number;
  step: number;
};

function getShortMonthName(monthIndex: number, locale: string): string {
  const date = new Date(2026, monthIndex, 1);
  try {
    const name = new Intl.DateTimeFormat(locale, { month: 'short' }).format(date);
    return name.charAt(0).toUpperCase() + name.slice(1);
  } catch {
    return ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'][
      monthIndex
    ]!;
  }
}

export function formatChartLabel(label: string, granularity: Granularity, locale: string): string {
  if (granularity === 'day') {
    const m = label.match(/(?:^|\D)(\d{1,2})[\-/](\d{1,2})(?:$|\D)/);
    if (m) return `${parseInt(m[1])}/${parseInt(m[2])}`;
    if (/^\d{1,2}$/.test(label)) return label;
  }
  if (granularity === 'week') {
    const w = label.match(/[WSws]\s*0*(\d+)/);
    if (w) return `${label.match(/[WSws]/)?.[0]?.toUpperCase() ?? 'W'}${w[1]}`;
  }
  if (granularity === 'month') {
    const monthNum = parseInt(
      label
        .replace(/^\D+/, '')
        .split(/[\s-]+/)
        .pop() || '',
      10,
    );
    if (monthNum >= 1 && monthNum <= 12) {
      return getShortMonthName(monthNum - 1, locale);
    }
    const parts = label.split(/[\s-]+/);
    const last = parts[parts.length - 1];
    return last.length <= 4 ? last : last.slice(0, 3);
  }
  return label;
}

export function buildChartMeta(
  series: Array<{ label: string; value: number }>,
  width: number = CHART_WIDTH,
  height: number = CHART_HEIGHT,
): ChartMeta {
  const max = Math.max(...series.map((p) => p.value), 1);
  const min = Math.min(...series.map((p) => p.value), 0);
  const step = series.length > 1 ? width / (series.length - 1) : width;
  const points: ChartPoint[] = series.map((point, index) => {
    const x = index * step;
    const normalized = max === min ? 0.5 : (point.value - min) / (max - min);
    const y = height - normalized * (height - 18) - 10;
    return { ...point, x, y };
  });
  const maxVisibleLabels = Math.max(1, Math.floor(width / MIN_LABEL_SPACING));
  const labelInterval = Math.max(1, Math.ceil(points.length / maxVisibleLabels));
  const line = points.map((p, i) => `${i === 0 ? 'M' : 'L'} ${p.x},${p.y}`).join(' ');
  const area = `${line} L ${width},${height} L 0,${height} Z`;
  return { max, min, width, height, points, line, area, labelInterval, step };
}
