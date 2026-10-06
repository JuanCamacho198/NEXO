import { metricsStore } from '$lib/shared/logger/MetricsStore';
import type { MetricEvent } from '$lib/shared/logger/metricTypes';

export function createDebugIpcStats(): {
  readonly ipcCalls: MetricEvent[];
  readonly ipcSummary: {
    totalCalls: number;
    avgDuration: number;
    maxDuration: number;
    successRate: number;
  };
  readonly recentCalls: MetricEvent[];
  readonly ipcCommands: Array<{
    feature: string;
    count: number;
    avgDuration: number;
    minDuration: number;
    p50Duration: number;
    maxDuration: number;
    successRate: number;
  }>;
  readonly refreshTick: number;
} {
  let refreshTick = $state(0);

  $effect(() => {
    const interval = setInterval(() => {
      refreshTick++;
    }, 2000);
    return () => clearInterval(interval);
  });

  const ipcCalls = $derived.by(() => {
    void refreshTick;
    return metricsStore.getByName('ipc_call');
  });

  const ipcSummary = $derived.by(() => {
    const totalCalls = ipcCalls.length;
    const withDuration = ipcCalls.filter((c) => c.durationMs != null);
    const successCount = ipcCalls.filter((c) => c.success).length;
    const totalDuration = withDuration.reduce((s, c) => s + (c.durationMs ?? 0), 0);
    const avgDuration =
      withDuration.length > 0 ? Math.round(totalDuration / withDuration.length) : 0;
    const maxDuration =
      withDuration.length > 0
        ? Math.round(Math.max(...withDuration.map((c) => c.durationMs ?? 0)))
        : 0;
    const successRate = totalCalls > 0 ? Math.round((successCount / totalCalls) * 100) : 100;
    return { totalCalls, avgDuration, maxDuration, successRate };
  });

  const recentCalls = $derived(ipcCalls.slice(-25).reverse());

  const ipcCommands = $derived.by(() => {
    type Accum = { feature: string; count: number; durations: number[] };
    const grouped = new Map<string, Accum>();
    for (const call of ipcCalls) {
      const key = call.feature ?? 'unknown';
      let e = grouped.get(key);
      if (!e) {
        e = { feature: key, count: 0, durations: [] };
        grouped.set(key, e);
      }
      e.count++;
      if (call.durationMs != null) e.durations.push(call.durationMs);
    }
    type Row = {
      feature: string;
      count: number;
      avgDuration: number;
      minDuration: number;
      p50Duration: number;
      maxDuration: number;
      successRate: number;
    };
    const result: Row[] = [];
    for (const entry of grouped.values()) {
      const sorted = [...entry.durations].sort((a, b) => a - b);
      const minDuration = sorted.length > 0 ? Math.round(sorted[0]!) : 0;
      const maxDuration = sorted.length > 0 ? Math.round(sorted[sorted.length - 1]!) : 0;
      const avgDuration =
        sorted.length > 0 ? Math.round(sorted.reduce((a, b) => a + b, 0) / sorted.length) : 0;
      const p50Duration =
        sorted.length > 0 ? Math.round(sorted[Math.floor(sorted.length / 2)]!) : 0;
      const successCount = ipcCalls.filter((c) => c.feature === entry.feature && c.success).length;
      const successRate = entry.count > 0 ? Math.round((successCount / entry.count) * 100) : 100;
      result.push({
        feature: entry.feature,
        count: entry.count,
        avgDuration,
        minDuration,
        p50Duration,
        maxDuration,
        successRate,
      });
    }
    result.sort((a, b) => b.count - a.count);
    return result;
  });

  return {
    get ipcCalls() {
      return ipcCalls;
    },
    get ipcSummary() {
      return ipcSummary;
    },
    get recentCalls() {
      return recentCalls;
    },
    get ipcCommands() {
      return ipcCommands;
    },
    get refreshTick() {
      return refreshTick;
    },
  };
}

export function computeIpcSummary(calls: Array<{ durationMs?: number | null; success: boolean }>): {
  totalCalls: number;
  avgDuration: number;
  maxDuration: number;
  successRate: number;
} {
  const totalCalls = calls.length;
  const withDuration = calls.filter((c) => c.durationMs != null);
  const successCount = calls.filter((c) => c.success).length;
  const totalDuration = withDuration.reduce((s, c) => s + (c.durationMs ?? 0), 0);
  const avgDuration = withDuration.length > 0 ? Math.round(totalDuration / withDuration.length) : 0;
  const maxDuration =
    withDuration.length > 0
      ? Math.round(Math.max(...withDuration.map((c) => c.durationMs ?? 0)))
      : 0;
  const successRate = totalCalls > 0 ? Math.round((successCount / totalCalls) * 100) : 100;
  return { totalCalls, avgDuration, maxDuration, successRate };
}

export function computeP50(durations: number[]): number {
  if (durations.length === 0) return 0;
  const sorted = [...durations].sort((a, b) => a - b);
  return Math.round(sorted[Math.floor(sorted.length / 2)]!);
}

export function groupIpcCommands(
  calls: Array<{ feature?: string | null; durationMs?: number | null; success: boolean }>,
): Array<{
  feature: string;
  count: number;
  avgDuration: number;
  minDuration: number;
  p50Duration: number;
  maxDuration: number;
  successRate: number;
}> {
  const grouped = new Map<string, { feature: string; count: number; durations: number[] }>();
  for (const call of calls) {
    const key = call.feature ?? 'unknown';
    let e = grouped.get(key);
    if (!e) {
      e = { feature: key, count: 0, durations: [] };
      grouped.set(key, e);
    }
    e.count++;
    if (call.durationMs != null) e.durations.push(call.durationMs);
  }
  const result: Array<{
    feature: string;
    count: number;
    avgDuration: number;
    minDuration: number;
    p50Duration: number;
    maxDuration: number;
    successRate: number;
  }> = [];
  for (const entry of grouped.values()) {
    const sorted = [...entry.durations].sort((a, b) => a - b);
    const minDuration = sorted.length > 0 ? Math.round(sorted[0]!) : 0;
    const maxDuration = sorted.length > 0 ? Math.round(sorted[sorted.length - 1]!) : 0;
    const avgDuration =
      sorted.length > 0 ? Math.round(sorted.reduce((a, b) => a + b, 0) / sorted.length) : 0;
    const p50Duration = sorted.length > 0 ? Math.round(sorted[Math.floor(sorted.length / 2)]!) : 0;
    const successCount = calls.filter(
      (c) => (c.feature ?? 'unknown') === entry.feature && c.success,
    ).length;
    const successRate = entry.count > 0 ? Math.round((successCount / entry.count) * 100) : 100;
    result.push({
      feature: entry.feature,
      count: entry.count,
      avgDuration,
      minDuration,
      p50Duration,
      maxDuration,
      successRate,
    });
  }
  result.sort((a, b) => b.count - a.count);
  return result;
}

export type DebugIpcStatsState = ReturnType<typeof createDebugIpcStats>;
