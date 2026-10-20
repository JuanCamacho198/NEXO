/**
 * The location-echo rule — the single owner's arbiter.
 *
 * Every case here is the shape of a real event: the reader publishes a CFI, the
 * parent stores it, and it comes back as `initialLocation`. The rule that turns
 * an infinite feedback loop into a one-shot is `isEcho`; the bounce this fixes
 * was measured live over CDP (`odd/tasks/single-scroll-owner.md`, 5/10 seeded
 * opens), so the rule's edge cases are pinned rather than assumed.
 */
import { describe, expect, it } from 'vitest';
import {
  EMITTED_LOCATIONS_CAP,
  createLocationEcho,
} from '$lib/features/reader/viewer-epub/epubLocationEcho';

describe('createLocationEcho', () => {
  it('treats a remembered location as an echo', () => {
    const echo = createLocationEcho();
    echo.remember('epubcfi(/6/16!/4/2/2/2,/1:0,/1:1)');
    expect(echo.isEcho('epubcfi(/6/16!/4/2/2/2,/1:0,/1:1)')).toBe(true);
  });

  it('does not treat a location that was never emitted as an echo', () => {
    const echo = createLocationEcho();
    echo.remember('epubcfi(/6/16!...)');
    // A genuine external request from the library names a position the reader
    // has not published this session, so it must pass through.
    expect(echo.isEcho('epubcfi(/6/9!/4/4,/1:0,/1:1)')).toBe(false);
  });

  it('recognises an EARLIER emission that arrives after a later one', () => {
    // The measured regression the Set exists for: a turn emits 30 then 29, and
    // 29's echo lands last. A "last value" guard missed this.
    const echo = createLocationEcho();
    echo.remember('cfi-29');
    echo.remember('cfi-30');
    expect(echo.isEcho('cfi-29')).toBe(true);
    expect(echo.isEcho('cfi-30')).toBe(true);
  });

  it('ignores the empty string', () => {
    const echo = createLocationEcho();
    echo.remember('');
    expect(echo.isEcho('')).toBe(false);
  });

  it('bounds memory: the oldest emission is forgotten past the cap', () => {
    const echo = createLocationEcho(3);
    echo.remember('a');
    echo.remember('b');
    echo.remember('c');
    echo.remember('d');
    expect(echo.isEcho('a')).toBe(false);
    expect(echo.isEcho('b')).toBe(true);
    expect(echo.isEcho('d')).toBe(true);
  });

  it('clears the memory (a new book must not inherit the old echoes)', () => {
    const echo = createLocationEcho();
    echo.remember('cfi-1');
    echo.clear();
    expect(echo.isEcho('cfi-1')).toBe(false);
  });

  it('defaults to a cap large enough for a chapter of settles', () => {
    expect(EMITTED_LOCATIONS_CAP).toBeGreaterThanOrEqual(32);
  });
});
