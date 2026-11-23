import { beforeEach, describe, expect, it } from 'vitest';
import { getLastChapter, saveLastChapter } from '$lib/shared/stores/lastChapterStore';

describe('lastChapterStore', () => {
  beforeEach(() => localStorage.clear());

  it('remembers the chapter per book', () => {
    saveLastChapter('a', 'Sinopsis');
    saveLastChapter('b', '1. Rusia');
    expect(getLastChapter('a')).toBe('Sinopsis');
    expect(getLastChapter('b')).toBe('1. Rusia');
    expect(getLastChapter('c')).toBeNull();
  });

  it('ignores blank titles and survives corrupt storage', () => {
    saveLastChapter('a', '   ');
    expect(getLastChapter('a')).toBeNull();
    localStorage.setItem('nexo.lastChapter.v1', '{not json');
    expect(getLastChapter('a')).toBeNull();
    saveLastChapter('a', 'Sinopsis');
    expect(getLastChapter('a')).toBe('Sinopsis');
  });
});
