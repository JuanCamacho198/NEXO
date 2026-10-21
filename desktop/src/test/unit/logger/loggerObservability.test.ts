/**
 * Frontend observability gates (SDD `observability-logging`, phase 3.5).
 *
 * Locks the spec scenarios implemented by `Logger.ts`, `TauriSink.ts`, and
 * `ConsoleSink.ts`:
 *  - "Debug dropped in release" / threshold gate — below-threshold events are
 *    dropped before any sink dispatch, so they cost zero IPC.
 *  - "Routine logs stay local until flush" — a routine burst triggers no
 *    per-event IPC; the batch transfers together on flush.
 *  - "Console silent in production" — a production (disabled) ConsoleSink
 *    writes nothing while the ring still retains the event.
 *  - "Overflow evicts oldest first" — the bounded local ring evicts the oldest
 *    record and never exceeds its cap.
 *  - Universal redaction — secrets, DSN values, absolute paths, and sensitive
 *    keys never survive `redactRecord`.
 */
import { describe, expect, it, vi } from 'vitest';
import { invoke } from '@tauri-apps/api/core';
import {
  LOG_RING_CAP,
  levelPasses,
  logger,
  redactRecord,
  type LogRecord,
  type LoggerSink,
} from '$lib/shared/logger';
import { ConsoleSink } from '$lib/shared/logger/ConsoleSink';
import { BatchedTauriSink } from '$lib/shared/logger/TauriSink';
import type { ErrorEvent } from '$lib/shared/events/ErrorEvent';

function makeEvent(overrides: Partial<ErrorEvent> = {}): ErrorEvent {
  return {
    timestamp: '2026-10-07T00:00:00.000Z',
    severity: 'low',
    category: 'runtime',
    code: 'TEST_CODE',
    message: 'synthetic',
    correlationId: 'corr-1',
    source: 'app_shell',
    recoverable: true,
    context: {},
    ...overrides,
  };
}

const gateLog = vi.fn();
const fileLog = vi.fn();
const gateSpy: LoggerSink = { log: gateLog };
const fileSpy: LoggerSink = { log: fileLog };
const productionConsoleSink = new ConsoleSink(false);

// Registered once: the logger singleton has no unregister, and vitest isolates
// this file's module registry from other test files.
logger.registerSink(gateSpy);
logger.registerSink(fileSpy);
logger.registerSink(productionConsoleSink);

describe('levelPasses — threshold ordering', () => {
  it('passes events at or above the threshold and drops lower levels', () => {
    expect(levelPasses('error', 'info')).toBe(true);
    expect(levelPasses('warn', 'info')).toBe(true);
    expect(levelPasses('info', 'info')).toBe(true);
    expect(levelPasses('debug', 'info')).toBe(false);
    expect(levelPasses('info', 'error')).toBe(false);
  });
});

describe('Logger default threshold — debug does not reach sinks by default', () => {
  it('defaults to info in every build, dev included', async () => {
    // The singleton's threshold is mutated by the tests above, so read the
    // module fresh. `info` (not `debug`) is the default in dev too: shipping
    // debug to the sink is what saturated IPC and froze book opens
    // (odd/tasks/log-ipc-hang.md). The debug call sites are untouched —
    // setThreshold('debug') still turns them on for a session.
    vi.resetModules();
    const fresh = await import('$lib/shared/logger/Logger');
    expect(fresh.logger.getThreshold()).toBe('info');
    expect(!fresh.levelPasses('debug', fresh.logger.getThreshold())).toBe(true);
    expect(fresh.levelPasses('info', fresh.logger.getThreshold())).toBe(true);
    expect(fresh.levelPasses('error', fresh.logger.getThreshold())).toBe(true);
  });
});

describe('Logger level gate — below-threshold events cost zero IPC', () => {
  it('drops below-threshold events before any sink dispatch', () => {
    logger.setThreshold('error');
    gateLog.mockClear();
    logger.debug('suppressed debug');
    logger.info('suppressed info');
    logger.warn('suppressed warn');
    expect(gateLog).not.toHaveBeenCalled();

    logger.error('kept error');
    expect(gateLog).toHaveBeenCalledTimes(1);
    logger.setThreshold('debug');
  });
});

describe('Logger local ring — bounded with oldest-evict', () => {
  it('caps the ring at LOG_RING_CAP and evicts the oldest record', () => {
    logger.setThreshold('error');
    logger.clearRing();
    for (let i = 0; i <= LOG_RING_CAP; i += 1) {
      logger.error(`event-${i}`);
    }

    const records = logger.records();
    expect(records).toHaveLength(LOG_RING_CAP);
    expect(records[0]?.message).toBe('event-1');
    expect(records[records.length - 1]?.message).toBe(`event-${LOG_RING_CAP}`);

    logger.clearRing();
    logger.setThreshold('debug');
  });
});

describe('redactRecord — universal redaction before retention/transfer', () => {
  it('scrubs secret assignments, DSN values, absolute paths and sensitive keys', () => {
    const record: LogRecord = {
      level: 'error',
      message:
        'load failed at C:\\Users\\Juan\\library\\book.epub token=abc with https://lookupkey@dsn.ingest',
      context: { highlightText: 'selected book content', apiKey: 'secret-key', attempts: 2 },
      source: 'reader',
      timestamp: '2026-10-07T00:00:00.000Z',
    };

    const redacted = redactRecord(record);

    expect(redacted.message).not.toContain('abc');
    expect(redacted.message).not.toContain('C:\\Users');
    expect(redacted.message).not.toContain('lookupkey@');
    expect(redacted.message).toContain('[REDACTED]');
    expect(redacted.context.highlightText).toBe('[REDACTED]');
    expect(redacted.context.apiKey).toBe('[REDACTED]');
    expect(redacted.context.attempts).toBe(2);
  });
});

describe('ConsoleSink gating — release mute', () => {
  it('writes nothing when disabled (production) and writes when enabled (dev)', () => {
    const logSpy = vi.spyOn(console, 'log').mockImplementation(() => undefined);
    const warnSpy = vi.spyOn(console, 'warn').mockImplementation(() => undefined);
    const errorSpy = vi.spyOn(console, 'error').mockImplementation(() => undefined);
    const debugSpy = vi.spyOn(console, 'debug').mockImplementation(() => undefined);

    new ConsoleSink(false).log(makeEvent({ severity: 'high', message: 'must stay silent' }));
    expect(logSpy).not.toHaveBeenCalled();
    expect(warnSpy).not.toHaveBeenCalled();
    expect(errorSpy).not.toHaveBeenCalled();
    expect(debugSpy).not.toHaveBeenCalled();

    new ConsoleSink(true).log(makeEvent({ severity: 'high', message: 'dev output' }));
    expect(errorSpy).toHaveBeenCalledTimes(1);

    logSpy.mockRestore();
    warnSpy.mockRestore();
    errorSpy.mockRestore();
    debugSpy.mockRestore();
  });

  it('release-mute: no console output while the file-bound sink still receives the event', () => {
    const errorSpy = vi.spyOn(console, 'error').mockImplementation(() => undefined);
    logger.setThreshold('debug');
    logger.clearRing();
    fileLog.mockClear();

    logger.info('release event');

    expect(errorSpy).not.toHaveBeenCalled();
    expect(fileLog).toHaveBeenCalledTimes(1);
    expect(logger.records()).toHaveLength(1);

    errorSpy.mockRestore();
    logger.clearRing();
  });
});

describe('BatchedTauriSink — local retention with batched transfer', () => {
  it('buffers routine events and transfers them in ONE call on flush', async () => {
    const invokeMock = vi.mocked(invoke);
    invokeMock.mockClear();
    const sink = new BatchedTauriSink();

    for (let i = 0; i < 10; i += 1) {
      sink.log(makeEvent({ message: `routine-${i}` }), 'info');
    }
    expect(invokeMock).not.toHaveBeenCalled();

    await sink.flush();

    // One IPC call for the whole batch, not one per event: the per-event shape
    // serialised behind the backend's global logger lock and froze book opens
    // for seconds (odd/tasks/log-ipc-hang.md).
    expect(invokeMock).toHaveBeenCalledTimes(1);
    expect(invokeMock).toHaveBeenCalledWith('logEvents', {
      events: expect.arrayContaining([expect.objectContaining({ message: 'routine-0' })]),
    });
    const [, payload] = invokeMock.mock.calls[0] as [string, { events: unknown[] }];
    expect(payload.events).toHaveLength(10);
  });

  it('flushes on the 5s interval when the event cap is not reached', async () => {
    vi.useFakeTimers();
    try {
      const invokeMock = vi.mocked(invoke);
      invokeMock.mockClear();
      const sink = new BatchedTauriSink();

      for (let i = 0; i < 3; i += 1) {
        sink.log(makeEvent({ message: `tick-${i}` }), 'warn');
      }
      expect(invokeMock).not.toHaveBeenCalled();

      await vi.advanceTimersByTimeAsync(5000);

      expect(invokeMock).toHaveBeenCalledTimes(1);
      expect(invokeMock).toHaveBeenCalledWith('logEvents', expect.anything());
    } finally {
      vi.useRealTimers();
    }
  });

  it('retains the batch for retry when the batched call fails — never drops silently', async () => {
    const invokeMock = vi.mocked(invoke);
    invokeMock.mockClear();
    invokeMock.mockRejectedValueOnce(new Error('ipc down'));
    const sink = new BatchedTauriSink();

    sink.log(makeEvent({ message: 'must-survive' }), 'info');
    await sink.flush();
    expect(invokeMock).toHaveBeenCalledTimes(1);

    // The failed batch is retried on the next flush, not lost.
    invokeMock.mockResolvedValueOnce(undefined);
    await sink.flush();
    expect(invokeMock).toHaveBeenCalledTimes(2);
    const [, retry] = invokeMock.mock.calls[1] as [string, { events: Array<{ message: string }> }];
    expect(retry.events.map((e) => e.message)).toContain('must-survive');
  });

  it('routes ERROR events through reportErrorEvent', async () => {
    const invokeMock = vi.mocked(invoke);
    invokeMock.mockClear();
    const sink = new BatchedTauriSink();

    sink.log(makeEvent({ severity: 'high' }), 'error');
    await sink.flush();

    expect(invokeMock).toHaveBeenCalledWith('reportErrorEvent', expect.anything());
  });
});
