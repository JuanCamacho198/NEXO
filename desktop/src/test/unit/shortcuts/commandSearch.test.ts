import { describe, expect, it } from 'vitest';
import { isSubsequence, matchRank, rankMatches } from '$lib/shared/shortcuts/commandSearch';

type Row = { id: string; label: string; description: string };

const row = (id: string, label: string, description: string): Row => ({ id, label, description });
const getLabel = (item: Row): string => item.label;
const getDescription = (item: Row): string => item.description;

const ids = (rows: Row[]): string[] => rows.map((item) => item.id);

describe('commandSearch', () => {
  it('matches subsequences with gaps and rejects broken ones', () => {
    expect(isSubsequence('libr', 'library')).toBe(true);
    expect(isSubsequence('lbr', 'library')).toBe(true);
    expect(isSubsequence('librx', 'library')).toBe(false);
    expect(isSubsequence('', 'anything')).toBe(true);
  });

  it('ranks a label match above a description-only match', () => {
    const rows = [
      row('byDescription', 'Open Library', 'Discover catalog'),
      row('byLabel', 'Discover', 'Navigation'),
    ];

    expect(ids(rankMatches(rows, 'discover', getLabel, getDescription))).toEqual([
      'byLabel',
      'byDescription',
    ]);
  });

  it('treats a fuzzy label hit as a label match', () => {
    expect(matchRank('lbr', 'Library', 'Navigation')).toBe(0);
    expect(matchRank('navig', 'Library', 'Navigation')).toBe(1);
    expect(matchRank('zzz', 'Library', 'Navigation')).toBeNull();
  });

  it('keeps the input order stable within the same rank', () => {
    const rows = [row('a', 'Alpha', 'x'), row('b', 'Beta', 'x'), row('c', 'Gamma', 'x')];

    // "a" is a fuzzy match for all three labels, so the rank ties and the order holds.
    expect(ids(rankMatches(rows, 'a', getLabel, getDescription))).toEqual(['a', 'b', 'c']);
  });

  it('drops rows that match neither the label nor the description', () => {
    const rows = [row('a', 'Alpha', 'x'), row('z', 'Zulu', 'y')];

    expect(ids(rankMatches(rows, 'lph', getLabel, getDescription))).toEqual(['a']);
  });
});
