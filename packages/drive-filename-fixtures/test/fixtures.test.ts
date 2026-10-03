import { describe, expect, it } from 'vitest';
import fixtures from '../fixtures.json';

const RESERVED = new Set(
  ['CON', 'PRN', 'AUX', 'NUL'].concat(
    Array.from({ length: 9 }, (_, i) => `COM${i + 1}`),
    Array.from({ length: 9 }, (_, i) => `LPT${i + 1}`),
  ),
);

describe('drive filename fixtures contract', () => {
  it('exposes a non-empty list of input/expectedStem pairs', () => {
    expect(Array.isArray(fixtures)).toBe(true);
    expect(fixtures.length).toBeGreaterThanOrEqual(14);
    for (const entry of fixtures) {
      expect(typeof entry.input).toBe('string');
      expect(typeof entry.expectedStem).toBe('string');
    }
  });

  it('keeps every stem within the canonical alphabet and length bound', () => {
    for (const entry of fixtures) {
      expect(entry.expectedStem).toMatch(/^[a-z0-9_-]{1,120}$/);
      expect(RESERVED.has(entry.expectedStem.toUpperCase())).toBe(false);
    }
  });

  it('pins the load-bearing cases', () => {
    const byInput = new Map(fixtures.map((entry) => [entry.input, entry.expectedStem]));
    expect(byInput.get('gutendex:2701')).toBe('gutendex2701');
    expect(byInput.get('')).toBe('book');
    expect(byInput.get(':::')).toBe('book');
    expect(byInput.get('CON')).toBe('con_');
    expect(byInput.get('gutendex2701')).toBe('gutendex2701');
  });
});
