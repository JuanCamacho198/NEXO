import { describe, expect, it, vi, beforeEach } from 'vitest';

vi.mock('@tauri-apps/api/core', () => ({
  invoke: vi.fn(),
  convertFileSrc: vi.fn((path: string) => `asset://localhost/${String(path).replace(/\\/g, '/')}`),
}));

import {
  resolveResourcePath,
  toAssetUrl,
  collectFontFaceAssetUrls,
  probeMissingFontUrls,
  stripMissingFontFaces,
  getThemeStyles,
  getThemeBgColor,
  buildReaderOverrideCss,
  buildChapterSrcdoc,
} from '$lib/features/reader/viewer-epub/useEpubRender.svelte';
import { sanitizeEpubHtml } from '$lib/features/reader/viewer-epub/epubViewerHelpers';

const RESOURCES = '/tmp/resources';
const CHAPTER_PATH = 'OEBPS/Text/chapter1.xhtml';

describe('useEpubRender — pure helpers', () => {
  describe('resolveResourcePath', () => {
    it('resolves relative href against chapter dir', () => {
      expect(resolveResourcePath('OEBPS/Text/chap.xhtml', '../Images/cover.jpg')).toBe(
        'OEBPS/Images/cover.jpg',
      );
      expect(resolveResourcePath('OEBPS/Text/chap.xhtml', 'style.css')).toBe(
        'OEBPS/Text/style.css',
      );
      expect(resolveResourcePath('chap.xhtml', 'a/b.css')).toBe('a/b.css');
    });
    it('handles dot segments', () => {
      expect(resolveResourcePath('OEBPS/Text/chap.xhtml', './style.css')).toBe(
        'OEBPS/Text/style.css',
      );
    });
  });

  describe('toAssetUrl', () => {
    it('converts to asset:// via convertFileSrc', () => {
      const url = toAssetUrl('/tmp/resources', 'OEBPS/Images/cover.jpg');
      expect(url).toContain('OEBPS/Images/cover.jpg');
      expect(url).toContain('asset://');
    });
  });

  describe('sanitizeEpubHtml (re-export parity)', () => {
    it('strips chrome-extension:// and floatBarImgId', () => {
      const raw = `<div><img id="floatBarImgId" src="x"><img src="chrome-extension://abc/img.png"><p>hi</p></div>`;
      const out = sanitizeEpubHtml(raw);
      expect(out).not.toContain('chrome-extension://');
      expect(out).not.toContain('floatBarImgId');
      expect(out).toContain('<p>hi</p>');
    });
  });

  describe('collectFontFaceAssetUrls', () => {
    it('collects only @font-face urls with font extensions', () => {
      const html = `<!DOCTYPE html><html><head><style>
        @font-face { font-family:'Neutra'; src: url("fonts/Neutraface.ttf"); }
        @font-face { font-family:'Other'; src: url('fonts/other.woff2'); }
        .bg { background: url("bg.png"); }
      </style></head><body><p>hi</p></body></html>`;
      const urls = collectFontFaceAssetUrls(html, CHAPTER_PATH, RESOURCES);
      expect(urls.length).toBe(2);
      expect(urls.join(',')).toContain('Neutraface.ttf');
      expect(urls.join(',')).toContain('other.woff2');
      expect(urls.join(',')).not.toContain('bg.png');
    });

    it('ignores http/data urls and non-font extensions', () => {
      const html = `<html><head><style>@font-face{src:url("http://cdn/font.ttf")} @font-face{src:url("data:font/woff2;base64,abc")}</style></head></html>`;
      const urls = collectFontFaceAssetUrls(html, CHAPTER_PATH, RESOURCES);
      expect(urls.length).toBe(0);
    });

    it('returns empty when no @font-face', () => {
      const html = `<html><head><style>.a{color:red}</style></head></html>`;
      expect(collectFontFaceAssetUrls(html, CHAPTER_PATH, RESOURCES)).toEqual([]);
    });
  });

  describe('probeMissingFontUrls', () => {
    beforeEach(() => vi.restoreAllMocks());

    it('returns set of URLs that HEAD 403/throw', async () => {
      const urls = ['asset://localhost/a.ttf', 'asset://localhost/b.ttf'];
      // @ts-expect-error global fetch mock
      global.fetch = vi.fn((url: string) => {
        if (String(url).includes('a.ttf'))
          return Promise.resolve({ ok: false, status: 403 } as Response);
        return Promise.resolve({ ok: true, status: 200 } as Response);
      });
      const missing = await probeMissingFontUrls(urls);
      expect(missing.has('asset://localhost/a.ttf')).toBe(true);
      expect(missing.has('asset://localhost/b.ttf')).toBe(false);
    });

    it('returns empty for empty input', async () => {
      const missing = await probeMissingFontUrls([]);
      expect(missing.size).toBe(0);
    });
  });

  describe('stripMissingFontFaces', () => {
    it('removes @font-face rules whose url is in missing set', () => {
      const css = `@font-face{src:url("asset://localhost/a.ttf")} body{color:red} @font-face{src:url("asset://localhost/b.ttf")}`;
      const missing = new Set(['asset://localhost/a.ttf']);
      const out = stripMissingFontFaces(css, missing);
      expect(out).not.toContain('a.ttf');
      expect(out).toContain('b.ttf');
      expect(out).toContain('body');
    });
    it('is no-op when missing empty', () => {
      const css = `@font-face{src:url("a.ttf")}`;
      expect(stripMissingFontFaces(css, new Set())).toBe(css);
    });
  });

  describe('getThemeStyles / getThemeBgColor', () => {
    it('returns paper theme by default for unknown', () => {
      expect(getThemeStyles('unknown')).toContain('#faf8f5');
      expect(getThemeBgColor('unknown')).toBe('#faf8f5');
    });
    it('returns distinct css per theme', () => {
      expect(getThemeStyles('night')).toContain('#0f1320');
      expect(getThemeBgColor('sepia')).toBe('#f5eedd');
      expect(getThemeStyles('dark')).toContain('#1a1a2e');
      expect(getThemeStyles('blue')).toContain('#1e3a5f');
    });
  });

  describe('buildReaderOverrideCss', () => {
    it('computes effectiveFontSize 150%', () => {
      const css = buildReaderOverrideCss({
        fontSize: 100,
        zoomLevel: 150,
        themeMode: 'paper',
        lineHeight: 1.8,
        letterSpacing: 0,
        paragraphSpacing: 1,
        textAlign: 'left',
        direction: 'ltr',
        hyphenation: false,
        margins: { top: 1.5, bottom: 1.5, left: 2, right: 2 },
      });
      expect(css).toContain('font-size: 150%');
      expect(css).toContain('background: #faf8f5');
      expect(css).toContain('hyphens: none');
    });
    it('zoom affects CSS', () => {
      const css = buildReaderOverrideCss({
        fontSize: 100,
        zoomLevel: 200,
        themeMode: 'sepia',
        lineHeight: 1.8,
        letterSpacing: 0,
        paragraphSpacing: 1,
        textAlign: 'left',
        direction: 'ltr',
        hyphenation: true,
        margins: { top: 1, bottom: 1, left: 1, right: 1 },
      });
      expect(css).toContain('font-size: 200%');
      expect(css).toContain('hyphens: auto');
    });
  });

  describe('buildChapterSrcdoc — golden', () => {
    const fixtureHtml = `<!DOCTYPE html><html><head>
      <link rel="stylesheet" href="style.css">
      <link rel="stylesheet" href="http://cdn/external.css">
      <style>
        @font-face { font-family:'Neutra'; src: url("fonts/Neutraface.ttf"); }
        @font-face { font-family:'Ok'; src: url("fonts/ok.woff"); }
        .hero { background: url("images/bg.png"); }
      </style>
      <style>p.title{font-weight:700}</style>
    </head><body>
      <p>Chapter content</p>
      <img src="images/cover.jpg" alt="cover">
      <svg xmlns="http://www.w3.org/2000/svg" xmlns:xlink="http://www.w3.org/1999/xlink"><image height="900" width="600" xlink:href="../Images/svg-cover.jpg"/></svg>
      <video src="video/intro.mp4"></video>
      <div id="floatBarImgId">injected</div>
      <img src="chrome-extension://abc/injected.png">
    </body></html>`;

    const chapterData = {
      index: 2,
      html: fixtureHtml,
      mime: 'application/xhtml+xml',
      chapterBasePath: 'OEBPS/Text',
      chapterPath: 'OEBPS/Text/chapter1.xhtml',
    };

    it('strips chrome-extension:// and floatBarImgId before rewrite', () => {
      const srcdoc = buildChapterSrcdoc(
        chapterData as any,
        RESOURCES,
        ['OEBPS/Text/chapter1.xhtml'],
        'OEBPS/Text/chapter1.xhtml',
        2,
      );
      expect(srcdoc).not.toContain('chrome-extension://');
      expect(srcdoc).not.toContain('floatBarImgId');
    });

    it('injects <base href> and rewrites asset URLs via convertFileSrc', () => {
      const srcdoc = buildChapterSrcdoc(
        chapterData as any,
        RESOURCES,
        ['OEBPS/Text/chapter1.xhtml'],
        'OEBPS/Text/chapter1.xhtml',
        2,
      );
      // base href
      expect(srcdoc).toContain('<base href="');
      expect(srcdoc).toContain('OEBPS/Text');
      // link href rewritten
      expect(srcdoc).not.toContain('href="style.css"');
      expect(srcdoc).toContain('OEBPS/Text/style.css');
      // http remains
      expect(srcdoc).toContain('http://cdn/external.css');
      // img src rewritten
      expect(srcdoc).not.toContain('src="images/cover.jpg"');
      expect(srcdoc).toContain('OEBPS/Text/images/cover.jpg');
      // svg xlink:href rewritten (../Images/svg-cover.jpg -> OEBPS/Images/svg-cover.jpg)
      expect(srcdoc).toContain('OEBPS/Images/svg-cover.jpg');
      expect(srcdoc).not.toContain('xlink:href="../Images/svg-cover.jpg"');
      // style url rewritten
      expect(srcdoc).toContain('OEBPS/Text/images/bg.png');
      // video src rewritten
      expect(srcdoc).toContain('OEBPS/Text/video/intro.mp4');
      // pasted srcdoc via convertFileSrc => asset://
      expect(srcdoc).toContain('asset://localhost');
    });

    it('injects separate nexo-reader-overrides and nexo-highlight-styles (::highlight)', () => {
      const srcdoc = buildChapterSrcdoc(
        chapterData as any,
        RESOURCES,
        ['OEBPS/Text/chapter1.xhtml'],
        'OEBPS/Text/chapter1.xhtml',
        2,
      );
      expect(srcdoc).toContain('id="nexo-reader-overrides"');
      expect(srcdoc).toContain('id="nexo-highlight-styles"');
      expect(srcdoc).toContain('::highlight(epub-hl-yellow)');
      // highlight rules must NOT be inside overrides
      const parser = new DOMParser();
      const doc = parser.parseFromString(srcdoc, 'text/html');
      const overrides = doc.getElementById('nexo-reader-overrides');
      expect(overrides?.textContent ?? '').not.toContain('::highlight(');
      const hl = doc.getElementById('nexo-highlight-styles');
      expect(hl?.textContent ?? '').toContain('::highlight(epub-hl-yellow)');
    });

    it('injects spine + bridge + resize + highlight + selection scripts in order', () => {
      const spine = ['OEBPS/Text/ch1.xhtml', 'OEBPS/Text/ch2.xhtml'];
      const srcdoc = buildChapterSrcdoc(
        chapterData as any,
        RESOURCES,
        spine,
        'OEBPS/Text/chapter1.xhtml',
        0,
      );
      expect(srcdoc).toContain('__cfiBridge');
      expect(srcdoc).toContain('__epubHighlightOverlay');
      expect(srcdoc).toContain('epub-selection');
      expect(srcdoc).toContain('epub-resize');
      expect(srcdoc).toContain('setSpine');
      // spine JSON injected
      expect(srcdoc).toContain(JSON.stringify(spine).slice(1, 10));
    });

    it('strips missing @font-face rules after probe (Neutraface 403 scenario)', () => {
      // Instead of guessing exact asset URL, build it via toAssetUrl to get exact missing key
      const neutraUrl = toAssetUrl(RESOURCES, 'OEBPS/Text/fonts/Neutraface.ttf');
      const miss2 = new Set([neutraUrl]);
      const srcdoc = buildChapterSrcdoc(
        chapterData as any,
        RESOURCES,
        ['OEBPS/Text/chapter1.xhtml'],
        'OEBPS/Text/chapter1.xhtml',
        2,
        miss2,
      );
      // The Neura @font-face rule should be stripped
      // The css after rewrite contains asset URL, so stripped rule disappears
      expect(srcdoc).not.toContain('Neutraface.ttf');
      // ok font remains
      expect(srcdoc).toContain('ok.woff');
    });

    it('returns complete <!DOCTYPE html> document with outerHTML serialization', () => {
      const srcdoc = buildChapterSrcdoc(
        chapterData as any,
        RESOURCES,
        [],
        'OEBPS/Text/chapter1.xhtml',
        0,
      );
      expect(srcdoc.startsWith('<!DOCTYPE html>')).toBe(true);
      expect(srcdoc).toContain('<html');
      expect(srcdoc).toContain('Chapter content');
    });

    it('preserves chapter href and spine index in injected script (CHAPTER_HREF / CHAPTER_INDEX)', () => {
      const srcdoc = buildChapterSrcdoc(
        chapterData as any,
        RESOURCES,
        [],
        'OEBPS/Text/chapter1.xhtml',
        5,
      );
      expect(srcdoc).toContain('CHAPTER_HREF');
      expect(srcdoc).toContain('CHAPTER_INDEX');
      expect(srcdoc).toContain('OEBPS/Text/chapter1.xhtml');
    });
  });
});
