import { describe, expect, it } from 'vitest';
import {
  locatorFromCfi,
  locatorFromJson,
  locatorToJson,
  normalizeHref,
  normalizeLocatorJson,
  parseSpineIndex,
} from '$lib/shared/sync/LocatorCodec';

// Golden vectors shared with Android LocatorCodec.kt
// These vectors MUST stay in sync cross-language.
// If you update them here, update android/app/src/test/java/com/nexo/data/sync/LocatorCodecGoldenTest.kt
const GOLDEN_VECTORS = [
  {
    name: 'backslash href normalized via normalizeHref',
    input: 'OEBPS\\Text\\chapter1.xhtml',
    expected: 'OEBPS/Text/chapter1.xhtml',
  },
  {
    name: 'backslash in readingOrder resolved via spineHrefs',
    readingOrder: ['OEBPS\\Text\\chap1.xhtml', 'OEBPS/Text/chap2.xhtml', 'OEBPS/Text/chap3.xhtml'],
    cfi: 'epubcfi(/6/2!/4/2,/1:0,/1:5)',
    expectedHref: 'OEBPS/Text/chap2.xhtml',
  },
  {
    name: 'locatorJson backslash normalized',
    json: '{"href":"OEBPS\\\\Text\\\\chap1.xhtml","type":"application/xhtml+xml","locations":{"progression":0.5,"fragment":"epubcfi(/6/1!/4/2,/1:0,/1:5)"}}',
    expectedHref: 'OEBPS/Text/chap1.xhtml',
  },
] as const;

describe('LocatorCodec golden cross-language', () => {
  it('normalizeHref converts backslashes to forward slashes (parity with Kotlin)', () => {
    expect(normalizeHref('a\\b\\c')).toBe('a/b/c');
    expect(normalizeHref('OEBPS\\Text\\chap1.xhtml')).toBe('OEBPS/Text/chap1.xhtml');
    expect(normalizeHref('already/correct')).toBe('already/correct');
    expect(normalizeHref(GOLDEN_VECTORS[0].input)).toBe(GOLDEN_VECTORS[0].expected);
  });

  it('parseSpineIndex extracts spine index correctly (parity with Kotlin SPINE_INDEX_RE)', () => {
    expect(parseSpineIndex('epubcfi(/6/1!/4/2,/1:0,/1:5)')).toBe(1);
    expect(parseSpineIndex('epubcfi(/6/24!/4/2,/1:0,/1:5)')).toBe(24);
    expect(parseSpineIndex('epubcfi(/6/2!foo)')).toBe(2);
    expect(parseSpineIndex('invalid')).toBeNull();
    expect(parseSpineIndex(null as unknown as string)).toBeNull();
  });

  it('readingOrder = spineHrefs: locatorFromCfi resolves via spine index', () => {
    const readingOrder = GOLDEN_VECTORS[1].readingOrder as unknown as string[];
    const loc = locatorFromCfi(readingOrder, GOLDEN_VECTORS[1].cfi, null);
    expect(loc).not.toBeNull();
    expect(loc!.href).toBe(GOLDEN_VECTORS[1].expectedHref);
    // Also verify backslash in readingOrder is normalized
    const loc2 = locatorFromCfi(readingOrder, 'epubcfi(/6/1!/4/2,/1:0,/1:5)', null);
    expect(loc2!.href).toBe('OEBPS/Text/chap1.xhtml');
  });

  it('locatorToJson normalizes href backslashes before serialization', () => {
    const loc = locatorFromCfi(
      ['OEBPS\\Text\\chap1.xhtml', 'b.html'],
      'epubcfi(/6/1!/4/2,/1:0,/1:5)',
      null,
    )!;
    const json = locatorToJson(loc);
    const parsed = JSON.parse(json) as { href: string };
    expect(parsed.href).toBe('OEBPS/Text/chap1.xhtml');
    expect(json).not.toContain('\\');
  });

  it('locatorFromJson normalizes backslash href on deserialization (golden)', () => {
    const v = GOLDEN_VECTORS[2];
    const loc = locatorFromJson(v.json as string);
    expect(loc).not.toBeNull();
    expect(loc!.href).toBe(v.expectedHref);
  });

  it('normalizeLocatorJson parity: backslash in href replaced, CFI preserved', () => {
    const json = GOLDEN_VECTORS[2].json as string;
    const normalized = normalizeLocatorJson(json)!;
    const parsed = JSON.parse(normalized) as { href: string; locations: { fragment: string } };
    expect(parsed.href).toBe(GOLDEN_VECTORS[2].expectedHref);
    expect(parsed.locations.fragment).toBe('epubcfi(/6/1!/4/2,/1:0,/1:5)');
  });

  it('round-trip spineHrefs golden: Historia offset-2 simulation', () => {
    // Simulate Historia: spine 4, TOC 0 offset (spine[2] is first TOC entry)
    const spineHrefs = [
      'OEBPS/Text/cover.xhtml',
      'OEBPS/Text/toc.xhtml',
      'OEBPS/Text/HM-colombia-1.html',
      'OEBPS/Text/HM-colombia-2.html',
      'OEBPS/Text/HM-colombia-3.html',
    ];
    // Toc entry 0 maps to spine index 2 (0-based) => cfi /6/3
    const cfiForToc0 = 'epubcfi(/6/3!/4/2,/1:0,/1:5)';
    const loc = locatorFromCfi(spineHrefs, cfiForToc0, { chapterChars: 1000, charOffset: 250 });
    expect(loc).not.toBeNull();
    expect(loc!.href).toBe('OEBPS/Text/HM-colombia-1.html');
    expect(loc!.locations.progression).toBeCloseTo(0.25, 5);
    expect(loc!.locations.fragment).toBe(cfiForToc0);
    const json = locatorToJson(loc!);
    const restored = locatorFromJson(json)!;
    expect(restored.href).toBe(loc!.href);
    expect(restored.locations.fragment).toBe(cfiForToc0);
  });
});
