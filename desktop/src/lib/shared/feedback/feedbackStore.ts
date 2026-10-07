/**
 * Crash feedback queue (sdd/sentry-observability-v2 PR3 desktop).
 *
 * Per design #2463: localStorage-backed offline queue (cap 25 FIFO),
 * dismissed set (eventId idempotence), and lastEventId round-trip for the
 * next-launch prompt path. The transport is injected so this module is
 * testable in isolation.
 *
 * PII policy: bookTitle/chapterLabel only ever leave the device through a
 * feedback-event capture (the scrubber strips them on non-feedback events).
 */
import * as Sentry from '@sentry/browser';

const QUEUE_KEY = 'np.feedback.queue';
const DISMISSED_KEY = 'np.feedback.dismissed';
const LAST_EVENT_ID_KEY = 'np.feedback.lastEventId';
const QUEUE_CAP = 25;

/**
 * Per-entry size ceiling, measured on the serialized entry. A queued entry
 * that carries a diagnostics bundle above this ceiling is delivered
 * bundle-free instead of truncated or failed (spec: oversized bundle is
 * dropped explicitly and the submission survives). Sized so a realistic
 * bundle (capped log tail + scrubbed diagnose) still fits while a single
 * entry cannot blow the localStorage quota shared by the whole queue.
 */
export const FEEDBACK_ENTRY_MAX_BYTES = 128 * 1024;

export interface BookContext {
  bookId: string;
  chapterIndex: number;
  page: number;
  title: string; // ≤100 chars
  chapterLabel: string; // ≤80 chars
}

export interface QueuedFeedback {
  eventId: string | null;
  message: string;
  /** Book context; this is the only channel through which bookTitle/chapterLabel are allowed egress. */
  contexts: { book: BookContext };
  enqueuedAt: number; // epoch ms
  /**
   * Optional, consent-gated diagnostics bundle text (from
   * `collectDiagnosticsBundle`). Absent for bundle-free submissions. Only ever
   * sent in full or dropped in full — never truncated.
   */
  bundle?: string;
}

export interface FlushTransport {
  send: (entry: QueuedFeedback) => Promise<boolean>;
}

/** Default transport — sends via Sentry.captureFeedback. The browser
 *  Sentry SDK does not accept a `contexts` field on captureFeedback, so the
 *  book context and the optional diagnostics bundle travel via
 *  `scope.setContext` (same pattern the AppModals transport uses). A bundle
 *  present on the entry is attached in full; the flush layer has already
 *  dropped oversized bundles before calling `send`.
 */
const defaultTransport: FlushTransport = {
  send: async (entry) => {
    try {
      Sentry.withScope((scope) => {
        scope.setContext('book', entry.contexts.book as unknown as Record<string, unknown>);
        if (entry.bundle) {
          scope.setContext('diagnostics', { bundle: entry.bundle });
        }
        Sentry.captureFeedback({
          message: entry.message,
          associatedEventId: entry.eventId ?? undefined,
        });
      });
      return true;
    } catch {
      return false;
    }
  },
};

function readJSON<T>(key: string, fallback: T): T {
  if (typeof localStorage === 'undefined') return fallback;
  const raw = localStorage.getItem(key);
  if (!raw) return fallback;
  try {
    return JSON.parse(raw) as T;
  } catch {
    return fallback;
  }
}

function writeJSON(key: string, value: unknown): void {
  if (typeof localStorage === 'undefined') return;
  try {
    localStorage.setItem(key, JSON.stringify(value));
  } catch {
    // quota or privacy mode: drop quietly; egress is best-effort
  }
}

/** Serialized byte size of a queue entry (bundle text included). */
function entryByteLength(entry: QueuedFeedback): number {
  const json = JSON.stringify(entry);
  if (typeof json !== 'string') return 0;
  if (typeof TextEncoder !== 'undefined') return new TextEncoder().encode(json).length;
  return json.length; // ASCII fallback; jsdom/Node always provide TextEncoder
}

/**
 * Whether a queue entry fits the per-entry ceiling. Bundle-free entries always
 * fit: their message and book context are themselves bounded by the dialog.
 */
export function feedbackEntryFits(entry: QueuedFeedback): boolean {
  if (entry.bundle === undefined) return true;
  return entryByteLength(entry) <= FEEDBACK_ENTRY_MAX_BYTES;
}

/**
 * Explicit drop: returns a copy of the entry with the bundle removed. The
 * submission payload is otherwise untouched — never a partial bundle.
 */
function withoutBundle(entry: QueuedFeedback): QueuedFeedback {
  const clone: QueuedFeedback = { ...entry };
  delete clone.bundle;
  return clone;
}

/** Read the last captured Sentry event id (or null). */
export function readLastEventId(): string | null {
  return readJSON<string | null>(LAST_EVENT_ID_KEY, null);
}

/** Persist a Sentry eventId for the next-launch prompt path. */
export function recordLastEventId(id: string | null): void {
  writeJSON(LAST_EVENT_ID_KEY, id);
}

export function isDismissed(eventId: string): boolean {
  const set = readJSON<string[]>(DISMISSED_KEY, []);
  return set.includes(eventId);
}

export function markDismissed(eventId: string): void {
  const set = readJSON<string[]>(DISMISSED_KEY, []);
  if (!set.includes(eventId)) {
    set.push(eventId);
    writeJSON(DISMISSED_KEY, set);
  }
}

export function clearDismissed(): void {
  if (typeof localStorage !== 'undefined') localStorage.removeItem(DISMISSED_KEY);
}

export function enqueueFeedback(entry: QueuedFeedback): void {
  const queue = readJSON<QueuedFeedback[]>(QUEUE_KEY, []);
  // Oversized bundles are dropped explicitly at enqueue time so a single entry
  // can never blow the localStorage quota and silently lose the submission.
  queue.push(feedbackEntryFits(entry) ? entry : withoutBundle(entry));
  // FIFO cap: drop oldest beyond QUEUE_CAP
  while (queue.length > QUEUE_CAP) queue.shift();
  writeJSON(QUEUE_KEY, queue);
}

export function readFeedbackQueue(): QueuedFeedback[] {
  return readJSON<QueuedFeedback[]>(QUEUE_KEY, []);
}

export function clearFeedbackQueue(): void {
  if (typeof localStorage !== 'undefined') localStorage.removeItem(QUEUE_KEY);
}

/** Attempt to flush the queue using the provided transport. Stops on first failure.
 *
 *  Carry-or-explicit-drop: an entry that fits is sent with its full bundle; one
 *  whose bundle pushes it past {@link FEEDBACK_ENTRY_MAX_BYTES} is sent
 *  bundle-free. The submission itself is never failed or partially sent.
 */
export async function flushFeedbackQueue(
  transport: FlushTransport = defaultTransport,
): Promise<{ sent: number; failed: QueuedFeedback[] }> {
  const queue = readFeedbackQueue();
  if (queue.length === 0) return { sent: 0, failed: [] };
  const sent: QueuedFeedback[] = [];
  const failed: QueuedFeedback[] = [];
  for (const entry of queue) {
    const deliverable = feedbackEntryFits(entry) ? entry : withoutBundle(entry);
    const ok = await transport.send(deliverable);
    if (ok) sent.push(entry);
    else failed.push(entry);
    if (!ok) break; // stop on first failure; remaining items are kept
  }
  if (sent.length > 0) {
    const remaining = queue.filter((e) => !sent.includes(e));
    writeJSON(QUEUE_KEY, remaining);
  }
  return { sent: sent.length, failed };
}

let autoFlushTimer: ReturnType<typeof setInterval> | null = null;

/** Start a 30s background flush cycle. Returns the stop function. */
export function startAutoFlush(transport: FlushTransport = defaultTransport): () => void {
  if (autoFlushTimer) return stopAutoFlush;
  autoFlushTimer = setInterval(() => {
    if (typeof navigator !== 'undefined' && !navigator.onLine) return;
    void flushFeedbackQueue(transport);
  }, 30_000);
  return stopAutoFlush;
}

export function stopAutoFlush(): void {
  if (autoFlushTimer) {
    clearInterval(autoFlushTimer);
    autoFlushTimer = null;
  }
}

/** Truncate a user message to the platform limit (helper for dialogs). */
export function truncateMessage(message: string, maxChars: number): string {
  if (message.length <= maxChars) return message;
  return message.slice(0, maxChars);
}

/** Build a BookContext with hard caps (100/80). */
export function buildBookContext(input: {
  bookId: string;
  chapterIndex: number;
  page: number;
  title?: string;
  chapterLabel?: string;
}): BookContext {
  return {
    bookId: input.bookId,
    chapterIndex: input.chapterIndex,
    page: input.page,
    title: (input.title ?? '').slice(0, 100),
    chapterLabel: (input.chapterLabel ?? '').slice(0, 80),
  };
}

export const FEEDBACK_QUEUE_CAP = QUEUE_CAP;
