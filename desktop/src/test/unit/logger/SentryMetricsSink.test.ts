/**
 * Unit tests for {@link $lib/shared/logger/SentryMetricsSink}.
 *
 * Mapping per event type, bucketing edge values, zero SDK calls with
 * DSN-less/disabled settings, `error_code` truncation, and the per-emit
 * `enabled` check (mid-session disable stops egress immediately).
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

const distribution = vi.fn();
const gauge = vi.fn();
const count = vi.fn();

// Mirrors the real @sentry/core metrics surface: count, gauge, distribution.
// There is deliberately no `increment` — the SDK has no such method, and a mock
// that invented it once let a call to it pass CI while the real API rejected it.
vi.mock('@sentry/browser', () => ({
  metrics: {
    distribution: (...args: unknown[]) => distribution(...args),
    gauge: (...args: unknown[]) => gauge(...args),
    count: (...args: unknown[]) => count(...args),
  },
}));

import { SentryMetricsSink } from '$lib/shared/logger/SentryMetricsSink';
import type { MetricEvent } from '$lib/shared/logger/metricTypes';
import type { SentrySettings } from '$lib/shared/logger/sentryConfig';

const FULL_SETTINGS: SentrySettings = {
  dsn: 'https://valid@x.ingest.sentry.io/1',
  enabled: true,
  tracesSampleRate: 0.1,
  release: 'nexo-desktop@0.1.0+abc1234',
  environment: 'production',
  sendDefaultPii: false,
  replaysSessionSampleRate: 0,
  replaysOnErrorSampleRate: 0.1,
  maskAllText: true,
  maskAllInputs: true,
};

const makeEvent = (overrides: Partial<MetricEvent> = {}): MetricEvent => ({
  id: 'e1',
  sessionId: 's1',
  timestamp: new Date().toISOString(),
  name: 'ipc_call',
  count: 1,
  success: true,
  ...overrides,
});

describe('SentryMetricsSink', () => {
  beforeEach(() => {
    distribution.mockReset();
    gauge.mockReset();
    count.mockReset();
  });

  afterEach(() => {
    vi.restoreAllMocks();
  });

  it('maps duration events to a distribution with tags', () => {
    const sink = new SentryMetricsSink(FULL_SETTINGS);
    sink.handle(
      makeEvent({
        bucketedDurationMs: 2000,
        tags: { platform: 'desktop', source: 'reader', format: 'epub', engine: 'epubjs' },
      }),
    );
    expect(distribution).toHaveBeenCalledTimes(1);
    const [name, value, opts] = distribution.mock.calls[0];
    expect(name).toBe('ipc_call');
    expect(value).toBe(2000);
    expect(opts.unit).toBe('millisecond');
    expect(opts.attributes).toMatchObject({ platform: 'desktop', source: 'reader' });
  });

  it('maps count-only events to a counter', () => {
    const sink = new SentryMetricsSink(FULL_SETTINGS);
    sink.handle(makeEvent({ name: 'import_start', bucketedDurationMs: undefined }));
    expect(distribution).not.toHaveBeenCalled();
    expect(count).toHaveBeenCalledTimes(1);
    expect(count.mock.calls[0][0]).toBe('import_start');
  });

  it('adds an _error counter with truncated error_code on failure', () => {
    const sink = new SentryMetricsSink(FULL_SETTINGS);
    sink.handle(
      makeEvent({
        bucketedDurationMs: 1000,
        success: false,
        errorCode: 'IMPORT_FILE_TOO_LARGE_12345',
      }),
    );
    expect(count).toHaveBeenCalledTimes(1);
    const [name, , opts] = count.mock.calls[0];
    expect(name).toBe('ipc_call_error');
    expect(opts.attributes['error_code']).toBe('IMPORT_F');
    expect(opts.attributes['error_code']).not.toContain('TOO_LARGE');
  });

  it('emits zero SDK calls when the DSN is empty', () => {
    const sink = new SentryMetricsSink({ ...FULL_SETTINGS, dsn: '' });
    sink.handle(makeEvent({ bucketedDurationMs: 1000 }));
    expect(distribution).not.toHaveBeenCalled();
    expect(count).not.toHaveBeenCalled();
  });

  it('emits zero SDK calls when disabled at registration', () => {
    const sink = new SentryMetricsSink({ ...FULL_SETTINGS, enabled: false });
    sink.handle(makeEvent({ bucketedDurationMs: 1000 }));
    expect(distribution).not.toHaveBeenCalled();
    expect(count).not.toHaveBeenCalled();
  });

  it('stops egress immediately when disabled mid-session (per-emit check)', () => {
    const settings: SentrySettings = { ...FULL_SETTINGS };
    const sink = new SentryMetricsSink(settings);

    sink.handle(makeEvent({ bucketedDurationMs: 1000 }));
    expect(distribution).toHaveBeenCalledTimes(1);

    // Simulate the privacy-notice disable: flip the same settings object.
    settings.enabled = false;
    sink.handle(makeEvent({ bucketedDurationMs: 2000 }));
    expect(distribution).toHaveBeenCalledTimes(1);
  });
});
