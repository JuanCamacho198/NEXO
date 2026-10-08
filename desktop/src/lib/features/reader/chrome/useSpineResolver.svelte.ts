import { invoke } from '@tauri-apps/api/core';
import { normalizeHref } from '$lib/shared/sync/LocatorCodec';
import { stripFragment } from '$lib/features/reader/viewer-epub/epubViewerHelpers';
import { logger } from '$lib/shared/logger/Logger';

export type ParseEpubFn = (filePath: string, bookId: string) => Promise<{ spineHrefs: string[] }>;

const defaultParseEpub: ParseEpubFn = async (filePath, bookId) => {
  const meta = await invoke<{
    spineHrefs?: string[];
    spine_hrefs?: string[];
    toc?: Array<{ href: string }>;
    chapters?: Array<{ href: string }>;
    totalChapters: number;
  }>('parse_epub', { filePath, bookId });
  const raw = meta.spineHrefs ?? meta.spine_hrefs ?? [];
  if (Array.isArray(raw) && raw.length > 0) {
    return { spineHrefs: raw.map((h) => normalizeHref(h)) };
  }
  return { spineHrefs: [] };
};

export function createSpineResolver(deps: { parseEpub?: ParseEpubFn } = {}): {
  readonly epubSpineHrefs: string[];
  readonly epubSpineLoadedFor: string | null;
  getSpineIndexForHref(href: string, spine: string[]): number | null;
  ensureSpineHrefs(bookId: string, filePath: string): Promise<void>;
} {
  const parseEpub = deps.parseEpub ?? defaultParseEpub;

  let epubSpineHrefs = $state<string[]>([]);
  let epubSpineLoadedFor = $state<string | null>(null);

  function getSpineIndexForHref(href: string, spine: string[]): number | null {
    const raw = href.trim();
    const withoutPrefix = raw.startsWith('readium:') ? raw.slice('readium:'.length) : raw;
    const fragStripped = stripFragment(withoutPrefix);
    let norm = normalizeHref(fragStripped);
    while (norm.includes('//')) norm = norm.replace('//', '/');
    norm = norm.trim();
    if (!norm) return null;
    let idx = spine.findIndex((h) => normalizeHref(h) === norm);
    if (idx !== -1) return idx;
    const fileName = norm.split('/').pop() ?? '';
    if (fileName) {
      idx = spine.findIndex(
        (h) =>
          normalizeHref(h).endsWith('/' + fileName) ||
          normalizeHref(h).split('/').pop() === fileName,
      );
      if (idx !== -1) return idx;
    }
    idx = spine.findIndex((h) => {
      const nh = normalizeHref(h);
      return nh.endsWith(norm) || norm.endsWith(nh);
    });
    return idx !== -1 ? idx : null;
  }

  async function ensureSpineHrefs(bookId: string, filePath: string): Promise<void> {
    if (epubSpineLoadedFor === bookId && epubSpineHrefs.length > 0) return;
    try {
      const { spineHrefs } = await parseEpub(filePath, bookId);
      if (spineHrefs.length > 0) {
        epubSpineHrefs = spineHrefs;
        epubSpineLoadedFor = bookId;
        logger.warn(
          'RW: spine loaded',
          {
            spineHrefCount: epubSpineHrefs.length,
            bookId: bookId.slice(0, 4),
            firstHrefs: epubSpineHrefs.slice(0, 3),
          },
          'reader',
        );
      }
    } catch (e) {
      logger.warn(
        'RW: failed to load spine for readium fix',
        { error: e instanceof Error ? e.message : String(e) },
        'reader',
      );
    }
  }

  return {
    get epubSpineHrefs() {
      return epubSpineHrefs;
    },
    get epubSpineLoadedFor() {
      return epubSpineLoadedFor;
    },
    getSpineIndexForHref,
    ensureSpineHrefs,
  };
}

export type SpineResolver = ReturnType<typeof createSpineResolver>;
