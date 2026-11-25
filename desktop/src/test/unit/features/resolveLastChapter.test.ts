import { describe, expect, it, vi } from 'vitest';
import { resolveLastChapter } from '$lib/features/home/resolveLastChapter';

const toc = [
  { index: 0, label: 'Portada' },
  { index: 2, label: 'Prólogo' },
  { index: 4, label: '3. Estados Unidos' },
  { index: 8, label: '4. Europa Occidental' },
];
const epub = { id: 'b1', format: 'epub', filePath: 'C:/b1.epub' };

function deps(cfi: string | null) {
  return {
    getProgress: vi.fn(async () => (cfi === null ? null : { cfiLocation: cfi })),
    getToc: vi.fn(async () => toc),
  };
}

describe('resolveLastChapter', () => {
  it('names the chapter containing the saved position, even mid-chapter', async () => {
    // `/6/7` is spine 6: a continuation document of chapter 3.
    expect(await resolveLastChapter(epub, deps('epubcfi(/6/7!/4/2,/1:0,/1:1)'))).toBe(
      '3. Estados Unidos',
    );
    expect(await resolveLastChapter(epub, deps('epubcfi(/6/9!/4/2)'))).toBe('4. Europa Occidental');
  });

  it('answers nothing it cannot stand behind', async () => {
    expect(await resolveLastChapter(epub, deps(null))).toBeNull();
    expect(await resolveLastChapter(epub, deps('page:12'))).toBeNull();
    expect(await resolveLastChapter({ ...epub, format: 'pdf' }, deps('epubcfi(/6/7!)'))).toBeNull();
    const failing = {
      getProgress: async () => {
        throw new Error('ipc');
      },
      getToc: async () => toc,
    };
    expect(await resolveLastChapter(epub, failing)).toBeNull();
  });
});
