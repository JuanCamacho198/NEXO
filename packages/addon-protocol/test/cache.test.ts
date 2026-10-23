import { describe, expect, it } from 'vitest';
import { TtlCache } from '../src/cache';

function clock(start = 1_000_000) {
  let current = start;
  return {
    now: () => current,
    advance(ms: number) {
      current += ms;
    },
  };
}

describe('TtlCache', () => {
  it('returns undefined for a key it never stored', () => {
    const cache = new TtlCache({ now: clock().now });
    expect(cache.get('missing')).toBeUndefined();
  });

  it('expires an entry against the injected clock, not wall time', () => {
    const time = clock();
    const cache = new TtlCache({ now: time.now });
    cache.set('k', { value: 1 }, 300_000);

    time.advance(299_999);
    expect(cache.get('k')).toEqual({ value: 1 });

    // The ttl boundary is exclusive: 300 s after the write the entry is gone.
    time.advance(1);
    expect(cache.get('k')).toBeUndefined();
  });

  it('re-sets a key with a fresh value and a fresh ttl', () => {
    const time = clock();
    const cache = new TtlCache({ now: time.now });
    cache.set('k', 'first', 1_000);
    cache.set('k', 'second', 1_000);
    expect(cache.get('k')).toBe('second');

    time.advance(999);
    expect(cache.get('k')).toBe('second');
    time.advance(1);
    expect(cache.get('k')).toBeUndefined();
  });

  it('evicts the least recently used entry at maxEntries', () => {
    const cache = new TtlCache({ now: clock().now, maxEntries: 2 });
    cache.set('a', 1, 60_000);
    cache.set('b', 2, 60_000);

    // Reading `a` makes `b` the least recently used entry.
    expect(cache.get('a')).toBe(1);
    cache.set('c', 3, 60_000);

    expect(cache.get('a')).toBe(1);
    expect(cache.get('b')).toBeUndefined();
    expect(cache.get('c')).toBe(3);
  });

  it('evicts an expired entry before a live one', () => {
    const time = clock();
    const cache = new TtlCache({ now: time.now, maxEntries: 2 });
    cache.set('short', 'gone-soon', 10);
    cache.set('long', 'stays', 60_000);

    time.advance(10);
    cache.set('new', 'fresh', 60_000);

    expect(cache.get('long')).toBe('stays');
    expect(cache.get('new')).toBe('fresh');
  });

  it('drops every entry on clear()', () => {
    const cache = new TtlCache({ now: clock().now });
    cache.set('a', 1, 60_000);
    cache.clear();
    expect(cache.get('a')).toBeUndefined();
  });
});
