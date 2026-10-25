/**
 * G6 (DISC-04b) — the author rails' pages survive a restart: the startup
 * preload covers exactly the current day's two page-1 author-rail keys
 * (`DISCOVER_AUTHOR_RAIL_COUNT`), under Google Books, and only those. The
 * deleted Gutendex thematic page is gone, and the fixed-source rails (Gutendex
 * popular, Standard Ebooks) are deliberately not preloaded.
 */
import { describe, expect, it } from 'vitest';
import { discoverPreloadPageKeys } from '$lib/shared/services/catalog/liveComposite';
import { pageCacheKey } from '$lib/shared/services/catalog/DiscoverCache';
import { BUILTIN_GOOGLEBOOKS } from '$lib/shared/services/catalog/CatalogProvider';
import { authorEntriesFor } from '$lib/features/discover/railRotation';
import { DISCOVER_AUTHOR_RAIL_COUNT } from '$lib/features/discover/railPlan';

describe('author rail startup preload (DISC-04b)', () => {
  it('preloads exactly the current day two author page-1 keys', () => {
    const day = new Date(2026, 5, 10, 12);
    expect(DISCOVER_AUTHOR_RAIL_COUNT).toBe(2);
    expect(discoverPreloadPageKeys(day)).toEqual(
      authorEntriesFor(day, DISCOVER_AUTHOR_RAIL_COUNT).map((entry) =>
        pageCacheKey(BUILTIN_GOOGLEBOOKS, entry.term, 1),
      ),
    );
    // 2026-06-10 → dayOfYear 161 → start index 0 → doyle, poe.
    expect(discoverPreloadPageKeys(day)).toEqual([
      'p:v2:builtin:googlebooks:arthur conan doyle:1',
      'p:v2:builtin:googlebooks:edgar allan poe:1',
    ]);
  });

  it('rolls with the local day and always stays two page-1 keys', () => {
    const today = discoverPreloadPageKeys(new Date(2026, 5, 10));
    const tomorrow = discoverPreloadPageKeys(new Date(2026, 5, 11));

    expect(today).toHaveLength(DISCOVER_AUTHOR_RAIL_COUNT);
    expect(tomorrow).toHaveLength(DISCOVER_AUTHOR_RAIL_COUNT);
    for (const key of [...today, ...tomorrow]) expect(key.endsWith(':1')).toBe(true);
    expect(today).not.toEqual(tomorrow);
    // No key ever warms the retired thematic path or a fixed-source rail.
    for (const key of [...today, ...tomorrow]) {
      expect(key).not.toContain('builtin:gutendex');
      expect(key).not.toContain('standard-ebooks');
    }
  });
});
