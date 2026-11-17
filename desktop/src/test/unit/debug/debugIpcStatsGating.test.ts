/**
 * T6 (#11) — the debug IPC sampler must not run when debug mode is off.
 *
 * `createDebugIpcStats` starts a 2 s `setInterval` inside a `$effect`, so it
 * only works inside a component scope and the tests mount the harness for that
 * scope. The decision is taken inside the hook, so no caller can start the
 * timer by mistake: leaving `enabled` off means no timer is ever created.
 */
import { render } from '@testing-library/svelte';
import { describe, it, expect, vi, afterEach } from 'vitest';
import Harness from './DebugIpcStatsHarness.svelte';
import type { DebugIpcStatsState } from '$lib/shared/debug/useDebugIpcStats.svelte';

const mountStats = (shouldRun: boolean): DebugIpcStatsState => {
  let stats: DebugIpcStatsState | null = null;
  render(Harness, {
    props: { shouldRun, onReady: (s: unknown) => (stats = s as DebugIpcStatsState) },
  });
  if (!stats) throw new Error('hook was not created');
  return stats;
};

afterEach(() => {
  vi.useRealTimers();
  vi.restoreAllMocks();
});

describe('createDebugIpcStats interval gating (T6)', () => {
  it('never creates a timer while disabled', () => {
    vi.useFakeTimers();
    const spy = vi.spyOn(globalThis, 'setInterval');

    const stats = mountStats(false);

    expect(spy).not.toHaveBeenCalled();
    expect(stats.refreshTick).toBe(0);
  });

  it('creates one timer while enabled and refreshes every 2 s', () => {
    vi.useFakeTimers();
    const spy = vi.spyOn(globalThis, 'setInterval');

    const stats = mountStats(true);

    expect(spy).toHaveBeenCalledTimes(1);
    expect(spy).toHaveBeenCalledWith(expect.any(Function), 2000);
    expect(stats.refreshTick).toBe(0);
    vi.advanceTimersByTime(2000);
    expect(stats.refreshTick).toBe(1);
  });
});
