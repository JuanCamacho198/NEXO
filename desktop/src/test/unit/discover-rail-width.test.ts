import { readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { describe, expect, it } from 'vitest';
import {
  deriveVisibleRailCount,
  DISCOVER_RAIL_FETCH_LIMIT,
  DISCOVER_RAIL_LIMIT,
  MAX_VISIBLE_RAIL_CARDS,
  MIN_VISIBLE_RAIL_CARDS,
  RAIL_CARD_GAP_PX,
  RAIL_CARD_MIN_WIDTH_PX,
} from '$lib/features/discover/railPlan';

const HERE = dirname(fileURLToPath(import.meta.url));
const DISCOVER_DIR = resolve(HERE, '../../lib/features/discover');

function readSource(file: string): string {
  return readFileSync(resolve(DISCOVER_DIR, file), 'utf8');
}

describe('rail visible count — width derivation', () => {
  it('fills one row from a narrow window to a very wide one', () => {
    // columns = floor((width + gap) / (cardMin + gap))
    expect(deriveVisibleRailCount(320)).toBe(2);
    expect(deriveVisibleRailCount(360)).toBe(2);
    expect(deriveVisibleRailCount(768)).toBe(4);
    expect(deriveVisibleRailCount(1024)).toBe(6);
    expect(deriveVisibleRailCount(1280)).toBe(7);
    expect(deriveVisibleRailCount(1920)).toBe(11);
    expect(deriveVisibleRailCount(2560)).toBe(14);
    expect(deriveVisibleRailCount(3840)).toBe(22);
  });

  it('floors a very narrow container and caps an ultra-wide one', () => {
    expect(deriveVisibleRailCount(1)).toBe(MIN_VISIBLE_RAIL_CARDS);
    expect(deriveVisibleRailCount(50)).toBe(MIN_VISIBLE_RAIL_CARDS);
    expect(deriveVisibleRailCount(6000)).toBe(MAX_VISIBLE_RAIL_CARDS);
    expect(deriveVisibleRailCount(100_000)).toBe(MAX_VISIBLE_RAIL_CARDS);
  });

  it('falls back to the unmeasured default before any width exists', () => {
    expect(deriveVisibleRailCount(0)).toBe(DISCOVER_RAIL_LIMIT);
    expect(deriveVisibleRailCount(-1)).toBe(DISCOVER_RAIL_LIMIT);
    expect(deriveVisibleRailCount(Number.NaN)).toBe(DISCOVER_RAIL_LIMIT);
    expect(deriveVisibleRailCount(Number.POSITIVE_INFINITY)).toBe(DISCOVER_RAIL_LIMIT);
  });

  it('never decreases as the container grows', () => {
    const stride = RAIL_CARD_MIN_WIDTH_PX + RAIL_CARD_GAP_PX;
    let previous = 0;
    for (let width = 100; width <= 8000; width += stride) {
      const count = deriveVisibleRailCount(width);
      expect(count).toBeGreaterThanOrEqual(previous);
      previous = count;
    }
  });

  it('fetches at least as many cards as the widest row can render', () => {
    expect(DISCOVER_RAIL_FETCH_LIMIT).toBe(MAX_VISIBLE_RAIL_CARDS);
    expect(DISCOVER_RAIL_FETCH_LIMIT).toBeGreaterThanOrEqual(deriveVisibleRailCount(8000));
  });
});

describe('rail visible count — render wiring', () => {
  it('derives loaded cards and skeleton slots from the same measured count', () => {
    const source = readSource('DiscoverRailSection.svelte');
    expect(source).toContain('deriveVisibleRailCount');
    expect(source).toContain('ResizeObserver');
    expect(source).toContain('books.slice(0, visibleCount)');
    expect(source).toContain('length: visibleCount');
    // No remaining static count on either path.
    expect(source).not.toContain('DISCOVER_RAIL_LIMIT');
  });

  it('fetches the rail page at the max render count, not the unmeasured default', () => {
    const source = readSource('DiscoverRailsDomainState.svelte.ts');
    expect(source).toContain('loadRail(this.provider, spec, DISCOVER_RAIL_FETCH_LIMIT)');
    // A source that returns a whole feed is bounded to one row's worth of cards.
    expect(source).toContain('boundRailPage(page, DISCOVER_RAIL_FETCH_LIMIT)');
  });
});
