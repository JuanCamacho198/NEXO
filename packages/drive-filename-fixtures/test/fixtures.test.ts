import { describe, expect, it } from 'vitest';
import fixtures from '../fixtures.json';

/** The id-bearing segment of a readable name: after the last ` -- `, else the whole stem. */
function idSegment(expectedName: string, extension: string): string {
  const stem = expectedName.slice(0, expectedName.length - extension.length - 1);
  const idx = stem.lastIndexOf(' -- ');
  return idx < 0 ? stem : stem.slice(idx + 4);
}


const RESERVED = new Set(
  ['CON', 'PRN', 'AUX', 'NUL'].concat(
    Array.from({ length: 9 }, (_, i) => `COM${i + 1}`),
    Array.from({ length: 9 }, (_, i) => `LPT${i + 1}`),
  ),
);

describe('drive filename fixtures contract', () => {
  it('exposes a non-empty list of input/expectedStem pairs', () => {
    expect(Array.isArray(fixtures.canonical)).toBe(true);
    expect(fixtures.canonical.length).toBeGreaterThanOrEqual(14);
    for (const entry of fixtures.canonical) {
      expect(typeof entry.input).toBe('string');
      expect(typeof entry.expectedStem).toBe('string');
    }
  });

  it('keeps every stem within the canonical alphabet and length bound', () => {
    for (const entry of fixtures.canonical) {
      expect(entry.expectedStem).toMatch(/^[a-z0-9_-]{1,120}$/);
      expect(RESERVED.has(entry.expectedStem.toUpperCase())).toBe(false);
    }
  });

  it('pins the load-bearing cases', () => {
    const byInput = new Map(fixtures.canonical.map((entry) => [entry.input, entry.expectedStem]));
    expect(byInput.get('gutendex:2701')).toBe('gutendex2701');
    expect(byInput.get('')).toBe('book');
    expect(byInput.get(':::')).toBe('book');
    expect(byInput.get('CON')).toBe('con_');
    expect(byInput.get('gutendex2701')).toBe('gutendex2701');
  });

  it('exposes the readable-name surface as title/bookId/extension/expectedName', () => {
    expect(Array.isArray(fixtures.readable)).toBe(true);
    expect(fixtures.readable.length).toBeGreaterThanOrEqual(7);
    for (const entry of fixtures.readable) {
      const ext = entry.extension.toLowerCase();
      expect(typeof entry.title).toBe('string');
      expect(typeof entry.bookId).toBe('string');
      expect(typeof entry.extension).toBe('string');
      expect(typeof entry.expectedName).toBe('string');
      expect(entry.expectedName.endsWith(`.${ext}`)).toBe(true);
      // The id-bearing segment is always present: either after the ` -- `
      // separator, or as the id-only canonical name when the title sanitizes to
      // nothing. It is canonicalized, so `bookId: "CON"` embeds `con_`.
      expect(idSegment(entry.expectedName, ext).length).toBeGreaterThan(0);
    }
  });

  it('pins the code-point truncation boundary', () => {
    expect(Array.isArray(fixtures.truncation)).toBe(true);
    expect(fixtures.truncation.length).toBeGreaterThanOrEqual(1);
    for (const entry of fixtures.truncation) {
      expect(typeof entry.title).toBe('string');
      expect(typeof entry.expectedSanitized).toBe('string');
      // A sanitized title never exceeds the 80-code-point bound.
      expect(Array.from(entry.expectedSanitized).length).toBeLessThanOrEqual(80);
    }
  });
});
