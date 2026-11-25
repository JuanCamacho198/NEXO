import type { SessionProgressEvent } from './readingSessionValidator';

/**
 * Time without a page turn after which the reader is taken to have stopped.
 * A window left open on a page is not reading: the session closes at the last
 * turn plus this grace, and the next turn opens a new one.
 */
export const READING_IDLE_GRACE_MS = 5 * 60_000;

export type TrackedReadingSession = SessionProgressEvent & {
  bookId: string;
  endedAt: string;
};

type OpenSession = {
  bookId: string;
  startMs: number;
  lastActivityMs: number;
  startPercentage: number;
  lastPercentage: number;
};

export type ReadingSessionTracker = {
  readonly activeBookId: string | null;
  /** Opens a session for `bookId`, closing (and returning) any open one. */
  start(bookId: string, percentage: number): TrackedReadingSession | null;
  /**
   * A position change. Returns the session it closed when the reader had been
   * idle past the grace; the turn itself then starts a new one.
   */
  activity(bookId: string, percentage: number): TrackedReadingSession | null;
  end(): TrackedReadingSession | null;
};

const clampPercentage = (value: number): number =>
  Number.isFinite(value) ? Math.min(100, Math.max(0, value)) : 0;

/**
 * One reading visit becomes one session: from opening the book until leaving
 * it (or the window going to the background), cut short at the idle grace.
 * Pure bookkeeping; the caller decides what to persist.
 */
export function createReadingSessionTracker(
  now: () => number = Date.now,
  idleGraceMs: number = READING_IDLE_GRACE_MS,
): ReadingSessionTracker {
  let open: OpenSession | null = null;

  const close = (session: OpenSession, atMs: number): TrackedReadingSession => {
    const endMs = Math.max(session.startMs, Math.min(atMs, session.lastActivityMs + idleGraceMs));
    return {
      bookId: session.bookId,
      startedAt: new Date(session.startMs).toISOString(),
      endedAt: new Date(endMs).toISOString(),
      durationSeconds: Math.round((endMs - session.startMs) / 1000),
      startPercentage: session.startPercentage,
      endPercentage: session.lastPercentage,
    };
  };

  const begin = (bookId: string, percentage: number, atMs: number): void => {
    const pct = clampPercentage(percentage);
    open = {
      bookId,
      startMs: atMs,
      lastActivityMs: atMs,
      startPercentage: pct,
      lastPercentage: pct,
    };
  };

  return {
    get activeBookId() {
      return open?.bookId ?? null;
    },
    start(bookId, percentage) {
      const at = now();
      const closed = open ? close(open, at) : null;
      begin(bookId, percentage, at);
      return closed;
    },
    activity(bookId, percentage) {
      if (!open || open.bookId !== bookId) return null;
      const at = now();
      if (at - open.lastActivityMs > idleGraceMs) {
        const closed = close(open, at);
        begin(bookId, percentage, at);
        return closed;
      }
      open.lastActivityMs = at;
      open.lastPercentage = clampPercentage(percentage);
      return null;
    },
    end() {
      if (!open) return null;
      const closed = close(open, now());
      open = null;
      return closed;
    },
  };
}
