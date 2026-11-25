import { describe, expect, it } from 'vitest';
import { chapterFromLocator, tocIndexForSpineIndex } from '$lib/features/reader/chrome/spineToToc';

// Cover, title, a part heading sharing its document with chapter 1, chapter 1
// spread over spine 4-6, chapter 2 from spine 7.
const toc = [
  { index: 0 }, // Portada
  { index: 2 }, // Título
  { index: 4 }, // Primera parte
  { index: 4 }, // 1. En la línea de salida
  { index: 7 }, // 2. Un experimento natural
  {}, // an entry the extractor could not resolve
];

describe('tocIndexForSpineIndex', () => {
  it('names the chapter a continuation document belongs to', () => {
    expect(tocIndexForSpineIndex(5, toc, 0)).toBe(3);
    expect(tocIndexForSpineIndex(6, toc, 0)).toBe(3);
    expect(tocIndexForSpineIndex(9, toc, 0)).toBe(4);
  });

  it('prefers the specific chapter over the part heading sharing its document', () => {
    expect(tocIndexForSpineIndex(4, toc, 0)).toBe(3);
  });

  it('keeps the fallback before the outline starts or for a bad index', () => {
    const late = [{ index: 3 }, { index: 5 }];
    expect(tocIndexForSpineIndex(1, late, 7)).toBe(7);
    expect(tocIndexForSpineIndex(-1, toc, 2)).toBe(2);
    expect(tocIndexForSpineIndex(Number.NaN, toc, 2)).toBe(2);
  });
});

describe('chapterFromLocator', () => {
  it('reads the 1-based spine step of the app CFI', () => {
    // `/6/6` is spine index 5, the middle of chapter 1.
    expect(chapterFromLocator('epubcfi(/6/6!/4/2,/1:0,/1:5)', toc, 0)).toBe(3);
  });

  it('returns null when the locator says nothing', () => {
    expect(chapterFromLocator('not-a-cfi', toc, 0)).toBeNull();
  });
});
