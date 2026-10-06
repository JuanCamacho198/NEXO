/**
 * useEpubRender — isolated EPUB render pipeline (PR4).
 * Extracts srcdoc construction, asset rewriting, font-face probing,
 * theme/override CSS, iframe height sync, and epoch-guarded chapter render
 * from EpubNativeViewer while preserving byte-identical behavior.
 *
 * Design: plain vars `renderEpoch/currentRenderIndex` NOT $state (epoch guard),
 * injected deps for testability (`invoke`, `iframeEl`, `getToc`, `getSpineHrefs`).
 * Pure helpers are exported for golden testing.
 */
import { invoke, convertFileSrc } from '@tauri-apps/api/core';
import { IFRAME_CFI_BRIDGE_SCRIPT } from '$lib/features/reader/viewer-epub/cfiBridgeIframe';
import { IFRAME_QUOTE_SCRIPT } from '$lib/features/reader/viewer-epub/epubQuoteIframe';
import { IFRAME_HIGHLIGHT_OVERLAY_SCRIPT } from '$lib/features/reader/viewer-epub/epubHighlightOverlayIframe';
import {
  HIGHLIGHT_COLORS,
  highlightFillRgba,
  nearestHighlightHex,
} from '$lib/features/reader/highlight/highlightColors';
import { normalizeHref } from '$lib/shared/sync/LocatorCodec';
import type { EpubChapterMeta } from '$lib/features/reader/viewer-epub/epubViewerHelpers';
import {
  sanitizeEpubHtml,
  stripFragment,
  extractFragment,
} from '$lib/features/reader/viewer-epub/epubViewerHelpers';
import { setReaderError } from '$lib/stores/readerErrorState.svelte';

// ─── Types ─────────────────────────────────────────────────────
export interface EpubChapterContent {
  index: number;
  html: string;
  mime: string;
  chapterBasePath: string;
  chapterPath: string;
}

export interface EpubMetadataExtract {
  title: string;
  author: string;
  language: string | null;
  publisher: string | null;
  toc: Array<{ index: number; id: string; label: string; href: string; depth?: number }>;
  spineHrefs: string[];
  chapters?: Array<{ index: number; id: string; label: string; href: string; depth?: number }>;
  spine_hrefs?: string[];
  totalChapters: number;
  total_chapters?: number;
  resourcesPath: string;
  resources_path?: string;
}

// ─── Pure helpers — exported for golden tests ──────────────────
export function resolveResourcePath(chapterPath: string, href: string): string {
  const chapterDir = chapterPath.includes('/')
    ? chapterPath.slice(0, chapterPath.lastIndexOf('/') + 1)
    : '';
  const parts = `${chapterDir}${href}`.split('/');
  const resolved: string[] = [];
  for (const part of parts) {
    if (part === '..') resolved.pop();
    else if (part !== '.' && part !== '') resolved.push(part);
  }
  return resolved.join('/');
}

export function toAssetUrl(resourcesPath: string, resourcePath: string): string {
  const normalized = resourcePath.replace(/\\/g, '/');
  const base = resourcesPath.replace(/\\/g, '/').replace(/\/$/, '');
  return `${convertFileSrc(`${base}/${normalized}`)}`;
}

/**
 * Scan the chapter HTML for `@font-face` rules whose `src:` points to a
 * local font file. Returns resolved `asset://` URLs for HEAD probing.
 */
export function collectFontFaceAssetUrls(
  html: string,
  chapterPath: string,
  resourcesPath: string,
): string[] {
  const urls = new Set<string>();
  const fontExt = /\.(ttf|otf|woff2?|eot)(\?|$|#)/i;
  const urlPattern = /url\(\s*(['"]?)([^'")]+)\1\s*\)/gi;

  const parser = new DOMParser();
  const doc = parser.parseFromString(html, 'text/html');
  const normalizedChapter = chapterPath.replace(/\\/g, '/');

  const collectFromCss = (cssText: string): void => {
    if (!/font-face/i.test(cssText)) return;
    let match: RegExpExecArray | null;
    urlPattern.lastIndex = 0;
    while ((match = urlPattern.exec(cssText)) !== null) {
      const rawUrl = match[2];
      if (
        !rawUrl ||
        rawUrl.startsWith('http') ||
        rawUrl.startsWith('data:') ||
        rawUrl.startsWith('asset:') ||
        rawUrl.startsWith('#') ||
        rawUrl.startsWith('local(')
      )
        continue;
      if (!fontExt.test(rawUrl)) continue;
      const resourcePath = resolveResourcePath(normalizedChapter, rawUrl);
      urls.add(toAssetUrl(resourcesPath, resourcePath));
    }
  };

  for (const styleEl of doc.querySelectorAll('style')) {
    collectFromCss(styleEl.textContent ?? '');
  }
  return Array.from(urls);
}

/**
 * HEAD-probe a batch of asset URLs and return the set that didn't respond OK.
 * Any error (network, 4xx, 5xx) is treated as "missing".
 */
export async function probeMissingFontUrls(urls: string[]): Promise<Set<string>> {
  const missing = new Set<string>();
  if (urls.length === 0) return missing;
  const results = await Promise.allSettled(
    urls.map(async (url) => {
      try {
        const res = await fetch(url, { method: 'HEAD' });
        if (!res.ok) throw new Error(`status ${res.status}`);
      } catch {
        throw new Error('missing');
      }
    }),
  );
  urls.forEach((url, i) => {
    if (results[i].status === 'rejected') missing.add(url);
  });
  return missing;
}

/**
 * Drop `@font-face` rules from a CSS string whose rewritten `src:` URL
 * appears in `missingFonts`.
 */
export function stripMissingFontFaces(cssText: string, missingFonts: Set<string>): string {
  if (missingFonts.size === 0) return cssText;
  return cssText.replace(/@font-face\s*\{[^{}]*\}/gi, (rule) => {
    const urlMatch = rule.match(/url\(\s*(['"]?)([^'")]+)\1\s*\)/i);
    if (!urlMatch) return rule;
    if (missingFonts.has(urlMatch[2])) return '';
    return rule;
  });
}

export function getThemeStyles(themeMode: string): string {
  const themes: Record<string, string> = {
    paper: `
        body { background: #faf8f5; color: #333; }
        a { color: #3366cc; }
      `,
    sepia: `
        body { background: #f5eedd; color: #5b4636; }
        a { color: #8b6914; }
      `,
    night: `
        body { background: #0f1320; color: #c8ccd8; }
        a { color: #7bb8ff; }
      `,
    dark: `
        body { background: #1a1a2e; color: #e0e0e0; }
        a { color: #66bbff; }
      `,
    blue: `
        body { background: #1e3a5f; color: #d6e4f0; }
        a { color: #88ccff; }
      `,
  };
  return themes[themeMode] || themes.paper;
}

export function getThemeBgColor(themeMode: string): string {
  const bgs: Record<string, string> = {
    paper: '#faf8f5',
    sepia: '#f5eedd',
    night: '#0f1320',
    dark: '#1a1a2e',
    blue: '#1e3a5f',
  };
  return bgs[themeMode] || bgs.paper;
}

export function buildReaderOverrideCss(opts: {
  fontSize: number;
  zoomLevel: number;
  themeMode: string;
  lineHeight: number;
  letterSpacing: number;
  paragraphSpacing: number;
  textAlign: string;
  direction: string;
  hyphenation: boolean;
  margins: { top: number; bottom: number; left: number; right: number };
}): string {
  const hyphensValue = opts.hyphenation ? 'auto' : 'none';
  const effectiveFontSize = (opts.fontSize * opts.zoomLevel) / 100;
  const themeCss = getThemeStyles(opts.themeMode);

  return `
      ${themeCss}
      html {
        height: auto !important;
        max-height: none !important;
        overflow-x: hidden !important;
        overflow-y: visible !important;
      }
      body {
        height: auto !important;
        min-height: 100% !important;
        max-height: none !important;
        overflow: visible !important;
        font-size: ${effectiveFontSize}% !important;
        line-height: ${opts.lineHeight} !important;
        letter-spacing: ${opts.letterSpacing}px !important;
        text-align: ${opts.textAlign} !important;
        direction: ${opts.direction} !important;
        hyphens: ${hyphensValue} !important;
        padding: ${opts.margins.top}rem ${opts.margins.right}rem ${opts.margins.bottom}rem ${opts.margins.left}rem !important;
        max-width: 38rem !important;
        margin: 0 auto !important;
        box-sizing: border-box !important;
      }
      body p {
        margin-bottom: ${opts.paragraphSpacing}em;
      }
      body img {
        max-width: 100%;
        height: auto;
      }
    `;
}

export function buildChapterSrcdoc(
  chapterData: EpubChapterContent,
  resourcesPath: string,
  spineHrefs: string[],
  currentChapterHref: string,
  chapterIndex: number,
  missingFonts: Set<string> = new Set(),
  readerCssOpts?: {
    fontSize: number;
    zoomLevel: number;
    themeMode: string;
    lineHeight: number;
    letterSpacing: number;
    paragraphSpacing: number;
    textAlign: string;
    direction: string;
    hyphenation: boolean;
    margins: { top: number; bottom: number; left: number; right: number };
    fontFamily: string;
  },
  chapterTitle?: string | null,
): string {
  console.warn('build srcdoc CHAPTER_INDEX', chapterIndex, 'href', currentChapterHref);
  const sanitizedHtml = sanitizeEpubHtml(chapterData.html);
  const parser = new DOMParser();
  const doc = parser.parseFromString(sanitizedHtml, 'text/html');
  for (const el of doc.querySelectorAll('[id="floatBarImgId"]')) el.remove();
  for (const el of doc.querySelectorAll('[src^="chrome-extension://"]')) el.remove();
  const chapterPath = chapterData.chapterPath.replace(/\\/g, '/');

  const baseUrl = chapterData.chapterBasePath
    ? toAssetUrl(resourcesPath, `${chapterData.chapterBasePath}/`)
    : toAssetUrl(resourcesPath, '');

  let baseEl = doc.querySelector('base');
  if (!baseEl) {
    baseEl = doc.createElement('base');
    doc.head.prepend(baseEl);
  }
  baseEl.setAttribute('href', baseUrl.endsWith('/') ? baseUrl : `${baseUrl}/`);

  for (const link of doc.querySelectorAll('link[rel="stylesheet"]')) {
    const href = link.getAttribute('href');
    if (!href || href.startsWith('http') || href.startsWith('data:')) continue;
    const resourcePath = resolveResourcePath(chapterPath, href);
    link.setAttribute('href', toAssetUrl(resourcesPath, resourcePath));
  }

  for (const el of doc.querySelectorAll('img, image, video, audio, source, object')) {
    const isSvgImage = el.tagName.toLowerCase() === 'image';
    const attrs = isSvgImage
      ? [el.getAttribute('href'), el.getAttribute('xlink:href')]
      : [el.getAttribute('src')];
    const src = attrs.find(
      (s) =>
        s &&
        !s.startsWith('http') &&
        !s.startsWith('data:') &&
        !s.startsWith('asset:') &&
        !s.startsWith('chrome-extension:') &&
        !s.startsWith('blob:') &&
        !s.startsWith('chrome:'),
    );
    if (!src) continue;
    const resourcePath = resolveResourcePath(chapterPath, src);
    const assetUrl = toAssetUrl(resourcesPath, resourcePath);
    if (isSvgImage) {
      if (el.getAttribute('href') === src) el.setAttribute('href', assetUrl);
      else el.setAttribute('xlink:href', assetUrl);
    } else {
      el.setAttribute('src', assetUrl);
    }
  }

  for (const styleEl of doc.querySelectorAll('style')) {
    let cssText = styleEl.textContent ?? '';
    if (!cssText.includes('url(')) continue;
    cssText = cssText.replace(
      /url\(\s*(['"]?)([^'")]+)\1\s*\)/gi,
      (_match, quote: string, rawUrl: string) => {
        if (
          rawUrl.startsWith('http') ||
          rawUrl.startsWith('data:') ||
          rawUrl.startsWith('asset:') ||
          rawUrl.startsWith('#')
        ) {
          return `url(${quote}${rawUrl}${quote})`;
        }
        const resourcePath = resolveResourcePath(chapterPath, rawUrl);
        return `url(${quote}${toAssetUrl(resourcesPath, resourcePath)}${quote})`;
      },
    );
    if (missingFonts.size > 0) {
      cssText = stripMissingFontFaces(cssText, missingFonts);
    }
    styleEl.textContent = cssText;
  }

  // Reader overrides — use provided opts or fall back to defaults (for golden tests)
  const cssOpts = readerCssOpts ?? {
    fontSize: 100,
    zoomLevel: 100,
    themeMode: 'paper',
    lineHeight: 1.8,
    letterSpacing: 0,
    paragraphSpacing: 1,
    textAlign: 'left',
    direction: 'ltr',
    hyphenation: false,
    margins: { top: 1.5, bottom: 1.5, left: 2, right: 2 },
    fontFamily: 'serif',
  };
  let readerCss = buildReaderOverrideCss(cssOpts);
  const userFont = (cssOpts.fontFamily ?? 'serif').trim();
  if (userFont && userFont !== 'serif') {
    readerCss += `
        body {
          font-family: ${userFont}, serif !important;
        }
      `;
  }

  let readerStyle = doc.getElementById('nexo-reader-overrides');
  if (!readerStyle) {
    readerStyle = doc.createElement('style');
    readerStyle.id = 'nexo-reader-overrides';
    doc.head.appendChild(readerStyle);
  }
  readerStyle.textContent = readerCss;

  const highlightStyle = doc.createElement('style');
  highlightStyle.id = 'nexo-highlight-styles';
  highlightStyle.textContent = HIGHLIGHT_COLORS.map(
    (color) =>
      `::highlight(epub-hl-${color.label}) { background-color: ${highlightFillRgba(nearestHighlightHex(color.hex), 0.4)}; }`,
  ).join('\n');
  doc.head.appendChild(highlightStyle);

  const errorScript = doc.createElement('script');
  errorScript.textContent = `window.onerror = function(msg, url, line, col){ try{ window.parent.postMessage({type:'epub-srcdoc-error', kind:'js', msg: String(msg), line: line, col: col, url: String(url)}, '*'); }catch(e){} }; window.addEventListener('unhandledrejection', function(e){ try{ var m = e.reason && e.reason.message ? e.reason.message : String(e.reason); window.parent.postMessage({type:'epub-srcdoc-error', kind:'rejection', msg: m, line: 0, col: 0, url: location.href}, '*'); }catch(_){} });`;
  doc.head.prepend(errorScript);
  const bridgeScript = doc.createElement('script');
  bridgeScript.textContent = IFRAME_CFI_BRIDGE_SCRIPT;

  const quoteScript = doc.createElement('script');
  quoteScript.textContent = IFRAME_QUOTE_SCRIPT;

  const spineScript = doc.createElement('script');
  spineScript.textContent = `
      (function() {
        try {
          window.__cfiBridge.setSpine(${JSON.stringify(spineHrefs)});
        } catch (e) {
          console.warn('epub-cfi: failed to set spine', e);
        }
      })();
    `;

  const resizeScript = doc.createElement('script');
  resizeScript.textContent = `
      (function() {
        function notifyResize() {
          window.parent.postMessage({ type: 'epub-resize' }, '*');
        }
        window.addEventListener('load', notifyResize);
        if (document.readyState === 'complete') notifyResize();
        if (typeof ResizeObserver !== 'undefined') {
          new ResizeObserver(notifyResize).observe(document.body);
        }
      })();
    `;

  const highlightOverlayScript = doc.createElement('script');
  highlightOverlayScript.textContent = IFRAME_HIGHLIGHT_OVERLAY_SCRIPT;

  const selectionScript = doc.createElement('script');
  selectionScript.textContent = `
      (function() {
        var timer = null;
        var mouseupDebounceTimer = null;
        var CHAPTER_HREF = ${JSON.stringify(normalizeHref(currentChapterHref))};
        var CHAPTER_INDEX = ${chapterIndex};
        var CHAPTER_TITLE = ${JSON.stringify(chapterTitle ?? null)};

        document.addEventListener('click', function(ev) {
          var hit = null;
          try {
            if (window.__epubHighlightOverlay &&
                typeof window.__epubHighlightOverlay.hitTest === 'function') {
              hit = window.__epubHighlightOverlay.hitTest(ev.clientX, ev.clientY);
            }
          } catch (e) {
            hit = null;
          }
          if (!hit) return;
          ev.stopPropagation();
          if (ev.preventDefault) ev.preventDefault();
          if (mouseupDebounceTimer) {
            clearTimeout(mouseupDebounceTimer);
            mouseupDebounceTimer = null;
          }
          if (timer) {
            clearTimeout(timer);
            timer = null;
          }
          var sel = window.getSelection();
          if (sel) sel.removeAllRanges();
          window.parent.postMessage({
            type: 'epub-highlight-click',
            id: hit.id,
            x: ev.clientX,
            y: ev.clientY,
            color: hit.color,
            text: hit.text,
            pageNumber: CHAPTER_INDEX
          }, '*');
        }, true);

        document.addEventListener('mouseup', function() {
          if (timer) clearTimeout(timer);
          if (mouseupDebounceTimer) clearTimeout(mouseupDebounceTimer);
          mouseupDebounceTimer = setTimeout(function() {
            mouseupDebounceTimer = null;
            var sel = window.getSelection();
            if (!sel || sel.isCollapsed || !sel.toString().trim()) return;
            var text = sel.toString().trim();
            var range = sel.getRangeAt(0);
            var overlapsHl = false;
            try {
              if (window.__epubHighlightOverlay &&
                  typeof window.__epubHighlightOverlay.rangeOverlapsHighlight === 'function') {
                overlapsHl = window.__epubHighlightOverlay.rangeOverlapsHighlight(range);
              }
            } catch (e) {
              overlapsHl = false;
            }
            if (overlapsHl) return;
            var frameRect = (window.frameElement && window.frameElement.getBoundingClientRect)
              ? window.frameElement.getBoundingClientRect()
              : { left: 0, top: 0, right: 0, bottom: 0, width: 0, height: 0 };
            var rect = range.getBoundingClientRect();
            var bounds = {
              left: rect.left,
              top: rect.top,
              right: rect.right,
              bottom: rect.bottom
            };
            var containerRect = {
              left: frameRect.left,
              top: frameRect.top,
              width: frameRect.width,
              height: frameRect.height
            };
            var clientRects = range.getClientRects();
            var rects = [];
            for (var i = 0; i < clientRects.length; i++) {
              var r = clientRects[i];
              rects.push({
                left: r.left,
                top: r.top,
                width: r.width,
                height: r.height
              });
            }
            var cfi = null;
            try {
              if (window.__cfiBridge &&
                  typeof window.__cfiBridge.rangeToCFI === 'function') {
                cfi = window.__cfiBridge.rangeToCFI(range, CHAPTER_HREF, document);
              }
            } catch (e) {
              console.warn('epub-cfi: rangeToCFI threw', e);
            }
            var quote = null;
            try {
              if (window.__epubQuote &&
                  typeof window.__epubQuote.extract === 'function') {
                quote = window.__epubQuote.extract(range, document).quote;
              }
            } catch (e) {
              quote = null;
            }
            window.parent.postMessage({
              type: 'epub-selection',
              text: text,
              bounds: bounds,
              container: containerRect,
              rects: rects,
              pageNumber: CHAPTER_INDEX,
              cfi: cfi,
              quote: quote,
              chapterTitle: CHAPTER_TITLE
            }, '*');
          }, 100);
        });
        document.addEventListener('selectionchange', function() {
          var sel = window.getSelection();
          if (!sel || sel.isCollapsed) {
            if (timer) clearTimeout(timer);
            timer = setTimeout(function() {
              window.parent.postMessage({ type: 'epub-selection', text: '', pageNumber: CHAPTER_INDEX }, '*');
            }, 200);
          }
        });
        var scrollDismissTimer = null;
        function dismissOnScroll() {
          if (scrollDismissTimer) clearTimeout(scrollDismissTimer);
          scrollDismissTimer = setTimeout(function() {
            window.parent.postMessage({ type: 'epub-selection', text: '', pageNumber: CHAPTER_INDEX }, '*');
          }, 50);
        }
        window.addEventListener('scroll', dismissOnScroll, true);
        document.addEventListener('scroll', dismissOnScroll, true);
      })();
    `;

  for (const script of doc.querySelectorAll('script')) {
    script.remove();
  }

  doc.body.appendChild(errorScript);
  doc.body.appendChild(bridgeScript);
  doc.body.appendChild(quoteScript);
  doc.body.appendChild(spineScript);
  doc.body.appendChild(highlightOverlayScript);
  doc.body.appendChild(resizeScript);
  doc.body.appendChild(selectionScript);

  const serialized = doc.documentElement.outerHTML;
  if (currentChapterHref.includes('HM-colombia-5')) {
    console.warn('SERIALIZED SNIPPET HM5 START', serialized.slice(0, 6000));
    console.warn('SERIALIZED SNIPPET HM5 END', serialized.slice(-8000));
    console.warn('BRIDGE SNIPPET', IFRAME_CFI_BRIDGE_SCRIPT.slice(1800, 2400));
  }
  return `<!DOCTYPE html>\n${serialized}`;
}

// ─── Stateful composable ───────────────────────────────────────
export type EpubRenderDeps = {
  getIframeEl: () => HTMLIFrameElement | null;
  getZoomContainerEl: () => HTMLDivElement | null;
  getMetadata: () => EpubMetadataExtract | null;
  getSpineHrefs: () => string[];
  getToc: () => EpubChapterMeta[];
  spineIndexForToc: (tocIndex: number) => number;
  getBookId: () => string;
  getResourcesPath: () => string;
  getZoomLevel: () => number;
  getFontSize: () => number;
  getFontFamily: () => string;
  getThemeMode: () => string;
  getLineHeight: () => number;
  getLetterSpacing: () => number;
  getParagraphSpacing: () => number;
  getTextAlign: () => string;
  getDirection: () => string;
  getHyphenation: () => boolean;
  getMargins: () => { top: number; bottom: number; left: number; right: number };
  getCurrentChapterIndex: () => number;
  setCurrentChapterIndex: (v: number) => void;
  getCurrentSpineIndex: () => number;
  getPendingFragment: () => string | null;
  setPendingFragment: (v: string | null) => void;
  getPendingCfiScroll: () => string | null;
  setPendingCfiScroll: (v: string | null) => void;
  getLastRenderedChapter: () => number;
  setLastRenderedChapter: (v: number) => void;
  getTotalChapters: () => number;
  getIframeContentHeight: () => number;
  setIframeContentHeight: (v: number) => void;
  scrollToCfi: (cfi: string) => void;
  scrollToFragment: (fragment: string) => void;
  emitPreciseLocation: () => void;
  setError: (msg: string) => void;
};

export function createEpubRender(deps: EpubRenderDeps): {
  iframeContentHeight: number;
  getEpoch(): number;
  getCurrentRenderIndex(): number | null;
  syncIframeHeight(): void;
  iframeContentHeightValue(): number;
  buildReaderOverrideCss(): string;
  refreshReaderStyles(): void;
  renderChapter(index: number): Promise<void>;
  resolveResourcePath: typeof resolveResourcePath;
  toAssetUrl: typeof toAssetUrl;
  collectFontFaceAssetUrls: typeof collectFontFaceAssetUrls;
  probeMissingFontUrls: typeof probeMissingFontUrls;
  stripMissingFontFaces: typeof stripMissingFontFaces;
  getThemeStyles: typeof getThemeStyles;
  getThemeBgColor: typeof getThemeBgColor;
  buildChapterSrcdoc: typeof buildChapterSrcdoc;
  buildReaderOverrideCssPure: typeof buildReaderOverrideCss;
} {
  // Epoch guard — plain vars NOT $state (prevents ping-pong BUILD v4)
  let renderEpoch = 0;
  let currentRenderIndex: number | null = null;
  // iframeContentHeight is owned externally (viewer $state) to keep template reactive
  let internalHeight = $state(deps.getIframeContentHeight());
  // Keep internal in sync if external changes outside render (e.g. reset)
  $effect(() => {
    void deps.getIframeContentHeight();
    internalHeight = deps.getIframeContentHeight();
  });

  function getEpoch(): number {
    return renderEpoch;
  }

  function getCurrentRenderIndex(): number | null {
    return currentRenderIndex;
  }

  function syncIframeHeight(): void {
    const iframeEl = deps.getIframeEl();
    if (!iframeEl?.contentDocument) return;
    const doc = iframeEl.contentDocument;
    const docEl = doc.documentElement;
    const body = doc.body;
    if (!docEl || !body) return;
    const height = Math.max(docEl.scrollHeight, body.scrollHeight);
    const next = height > 0 ? height : 0;
    internalHeight = next;
    deps.setIframeContentHeight(next);
  }

  function iframeContentHeightValue(): number {
    return internalHeight;
  }

  function buildReaderOverrideCssLocal(): string {
    return buildReaderOverrideCss({
      fontSize: deps.getFontSize(),
      zoomLevel: deps.getZoomLevel(),
      themeMode: deps.getThemeMode(),
      lineHeight: deps.getLineHeight(),
      letterSpacing: deps.getLetterSpacing(),
      paragraphSpacing: deps.getParagraphSpacing(),
      textAlign: deps.getTextAlign(),
      direction: deps.getDirection(),
      hyphenation: deps.getHyphenation(),
      margins: deps.getMargins(),
    });
  }

  function refreshReaderStyles(): void {
    const iframeEl = deps.getIframeEl();
    if (!iframeEl?.contentDocument) return;
    const doc = iframeEl.contentDocument;
    const head = doc.head;
    if (!head) return;
    let readerStyle = doc.getElementById('nexo-reader-overrides');
    if (!readerStyle) {
      readerStyle = doc.createElement('style');
      readerStyle.id = 'nexo-reader-overrides';
      head.appendChild(readerStyle);
    }
    let css = buildReaderOverrideCssLocal();
    const userFont = deps.getFontFamily().trim();
    if (userFont && userFont !== 'serif') {
      css += `
        body {
          font-family: ${userFont}, serif !important;
        }
      `;
    }
    readerStyle.textContent = css;
    syncIframeHeight();
  }

  async function renderChapter(index: number): Promise<void> {
    const metadata = deps.getMetadata();
    const iframeEl = deps.getIframeEl();
    if (!metadata || !iframeEl) return;
    const myEpoch = ++renderEpoch;
    currentRenderIndex = index;
    const spineIndex = deps.spineIndexForToc(index);
    const tocEntry = deps.getToc()[index];
    const tocHrefForLog = tocEntry?.href ?? '';
    const spineHrefForLog = deps.getSpineHrefs()[spineIndex] ?? `(spine ${spineIndex})`;
    const frag = extractFragment(tocHrefForLog);
    if (frag) deps.setPendingFragment(frag);
    console.warn(
      'epub-hl: renderChapter called tocIndex=',
      index,
      'spineIndex=',
      spineIndex,
      'epoch=',
      myEpoch,
      'srcdoc CHAPTER_INDEX(spine)=',
      spineIndex,
      'spineHrefs',
      deps.getSpineHrefs().length,
      'chapterHref(toc)',
      tocHrefForLog,
      'spineHref',
      spineHrefForLog,
      'fragment',
      frag ?? '(none)',
    );
    if (spineIndex !== index) {
      console.warn(
        'epub-toc: renderChapter toc',
        index,
        '-> spine',
        spineIndex,
        'href',
        tocHrefForLog,
      );
    }

    try {
      const chapterData = await invoke<EpubChapterContent>('get_epub_chapter', {
        bookId: deps.getBookId(),
        chapterIndex: spineIndex,
      });

      if (myEpoch !== renderEpoch || currentRenderIndex !== index) {
        console.warn(
          'epub-hl: renderChapter stale abort before srcdoc index',
          index,
          'epoch',
          myEpoch,
          'current',
          renderEpoch,
        );
        return;
      }

      deps.setCurrentChapterIndex(index);
      internalHeight = 0;
      deps.setIframeContentHeight(0);

      const spineHrefs = deps.getSpineHrefs();
      const tocHrefRaw = deps.getToc()[index]?.href ?? '';
      const chapterHref = normalizeHref(stripFragment(tocHrefRaw) || spineHrefs[spineIndex] || '');
      const fontUrls = collectFontFaceAssetUrls(
        chapterData.html,
        chapterData.chapterPath,
        metadata.resourcesPath,
      );
      const missingFonts = await probeMissingFontUrls(fontUrls);
      const readerCssOpts = {
        fontSize: deps.getFontSize(),
        zoomLevel: deps.getZoomLevel(),
        themeMode: deps.getThemeMode(),
        lineHeight: deps.getLineHeight(),
        letterSpacing: deps.getLetterSpacing(),
        paragraphSpacing: deps.getParagraphSpacing(),
        textAlign: deps.getTextAlign(),
        direction: deps.getDirection(),
        hyphenation: deps.getHyphenation(),
        margins: deps.getMargins(),
        fontFamily: deps.getFontFamily(),
      };
      const srcdoc = buildChapterSrcdoc(
        chapterData,
        metadata.resourcesPath,
        spineHrefs,
        chapterHref,
        spineIndex,
        missingFonts,
        readerCssOpts,
        deps.getToc()[index]?.label ?? null,
      );

      iframeEl.onload = () => {
        syncIframeHeight();
        const zoomContainerEl = deps.getZoomContainerEl();
        if (zoomContainerEl) zoomContainerEl.scrollTop = 0;
        const maxRetries = 25;
        const interval = 20;
        let attempt = 0;
        function markReady(): void {
          if (myEpoch !== renderEpoch || currentRenderIndex !== index) {
            console.warn(
              'epub-hl: markReady stale epoch',
              myEpoch,
              'current',
              renderEpoch,
              'index',
              index,
              'currentRenderIndex',
              currentRenderIndex,
            );
            return;
          }
          const win = iframeEl?.contentWindow as
            | (Window & {
                __cfiBridge?: { setSpine: (h: string[]) => void };
                __epubHighlightOverlay?: {
                  render: (h: unknown[], chapterHref: string, idx: number) => void;
                  isReady?: () => boolean;
                };
              })
            | null;
          const bridgeReady = !!win?.__cfiBridge && typeof win.__cfiBridge.setSpine === 'function';
          const overlayReady = !!win?.__epubHighlightOverlay;
          if (bridgeReady && overlayReady) {
            try {
              win!.__cfiBridge!.setSpine(spineHrefs);
            } catch (e) {
              console.warn('epub-cfi: failed to re-init iframe on load', e);
            }
            deps.setLastRenderedChapter(index);
            requestAnimationFrame(deps.emitPreciseLocation);
            const pendingCfi = deps.getPendingCfiScroll();
            if (pendingCfi) {
              requestAnimationFrame(() => deps.scrollToCfi(pendingCfi));
            }
            const pendingFrag = deps.getPendingFragment();
            if (pendingFrag) {
              requestAnimationFrame(() => deps.scrollToFragment(pendingFrag));
            }
            return;
          }
          if (attempt++ < maxRetries) {
            setTimeout(markReady, interval);
          } else {
            console.warn(
              'epub-hl: onload markReady timed out bridgeReady=',
              bridgeReady,
              'overlayReady=',
              overlayReady,
            );
            try {
              if (bridgeReady) win!.__cfiBridge!.setSpine(spineHrefs);
            } catch {}
            deps.setLastRenderedChapter(index);
            requestAnimationFrame(deps.emitPreciseLocation);
            const pendingCfi2 = deps.getPendingCfiScroll();
            if (pendingCfi2) requestAnimationFrame(() => deps.scrollToCfi(pendingCfi2));
            const pendingFrag2 = deps.getPendingFragment();
            if (pendingFrag2) requestAnimationFrame(() => deps.scrollToFragment(pendingFrag2));
          }
        }
        markReady();
      };
      iframeEl.srcdoc = srcdoc;
    } catch (err) {
      const msg = err instanceof Error ? err.message : String(err);
      deps.setError(msg);
      setReaderError(msg);
    }
  }

  return {
    get iframeContentHeight(): number {
      return internalHeight;
    },
    set iframeContentHeight(v: number) {
      internalHeight = v;
      deps.setIframeContentHeight(v);
    },
    getEpoch,
    getCurrentRenderIndex,
    syncIframeHeight,
    iframeContentHeightValue,
    buildReaderOverrideCss: buildReaderOverrideCssLocal,
    refreshReaderStyles,
    renderChapter,
    // Re-export pure helpers for direct testing without composable
    resolveResourcePath,
    toAssetUrl,
    collectFontFaceAssetUrls,
    probeMissingFontUrls,
    stripMissingFontFaces,
    getThemeStyles,
    getThemeBgColor,
    buildChapterSrcdoc,
    buildReaderOverrideCssPure: buildReaderOverrideCss,
  };
}

export type EpubRenderState = ReturnType<typeof createEpubRender>;
