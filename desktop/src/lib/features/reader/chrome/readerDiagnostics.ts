/**
 * The reader's own live layout state, for the opt-in diagnostic panel.
 *
 * ## Why this exists
 *
 * Two subsystems write the chapter's inline scroll offset — the CFI/fragment
 * restore and the page turn — with nothing on screen arbitrating between them.
 * Surfaceing that conflict took a two-hour live session because neither writer
 * nor the layout's settled state was observable anywhere in the app. Everything
 * here is a read of state the reader already owns; nothing is written back.
 *
 * `restoring` and `layoutSettled` are the two that matter most: they are the
 * state no UI exposes today, and they are what the reader had to infer.
 */

/** The reader's live layout state, as the diagnostic panel reports it. */
export type ReaderDiagnostics = {
  /** Render mode the chapter document is actually laid out in. */
  mode: 'PAGINATED' | 'SCROLL';
  /** Page the reader is on, 1-based (paginated mode only; 1 otherwise). */
  page: number;
  /** Pages in the chapter (paginated mode only; 1 otherwise). */
  totalPages: number;
  /** Inline offset of the element that owns the chapter's overflow. */
  scrollLeft: number;
  /** The chapter is laid out: a usable page box and more than one column. */
  layoutSettled: boolean;
  /** A CFI or fragment restore is in flight (see `navigation.pendingCfiScroll`). */
  restoring: boolean;
};

/** Heading the diagnostic panel renders; kept out of the component for testability. */
export const READER_DIAGNOSTICS_LABEL = 'DEBUG';

/**
 * The state the panel opens on: nothing laid out, nothing in flight. Used as the
 * default before the first measurement so the panel never renders a stale frame.
 */
export function emptyReaderDiagnostics(): ReaderDiagnostics {
  return {
    mode: 'SCROLL',
    page: 1,
    totalPages: 1,
    scrollLeft: 0,
    layoutSettled: false,
    restoring: false,
  };
}
