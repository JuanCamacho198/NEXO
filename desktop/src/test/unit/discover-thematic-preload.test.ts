/**
 * G6 (DISC-04b) — the topic rails' pages survive a restart: the startup preload
 * covers exactly the current day's two page-1 topic-rail keys
 * (`DISCOVER_TOPIC_RAIL_COUNT`), under Gutendex, and only those. The fixed-source
 * rails (Gutendex popular, Standard Ebooks) are deliberately not preloaded, and
 * Google Books is no longer preloaded at all: it stopped being a rail source.
 */
import { describe, expect, it } from 'vitest';
import { discoverPreloadPageKeys } from '$lib/shared/services/catalog/liveComposite';
import { topicCacheKey } from '$lib/shared/services/catalog/DiscoverCache';
import { BUILTIN_GUTENDEX } from '$lib/shared/services/catalog/CatalogProvider';
import { topicEntriesFor } from '$lib/features/discover/railRotation';
import { DISCOVER_TOPIC_RAIL_COUNT } from '$lib/features/discover/railPlan';

describe('topic rail startup preload', () => {
  it('preloads exactly the current day two topic page-1 keys', () => {
    const day = new Date(2026, 5, 10, 12);
    expect(DISCOVER_TOPIC_RAIL_COUNT).toBe(2);
    expect(discoverPreloadPageKeys(day)).toEqual(
      topicEntriesFor(day, DISCOVER_TOPIC_RAIL_COUNT).map((entry) =>
        topicCacheKey(BUILTIN_GUTENDEX, entry.term, 1),
      ),
    );
    // 2026-06-10 → dayOfYear 161 → start index 0 → science fiction, adventure.
    // The `t:` namespace is the one the topic rail actually reads; `p:` rows are
    // never served to a `?topic=` page.
    expect(discoverPreloadPageKeys(day)).toEqual([
      't:v2:builtin:gutendex:science fiction:1',
      't:v2:builtin:gutendex:adventure:1',
    ]);
  });

  it('rolls with the local day and always stays two page-1 keys', () => {
    const today = discoverPreloadPageKeys(new Date(2026, 5, 10));
    const tomorrow = discoverPreloadPageKeys(new Date(2026, 5, 11));

    expect(today).toHaveLength(DISCOVER_TOPIC_RAIL_COUNT);
    expect(tomorrow).toHaveLength(DISCOVER_TOPIC_RAIL_COUNT);
    for (const key of [...today, ...tomorrow]) expect(key.startsWith('t:v2:')).toBe(true);
    for (const key of [...today, ...tomorrow]) expect(key.endsWith(':1')).toBe(true);
    expect(today).not.toEqual(tomorrow);
    // No key ever warms the retired Google Books rail path or a fixed-source rail.
    for (const key of [...today, ...tomorrow]) {
      expect(key).not.toContain('builtin:googlebooks');
      expect(key).not.toContain('standard-ebooks');
    }
  });
});
