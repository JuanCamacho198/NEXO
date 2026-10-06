/**
 * G6 (DISC-04b) — the author rails' pages survive a restart: the startup
 * preload covers exactly the current day's three page-1 author-rail keys, under
 * Google Books, and only those. The retired Gutendex thematic page is gone.
 */
import { describe, expect, it } from 'vitest';
import { discoverPreloadPageKeys } from '$lib/shared/services/catalog/liveComposite';
import { pageCacheKey } from '$lib/shared/services/catalog/DiscoverCache';
import { BUILTIN_GOOGLEBOOKS } from '$lib/shared/services/catalog/CatalogProvider';
import { authorEntriesFor } from '$lib/features/discover/railRotation';

describe('author rail startup preload (DISC-04b)', () => {
  it('preloads exactly the current day three author page-1 keys', () => {
    const day = new Date(2026, 5, 10, 12);
    expect(discoverPreloadPageKeys(day)).toEqual(
      authorEntriesFor(day).map((entry) => pageCacheKey(BUILTIN_GOOGLEBOOKS, entry.term, 1)),
    );
    // 2026-06-10 → dayOfYear 161 → start index 0 → doyle, poe, wilde.
    expect(discoverPreloadPageKeys(day)).toEqual([
      'p:v2:builtin:googlebooks:arthur conan doyle:1',
      'p:v2:builtin:googlebooks:edgar allan poe:1',
      'p:v2:builtin:googlebooks:oscar wilde:1',
    ]);
  });

  it('rolls with the local day and always stays three page-1 keys', () => {
    const today = discoverPreloadPageKeys(new Date(2026, 5, 10));
    const tomorrow = discoverPreloadPageKeys(new Date(2026, 5, 11));

    expect(today).toHaveLength(3);
    expect(tomorrow).toHaveLength(3);
    for (const key of [...today, ...tomorrow]) expect(key.endsWith(':1')).toBe(true);
    expect(today).not.toEqual(tomorrow);
    // No key ever warms the retired Gutendex rail path.
    for (const key of [...today, ...tomorrow]) expect(key).not.toContain('builtin:gutendex');
  });
});
