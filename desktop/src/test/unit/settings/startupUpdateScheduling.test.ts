/**
 * T6 (#11) — the startup update check must not compete with first paint.
 *
 * The behaviour that matters is ordering, and it is testable because the
 * scheduler is a plain function rather than something buried in `App.svelte`:
 * no timer before the window has loaded and the thread is idle, one run only,
 * and a run that survives a load event that never arrives (#17 depends on it).
 */
import { describe, it, expect, vi, afterEach } from 'vitest';
import { scheduleAfterFirstPaint } from '$lib/features/settings/update/startupScheduling';

const fireLoad = (): void => {
  window.dispatchEvent(new Event('load'));
};

const setReadyState = (value: DocumentReadyState): void => {
  Object.defineProperty(document, 'readyState', { value, configurable: true });
};

afterEach(() => {
  vi.useRealTimers();
  vi.restoreAllMocks();
  setReadyState('complete');
});

describe('scheduleAfterFirstPaint (T6 startup ordering)', () => {
  it('schedules nothing while the window is still loading', () => {
    vi.useFakeTimers();
    setReadyState('loading');
    const task = vi.fn();

    scheduleAfterFirstPaint(task);

    expect(task).not.toHaveBeenCalled();
    vi.advanceTimersByTime(1000);
    expect(task).not.toHaveBeenCalled();
  });

  it('runs only after load and the minimum delay has elapsed', () => {
    vi.useFakeTimers();
    setReadyState('loading');
    const task = vi.fn();

    scheduleAfterFirstPaint(task, { minDelayMs: 1500 });

    fireLoad();
    // Reload trigers the idle probe; without requestIdleCallback in jsdom this
    // is the synchronous path, so the delay timer exists but has not fired.
    vi.advanceTimersByTime(1400);
    expect(task).not.toHaveBeenCalled();
    vi.advanceTimersByTime(100);
    expect(task).toHaveBeenCalledTimes(1);
  });

  it('runs at most once even if load fires twice', () => {
    vi.useFakeTimers();
    setReadyState('loading');
    const task = vi.fn();

    scheduleAfterFirstPaint(task, { minDelayMs: 10 });
    fireLoad();
    fireLoad();
    vi.advanceTimersByTime(100);

    expect(task).toHaveBeenCalledTimes(1);
  });

  it('still runs when the load event never arrives', () => {
    vi.useFakeTimers();
    setReadyState('loading');
    const task = vi.fn();

    scheduleAfterFirstPaint(task, { minDelayMs: 10 });
    // No load event: the 5 s fallback has to take over or #17 loses the check.
    vi.advanceTimersByTime(6000);

    expect(task).toHaveBeenCalledTimes(1);
  });

  it('never runs after cancellation', () => {
    vi.useFakeTimers();
    setReadyState('loading');
    const task = vi.fn();

    const cancel = scheduleAfterFirstPaint(task, { minDelayMs: 10 });
    cancel();
    fireLoad();
    vi.advanceTimersByTime(10000);

    expect(task).not.toHaveBeenCalled();
  });

  it('waits for an idle callback when the platform provides one', () => {
    vi.useFakeTimers();
    setReadyState('complete');
    const task = vi.fn();
    let idle: (() => void) | null = null;
    const requestIdleCallback = vi.fn((cb: IdleRequestCallback) => {
      idle = () => cb({ didTimeout: false, timeRemaining: () => 50 });
      return 1;
    });
    // jsdom has no requestIdleCallback; install one to prove it is preferred.
    Object.defineProperty(window, 'requestIdleCallback', {
      value: requestIdleCallback,
      configurable: true,
      writable: true,
    });

    try {
      scheduleAfterFirstPaint(task, { minDelayMs: 100 });
      vi.advanceTimersByTime(1000);
      expect(task).not.toHaveBeenCalled();
      expect(requestIdleCallback).toHaveBeenCalledTimes(1);

      (idle as unknown as () => void)();
      vi.advanceTimersByTime(100);
      expect(task).toHaveBeenCalledTimes(1);
    } finally {
      delete (window as unknown as Record<string, unknown>)['requestIdleCallback'];
    }
  });
});
