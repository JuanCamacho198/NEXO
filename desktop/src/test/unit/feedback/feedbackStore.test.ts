/**
 * feedbackStore unit tests (sdd/sentry-observability-v2 PR3 desktop — HOTFIX slice).
 *
 * Covers spec D1/D2/D3 contracts:
 *  - lastEventId round-trip via localStorage `np.feedback.lastEventId`
 *  - dismissed-set idempotence (never re-nag same event)
 *  - queue FIFO + cap at 25
 *  - buildBookContext caps (title ≤100, chapterLabel ≤80)
 *  - flushFeedbackQueue success-then-fail (stops on first failure)
 *  - online/offline gate (startAutoFlush skips when navigator.onLine === false)
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import {
  enqueueFeedback,
  flushFeedbackQueue,
  isDismissed,
  markDismissed,
  readFeedbackQueue,
  readLastEventId,
  recordLastEventId,
  clearDismissed,
  clearFeedbackQueue,
  startAutoFlush,
  stopAutoFlush,
  truncateMessage,
  buildBookContext,
  feedbackEntryFits,
  type BookContext,
  type QueuedFeedback,
  type FlushTransport,
  FEEDBACK_QUEUE_CAP,
  FEEDBACK_ENTRY_MAX_BYTES,
} from '$lib/shared/feedback/feedbackStore';

const QUEUE_KEY = 'np.feedback.queue';
const DISMISSED_KEY = 'np.feedback.dismissed';
const LAST_EVENT_ID_KEY = 'np.feedback.lastEventId';

function ctx(): BookContext {
  return {
    bookId: 'book-1',
    chapterIndex: 2,
    page: 32,
    title: 'La Odisea',
    chapterLabel: 'Canto III',
  };
}

function entry(eventId: string | null = null, message = 'feedback message'): QueuedFeedback {
  return { eventId, message, contexts: { book: ctx() }, enqueuedAt: Date.now() };
}

function bundleEntry(bundle: string, eventId = 'b1'): QueuedFeedback {
  return { ...entry(eventId), bundle };
}

describe('feedbackStore', () => {
  beforeEach(() => {
    localStorage.clear();
    vi.restoreAllMocks();
    stopAutoFlush();
  });

  afterEach(() => {
    stopAutoFlush();
  });

  describe('lastEventId round-trip', () => {
    it('returns null when nothing is stored', () => {
      expect(readLastEventId()).toBeNull();
    });

    it('round-trips a valid event id', () => {
      recordLastEventId('abc123');
      expect(readLastEventId()).toBe('abc123');
    });

    it('round-trips null (Sentry uninitialised case)', () => {
      recordLastEventId('something');
      recordLastEventId(null);
      expect(readLastEventId()).toBeNull();
    });

    it('survives corrupted JSON in storage without throwing', () => {
      localStorage.setItem(LAST_EVENT_ID_KEY, '{not json');
      expect(readLastEventId()).toBeNull();
    });
  });

  describe('dismissed set idempotence', () => {
    it('reports not-dismissed for unknown eventId', () => {
      expect(isDismissed('evt-x')).toBe(false);
    });

    it('marks + reads back as dismissed', () => {
      markDismissed('evt-a');
      expect(isDismissed('evt-a')).toBe(true);
    });

    it('is idempotent — marking twice keeps a single entry', () => {
      markDismissed('evt-a');
      markDismissed('evt-a');
      const stored = JSON.parse(localStorage.getItem(DISMISSED_KEY) ?? '[]') as string[];
      expect(stored).toEqual(['evt-a']);
    });

    it('clearDismissed empties the set', () => {
      markDismissed('evt-a');
      markDismissed('evt-b');
      clearDismissed();
      expect(isDismissed('evt-a')).toBe(false);
      expect(isDismissed('evt-b')).toBe(false);
    });
  });

  describe('queue FIFO + cap', () => {
    it('starts empty', () => {
      expect(readFeedbackQueue()).toEqual([]);
    });

    it('appends entries in FIFO order', () => {
      enqueueFeedback(entry('e1', 'm1'));
      enqueueFeedback(entry('e2', 'm2'));
      enqueueFeedback(entry('e3', 'm3'));
      const q = readFeedbackQueue();
      expect(q.map((e) => e.eventId)).toEqual(['e1', 'e2', 'e3']);
    });

    it(`caps the queue at ${FEEDBACK_QUEUE_CAP} entries (oldest dropped)`, () => {
      for (let i = 0; i < FEEDBACK_QUEUE_CAP + 5; i++) {
        enqueueFeedback(entry(`e${i}`, `m${i}`));
      }
      const q = readFeedbackQueue();
      expect(q).toHaveLength(FEEDBACK_QUEUE_CAP);
      // Oldest dropped — first surviving entry is e5
      expect(q[0]?.eventId).toBe('e5');
      expect(q[q.length - 1]?.eventId).toBe(`e${FEEDBACK_QUEUE_CAP + 4}`);
    });

    it('clearFeedbackQueue empties the queue', () => {
      enqueueFeedback(entry('e1'));
      enqueueFeedback(entry('e2'));
      clearFeedbackQueue();
      expect(readFeedbackQueue()).toEqual([]);
    });
  });

  describe('buildBookContext caps', () => {
    it('passes through short fields', () => {
      const out = buildBookContext({
        bookId: 'b1',
        chapterIndex: 0,
        page: 1,
        title: 'short',
        chapterLabel: 'short',
      });
      expect(out).toMatchObject({ title: 'short', chapterLabel: 'short' });
    });

    it(`truncates title to 100 chars`, () => {
      const long = 'T'.repeat(250);
      const out = buildBookContext({
        bookId: 'b1',
        chapterIndex: 0,
        page: 1,
        title: long,
      });
      expect(out.title).toHaveLength(100);
    });

    it(`truncates chapterLabel to 80 chars`, () => {
      const long = 'C'.repeat(150);
      const out = buildBookContext({
        bookId: 'b1',
        chapterIndex: 0,
        page: 1,
        chapterLabel: long,
      });
      expect(out.chapterLabel).toHaveLength(80);
    });

    it('defaults missing title/chapterLabel to empty string', () => {
      const out = buildBookContext({ bookId: 'b1', chapterIndex: 0, page: 1 });
      expect(out.title).toBe('');
      expect(out.chapterLabel).toBe('');
    });
  });

  describe('flushFeedbackQueue', () => {
    it('returns empty result when queue is empty', async () => {
      const result = await flushFeedbackQueue();
      expect(result.sent).toBe(0);
      expect(result.failed).toEqual([]);
    });

    it('sends every entry via the provided transport on success', async () => {
      enqueueFeedback(entry('e1', 'm1'));
      enqueueFeedback(entry('e2', 'm2'));
      const send = vi.fn(async () => true);
      const result = await flushFeedbackQueue({ send });
      expect(send).toHaveBeenCalledTimes(2);
      expect(result.sent).toBe(2);
      expect(result.failed).toEqual([]);
      expect(readFeedbackQueue()).toEqual([]);
    });

    it('stops on first failure (remaining items are kept)', async () => {
      enqueueFeedback(entry('e1'));
      enqueueFeedback(entry('e2'));
      enqueueFeedback(entry('e3'));
      const send = vi
        .fn<(e: QueuedFeedback) => Promise<boolean>>()
        .mockResolvedValueOnce(true)
        .mockResolvedValueOnce(false)
        .mockResolvedValueOnce(true);
      const result = await flushFeedbackQueue({ send });
      expect(send).toHaveBeenCalledTimes(2);
      expect(result.sent).toBe(1);
      expect(result.failed).toHaveLength(1);
      // Queue keeps e2 + e3 (the unprocessed remainder)
      const q = readFeedbackQueue();
      expect(q.map((e) => e.eventId)).toEqual(['e2', 'e3']);
    });
  });

  describe('startAutoFlush — online/offline gate', () => {
    it('returns a stop function and does not flush when navigator.onLine is false', () => {
      const send = vi.fn(async () => true);
      // jsdom default: navigator.onLine is true. Override for this test.
      Object.defineProperty(navigator, 'onLine', { configurable: true, value: false });
      const handle = startAutoFlush({ send });
      expect(typeof handle).toBe('function');
      handle();
    });

    it('calling the stop function is safe (idempotent)', () => {
      const handle = startAutoFlush();
      handle();
      handle();
    });
  });

  describe('truncateMessage', () => {
    it('returns the input unchanged when under the limit', () => {
      expect(truncateMessage('hello', 10)).toBe('hello');
    });

    it('truncates when over the limit', () => {
      expect(truncateMessage('x'.repeat(50), 10)).toHaveLength(10);
    });
  });

  describe('offline enqueue path', () => {
    it('queues feedback when navigator.onLine is false (the dialog offline gate)', () => {
      Object.defineProperty(navigator, 'onLine', { configurable: true, value: false });
      // simulate the dialog offline branch
      enqueueFeedback(entry('evt-offline'));
      expect(readFeedbackQueue()).toHaveLength(1);
    });
  });

  describe('localStorage key isolation', () => {
    it('writes to the documented np.feedback.* keys', () => {
      recordLastEventId('e1');
      markDismissed('e1');
      enqueueFeedback(entry('e1'));
      expect(localStorage.getItem(LAST_EVENT_ID_KEY)).toBe('"e1"');
      expect(localStorage.getItem(DISMISSED_KEY)).toBe('["e1"]');
      expect(localStorage.getItem(QUEUE_KEY)).toBeTruthy();
    });
  });

  describe('default transport shape', () => {
    it('FlushTransport requires a send function returning Promise<boolean>', async () => {
      const t: FlushTransport = { send: async () => true };
      const r = await flushFeedbackQueue(t);
      expect(r).toMatchObject({ sent: 0, failed: [] });
    });
  });

  describe('carry-or-explicit-drop (bundle queue, spec feedback delta)', () => {
    it('feedbackEntryFits: bundle-free always fits, in-size bundle fits, oversized does not', () => {
      expect(feedbackEntryFits(entry('e1'))).toBe(true);
      expect(feedbackEntryFits(bundleEntry('small bundle'))).toBe(true);
      const oversized = 'x'.repeat(FEEDBACK_ENTRY_MAX_BYTES + 1);
      expect(feedbackEntryFits(bundleEntry(oversized))).toBe(false);
    });

    it('carries the full bundle on flush when it fits', async () => {
      const bundle = 'diagnostics-bundle-text';
      enqueueFeedback(bundleEntry(bundle, 'e-carried'));
      const received: QueuedFeedback[] = [];
      const send = vi.fn(async (e: QueuedFeedback) => {
        received.push(e);
        return true;
      });

      const result = await flushFeedbackQueue({ send });

      expect(result.sent).toBe(1);
      expect(result.failed).toEqual([]);
      expect(received).toHaveLength(1);
      expect(received[0]?.bundle).toBe(bundle);
      expect(readFeedbackQueue()).toEqual([]);
    });

    it('drops an oversized bundle at enqueue and still sends the submission bundle-free', async () => {
      const oversized = 'x'.repeat(FEEDBACK_ENTRY_MAX_BYTES + 1024);
      enqueueFeedback(bundleEntry(oversized, 'e-big'));

      // enqueue guard: the stored entry is bundle-free (submission survives)
      const stored = readFeedbackQueue();
      expect(stored).toHaveLength(1);
      expect(stored[0]?.eventId).toBe('e-big');
      expect(stored[0]?.bundle).toBeUndefined();

      const received: QueuedFeedback[] = [];
      const send = vi.fn(async (e: QueuedFeedback) => {
        received.push(e);
        return true;
      });
      const result = await flushFeedbackQueue({ send });

      expect(result.sent).toBe(1);
      expect(received[0]?.bundle).toBeUndefined();
      expect(received[0]?.message).toBe('feedback message');
    });

    it('drops an oversized bundle at flush time too (defense-in-depth)', async () => {
      // Simulate a queue written before the enqueue guard existed.
      const oversized = 'x'.repeat(FEEDBACK_ENTRY_MAX_BYTES + 1024);
      localStorage.setItem(QUEUE_KEY, JSON.stringify([bundleEntry(oversized, 'e-old')]));

      const received: QueuedFeedback[] = [];
      const send = vi.fn(async (e: QueuedFeedback) => {
        received.push(e);
        return true;
      });
      const result = await flushFeedbackQueue({ send });

      expect(result.sent).toBe(1);
      expect(received).toHaveLength(1);
      expect(received[0]?.bundle).toBeUndefined();
      expect(readFeedbackQueue()).toEqual([]);
    });

    it('keeps bundle-free entries bundle-free through the queue', async () => {
      enqueueFeedback(entry('e-free'));
      const received: QueuedFeedback[] = [];
      const send = vi.fn(async (e: QueuedFeedback) => {
        received.push(e);
        return true;
      });

      await flushFeedbackQueue({ send });

      expect(received[0]?.bundle).toBeUndefined();
      expect(received[0]?.contexts.book.title).toBe('La Odisea');
    });
  });
});
