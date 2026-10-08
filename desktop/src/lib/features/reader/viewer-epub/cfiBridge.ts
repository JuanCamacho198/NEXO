/**
 * Minimal EPUB CFI (Canonical Fragment Identifier) bridge.
 *
 * Why a homegrown implementation:
 * - The only published npm package under the name `epub-cfi` is the
 *   ReadiumJS fork from 2014 (v0.0.1). It is published as CJS, depends
 *   on jQuery 2.1.3, and is essentially abandonware. Bundling jQuery
 *   into a Svelte 5 / Vite / Tauri app just for CFI math is a 30+KB
 *   regression for a ~2KB feature. The `epub-cfi` dep remains in
 *   `package.json` (T-01) so the implementation can be swapped in
 *   later if a maintained package surfaces.
 *
 * What this implements (the subset we need):
 * - Convert a DOM `Range` inside a stripped EPUB chapter into a CFI
 *   string of the form
 *     `epubcfi(/6/{spineIndex}!{localPath},{startStep}:{startOffset},{endStep}:{endOffset})`
 * - Resolve such a CFI back to a DOM `Range` in the chapter's document.
 * - Compute the chapter base CFI prefix for a given chapter href.
 *
 * The DOM we operate on is the chapter `srcdoc` produced by
 * `EpubNativeViewer.buildChapterSrcdoc`, which has had all `<script>`
 * tags removed and an injected `<style id="nexo-reader-overrides">`
 * appended. Blacklisting is by element-id (the injected style is the only
 * element that ever has a non-`null` `id` in the chapter body), and by
 * a fixed set of element tags that should never appear in CFI paths
 * (the same blacklist as Readium CFI: `audio`, `video`, `script`,
 * `link`, `style`, `object`, `embed`).
 *
 * CFI spec reference: EPUB 3 spec §7.2.4 "Canonical Fragment
 * Identifiers" (https://idpf.org/epub/linking/cfi/). The
 * child-indexing convention we use:
 *   - Even integers (2, 4, 6, ...) for element nodes
 *   - Odd  integers (1, 3, 5, ...) for text nodes
 * This lets a single integer uniquely identify a node of one kind
 * within its parent's children list, even when elements and texts
 * interleave.
 *
 * All public functions are total: they NEVER throw on invalid input.
 * On failure they `logger.warn` and return `null`.
 *
 * @module $lib/features/reader/viewer-epub/cfiBridge
 */

import { IFRAME_CFI_BRIDGE_SCRIPT } from './cfiBridgeIframe';
import { logger } from '$lib/shared/logger/Logger';

const BLACKLIST_TAGS = new Set(['audio', 'video', 'script', 'link', 'style', 'object', 'embed']);

export function normalizeHref(href: string): string {
  return href.replace(/\\/g, '/');
}

export const CFI_RE = /^epubcfi\(\/6\/(\d+)!(.+)\)$/;
export const TERMINUS_RE = /^(\d+):(\d+)$/;

/**
 * Assert that the iframe bridge script regexes match the parent constants.
 * Throws if drift is detected — used as a build-time lockstep check.
 * The iframe script is a plain-JS string inlined into srcdoc; its regex literals
 * must be byte-for-byte equivalent (modulo escaping) to CFI_RE / TERMINUS_RE.
 */
export function assertLockstep(): void {
  const script = IFRAME_CFI_BRIDGE_SCRIPT as string;
  const cfiSource = CFI_RE.source;
  const terminusSource = TERMINUS_RE.source;
  const hasCFI = script.includes(cfiSource);
  const hasTerminus = script.includes(terminusSource);
  if (!hasCFI || !hasTerminus) {
    throw new Error(
      `CFI lockstep failure: IFRAME_CFI_BRIDGE_SCRIPT regex drift. Expected CFI_RE ${CFI_RE} and TERMINUS_RE ${TERMINUS_RE} in iframe script. hasCFI=${hasCFI} hasTerminus=${hasTerminus}`,
    );
  }
  // Also ensure double-escape bug is not present: runtime script must NOT contain \\( or \\d
  if (script.includes('\\\\(') || script.includes('\\\\d')) {
    throw new Error(
      'CFI lockstep failure: iframe script contains double-escaped regex (\\\\( or \\\\d) — remove extra backslash',
    );
  }
}

/**
 * Spine registry: maps a chapter href (1:1 with the EPUB's spine order)
 * to its 1-based index. The parent (or a build step) calls
 * {@link setSpine} once at iframe init with the full ordered list of
 * chapter hrefs. Subsequent calls to {@link getChapterBaseCFI} or
 * {@link rangeToCFI} use this registry to compute the spine index.
 *
 * The registry is intentionally a module-private variable (not a prop on
 * each function) so the iframe-side bridge -- which is inlined as a
 * `<script>` in the srcdoc -- does not need to thread the spine through
 * every call site.
 */
let spineHrefs: string[] = [];

/**
 * Register the ordered list of chapter hrefs from the EPUB's spine.
 * The bridge uses this registry to map a chapter href to its 1-based
 * spine index.
 *
 * Idempotent: calling it multiple times replaces the registry.
 *
 * @param hrefs - Ordered list of chapter hrefs, as they appear in the
 *                EPUB spine. `null`/`undefined` resets the registry.
 */
export function setSpine(hrefs: string[] | null | undefined): void {
  if (!hrefs || !Array.isArray(hrefs)) {
    spineHrefs = [];
    return;
  }
  spineHrefs = hrefs.map((h) => normalizeHref(h));
}

/**
 * Look up the 1-based spine index of a chapter href. Returns `null`
 * if the href is not registered.
 *
 * @param chapterHref - The chapter's href inside the EPUB.
 */
export function getSpineIndex(chapterHref: string): number | null {
  if (typeof chapterHref !== 'string' || chapterHref.length === 0) return null;
  const normalized = normalizeHref(chapterHref);
  const idx = spineHrefs.indexOf(normalized);
  if (idx < 0) return null;
  return idx + 1; // 1-based per EPUB CFI spec
}

/**
 * Compute the base CFI for a chapter: the degenerate CFI that points to
 * the start of the chapter. Returned as a complete CFI string
 * (`epubcfi(/6/N!)`) so callers can use it as a stand-alone CFI when
 * needed. To compose a range CFI, drop the trailing `)` and append a
 * local path plus termini -- see {@link rangeToCFI} for the canonical
 * composition.
 *
 * Format: `epubcfi(/6/N!)` where N is the chapter's 1-based spine index.
 *
 * @param chapterHref - The chapter's href.
 * @returns The base CFI, or `null` if the chapter is not registered.
 */
export function getChapterBaseCFI(chapterHref: string): string | null {
  const idx = getSpineIndex(chapterHref);
  if (idx === null) return null;
  return `epubcfi(/6/${idx}!)`;
}

/**
 * Internal: like {@link getChapterBaseCFI} but without the trailing
 * `)`. Used by {@link rangeToCFI} to compose the final CFI without
 * stripping a char off the public-facing format.
 */
function getChapterBasePrefix(chapterHref: string): string | null {
  const idx = getSpineIndex(chapterHref);
  if (idx === null) return null;
  return `epubcfi(/6/${idx}!`;
}

/**
 * Filter children of an element, removing nodes that should not appear
 * in CFI paths (blacklist tags) and any element with the injected
 * reader-style id (`nexo-reader-overrides`).
 */
function isBlacklisted(node: Node): boolean {
  if (node.nodeType !== 1) return false;
  const el = node as Element;
  if (el.id === 'nexo-reader-overrides') return true;
  return BLACKLIST_TAGS.has(el.tagName.toLowerCase());
}

/**
 * Index a target child node within `parent` using the CFI child-index
 * convention, ignoring blacklisted nodes.
 *
 * - Element targets get an even integer: 2 * (siblingIndex) where
 *   siblingIndex is 1-based among non-blacklisted element siblings.
 * - Text targets get an odd integer: 2 * (textSiblingIndex) + 1 where
 *   textSiblingIndex is 0-based among non-blacklisted text siblings.
 *
 * @returns The CFI child index, or `null` if the target is not a
 *          non-blacklisted child of `parent`.
 */
function cfiChildIndex(parent: Node, target: Node): number | null {
  const children = Array.from(parent.childNodes);
  let elementIndex = 0;
  let textIndex = 0;
  let found = false;
  let result = 0;

  for (const child of children) {
    if (isBlacklisted(child)) continue;

    if (child === target) {
      if (child.nodeType === 3 /* TEXT_NODE */) {
        result = 2 * textIndex + 1;
      } else {
        result = 2 * (elementIndex + 1);
      }
      found = true;
      break;
    }

    if (child.nodeType === 3) {
      textIndex += 1;
    } else {
      elementIndex += 1;
    }
  }

  return found ? result : null;
}

/**
 * Build the CFI local path from `startNode` (an element) up to (but
 * not including) the document's `<html>` root. Returns the
 * concatenated local path with a leading `/`, or `null` if the path
 * cannot be resolved (e.g. the node is detached or sits outside the
 * document).
 *
 * Per the EPUB CFI spec:
 *   - The local path contains ELEMENT steps only. Text-node
 *     positions live in the termini.
 *   - The local path stops at the content document's root
 *     (`<html>`). The spine indirection `/6/N!` already established
 *     which content document we're in, so the html element's own
 *     child index in the `Document` is NOT part of the path.
 *
 * If the range's common ancestor is a text node, the caller should
 * pass the text node's parent element here.
 */
function buildLocalPath(startNode: Node, doc: Document): string | null {
  // Normalize: if the caller passed a text node (or any non-element),
  // walk up to the nearest element ancestor. The text node's own
  // child index is captured in the termini, not the local path.
  let current: Node | null = startNode;
  if (current.nodeType !== 1 /* ELEMENT_NODE */) {
    current = current.parentNode;
  }
  if (!current || current.nodeType !== 1) return null;

  const docElement = doc.documentElement;
  if (!docElement) return null;

  // Walk from `current` up to but not including the document element.
  // The first step of the local path is the common ancestor's
  // child index in its parent (e.g. body[2] in html[2] for the body
  // child); the last step is the common ancestor's parent's child
  // index in the document element (e.g. body[2] in html[2] = the body
  // node). The document element's own child index in the Document
  // is excluded.
  const segments: number[] = [];
  let node: Node | null = current;
  while (node && node !== docElement) {
    if (node.nodeType === 1 /* ELEMENT_NODE */) {
      const parent: Node | null = node.parentNode;
      if (!parent) return null;
      const idx = cfiChildIndex(parent, node);
      if (idx === null) return null;
      segments.unshift(idx);
    }
    node = node.parentNode;
  }

  if (node !== docElement) {
    // We walked past the document element without reaching it.
    // The start node sits outside the document.
    return null;
  }

  if (segments.length === 0) return null;
  return '/' + segments.join('/');
}

/**
 * Convert a DOM `Range` into a full CFI string anchored to the
 * chapter.
 *
 * @param range       A `Range` from the chapter's document.
 * @param chapterHref The chapter's href inside the EPUB (used to look
 *                    up the spine index).
 * @param doc         The chapter's parsed `Document` (the iframe's
 *                    `contentDocument`).
 * @returns The full CFI string, or `null` on any failure.
 */
export function rangeToCFI(
  range: Range | null | undefined,
  chapterHref: string,
  doc: Document | null | undefined,
): string | null {
  try {
    if (!range || !doc || typeof chapterHref !== 'string' || chapterHref.length === 0) {
      return null;
    }

    const base = getChapterBasePrefix(chapterHref);
    if (!base) {
      logger.warn('epub-cfi: chapter not in spine registry', { chapterHref }, 'reader');
      return null;
    }

    const startContainer = range.startContainer;
    const endContainer = range.endContainer;
    const startOffset = range.startOffset;
    const endOffset = range.endOffset;

    // The CFI spec defines the common ancestor as the deepest ELEMENT
    // that contains both endpoints. If `range.commonAncestorContainer`
    // returns a text node (typical for a single-text-node selection),
    // walk up to its parent element.
    const rawAncestor = range.commonAncestorContainer;
    let commonAncestor: Node = rawAncestor;
    if (commonAncestor.nodeType === 3 /* TEXT_NODE */) {
      if (!commonAncestor.parentNode) {
        logger.warn('epub-cfi: common ancestor has no parent', { chapterHref }, 'reader');
        return null;
      }
      commonAncestor = commonAncestor.parentNode;
    }
    if (commonAncestor.nodeType !== 1 /* ELEMENT_NODE */) {
      logger.warn('epub-cfi: common ancestor is not an element', { chapterHref }, 'reader');
      return null;
    }

    const localPath = buildLocalPath(commonAncestor, doc);
    if (!localPath) {
      const el = commonAncestor as Element | null;
      const startEl = startContainer.nodeType === 1 ? (startContainer as Element) : null;
      const startParentEl =
        startContainer.parentNode && startContainer.parentNode.nodeType === 1
          ? (startContainer.parentNode as Element)
          : null;
      let depth = 0;
      let cursor: Node | null = el;
      while (cursor && cursor.nodeType === 1 && cursor !== doc.documentElement && depth < 20) {
        cursor = cursor.parentNode;
        depth += 1;
      }
      logger.warn(
        'epub-cfi: failed to build local path for range',
        {
          chapterHref,
          ancestorTag: el?.tagName.toLowerCase() ?? String(commonAncestor),
          ancestorId: el?.id ?? '',
          ancestorIsRoot: commonAncestor === doc.documentElement,
          ancestorIsBody: commonAncestor === doc.body,
          depthToRoot: depth,
          startTag: startEl?.tagName.toLowerCase() ?? 'text',
          startParentTag: startParentEl?.tagName.toLowerCase() ?? 'none',
          startOffset,
        },
        'reader',
      );
      return null;
    }

    // The local path already includes its leading `/`. Compose:
    //   `epubcfi(/6/N!` + `/A/B/C` + `,/S:Os,/E:Oe` + `)`
    const startTerminus = textTerminusStep(startContainer, startOffset, commonAncestor);
    if (startTerminus === null) {
      logger.warn('epub-cfi: failed to compute start terminus', { chapterHref }, 'reader');
      return null;
    }

    const endTerminus = textTerminusStep(endContainer, endOffset, commonAncestor);
    if (endTerminus === null) {
      logger.warn('epub-cfi: failed to compute end terminus', { chapterHref }, 'reader');
      return null;
    }

    return `${base}${localPath},${startTerminus},${endTerminus})`;
  } catch (err) {
    logger.warn(
      'epub-cfi: rangeToCFI failed',
      { error: err instanceof Error ? err.message : String(err) },
      'reader',
    );
    return null;
  }
}

/**
 * Find the first text-node descendant of `node` (depth-first). Used by
 * {@link textTerminusStep} when the range endpoint's container is an
 * element node: we need to descend to a text node before the CFI
 * terminus can carry a character offset. Returns `null` if `node` has
 * no text descendants.
 */
function findFirstTextDescendant(node: Node): Text | null {
  if (node.nodeType === 3 /* TEXT_NODE */) return node as Text;
  for (const child of Array.from(node.childNodes)) {
    if (isBlacklisted(child)) continue;
    const found = findFirstTextDescendant(child);
    if (found) return found;
  }
  return null;
}

/**
 * Compute the text-terminus step chain for one endpoint of a range.
 *
 * Per the EPUB CFI spec, a terminus can be a chain of CFI steps ending
 * in a text-node offset. The chain descends from the range's common
 * ancestor down to the text node:
 *
 *   - Simple case: text node is a direct child of the common ancestor.
 *     Returns `/N:offset` (e.g. `/1:87`).
 *   - Nested case: text node is nested under the common ancestor (e.g.
 *     in a sibling paragraph). Returns `/A/B/N:offset` (e.g. `/4/1:87`)
 *     where the leading steps are the chain of element children from
 *     the common ancestor down to the text node's parent, and the
 *     final `/N:offset` is the text node's child index plus offset.
 *
 * If the endpoint's container is not a text node, we walk to the
 * nearest text descendant (the mouse-selection happy path).
 *
 * Element-container semantics (Bug B fix, see design Decision 1):
 * when `container.nodeType === 1`, we branch on `offset` against
 * `childNodes.length`:
 *   - `offset <= 0`: target `children[0]`, first text descendant, char 0
 *   - `offset >= children.length`: target `children[last]`, first text
 *     descendant, char at end of text (the "after the last child"
 *     position resolves to the end of the last text node)
 *   - `0 < offset < children.length`: target `children[offset]`, first
 *     text descendant, char 0
 * This matches browser `Range.endOffset` semantics for element
 * containers and prevents the wrap from overshooting into the next
 * paragraph's leading whitespace.
 *
 * @param container        The range's start or end container.
 * @param offset           The range's start or end offset.
 * @param commonAncestor   The range's common ancestor (the deepest
 *                         element that contains both endpoints).
 * @param doc              The chapter's document.
 * @returns The terminus step chain string, or `null`.
 */
function textTerminusStep(container: Node, offset: number, commonAncestor: Node): string | null {
  let textNode: Node | null = null;
  // When true, the resolved character offset is the END of the chosen
  // text node (not the `offset` argument). Set when the element-
  // container offset was at or past the end of `children.length`, which
  // is the "after the last child" position and should resolve to the
  // end of the last text descendant.
  let forceTextEnd = false;

  if (container.nodeType === 3 /* TEXT_NODE */) {
    textNode = container;
  } else if (container.nodeType === 1 /* ELEMENT_NODE */) {
    // Pick the child to descend into based on `offset` vs `childNodes.length`.
    // See the element-container semantics block in the JSDoc above.
    const el = container as Element;
    const children = el.childNodes;
    if (children.length === 0) return null;

    let targetChild: Node;
    if (offset <= 0) {
      targetChild = children[0]!;
    } else if (offset >= children.length) {
      targetChild = children[children.length - 1]!;
      forceTextEnd = true;
    } else {
      targetChild = children[offset]!;
    }

    textNode = findFirstTextDescendant(targetChild);
  } else {
    return null;
  }

  if (!textNode || !textNode.parentNode) return null;
  if (textNode.nodeType !== 3) return null;

  // Verify the text node is in the common ancestor's subtree.
  if (!isDescendantOf(textNode, commonAncestor) && textNode !== commonAncestor) {
    return null;
  }

  // Build the step chain: collect child indices from the common
  // ancestor down to the text node's parent, then the text node's
  // own child index + offset.
  const segments: number[] = [];
  let current: Node | null = textNode.parentNode;
  while (current && current !== commonAncestor) {
    const parent: Node | null = current.parentNode;
    if (!parent) return null;
    const idx = cfiChildIndex(parent, current);
    if (idx === null) return null;
    segments.unshift(idx);
    current = parent;
  }

  if (current !== commonAncestor) return null;

  // Add the text node's own child index.
  const textIdx = cfiChildIndex(textNode.parentNode, textNode);
  if (textIdx === null) return null;
  segments.push(textIdx);

  const totalLen = (textNode.nodeValue ?? '').length;
  // For element-container endpoints at the "after the last child"
  // position, the supplied `offset` is a child index, not a character
  // offset. Resolve to the END of the text node instead.
  const clampedOffset = forceTextEnd ? totalLen : Math.max(0, Math.min(offset, totalLen));

  return `/${segments.join('/')}:${clampedOffset}`;
}

/**
 * Test whether `node` is a descendant of (or equal to) `ancestor`.
 * Walks up the parent chain. Returns `false` if either node is null
 * or detached.
 */
function isDescendantOf(node: Node, ancestor: Node): boolean {
  let current: Node | null = node;
  while (current) {
    if (current === ancestor) return true;
    current = current.parentNode;
  }
  return false;
}

/**
 * Convert a CFI string back to a DOM `Range` in the chapter's
 * document.
 *
 * @param cfi         The full CFI from a saved highlight.
 * @param chapterHref The chapter's href. MUST match the href used when
 *                    the CFI was generated.
 * @param doc         The chapter's parsed `Document`.
 * @returns A `Range`, or `null` if the CFI doesn't resolve (e.g. text
 *          shifted, chapter re-extracted).
 */
export function cfiToRange(
  cfi: string | null | undefined,
  chapterHref: string,
  doc: Document | null | undefined,
): Range | null {
  try {
    if (!cfi || !doc || typeof chapterHref !== 'string' || chapterHref.length === 0) {
      return null;
    }

    const parsed = parseCFI(cfi);
    if (!parsed) {
      logger.warn('epub-cfi: failed to parse cfi', { cfi }, 'reader');
      return null;
    }

    // Sanity-check the spine index matches the registered chapter.
    const registeredIdx = getSpineIndex(chapterHref);
    if (registeredIdx === null || registeredIdx !== parsed.spineIndex) {
      logger.warn(
        'epub-cfi: spine index mismatch',
        {
          cfi,
          chapterHref,
          cfiSpineIndex: parsed.spineIndex,
          registeredSpineIndex: registeredIdx,
        },
        'reader',
      );
      return null;
    }

    // Walk the local path from the chapter root (<html>) to the common
    // ancestor.
    const root = doc.documentElement;
    if (!root) return null;
    const commonAncestor = walkLocalPath(root, parsed.localPath);
    if (!commonAncestor) {
      logger.warn('epub-cfi: local path did not resolve', { cfi }, 'reader');
      return null;
    }

    const startText = resolveTextTerminus(commonAncestor, parsed.startChain, parsed.startOffset);
    const endText = resolveTextTerminus(commonAncestor, parsed.endChain, parsed.endOffset);
    if (!startText || !endText) {
      logger.warn('epub-cfi: text terminus did not resolve', { cfi }, 'reader');
      return null;
    }

    const range = doc.createRange();
    range.setStart(startText.node, startText.offset);
    range.setEnd(endText.node, endText.offset);
    return range;
  } catch (err) {
    logger.warn(
      'epub-cfi: cfiToRange failed',
      { error: err instanceof Error ? err.message : String(err) },
      'reader',
    );
    return null;
  }
}

interface ParsedCFI {
  spineIndex: number;
  localPath: number[]; // even integers for element steps
  startChain: number[]; // CFI steps from common ancestor to text node
  startOffset: number; // character offset within the start text node
  endChain: number[]; // CFI steps from common ancestor to text node
  endOffset: number; // character offset within the end text node
}

/**
 * Parse a CFI string of the form
 *   epubcfi(/6/N!/A/B/C,/S1/S2/.../St:Os,/E1/E2/.../Et:Oe)
 * into its components. The start/end termini are step chains: a
 * sequence of CFI child indices ending in a text-node step
 * (odd integer) with a character offset. Returns `null` on
 * malformed input.
 */
function parseCFI(cfi: string): ParsedCFI | null {
  // Match the high-level shape: `epubcfi(/6/N!PATH,S,E)`
  const m = CFI_RE.exec(cfi);
  if (!m || !m[1] || !m[2]) return null;

  const spineIndex = Number.parseInt(m[1], 10);
  if (!Number.isFinite(spineIndex) || spineIndex <= 0) return null;

  const tail = m[2];
  // Split into [localPath, startTerminus, endTerminus]
  const parts = tail.split(',');
  if (parts.length < 3) return null;
  const localPathStr = parts[0] ?? '';
  const startStr = parts[1] ?? '';
  const endStr = parts[2] ?? '';

  const localPath = localPathStr
    .split('/')
    .filter((s) => s.length > 0)
    .map((s) => Number.parseInt(s, 10))
    .filter((n) => Number.isFinite(n) && n > 0);

  const start = parseTerminusChain(startStr);
  const end = parseTerminusChain(endStr);
  if (!start || !end) return null;

  return {
    spineIndex,
    localPath,
    startChain: start.chain,
    startOffset: start.offset,
    endChain: end.chain,
    endOffset: end.offset,
  };
}

/**
 * Parse a terminus step chain like `/4/1:87` (element steps then a
 * text step with offset) or `/1:87` (direct text step). Returns the
 * full chain + the offset, or `null` on malformed input.
 */
function parseTerminusChain(s: string): { chain: number[]; offset: number } | null {
  if (typeof s !== 'string' || s.length === 0) return null;
  // Split on `/`. The first segment is empty (string starts with `/`).
  const segments = s.split('/').filter((seg) => seg.length > 0);
  if (segments.length === 0) return null;

  // The LAST segment must be `N:OFFSET` (text terminus). All earlier
  // segments are element steps.
  const last = segments[segments.length - 1] ?? '';
  const m = TERMINUS_RE.exec(last);
  if (!m || !m[1] || !m[2]) return null;
  const lastStep = Number.parseInt(m[1], 10);
  const offset = Number.parseInt(m[2], 10);
  if (
    !Number.isFinite(lastStep) ||
    !Number.isFinite(offset) ||
    lastStep <= 0 ||
    lastStep % 2 !== 1 ||
    offset < 0
  ) {
    return null;
  }

  const chain: number[] = [];
  for (let i = 0; i < segments.length - 1; i++) {
    const seg = segments[i] ?? '';
    const n = Number.parseInt(seg, 10);
    if (!Number.isFinite(n) || n <= 0 || n % 2 !== 0) return null; // element steps must be even
    chain.push(n);
  }
  chain.push(lastStep);

  return { chain, offset };
}

/**
 * Walk a local path (array of even-integer child indices) starting
 * from `root`, returning the node at the end of the path. Returns
 * `null` if the path cannot be resolved.
 */
function walkLocalPath(root: Element, localPath: number[]): Element | null {
  let current: Element = root;
  for (const step of localPath) {
    const target = findChildByCfiIndex(current, step);
    if (!target || target.nodeType !== 1) return null;
    current = target as Element;
  }
  return current;
}

/**
 * Find a child of `parent` matching the given CFI child index,
 * respecting the blacklist.
 */
function findChildByCfiIndex(parent: Node, cfiIndex: number): Node | null {
  let elementIndex = 0;
  let textIndex = 0;
  for (const child of Array.from(parent.childNodes)) {
    if (isBlacklisted(child)) continue;
    if (cfiIndex % 2 === 1 /* odd -> text node */) {
      if (child.nodeType === 3) {
        if (2 * textIndex + 1 === cfiIndex) return child;
        textIndex += 1;
      } else {
        // Element where a text step expects a text node: skip.
      }
    } else {
      // even -> element node
      if (child.nodeType === 1) {
        if (2 * (elementIndex + 1) === cfiIndex) return child;
        elementIndex += 1;
      }
    }
  }
  return null;
}

/**
 * Resolve a text-terminus step chain to a (node, offset) pair given
 * the common ancestor. The chain is a sequence of CFI child indices
 * starting from the common ancestor's child and descending to the
 * text node. The last step (odd integer) is the text node's child
 * index in its parent.
 */
function resolveTextTerminus(
  commonAncestor: Node,
  chain: number[],
  offset: number,
): { node: Node; offset: number } | null {
  if (chain.length === 0) return null;
  const lastStep = chain[chain.length - 1];
  if (lastStep === undefined || lastStep % 2 !== 1) return null; // must end at a text step

  // Walk the chain from common ancestor down to the text node's parent.
  let current: Node = commonAncestor;
  for (let i = 0; i < chain.length - 1; i++) {
    const step = chain[i];
    if (step === undefined) return null;
    const target = findChildByCfiIndex(current, step);
    if (!target || target.nodeType !== 1) return null;
    current = target as Element;
  }

  // Final step: the text node, as a direct child of `current`.
  const textNode = findChildByCfiIndex(current, lastStep);
  if (!textNode || textNode.nodeType !== 3) return null;
  const text = textNode.nodeValue ?? '';
  const clamped = Math.max(0, Math.min(offset, text.length));
  return { node: textNode, offset: clamped };
}
