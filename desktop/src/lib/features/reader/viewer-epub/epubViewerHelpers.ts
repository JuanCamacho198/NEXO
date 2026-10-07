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
 * Resolve spine index (0..spineLen-1) for a TOC position (0..tocLen-1).
 * Mirrors Rust EpubExtractor's TOC index contract: toc[tocIdx].index is the filtered spine position.
 */
export function spineIndexForToc(toc: EpubChapterMeta[], tocIndex: number): number {
  if (tocIndex < 0 || tocIndex >= toc.length) {
    logger.warn(
      'epub-toc: spineIndexForToc tocIndex out-of-bounds',
      { tocIndex, tocLen: toc.length },
      'reader',
    );
    return tocIndex;
  }
  const entry = toc[tocIndex];
  if (!entry || typeof entry.index !== 'number') {
    logger.warn(
      'epub-toc: spineIndexForToc missing entry for tocIndex',
      { tocIndex, fallback: tocIndex },
      'reader',
    );
    return tocIndex;
  }
  if (entry.index < 0 || !Number.isFinite(entry.index)) {
    logger.warn(
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
    logger.warn(
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
