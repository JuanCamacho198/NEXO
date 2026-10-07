import {
  createSpineResolver,
  type ParseEpubFn,
} from '$lib/features/reader/chrome/useSpineResolver.svelte';
import { normalizeHref, stripFragment } from '$lib/features/reader/viewer-epub/epubViewerHelpers';
import {
  spineIndexForToc as pureSpineIndexForToc,
  tocIndexForSpine as pureTocIndexForSpine,
  type EpubChapterMeta,
} from '$lib/features/reader/viewer-epub/epubViewerHelpers';
import { logger } from '$lib/shared/logger/Logger';

export type { EpubChapterMeta };
export { normalizeHref, stripFragment };

export interface EpubMetadataExtract {
  title: string;
  author: string;
  language: string | null;
  publisher: string | null;
  toc: EpubChapterMeta[];
  spineHrefs: string[];
  chapters?: EpubChapterMeta[];
  spine_hrefs?: string[];
  totalChapters: number;
  total_chapters?: number;
  resourcesPath: string;
  resources_path?: string;
}

export type EpubSpineDeps = {
  getMetadata: () => EpubMetadataExtract | null;
  parseEpub?: ParseEpubFn;
};

export function createEpubSpine(deps: EpubSpineDeps): {
  getToc(): EpubChapterMeta[];
  getSpineHrefs(): string[];
  spineIndexForToc(tocIndex: number): number;
  tocIndexForSpine(spineIndex: number, spineHref?: string): number | null;
  ensureSpineHrefs(bookId: string, filePath: string): Promise<void>;
  readonly spineHrefs: string[];
  readonly spineLoadedFor: string | null;
  getSpineIndexForHref(href: string, spine: string[]): number | null;
  normalizeHref: typeof normalizeHref;
  stripFragment: typeof stripFragment;
} {
  const resolver = createSpineResolver({ parseEpub: deps.parseEpub });

  function getToc(): EpubChapterMeta[] {
    const meta = deps.getMetadata();
    if (!meta) return [];
    const t = (meta as EpubMetadataExtract).toc;
    if (Array.isArray(t) && t.length >= 0) return t;
    const ch = (meta as EpubMetadataExtract).chapters;
    if (Array.isArray(ch)) return ch;
    return [];
  }

  function getSpineHrefs(): string[] {
    const meta = deps.getMetadata();
    if (!meta) {
      // Fallback to resolver cache when metadata not yet loaded (pre-init)
      if (resolver.epubSpineHrefs.length > 0) return [...resolver.epubSpineHrefs];
      return [];
    }
    const sh = (meta as EpubMetadataExtract).spineHrefs;
    if (Array.isArray(sh) && sh.length > 0) return sh.map((h) => normalizeHref(h));
    const sh2 = (meta as EpubMetadataExtract).spine_hrefs;
    if (Array.isArray(sh2) && sh2.length > 0) return sh2.map((h) => normalizeHref(h));
    // Fallback: derive from toc hrefs — spine authority preferred. May be misaligned offset-2.
    const toc = getToc();
    if (toc.length > 0) {
      logger.warn(
        'epub-spine: falling back to TOC-derived hrefs (spine empty) — may be misaligned if offset-2',
        { tocLen: toc.length },
        'reader',
      );
      return toc.map((c) => normalizeHref(stripFragment(c.href)));
    }
    if (resolver.epubSpineHrefs.length > 0) return [...resolver.epubSpineHrefs];
    return [];
  }

  function spineIndexForToc(tocIndex: number): number {
    const meta = deps.getMetadata();
    if (!meta) return tocIndex;
    const toc = getToc();
    const entry = toc[tocIndex];
    if (!entry || typeof entry.index !== 'number') {
      logger.warn(
        'epub-toc: spineIndexForToc missing entry for tocIndex',
        { tocIndex, fallback: tocIndex, tocLen: toc.length },
        'reader',
      );
      return tocIndex;
    }
    const spineLen = getSpineHrefs().length;
    const resolved = pureSpineIndexForToc(toc, tocIndex);
    if (spineLen > 0 && (resolved < 0 || resolved >= spineLen)) {
      logger.warn(
        'epub-toc: spineIndexForToc resolved index out-of-bounds',
        { resolved, spineLen, tocIndex },
        'reader',
      );
    }
    return resolved;
  }

  function tocIndexForSpine(spineIndex: number, spineHref?: string): number | null {
    const meta = deps.getMetadata();
    if (!meta) return null;
    const toc = getToc();
    const res = pureTocIndexForSpine(toc, spineIndex, spineHref);
    if (res !== null) return res;
    logger.warn(
      'epub-toc: tocIndexForSpine no TOC entry for spineIndex',
      { spineIndex, spineHref: spineHref ?? '(none)' },
      'reader',
    );
    return null;
  }

  async function ensureSpineHrefs(bookId: string, filePath: string): Promise<void> {
    // Cache guard delegated to resolver (per bookId)
    await resolver.ensureSpineHrefs(bookId, filePath);
  }

  return {
    getToc,
    getSpineHrefs,
    spineIndexForToc,
    tocIndexForSpine,
    ensureSpineHrefs,
    get spineHrefs(): string[] {
      return resolver.epubSpineHrefs;
    },
    get spineLoadedFor(): string | null {
      return resolver.epubSpineLoadedFor;
    },
    getSpineIndexForHref: resolver.getSpineIndexForHref,
    normalizeHref,
    stripFragment,
  };
}

export type EpubSpineState = ReturnType<typeof createEpubSpine>;
