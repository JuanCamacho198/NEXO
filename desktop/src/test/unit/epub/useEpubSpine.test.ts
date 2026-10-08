import { describe, expect, it, vi } from 'vitest';
import { createEpubSpine } from '$lib/features/reader/viewer-epub/useEpubSpine.svelte';
import { normalizeHref, stripFragment } from '$lib/features/reader/viewer-epub/epubViewerHelpers';
import { logger } from '$lib/shared/logger/Logger';

describe('useEpubSpine — spine authority + cache guard', () => {
  function metaWithToc(spineHrefs: string[], toc: any[], totalChapters = spineHrefs.length) {
    return {
      title: 'Test',
      author: 'A',
      language: null,
      publisher: null,
      toc,
      spineHrefs,
      totalChapters,
      resourcesPath: '/tmp',
    } as any;
  }

  it('getToc returns toc or empty', () => {
    const toc = [{ index: 0, id: 'a', label: 'A', href: 'a.xhtml' }];
    const spine = createEpubSpine({ getMetadata: () => metaWithToc(['a.xhtml'], toc) });
    expect(spine.getToc()).toEqual(toc);
    const empty = createEpubSpine({ getMetadata: () => null });
    expect(empty.getToc()).toEqual([]);
  });

  it('getSpineHrefs prefers spineHrefs, strips via normalizeHref', () => {
    const toc = [{ index: 0, id: 'a', label: 'A', href: 'a.xhtml' }];
    const spine = createEpubSpine({
      getMetadata: () => metaWithToc(['OEBPS\\Text\\a.xhtml', 'b.xhtml'], toc),
    });
    expect(spine.getSpineHrefs()).toEqual(['OEBPS/Text/a.xhtml', 'b.xhtml']);
  });

  it('getSpineHrefs falls back to TOC-derived when spine empty (offset-2 warning path)', () => {
    const toc = [
      { index: 2, id: 'a', label: 'A', href: 'OEBPS/Text/a.xhtml#frag' },
      { index: 3, id: 'b', label: 'B', href: 'OEBPS/Text/b.xhtml' },
    ];
    const spine = createEpubSpine({ getMetadata: () => metaWithToc([], toc) });
    const warn = vi.spyOn(logger, 'warn').mockImplementation(() => {});
    const hrefs = spine.getSpineHrefs();
    expect(hrefs).toEqual(['OEBPS/Text/a.xhtml', 'OEBPS/Text/b.xhtml']);
    expect(warn).toHaveBeenCalled();
    warn.mockRestore();
  });

  it('spineIndexForToc delegates to pure helper (offset-2)', () => {
    const toc = [
      { index: 2, id: 'a', label: 'A', href: 'a.xhtml' },
      { index: 5, id: 'b', label: 'B', href: 'b.xhtml' },
    ];
    const spine = createEpubSpine({
      getMetadata: () =>
        metaWithToc(['a.xhtml', 'b.xhtml', 'c.xhtml', 'd.xhtml', 'e.xhtml', 'f.xhtml'], toc, 6),
    });
    expect(spine.spineIndexForToc(0)).toBe(2);
    expect(spine.spineIndexForToc(1)).toBe(5);
  });

  it('tocIndexForSpine delegates and returns null when not in TOC', () => {
    const toc = [
      { index: 2, id: 'a', label: 'A', href: 'OEBPS/Text/a.xhtml' },
      { index: 3, id: 'b', label: 'B', href: 'OEBPS/Text/b.xhtml' },
    ];
    const spine = createEpubSpine({
      getMetadata: () =>
        metaWithToc(['x.xhtml', 'y.xhtml', 'OEBPS/Text/a.xhtml', 'OEBPS/Text/b.xhtml'], toc),
    });
    expect(spine.tocIndexForSpine(2)).toBe(0);
    expect(spine.tocIndexForSpine(0)).toBeNull();
  });

  it('ensureSpineHrefs cache guard delegates to resolver (once per bookId)', async () => {
    const parseMock = vi.fn().mockResolvedValue({ spineHrefs: ['a.xhtml', 'b.xhtml'] });
    const spine = createEpubSpine({ getMetadata: () => null, parseEpub: parseMock });
    await spine.ensureSpineHrefs('book-1', '/tmp/book.epub');
    expect(parseMock).toHaveBeenCalledTimes(1);
    expect(spine.spineHrefs).toEqual(['a.xhtml', 'b.xhtml']);
    await spine.ensureSpineHrefs('book-1', '/tmp/book.epub');
    expect(parseMock).toHaveBeenCalledTimes(1); // cached
    await spine.ensureSpineHrefs('book-2', '/tmp/other.epub');
    expect(parseMock).toHaveBeenCalledTimes(2);
  });

  it('re-exports normalizeHref/stripFragment', () => {
    expect(normalizeHref('a\\b')).toBe('a/b');
    expect(stripFragment('a.xhtml#frag')).toBe('a.xhtml');
    const spine = createEpubSpine({ getMetadata: () => null });
    expect(spine.normalizeHref('a\\b')).toBe('a/b');
    expect(spine.stripFragment('a.xhtml#frag')).toBe('a.xhtml');
  });
});
