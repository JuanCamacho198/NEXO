import type { MetricEvent, MetricName } from './metricTypes';

const MAX_IN_MEMORY_METRICS = 500;

class MetricsStoreImpl {
  private sessionId: string;
  private buffer: MetricEvent[] = [];
  private listeners: Array<(event: MetricEvent) => void> = [];

  constructor() {
    this.sessionId = crypto.randomUUID();
  }

  getSessionId(): string {
    return this.sessionId;
  }

  /** Subscribe to every recorded metric (used by the SentryMetricsSink). */
  onRecord(listener: (event: MetricEvent) => void): () => void {
    this.listeners.push(listener);
    return () => {
      this.listeners = this.listeners.filter((l) => l !== listener);
    };
  }

  record(event: Omit<MetricEvent, 'id' | 'sessionId' | 'timestamp'>): MetricEvent {
    const metric: MetricEvent = {
      id: crypto.randomUUID(),
      sessionId: this.sessionId,
      timestamp: new Date().toISOString(),
      ...event,
    };

    this.buffer.push(metric);

    if (this.buffer.length > MAX_IN_MEMORY_METRICS) {
      this.buffer.shift();
    }

    for (const listener of this.listeners) {
      try {
        listener(metric);
      } catch {
        // Listener failures must never break metric recording.
      }
    }

    return metric;
  }

  recordSuccess(name: MetricName, durationMs?: number, feature?: string): MetricEvent {
    return this.record({
      name,
      durationMs,
      feature,
      count: 1,
      success: true,
    });
  }

  recordFailure(
    name: MetricName,
    errorCode: string,
    durationMs?: number,
    feature?: string,
  ): MetricEvent {
    return this.record({
      name,
      durationMs,
      feature,
      count: 1,
      success: false,
      errorCode,
    });
  }

  increment(name: MetricName, feature?: string): MetricEvent {
    return this.record({
      name,
      feature,
      count: 1,
      success: true,
    });
  }

  getAll(): MetricEvent[] {
    return [...this.buffer];
  }

  getByName(name: MetricName): MetricEvent[] {
    return this.buffer.filter((m) => m.name === name);
  }

  getBySession(sessionId: string): MetricEvent[] {
    return this.buffer.filter((m) => m.sessionId === sessionId);
  }

  flush(): MetricEvent[] {
    const events = [...this.buffer];
    this.buffer = [];
    return events;
  }

  clear(): void {
    this.buffer = [];
  }
}

export const metricsStore = new MetricsStoreImpl();

export const recordMetric = (
  name: MetricName,
  options?: {
    durationMs?: number;
    feature?: string;
    success?: boolean;
    errorCode?: string;
    bucketedDurationMs?: number;
    tags?: Record<string, string>;
  },
): MetricEvent => {
  if (options?.success === false) {
    return metricsStore.recordFailure(
      name,
      options.errorCode ?? 'UNKNOWN',
      options.durationMs,
      options.feature,
    );
  }
  return metricsStore.recordSuccess(name, options?.durationMs, options?.feature);
};

/**
 * Aggregate timing for one catalog operation. `operation` is a member of the
 * shared metric vocabulary (`metricTypes.ts`) — no parallel naming scheme.
 */
export interface OperationTiming {
  operation: MetricName;
  p50Ms: number;
  p95Ms: number;
}

/** Nearest-rank quantile over an ascending-sorted sample. */
function quantile(sortedAsc: readonly number[], q: number): number {
  const index = Math.min(sortedAsc.length - 1, Math.max(0, Math.ceil(q * sortedAsc.length) - 1));
  return sortedAsc[index]!;
}

/**
 * p50/p95 per catalog operation across the local ring. Samples are the
 * bucketed durations only (`bucketedDurationMs`), never the exact `durationMs`
 * — so the summary is safe to egress and can never surface a per-request
 * duration. There is no trace/span payload: an entry carries only operation,
 * p50, and p95. Operations with no bucketed sample are omitted.
 */
export function summarizeTimings(): OperationTiming[] {
  const byOperation = new Map<MetricName, number[]>();
  for (const metric of metricsStore.getAll()) {
    const duration = metric.bucketedDurationMs;
    if (typeof duration !== 'number') continue;
    const samples = byOperation.get(metric.name);
    if (samples) {
      samples.push(duration);
    } else {
      byOperation.set(metric.name, [duration]);
    }
  }

  const summary: OperationTiming[] = [];
  for (const [operation, durations] of byOperation) {
    const sorted = [...durations].sort((a, b) => a - b);
    summary.push({
      operation,
      p50Ms: quantile(sorted, 0.5),
      p95Ms: quantile(sorted, 0.95),
    });
  }

  summary.sort((a, b) => a.operation.localeCompare(b.operation));
  return summary;
}
