/**
 * Canonical Readium Locator JSON codec — cross-device continuity (T1).
 *
 * A single canonical locator string is stored in `locator_json` and consumed
 * by both NEXO engines (desktop + android). Shape (Readium):
 *
 *   {"href":"chapter/001.xhtml","type":"application/xhtml+xml",
 *    "locations":{"progression":0.37,"position":6,"fragment":"epubcfi(...)"}}
 *
 * Responsibilities:
 *   - Resolve a precise epubjs CFI to an href using the reading order (spine).
 *   - Compute within-chapter `progression` from a caller-supplied char offset
 *     (the caller derives the offset from the rendered chapter DOM in the
 *     iframe; this module is a pure serializer + resolver and owns no DOM).
 *   - Round-trip a locator back to its precise CFI (`cfiFromLocator`).
 *   - Serialise/deserialise the canonical JSON.
 *
 * The CFI spine-prefix format is the same as `cfiBridge`:
 *   `epubcfi(/6/{spineIndex}!)...` — spineIndex is 1-based.
 */

export interface LocatorChapterMetric {
  /** Total number of characters in the resolved chapter's text. */
  chapterChars: number;
  /** Character offset (0-based) of the anchor within that chapter text. */
  charOffset: number;
}

export interface LocatorLocations {
  /** 0..1 position within the chapter (or within the book for totals). */
  progression?: number;
  /** Raw precise CFI fragment used to re-anchor the node. */
  fragment?: string;
}

export interface CanonicalLocator {
  href: string;
  type: string;
  locations: LocatorLocations;
}

const FALLBACK_TYPE = 'application/xhtml+xml';

export function normalizeHref(href: string): string {
  return href.replace(/\\/g, '/');
}

export function normalizeLocatorJson(json: string | null | undefined): string | null {
  if (typeof json !== 'string' || json.length === 0) return json as string | null;
  try {
    const raw = JSON.parse(json) as { href?: unknown };
    if (typeof raw.href === 'string' && raw.href.includes('\\')) {
      raw.href = normalizeHref(raw.href);
      return JSON.stringify(raw);
    }
    return json;
  } catch {
    if (json.includes('\\')) {
      return json.replace(
        /"href"\s*:\s*"([^"]*)"/g,
        (_m: string, href: string) => `"href":"${href.replace(/\\/g, '/')}"`,
      );
    }
    return json;
  }
}

/** Regex matching the 1-based spine index of a CFI (`epubcfi(/6/N) where N ≥ 1`). */
const SPINE_INDEX_RE = /^epubcfi\(\/6\/(\d+)/;

/**
 * Extract the 1-based spine index from a CFI string. Returns null when the CFI
 * is malformed or has no spine index. Mirrors the `cfiBridge` grammar.
 */
export function parseSpineIndex(cfi: string | null | undefined): number | null {
  if (typeof cfi !== 'string' || cfi.length === 0) return null;
  const m = SPINE_INDEX_RE.exec(cfi);
  if (!m || !m[1]) return null;
  const parsed = Number.parseInt(m[1], 10);
  if (!Number.isFinite(parsed) || parsed < 1) return null;
  return parsed;
}

/**
 * Resolve a precise CFI to a canonical locator for the given reading order.
 * The spine index (from `/6/{N}`) maps to `readingOrder[N-1].href`.
 *
 * When `chapter` metrics are supplied, `locations.progression` is computed as
 * `charOffset / chapterChars` (clamped to [0, 1]) and `locations.fragment`
 * carries the raw precise CFI for re-anchoring. Progressions are only accurate
 * when the caller passes the resolved character geometry of the rendered
 * chapter.
 *
 * @returns null when the CFI is malformed or the spine index is out of range.
 */
export function locatorFromCfi(
  readingOrder: string[],
  cfi: string | null | undefined,
  chapter: LocatorChapterMetric | null | undefined,
): CanonicalLocator | null {
  const spineIndex = parseSpineIndex(cfi);
  if (spineIndex === null) return null;
  const rawHref = readingOrder[spineIndex - 1];
  if (!rawHref) return null;
  const href = normalizeHref(rawHref);

  const locations: LocatorLocations = {};
  const metric = chapter ?? null;
  if (metric) {
    const progression = charOffsetToProgression(metric.charOffset, metric.chapterChars);
    if (progression !== null) locations.progression = progression;
  }
  if (typeof cfi === 'string' && cfi.length > 0) {
    locations.fragment = cfi;
  }

  return { href, type: FALLBACK_TYPE, locations };
}

/**
 * Derive a chapter-anchored locator (progression 0.0, no precise CFI) for an
 * href present in the reading order. Used for legacy rows that only carry a
 * chapter reference (no mid-chapter precision).
 *
 * @returns null when the href is not in the readingOrder.
 */
export function deriveLocatorForChapter(
  readingOrder: string[],
  chapterHref: string | null | undefined,
): CanonicalLocator | null {
  if (typeof chapterHref !== 'string' || chapterHref.length === 0) return null;
  const normalizedChapterHref = normalizeHref(chapterHref);
  const normalizedOrder = readingOrder.map(normalizeHref);
  if (!normalizedOrder.includes(normalizedChapterHref)) return null;
  return {
    href: normalizedChapterHref,
    type: FALLBACK_TYPE,
    locations: { progression: 0 },
  };
}

/** Compute a clamped [0, 1] within-chapter progression. Returns null if the
 * total is non-positive. Pure geometry — no DOM. */
export function charOffsetToProgression(charOffset: number, chapterChars: number): number | null {
  if (!Number.isFinite(chapterChars) || chapterChars <= 0) return null;
  if (!Number.isFinite(charOffset)) return 0;
  return Math.max(0, Math.min(1, charOffset / chapterChars));
}

/** Return the precise CFI stored on the locator (round-trip), or null. */
export function cfiFromLocator(loc: CanonicalLocator): string | null {
  if (typeof loc?.locations?.fragment === 'string' && loc.locations.fragment.length > 0) {
    return loc.locations.fragment;
  }
  return null;
}

/** Serialise a locator to the canonical Readium JSON string. */
export function locatorToJson(loc: CanonicalLocator): string {
  const locations: Record<string, unknown> = {};
  if (typeof loc.locations.progression === 'number') {
    locations.progression = loc.locations.progression;
  }
  if (typeof loc.locations.fragment === 'string') {
    locations.fragment = loc.locations.fragment;
  }
  const href = normalizeHref(loc.href);
  return JSON.stringify({
    href,
    type: loc.type,
    locations,
  });
}

/**
 * Derived page helper — CFI-first page resolution.
 * For EPUB, the page is not a persisted source of truth; it is derived from
 * the canonical CFI. This helper returns `1` for any valid `epubcfi(...)`
 * (the minimal valid EPUB page) and `null` when the CFI is missing or
 * malformed, so callers can fall back via `?? 1`.
 *
 * Usage: `const page = fromCfi(cfiRange ?? cfiLocation) ?? 1`
 * `current_page` / `pageNumber` are deprecated as sync sources — they are
 * display-only and must not be written as canonical state.
 */
export function fromCfi(cfi: string | null | undefined): number | null {
  const idx = parseSpineIndex(cfi);
  return idx !== null ? 1 : null;
}

/** Alias for callers that expect `derivePage`. */
export const derivePage = fromCfi;

/** @deprecated Use `fromCfi(cfi) ?? 1` — `current_page` is no longer canonical. */
export const currentPageDeprecated = true;

/** Deserialise a canonical locator JSON string. Returns null on invalid input. */
export function locatorFromJson(json: string | null | undefined): CanonicalLocator | null {
  if (typeof json !== 'string' || json.length === 0) return null;
  const normalizedJson = normalizeLocatorJson(json);
  const toParse = normalizedJson ?? json;
  try {
    const raw = JSON.parse(toParse) as {
      href?: unknown;
      type?: unknown;
      locations?: { progression?: unknown; fragment?: unknown } | null;
    };
    if (typeof raw.href !== 'string' || raw.href.length === 0) return null;
    const href = normalizeHref(raw.href);
    const locations: LocatorLocations = {};
    if (raw.locations && typeof raw.locations.progression === 'number') {
      locations.progression = raw.locations.progression;
    }
    if (raw.locations && typeof raw.locations.fragment === 'string') {
      locations.fragment = raw.locations.fragment;
    }
    return {
      href,
      type: typeof raw.type === 'string' && raw.type.length > 0 ? raw.type : FALLBACK_TYPE,
      locations,
    };
  } catch {
    return null;
  }
}
