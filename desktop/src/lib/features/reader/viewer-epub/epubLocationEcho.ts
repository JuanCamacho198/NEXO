/**
 * epubLocationEcho — recognises the reader's own emitted position when it comes
 * back as an input.
 *
 * ## The defect this exists for
 *
 * The reader publishes its precise location on every settle
 * (`emitPreciseLocation` -> `onLocationChange`). The parent stores that value in
 * the reader state and passes it straight back as the viewer's
 * `initialLocation`. The bridge's `continue` effect then sees the change and
 * treats it as an external navigation request ("Continue reading" from the
 * library), re-navigating the reader to the chapter its OWN last emitted
 * position names. When that happens while a page turn is committing, the turn
 * lands and is pulled straight back — the measured bounce (`4,4,4,4,4,6,4,4,4,4`
 * and, live this session, `5/10` seeded opens on one book).
 *
 * ## Why a set, not a "last value" marker
 *
 * A turn emits several positions in a row (the chapter's `srcdoc` is swapped as
 * its assets settle, and each commit emits). An EARLIER value can therefore
 * arrive *after* a later one, which was measured as a turn to chapter 30
 * followed about seven seconds later by an echo naming chapter 29 that
 * re-navigated backwards. Remembering only the most recent value missed those
 * and left the bounce at `2/14`; remembering every value (bounded) took it to
 * `0/16`.
 *
 * Pure and dependency-free so the rule can be tested directly.
 */

/** How many emitted locations are remembered. A turn emits a handful; 64 is
 * ample for one chapter and bounds the memory a long session can hold. */
export const EMITTED_LOCATIONS_CAP = 64;

export interface LocationEcho {
  /** Record a location the reader itself published. */
  remember(location: string): void;
  /** True when `location` is one the reader published — an echo, not a request. */
  isEcho(location: string): boolean;
  /** Forget every remembered location (a new book, or a reader teardown). */
  clear(): void;
}

export function createLocationEcho(cap = EMITTED_LOCATIONS_CAP): LocationEcho {
  // Insertion-ordered, so the oldest entry is the first one to drop.
  const seen = new Set<string>();
  return {
    remember(location: string): void {
      if (!location) return;
      if (seen.size >= cap) {
        const oldest = seen.values().next().value;
        if (oldest !== undefined) seen.delete(oldest);
      }
      seen.add(location);
    },
    isEcho(location: string): boolean {
      return seen.has(location);
    },
    clear(): void {
      seen.clear();
    },
  };
}
