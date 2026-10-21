/**
 * Pure helpers for EpubNativeViewer — spine/TOC mapping, fragment handling, sanitize.
 * Extracted for Strict TDD: unit-testable without Svelte/DOM.
 */

import { logger } from '$lib/shared/logger/Logger';

export interface EpubChapterMeta {
  index: number;
  id: string;
  label: string;
  href: string;
  depth?: number;
}

export function normalizeHref(href: string): string {
  return href.replace(/\\/g, '/');
}

export function stripFragment(href: string): string {
  const idx = href.indexOf('#');
  return idx >= 0 ? href.slice(0, idx) : href;
}

export function extractFragment(href: string): string | null {
  const idx = href.indexOf('#');
  return idx >= 0 ? href.slice(idx + 1) : null;
}

/**
 * U5: resolve a `#fragment` footnote anchor to its note element.
 * Returns the target id + collapsed plain text (capped), or null when the
 * href is not a same-document fragment, the target is missing, or it holds
 * no text. Never throws on hostile ids.
 */
export function resolveFootnoteNote(
  anchorHref: string | null | undefined,
  doc: Document,
): { id: string; text: string } | null {
  if (!anchorHref || !anchorHref.startsWith('#') || anchorHref.length < 2) return null;
  let id: string;
  try {
    id = decodeURIComponent(anchorHref.slice(1));
  } catch {
    return null;
  }
  if (!id) return null;
  let target: Element | null = null;
  try {
    target = doc.getElementById(id);
    if (!target && typeof CSS !== 'undefined' && typeof CSS.escape === 'function') {
      target = doc.querySelector(`[id="${CSS.escape(id)}"], [name="${CSS.escape(id)}"]`);
    }
  } catch {
    return null;
  }
  if (!target) return null;
  const text = (target.textContent ?? '').trim().replace(/\s+/g, ' ');
  if (!text) return null;
  return { id, text: text.length > 600 ? text.slice(0, 600) : text };
}

/**
 * Resolve spine index (0..spineLen-1) for a TOC position (0..tocLen-1).
 * Mirrors Rust EpubExtractor's TOC index contract: toc[tocIdx].index is the filtered spine position.
 */
export function spineIndexForToc(toc: EpubChapterMeta[], tocIndex: number): number {
  if (tocIndex < 0 || tocIndex >= toc.length) {
    logger.debug(
      'epub-toc: spineIndexForToc tocIndex out-of-bounds',
      { tocIndex, tocLen: toc.length },
      'reader',
    );
    return tocIndex;
  }
  const entry = toc[tocIndex];
  if (!entry || typeof entry.index !== 'number') {
    logger.debug(
      'epub-toc: spineIndexForToc missing entry for tocIndex',
      { tocIndex, fallback: tocIndex },
      'reader',
    );
    return tocIndex;
  }
  if (entry.index < 0 || !Number.isFinite(entry.index)) {
    logger.debug(
      'epub-toc: spineIndexForToc invalid index',
      { index: entry.index, tocIndex },
      'reader',
    );
    return tocIndex;
  }
  return entry.index;
}

/**
 * Resolve TOC position for a 0-based spine index. Returns null when not in TOC.
 * Strategy: by index → by normalized href (fragment stripped) → by filename.
 * Handles Historia offset-2 (TOC subset of spine) and OEBPS/Text prefix variance.
 */
export function tocIndexForSpine(
  toc: EpubChapterMeta[],
  spineIndex: number,
  spineHref?: string,
): number | null {
  if (spineIndex < 0 || !Number.isFinite(spineIndex)) {
    logger.warn('epub-toc: tocIndexForSpine invalid spineIndex', { spineIndex }, 'reader');
    return null;
  }
  const byIndex = toc.findIndex((c) => c.index === spineIndex);
  if (byIndex !== -1) return byIndex;
  if (spineHref) {
    const norm = normalizeHref(stripFragment(spineHref));
    const byHref = toc.findIndex((c) => normalizeHref(stripFragment(c.href)) === norm);
    if (byHref !== -1) return byHref;
    const fileName = norm.split('/').pop() ?? norm;
    const byFile = toc.findIndex(
      (c) => (normalizeHref(stripFragment(c.href)).split('/').pop() ?? '') === fileName,
    );
    if (byFile !== -1) return byFile;
  }
  if (spineHref) {
    logger.debug(
      'epub-toc: tocIndexForSpine no TOC entry for spineIndex',
      { spineIndex, spineHref, tocLen: toc.length },
      'reader',
    );
  }
  return null;
}

/**
 * Strip injected `chrome-extension://` and `floatBarImgId` content.
 * Guarantees zero `chrome-extension://` in output.
 * Mirrors Rust `sanitize_html` (epub_extractor.rs) for JS parity.
 */
export function sanitizeEpubHtml(html: string): string {
  let result = html;

  // A self-closing `<title/>` is a trap: `<title>` is an RCDATA element, so the
  // parser never honours the `/>` and keeps consuming the rest of the document
  // as title text until it finds a `</title>`. It never does, so the whole head
  // and body end up inside `<title>`, the body stays empty, and the chapter
  // renders blank. Measured on *Prisioneros de la geografía* (2026-10-21): 39 of
  // 43 spine documents carry `<title/>` and every one of them rendered 0
  // characters, while the 4 with a named `<title>` (portada, índice, notas,
  // tematica) rendered their text. The empty *pair* `<title></title>` is safe,
  // so only the `/>` form is rewritten. Must run before `buildChapterSrcdoc`'s DOMParser: serialising the
  // already-corrupted tree back out (outerHTML) bakes the escaped remainder into
  // the srcdoc permanently.
  result = result.replace(/<title\s*\/\s*>/gi, '<title></title>');

  // Remove whole tags containing floatBarImgId
  result = result.replace(/<[^>]*\bfloatBarImgId\b[^>]*>/gi, '');
  // Remove whole tags where src="chrome-extension://..."
  result = result.replace(/<[^>]*\bsrc\s*=\s*["']chrome-extension:\/\/[^"']*["'][^>]*>/gi, '');
  // Remove any remaining chrome-extension:// URLs
  result = result.replace(/chrome-extension:\/\/[^"'\s>]+/g, '');
  // Final string guarantee
  result = result.replace(/chrome-extension:\/\//g, '');
  result = result.replace(/floatBarImgId/g, '');
  // Clean up empty src attributes
  result = result.replace(/\s+src\s*=\s*["']\s*["']/gi, '');
  return result;
}
