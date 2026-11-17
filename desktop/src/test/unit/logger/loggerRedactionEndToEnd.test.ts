/**
 * T14 — redaction proven on the REAL logging path (audit #18).
 *
 * `loggerObservability.test.ts` locks `redactRecord` on a hand-built record.
 * That proves the pure function, not the boundary: a sink, a formatter or a
 * future call site could hand the unredacted text to the ring or to egress and
 * the fixture test would still pass.
 *
 * This file feeds a log line that looks like production output — an OAuth
 * access token in a query string, a bearer header, an API key assignment, an
 * absolute Windows path — through `logger.error(...)` and asserts on what the
 * sinks and the retained ring actually receive. A sink registered here stands
 * in for both the local JSONL writer and Sentry, because `Logger` broadcasts
 * the SAME redacted event to every sink after redaction.
 */
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { logger, type LoggerSink } from '$lib/shared/logger';
import type { ErrorEvent } from '$lib/shared/events/ErrorEvent';

const SECRET = 'eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJ1c2VyLTQyIn0.c2lnbmF0dXJlLXZhbHVlLWhlcmU';
const API_KEY = 'sk-live-9f8e7d6c5b4a';
const USER_PATH = 'C:\\Users\\Juan\\Documents\\library\\private.epub';

const REAL_LOOKING_LINE =
  `token exchanged for ${SECRET} api_key=${API_KEY} ` +
  `authorization: Bearer ${SECRET} saved ${USER_PATH} ` +
  'callback http://127.0.0.1:1420/oauth?access_token=' +
  SECRET;

function collectingSink(): { sink: LoggerSink; events: ErrorEvent[] } {
  const events: ErrorEvent[] = [];
  return {
    sink: {
      log: (event: ErrorEvent) => {
        events.push(event);
      },
    },
    events,
  };
}

beforeEach(() => {
  logger.clearRing();
  logger.setThreshold('info');
});

describe('Logger redaction boundary — real log line, real broadcast', () => {
  it('never lets a seeded token, API key or user path reach a sink', () => {
    const { sink, events } = collectingSink();
    logger.registerSink(sink);

    logger.error(REAL_LOOKING_LINE, {
      // Context a reader error realistically carries: book identity plus the
      // token that just failed to refresh.
      bookId: 'book-1',
      accessToken: SECRET,
      nested: { refresh_token: SECRET, attempts: 3 },
    });

    expect(events).toHaveLength(1);
    const event = events[0] as ErrorEvent;
    const serialised = JSON.stringify(event);

    expect(serialised).not.toContain(SECRET);
    expect(serialised).not.toContain(API_KEY);
    expect(serialised).not.toContain('C:\\\\Users');
    expect(serialised).toContain('[REDACTED]');
    // Non-sensitive context survives, or the log stops being useful.
    expect(event.context.bookId).toBe('book-1');
    expect((event.context.nested as Record<string, unknown>).attempts).toBe(3);
  });

  it('retains the same redacted view in the ring the diagnostics bundle ships', () => {
    const { sink } = collectingSink();
    logger.registerSink(sink);

    logger.info(REAL_LOOKING_LINE, { apiKey: API_KEY });
    logger.warn(`refresh failed for ${SECRET}`);

    const records = logger.records();
    expect(records).toHaveLength(2);
    const serialised = JSON.stringify(records);

    expect(serialised).not.toContain(SECRET);
    expect(serialised).not.toContain(API_KEY);
    expect(serialised).not.toContain('C:\\\\Users');
    expect(serialised).toContain('[REDACTED]');
  });

  it('leaves an already-redacted line idempotent (no double markers)', () => {
    const { sink } = collectingSink();
    logger.registerSink(sink);

    logger.error('token: [REDACTED]');

    const records = logger.records();
    expect(records[0]?.message).toBe('token: [REDACTED]');
  });
});
