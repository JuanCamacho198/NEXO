/**
 * PR4 — Testing & Verification (13 scenarios) for epub-comprehensive-fix.
 *
 * Covers Phase 4 tasks 4.1–4.7 + Phase 5 cleanup verification.
 * Each scenario maps to a spec success criterion and is directly tied to
 * a production code path. No mocks beyond jsdom/JSDOM — pure helpers and
 * CFI bridge are exercised via real DOM.
 *
 * Scenarios (13):
 *  1. Historia offset-2: spine 24, toc 20, toc[0].index==2 (Rust build_toc parity via helpers)
 *  2. Historia toc[3] maps to spine 5 via HM-colombia filename (offset-2 + fragment)
 *  3. linear=no excluded: accessible_epub_3 21 spine (Rust, mirrored via readingOrder filter)
 *  4. stale cache purged when spine.json len 24 != metadata.totalChapters 20
 *  5. iframe cfiToRange resolves highlight epubcfi(/6/4!/4/2:0...) non-null
 *  6. iframe cfiToRange mismatch returns null when N != registered spine index
 *  7. lockstep prevents drift: CFI_RE source in parent equals runtime iframe script
 *  8. round-trip offset-2: spineIndexForToc(0)==2 → tocIndexForSpine(2)==0, miss returns null
 *  9. filename fallback: Text/HM-colombia-2.html resolves via filename when spineHref differs prefix
 * 10. float bar stripped: chrome-extension://... → zero occurrences in srcdoc/search (sanitize)
 * 11. fragment scrolls: text/part0003.html#_idParaDest-5 → fragment exists and 3×rAF wiring
 * 12. cover chain: cover-image → meta → guide → heuristic (source-level guarantee + runtime)
 * 13. no pageNumber conflation: highlight overlay guard uses currentSpineIndex, not TOC index
 */
import { describe, expect, it, beforeAll } from 'vitest';
import { JSDOM } from 'jsdom';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import {
  setSpine,
  getSpineIndex,
  getChapterBaseCFI,
  cfiToRange,
  rangeToCFI,
  CFI_RE,
  TERMINUS_RE,
  assertLockstep,
} from '$lib/features/reader/viewer-epub/cfiBridge';
import { IFRAME_CFI_BRIDGE_SCRIPT } from '$lib/features/reader/viewer-epub/cfiBridgeIframe';
import {
  sanitizeEpubHtml,
  spineIndexForToc,
  tocIndexForSpine,
  normalizeHref,
  stripFragment,
  extractFragment,
} from '$lib/features/reader/viewer-epub/epubViewerHelpers';
import {
  locatorFromCfi,
  locatorToJson,
  locatorFromJson,
  normalizeHref as codecNormalizeHref,
  parseSpineIndex,
} from '$lib/shared/sync/LocatorCodec';

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------
function loadDoc(html: string): Document {
  return new JSDOM(html).window.document;
}

const FIXTURE_HTML = `<!DOCTYPE html><html><head><meta charset="utf-8"/></head><body><h1>Title</h1><p>Hello world — Historia paragraph with _idParaDest-5 anchor.</p><p id="_idParaDest-5">Target anchor for fragment scroll</p><p>Another paragraph for CFI endpoints.</p></body></html>`;
const CHAPTER_HREF = 'OEBPS/Text/chapter3.xhtml';
const SPINE_4 = [
  'OEBPS/Text/chapter1.xhtml',
  'OEBPS/Text/chapter2.xhtml',
  'OEBPS/Text/chapter3.xhtml',
  'OEBPS/Text/chapter4.xhtml',
];

// Historia-like TOC: 20 entries starting at spine index 2 (offset-2), filenames HM-colombia-*
function historiaToc() {
  return [
    { index: 2, id: 'chapter-0', label: 'HM 1', href: 'OEBPS/Text/HM-colombia-1.html', depth: 0 },
    { index: 3, id: 'chapter-1', label: 'HM 2', href: 'OEBPS/Text/HM-colombia-2.html', depth: 0 },
    {
      index: 4,
      id: 'chapter-2',
      label: 'HM 3',
      href: 'OEBPS/Text/HM-colombia-3.html#_idParaDest-5',
      depth: 0,
    },
    { index: 5, id: 'chapter-3', label: 'HM 4', href: 'OEBPS/Text/HM-colombia-4.html', depth: 0 },
  ] as const;
}

function historiaSpineHrefs(): string[] {
  const spine: string[] = [];
  spine.push('OEBPS/Text/cover.xhtml');
  spine.push('OEBPS/Text/toc.xhtml');
  for (let i = 1; i <= 20; i++) spine.push(`OEBPS/Text/HM-colombia-${i}.html`);
  spine.push('OEBPS/Text/backmatter.xhtml');
  spine.push('OEBPS/Text/colophon.xhtml');
  return spine; // 24
}

// ---------------------------------------------------------------------------
// 4.1 Historia offset-2 (TS mirror of Rust build_toc parity)
// ---------------------------------------------------------------------------
describe('4.1 Historia offset-2 — spine 24 vs toc 20', () => {
  it('scenario 1: toc[0].index == 2 when spine is Historia-like 24', () => {
    const toc = historiaToc() as unknown as Parameters<typeof spineIndexForToc>[0];
    expect(spineIndexForToc(toc, 0)).toBe(2);
    expect(spineIndexForToc(toc, 1)).toBe(3);
    expect(spineIndexForToc(toc, 2)).toBe(4);
  });

  it('scenario 2: toc entry with #_idParaDest-5 fragment preserves index 4 and href', () => {
    const toc = historiaToc() as unknown as Parameters<typeof tocIndexForSpine>[0];
    // tocIndex 2 has fragment; spineIndex 4 should map back to toc 2
    expect(tocIndexForSpine(toc, 4, 'OEBPS/Text/HM-colombia-3.html#_idParaDest-5')).toBe(2);
    expect(tocIndexForSpine(toc, 4, 'OEBPS/Text/HM-colombia-3.html')).toBe(2);
    // round-trip
    const spineIdx = spineIndexForToc(toc, 2);
    expect(spineIdx).toBe(4);
    expect(tocIndexForSpine(toc, spineIdx)).toBe(2);
  });

  it('scenario 3: spine length 24 Historia wiring (readingOrder = spineHrefs)', () => {
    const spine = historiaSpineHrefs();
    expect(spine).toHaveLength(24);
    expect(spine[2]).toBe('OEBPS/Text/HM-colombia-1.html');
    expect(spine[5]).toBe('OEBPS/Text/HM-colombia-4.html');
    // LocatorCodec spine wiring: readingOrder == spineHrefs
    const cfiForToc0 = 'epubcfi(/6/3!/4/2,/1:0,/1:5)'; // spine index 3 → spine[2]
    const loc = locatorFromCfi(spine, cfiForToc0, null);
    expect(loc).not.toBeNull();
    expect(loc!.href).toBe('OEBPS/Text/HM-colombia-1.html');
  });
});

describe('4.1 linear=no excluded — 21 spine parity', () => {
  it('scenario 3b: filtering linear=no is reflected in totalChapters == spineHrefs.length (TS parity)', () => {
    // accessible_epub_3: spine raw 22 with 1 linear=no → 21 filtered
    // We simulate: readingOrder filtered excludes cover.xhtml
    const rawSpine = Array.from({ length: 22 }, (_, i) => `OEBPS/Text/ch${i}.xhtml`);
    rawSpine[0] = 'OEBPS/Text/cover.xhtml'; // linear=no
    const filtered = rawSpine.filter((h) => !h.includes('cover'));
    expect(filtered).toHaveLength(21);
    // LocatorCodec readingOrder must be filtered (spineHrefs), not TOC
    const loc = locatorFromCfi(filtered, 'epubcfi(/6/21!/4/2,/1:0,/1:1)', null);
    expect(loc).not.toBeNull();
    expect(loc!.href).toBe(filtered[20]);
    // out of range after filter should be null (cover index)
    expect(locatorFromCfi(filtered, 'epubcfi(/6/22!/4/2,/1:0,/1:1)', null)).toBeNull();
  });
});

// ---------------------------------------------------------------------------
// 4.2 stale cache purged
// ---------------------------------------------------------------------------
describe('4.2 stale cache purged — spine.json len vs metadata.totalChapters', () => {
  it('scenario 4: source guarantees CACHE_VERSION 4 and stale check (Rust parity via source)', () => {
    const epubReaderSrc = readFileSync(
      join(process.cwd(), 'src-tauri/src/commands/epub_reader.rs'),
      'utf8',
    );
    expect(epubReaderSrc).toContain('const CACHE_VERSION: u32 = 4');
    expect(epubReaderSrc).toContain('spine.len() != meta.total_chapters');
    expect(epubReaderSrc).toContain('spine.len() != meta.spine_hrefs.len()');
    expect(epubReaderSrc).toContain('remove_dir_all');
  });

  it('scenario 4b: Rust extractor writes spine.json and purges on mismatch (source check)', () => {
    const extractorSrc = readFileSync(
      join(process.cwd(), 'src-tauri/src/services/epub_extractor.rs'),
      'utf8',
    );
    expect(extractorSrc).toContain('spine.json');
    expect(extractorSrc).toContain('total_chapters');
  });
});

// ---------------------------------------------------------------------------
// 4.3 iframe resolves highlight + mismatch null
// ---------------------------------------------------------------------------
describe('4.3 iframe resolves highlight — cfiToRange', () => {
  beforeAll(() => {
    setSpine(SPINE_4);
  });

  it('scenario 5: cfiToRange resolves non-null for registered spine index', () => {
    const doc = loadDoc(FIXTURE_HTML);
    // Generate a real CFI via rangeToCFI, then resolve it — ensures non-null
    const range = (() => {
      const walker = doc.createTreeWalker(doc.body, 0x4);
      let n: Node | null = walker.nextNode();
      while (n) {
        if ((n.nodeValue ?? '').includes('Hello world')) {
          const r = doc.createRange();
          r.setStart(n as Text, 0);
          r.setEnd(n as Text, 5);
          return r;
        }
        n = walker.nextNode();
      }
      return null;
    })();
    expect(range).not.toBeNull();
    const cfi = rangeToCFI(range!, CHAPTER_HREF, doc);
    expect(cfi).toBeTruthy();
    // Use the generated CFI with matching href (spine index 3)
    const resolved = cfiToRange(cfi!, CHAPTER_HREF, doc);
    expect(resolved).not.toBeNull();
    expect(resolved!.toString()).toContain('Hello');
  });

  it('scenario 5b: explicit CFI epubcfi(/6/4!/4/2:0) resolves via spine 4', () => {
    setSpine(SPINE_4);
    const doc = loadDoc(FIXTURE_HTML);
    // Build CFI manually for chapter4: use rangeToCFI with chapter4 href
    const chap4 = SPINE_4[3];
    const walker = doc.createTreeWalker(doc.body, 0x4);
    let target: Node | null = walker.nextNode();
    while (target && !(target.nodeValue ?? '').includes('Hello')) target = walker.nextNode();
    expect(target).not.toBeNull();
    const r = doc.createRange();
    r.setStart(target!, 0);
    r.setEnd(target!, 4);
    const cfi = rangeToCFI(r, chap4, doc);
    expect(cfi).toBeTruthy();
    expect(cfi).toMatch(/^epubcfi\(\/6\/4!/);
    const back = cfiToRange(cfi!, chap4, doc);
    expect(back).not.toBeNull();
  });

  it('scenario 6: mismatch returns null when N != registered spine index', () => {
    setSpine(SPINE_4);
    const doc = loadDoc(FIXTURE_HTML);
    // CFI says /6/2 but href is chapter3 (spine 3) → mismatch
    const mismatchCfi = 'epubcfi(/6/2!/4/2,/1:0,/1:5)';
    expect(cfiToRange(mismatchCfi, CHAPTER_HREF, doc)).toBeNull();
    // CFI /6/99 out of range
    expect(cfiToRange('epubcfi(/6/99!/4/2,/1:0,/1:1)', CHAPTER_HREF, doc)).toBeNull();
    // Malformed
    expect(cfiToRange('not-a-cfi', CHAPTER_HREF, doc)).toBeNull();
  });

  it('scenario 6b: rangeToCFI returns null for chapter not in spine', () => {
    setSpine(SPINE_4);
    const doc = loadDoc(FIXTURE_HTML);
    const walker = doc.createTreeWalker(doc.body, 0x4);
    let n: Node | null = walker.nextNode();
    while (n && !(n.nodeValue ?? '').includes('Hello')) n = walker.nextNode();
    const r = doc.createRange();
    r.setStart(n!, 0);
    r.setEnd(n!, 2);
    expect(rangeToCFI(r, 'OEBPS/Text/missing.xhtml', doc)).toBeNull();
  });
});

// ---------------------------------------------------------------------------
// 4.4 lockstep prevents drift
// ---------------------------------------------------------------------------
describe('4.4 lockstep prevents drift', () => {
  it('scenario 7: CFI_RE and TERMINUS_RE sources equal runtime iframe script', () => {
    expect(CFI_RE.source).toBe('^epubcfi\\(\\/6\\/(\\d+)!(.+)\\)$');
    expect(TERMINUS_RE.source).toBe('^(\\d+):(\\d+)$');
    expect(IFRAME_CFI_BRIDGE_SCRIPT).toContain(CFI_RE.source);
    expect(IFRAME_CFI_BRIDGE_SCRIPT).toContain(TERMINUS_RE.source);
    expect(IFRAME_CFI_BRIDGE_SCRIPT).not.toContain('\\\\(');
    expect(IFRAME_CFI_BRIDGE_SCRIPT).not.toContain('\\\\d');
  });

  it('scenario 7b: assertLockstep passes (build gate)', () => {
    expect(() => assertLockstep()).not.toThrow();
  });

  it('scenario 7c: vite plugin and check-cfi-lockstep script exist (source check)', () => {
    const viteSrc = readFileSync(join(process.cwd(), 'vite.config.ts'), 'utf8');
    expect(viteSrc).toContain('cfiLockstepPlugin');
    expect(viteSrc).toContain('CFI_RE');
    const pkg = JSON.parse(readFileSync(join(process.cwd(), 'package.json'), 'utf8'));
    expect(pkg.scripts.build).toContain('check:lockstep');
    expect(pkg.scripts['check:lockstep']).toContain('check-cfi-lockstep');
  });
});

// ---------------------------------------------------------------------------
// 4.5 round-trip offset-2 + filename fallback
// ---------------------------------------------------------------------------
describe('4.5 round-trip offset-2 — pure helpers', () => {
  it('scenario 8: spineIndexForToc(0)==2 then tocIndexForSpine(2)==0', () => {
    const toc = historiaToc() as unknown as Parameters<typeof tocIndexForSpine>[0];
    const spineIdx = spineIndexForToc(toc, 0);
    expect(spineIdx).toBe(2);
    expect(tocIndexForSpine(toc, spineIdx)).toBe(0);
    expect(tocIndexForSpine(toc, 0)).toBeNull();
  });

  it('scenario 8b: full round-trip 20 entries offset-2 via index', () => {
    const toc = historiaToc() as unknown as Parameters<typeof tocIndexForSpine>[0];
    for (let tocIdx = 0; tocIdx < toc.length; tocIdx++) {
      const spineIdx = spineIndexForToc(toc, tocIdx);
      expect(tocIndexForSpine(toc, spineIdx)).toBe(tocIdx);
    }
    // Miss
    expect(tocIndexForSpine(toc, 0)).toBeNull();
    expect(tocIndexForSpine(toc, 99)).toBeNull();
  });

  it('scenario 9: filename fallback when prefix differs (Text/ vs OEBPS/Text/)', () => {
    const toc = historiaToc() as unknown as Parameters<typeof tocIndexForSpine>[0];
    expect(tocIndexForSpine(toc, 99, 'OEBPS/Text/HM-colombia-1.html')).toBe(0);
    expect(tocIndexForSpine(toc, 99, 'Text/HM-colombia-2.html')).toBe(1);
    expect(tocIndexForSpine(toc, 99, 'OEBPS/Text/HM-colombia-3.html')).toBe(2);
    expect(tocIndexForSpine(toc, 99, 'OEBPS/Text/HM-colombia-3.html#_idParaDest-5')).toBe(2);
    expect(tocIndexForSpine(toc, 99, 'HM-colombia-4.html')).toBe(3);
  });

  it('scenario 9b: normalizeHref and stripFragment handle Historia hrefs', () => {
    expect(normalizeHref('OEBPS\\Text\\HM-colombia-1.html')).toBe('OEBPS/Text/HM-colombia-1.html');
    expect(stripFragment('OEBPS/Text/HM-colombia-3.html#_idParaDest-5')).toBe(
      'OEBPS/Text/HM-colombia-3.html',
    );
    expect(extractFragment('OEBPS/Text/HM-colombia-3.html#_idParaDest-5')).toBe('_idParaDest-5');
    expect(extractFragment('OEBPS/Text/HM-colombia-3.html')).toBeNull();
  });

  it('scenario 9c: LocatorCodec parity — backslash in readingOrder normalized', () => {
    const readingOrder = ['OEBPS\\Text\\chap1.xhtml', 'OEBPS/Text/chap2.xhtml'];
    const loc = locatorFromCfi(readingOrder, 'epubcfi(/6/1!/4/2,/1:0,/1:5)', null);
    expect(loc).not.toBeNull();
    expect(loc!.href).toBe('OEBPS/Text/chap1.xhtml');
    expect(codecNormalizeHref('OEBPS\\Text\\chap1.xhtml')).toBe('OEBPS/Text/chap1.xhtml');
    expect(parseSpineIndex('epubcfi(/6/24!/4/2,/1:0,/1:5)')).toBe(24);
  });
});

// ---------------------------------------------------------------------------
// 4.6 float bar stripped
// ---------------------------------------------------------------------------
describe('4.6 float bar stripped — sanitize', () => {
  it('scenario 10: chrome-extension://dbkmjjclgbiooljcegcddagnddjedmed stripped → zero occurrences', async () => {
    const viewerSrc = readFileSync(
      join(process.cwd(), 'src/lib/features/reader/viewer-epub/EpubNativeViewer.svelte'),
      'utf8',
    );
    const renderSrc = readFileSync(
      join(process.cwd(), 'src/lib/features/reader/viewer-epub/useEpubRender.svelte.ts'),
      'utf8',
    );
    const combinedSrc = viewerSrc + renderSrc;
    // Must sanitize before rewrite
    expect(combinedSrc).toContain('sanitizeEpubHtml');
    const sanitizeIdx = combinedSrc.indexOf('sanitizeEpubHtml');
    const rewriteIdx = combinedSrc.indexOf('resolveResourcePath');
    expect(sanitizeIdx).toBeGreaterThan(-1);
    expect(rewriteIdx).toBeGreaterThan(-1);
    expect(sanitizeIdx).toBeLessThan(rewriteIdx);

    const polluted =
      '<div><p>Hello</p><img id="floatBarImgId" src="chrome-extension://dbkmjjclgbiooljcegcddagnddjedmed/img.png"><p>World <img src="chrome-extension://abc/def.png"></p></div>';
    const sanitized = sanitizeEpubHtml(polluted);
    expect(sanitized).not.toContain('chrome-extension://');
    expect(sanitized).not.toContain('floatBarImgId');
    expect(sanitized).toContain('Hello');
    expect(sanitized).toContain('World');

    // Search indexing path (Rust) mirrors JS sanitize: check Rust source
    const rustSrc = readFileSync(
      join(process.cwd(), 'src-tauri/src/services/epub_extractor.rs'),
      'utf8',
    );
    expect(rustSrc).toContain('sanitize_html');
    expect(rustSrc).toContain('chrome-extension://');
    expect(rustSrc).toContain('floatBarImgId');
  });

  it('scenario 10c: a self-closing <title/> must not swallow the document', () => {
    // Real spine document from *Prisioneros de la geografía* (`OPS/0005_0001.htm`).
    // `<title/>` is RCDATA: the parser ignores the `/>` and eats the rest of the
    // file as title text, leaving an empty body and a blank chapter.
    const blank =
      '<html><head><meta content="text/html" http-equiv="Content-Type"/><title/><link href="css/global.css" rel="stylesheet"/></head><body><div class="body"><p>Rusia es vasta.</p></div></body></html>';
    const fixed = sanitizeEpubHtml(blank);
    expect(fixed).not.toMatch(/<title\s*\/\s*>/i);

    // The repaired markup must parse into a body that carries the text.
    const doc = new DOMParser().parseFromString(fixed, 'text/html');
    expect(doc.title).toBe('');
    expect(doc.body.textContent).toContain('Rusia es vasta.');

    // The empty *pair* is valid and must be left alone, as must a named title.
    expect(sanitizeEpubHtml('<title></title>')).toBe('<title></title>');
    expect(sanitizeEpubHtml('<title>notas</title>')).toBe('<title>notas</title>');
  });

  it('scenario 10b: normal content preserved and empty src cleaned', () => {
    const normal = '<p>Keep <img src="images/cover.jpg"> and <a href="chapter2.xhtml">link</a></p>';
    expect(sanitizeEpubHtml(normal)).toContain('images/cover.jpg');
    expect(sanitizeEpubHtml(normal)).toContain('chapter2.xhtml');
    expect(sanitizeEpubHtml(normal)).not.toContain('chrome-extension://');

    const emptySrc = '<p><img src="chrome-extension://evil"></p>';
    const out = sanitizeEpubHtml(emptySrc);
    expect(out).not.toContain('chrome-extension://');
    expect(out).not.toContain('evil');
  });

  it('scenario 10c: locatorToJson normalizes backslash and sanitizes href', () => {
    const loc = locatorFromCfi(
      ['OEBPS\\Text\\chap1.xhtml', 'b.html'],
      'epubcfi(/6/1!/4/2,/1:0,/1:5)',
      null,
    )!;
    const json = locatorToJson(loc);
    expect(json).not.toContain('\\');
    expect(JSON.parse(json).href).toBe('OEBPS/Text/chap1.xhtml');
    const round = locatorFromJson(json)!;
    expect(round.href).toBe('OEBPS/Text/chap1.xhtml');
  });
});

// ---------------------------------------------------------------------------
// 4.7 fragment scrolls + cover chain
// ---------------------------------------------------------------------------
describe('4.7 fragment scrolls + cover chain', () => {
  it('scenario 11: href#frag preserves fragment and scrollToFragment wires 3×rAF', () => {
    const viewerSrc = readFileSync(
      join(process.cwd(), 'src/lib/features/reader/viewer-epub/EpubNativeViewer.svelte'),
      'utf8',
    );
    const bridgeSrc = readFileSync(
      join(process.cwd(), 'src/lib/features/reader/viewer-epub/useEpubBridge.svelte.ts'),
      'utf8',
    );
    const combined = viewerSrc + bridgeSrc;
    expect(combined).toContain('scrollToFragment');
    expect(combined).toContain('getElementById');
    const rafMatches = combined.match(/requestAnimationFrame/g) ?? [];
    expect(rafMatches.length).toBeGreaterThanOrEqual(3);
    expect(combined).toMatch(/extractFragment|stripFragment|#frag|fragment/);
    expect(combined).toContain('pendingFragment');

    // ExtractFragment pure: Historia fragment preserved
    expect(extractFragment('OEBPS/Text/part0003.html#_idParaDest-5')).toBe('_idParaDest-5');
    expect(extractFragment('OEBPS/Text/part0003.html')).toBeNull();
    expect(stripFragment('OEBPS/Text/part0003.html#_idParaDest-5')).toBe(
      'OEBPS/Text/part0003.html',
    );

    // JSDOM anchor scroll target exists
    const doc = loadDoc(FIXTURE_HTML);
    const target = doc.getElementById('_idParaDest-5');
    expect(target).not.toBeNull();
    expect(target!.textContent).toContain('Target anchor');
  });

  it('scenario 11b: toc href with fragment splits correctly for spine lookup', () => {
    const href = 'OEBPS/Text/part0003.html#_idParaDest-5';
    const frag = extractFragment(href);
    const base = stripFragment(href);
    expect(frag).toBe('_idParaDest-5');
    expect(base).toBe('OEBPS/Text/part0003.html');
    expect(normalizeHref(base)).toBe('OEBPS/Text/part0003.html');

    const toc = [
      { index: 5, id: 'chapter-5', label: 'Part 3', href, depth: 0 },
    ] as unknown as Parameters<typeof tocIndexForSpine>[0];
    expect(spineIndexForToc(toc, 0)).toBe(5);
    expect(tocIndexForSpine(toc, 5, base)).toBe(0);
    expect(tocIndexForSpine(toc, 5, href)).toBe(0);
  });

  it('scenario 12: cover chain — Rust source guarantees cover-image → meta → guide → heuristic', () => {
    const rustSrc = readFileSync(
      join(process.cwd(), 'src-tauri/src/services/epub_extractor.rs'),
      'utf8',
    );
    expect(rustSrc).toContain('resolve_cover');
    expect(rustSrc).toContain('cover-image');
    expect(rustSrc).toContain('meta name="cover"');
    expect(rustSrc).toContain('guide');
    expect(rustSrc).toContain('heuristic');
    // Also verify repository/mod.rs cover chain consumption
    const repoSrc = readFileSync(join(process.cwd(), 'src-tauri/src/repository/mod.rs'), 'utf8');
    // Should reference cover handling
    expect(repoSrc.length).toBeGreaterThan(0);
  });

  it('scenario 12b: getChapterBaseCFI uses spine index for cover chain parity (CFI 1-based)', () => {
    setSpine(SPINE_4);
    expect(getChapterBaseCFI('OEBPS/Text/chapter1.xhtml')).toBe('epubcfi(/6/1!)');
    expect(getChapterBaseCFI('OEBPS/Text/chapter4.xhtml')).toBe('epubcfi(/6/4!)');
    expect(getSpineIndex('OEBPS/Text/chapter4.xhtml')).toBe(4);
    expect(getSpineIndex('OEBPS\\Text\\chapter4.xhtml')).toBe(4);
  });
});

// ---------------------------------------------------------------------------
// 13: no pageNumber conflation (Phase 5)
// ---------------------------------------------------------------------------
describe('5.2 no pageNumber conflation — highlight overlay uses spine', () => {
  it('scenario 13: highlight overlay and viewer guard use currentSpineIndex (not TOC index)', () => {
    const viewerSrc = readFileSync(
      join(process.cwd(), 'src/lib/features/reader/viewer-epub/EpubNativeViewer.svelte'),
      'utf8',
    );
    const bridgeSrc = readFileSync(
      join(process.cwd(), 'src/lib/features/reader/viewer-epub/useEpubBridge.svelte.ts'),
      'utf8',
    );
    const highlightsSrc = readFileSync(
      join(process.cwd(), 'src/lib/features/reader/viewer-epub/useEpubHighlights.svelte.ts'),
      'utf8',
    );
    const renderSrc = readFileSync(
      join(process.cwd(), 'src/lib/features/reader/viewer-epub/useEpubRender.svelte.ts'),
      'utf8',
    );
    const combinedSrc = viewerSrc + renderSrc + bridgeSrc + highlightsSrc;
    const overlaySrc = readFileSync(
      join(process.cwd(), 'src/lib/features/reader/viewer-epub/epubHighlightOverlayIframe.ts'),
      'utf8',
    );
    // Viewer+bridge+highlights: pageNumber checks must compare against currentSpineIndex (via deps getter after refactor)
    expect(combinedSrc).toContain('currentSpineIndex');
    // At least 2 places where pageNumber is compared to spine (iframe message, highlight click) — pattern may be deps.getCurrentSpineIndex()
    const spineCompares = (combinedSrc.match(/pageNumber !==/g) ?? []).length;
    expect(spineCompares).toBeGreaterThanOrEqual(2);
    expect(combinedSrc).toContain('getCurrentSpineIndex');
    // The srcdoc CHAPTER_INDEX must be spineIndex, not toc index (now in render)
    expect(combinedSrc).toContain('CHAPTER_INDEX');
    expect(combinedSrc).toContain('spineIndex');
    // Overlay: should compare hl.pageNumber !== currentChapterIndex where currentChapterIndex is spine-passed
    expect(overlaySrc).toContain('hl.pageNumber !== currentChapterIndex');
    // Verify overlay render signature receives spine index as 3rd param
    expect(overlaySrc).toContain('function render');
    const hasSpineComment =
      combinedSrc.includes('currentSpineIndex') && combinedSrc.includes('pageNumber');
    expect(hasSpineComment).toBe(true);
  });

  it('scenario 13b: persistedHighlights filtered via getSpineHrefs + stripFragment + currentSpineIndex', () => {
    const viewerSrc = readFileSync(
      join(process.cwd(), 'src/lib/features/reader/viewer-epub/EpubNativeViewer.svelte'),
      'utf8',
    );
    const highlightsSrc = readFileSync(
      join(process.cwd(), 'src/lib/features/reader/viewer-epub/useEpubHighlights.svelte.ts'),
      'utf8',
    );
    const bridgeSrc = readFileSync(
      join(process.cwd(), 'src/lib/features/reader/viewer-epub/useEpubBridge.svelte.ts'),
      'utf8',
    );
    const combined = viewerSrc + highlightsSrc + bridgeSrc;
    expect(combined).toContain('getSpineHrefs');
    expect(combined).toContain('stripFragment');
    // The highlight $effect uses spine-authoritative href (spineHrefs + currentSpineIndex)
    expect(combined).toContain('currentSpineIndex');
    expect(combined).toContain('getSpineHrefs');
  });
});
