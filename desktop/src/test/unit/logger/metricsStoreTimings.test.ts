/**
 * L3 performance-timings gates (SDD `observability-logging`, tasks 6.1).
 *
 * Locks the aggregate contract implemented by `summarizeTimings`:
 *  - p50/p95 are reported per catalog operation, derived from bucketed
 *    durations only.
 *  - An exact `durationMs` is never consumed, so it can never surface in the
 *    summary. Streaming it through `JSON.stringify` is a stand-in for any
 *    external egress.
 *  - No trace/span payload exists: an entry carries only operation, p50, p95.
 *  - Operation names are recycled from the shared metric vocabulary — no
 *    parallel naming scheme.
 */
import { beforeEach, describe, expect, it } from 'vitest';
import { metricsStore, summarizeTimings } from '$lib/shared/logger/MetricsStore';
import { METRIC_NAMES, type MetricName, type MetricEvent } from '$lib/shared/logger/metricTypes';

type Recorded = Omit<MetricEvent, 'id' | 'sessionId' | 'timestamp'>;

function record(overrides: Partial<Recorded> & { name: MetricName }): void {
  metricsStore.record({ count: 1, success: true, ...overrides });
}

describe('summarizeTimings', () => {
  beforeEach(() => {
    metricsStore.clear();
  });

  it('reports p50/p95 per operation from bucketed durations', () => {
    record({ name: 'ipc_call', bucketedDurationMs: 1000 });
    record({ name: 'ipc_call', bucketedDurationMs: 100 });
    record({ name: 'ipc_call', bucketedDurationMs: 500 });
    record({ name: 'ipc_call', bucketedDurationMs: 250 });

    expect(summarizeTimings()).toEqual([{ operation: 'ipc_call', p50Ms: 250, p95Ms: 1000 }]);
  });

  it('never consumes an exact durationMs and never egresses one', () => {
    record({ name: 'reader_open', durationMs: 1237, bucketedDurationMs: 2000 });
    // No bucketed duration → not a timing sample at all.
    record({ name: 'book_import', durationMs: 777 });

    const summary = summarizeTimings();
    expect(summary).toEqual([{ operation: 'reader_open', p50Ms: 2000, p95Ms: 2000 }]);

    const egressed = JSON.stringify(summary);
    expect(egressed).not.toContain('1237');
    expect(egressed).not.toContain('777');
  });

  it('carries only operation + p50 + p95 — no trace/span fields', () => {
    record({ name: 'sync_flush', bucketedDurationMs: 500 });

    const [timing] = summarizeTimings();
    expect(Object.keys(timing!).sort()).toEqual(['operation', 'p50Ms', 'p95Ms']);
  });

  it('omits operations with no bucketed sample', () => {
    record({ name: 'reader_ttfp_web' });
    record({ name: 'reader_ttfp_native', count: 3 });

    expect(summarizeTimings()).toEqual([]);
  });

  it('reuses operation names from the shared metric vocabulary', () => {
    record({ name: 'ipc_call', bucketedDurationMs: 100 });
    record({ name: 'sync_flush', bucketedDurationMs: 250 });

    for (const timing of summarizeTimings()) {
      expect(Object.values(METRIC_NAMES)).toContain(timing.operation);
    }
  });

  it('orders operations deterministically by name', () => {
    record({ name: 'sync_flush', bucketedDurationMs: 250 });
    record({ name: 'ipc_call', bucketedDurationMs: 100 });

    expect(summarizeTimings().map((timing) => timing.operation)).toEqual([
      'ipc_call',
      'sync_flush',
    ]);
  });
});
