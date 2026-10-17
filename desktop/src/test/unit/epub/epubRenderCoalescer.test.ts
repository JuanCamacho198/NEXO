import { describe, expect, it, vi } from 'vitest';
import { createCoalescingRenderer } from '$lib/features/reader/viewer-epub/epubRenderCoalescer';

/** A `run` that resolves only when the returned `release` is called. */
function deferredRun(): {
  run: (index: number) => Promise<void>;
  started: number[];
  release: () => void;
} {
  const started: number[] = [];
  let resolveCurrent: (() => void) | null = null;
  const run = async (index: number): Promise<void> => {
    started.push(index);
    await new Promise<void>((resolve) => {
      resolveCurrent = resolve;
    });
  };
  return {
    run,
    started,
    release: () => {
      const resolve = resolveCurrent;
      resolveCurrent = null;
      resolve?.();
    },
  };
}

/** Lets the microtask queue drain so the scheduler can advance. */
const flush = (): Promise<void> => new Promise((resolve) => setTimeout(resolve, 0));

describe('createCoalescingRenderer', () => {
  it('runs the first request immediately (leading edge, one press = one render)', async () => {
    const { run, started, release } = deferredRun();
    const scheduler = createCoalescingRenderer(run, vi.fn());

    scheduler.request(3);
    await flush();

    // The first request runs without waiting for any debounce window.
    expect(started).toEqual([3]);

    release();
    await scheduler.idle();
  });

  it('collapses a burst to the first and the last destination', async () => {
    const { run, started, release } = deferredRun();
    const scheduler = createCoalescingRenderer(run, vi.fn());

    scheduler.request(1);
    await flush();
    expect(started).toEqual([1]);

    // A burst arrives while 1 is in flight. Only the last one survives.
    scheduler.request(2);
    scheduler.request(3);
    scheduler.request(4);
    expect(scheduler.hasPending()).toBe(true);

    release(); // finish 1 -> drain picks the coalesced 4
    await flush();
    expect(started).toEqual([1, 4]);

    release();
    await scheduler.idle();

    // Ten requests would have been ten runs without coalescing; here it is two.
    expect(started).toHaveLength(2);
  });

  it('reports the in-flight index and clears it once idle', async () => {
    const { run, release } = deferredRun();
    const scheduler = createCoalescingRenderer(run, vi.fn());

    expect(scheduler.inflightIndex()).toBeNull();
    scheduler.request(7);
    await flush();
    expect(scheduler.inflightIndex()).toBe(7);

    release();
    await scheduler.idle();
    expect(scheduler.inflightIndex()).toBeNull();
    expect(scheduler.hasPending()).toBe(false);
  });

  it('routes a failing render to onError and keeps scheduling', async () => {
    const onError = vi.fn();
    let calls = 0;
    const run = async (): Promise<void> => {
      calls += 1;
      if (calls === 1) throw new Error('boom');
    };
    const scheduler = createCoalescingRenderer(run, onError);

    scheduler.request(1);
    await scheduler.idle();

    expect(onError).toHaveBeenCalledTimes(1);
    expect(onError.mock.calls[0]?.[0]).toBeInstanceOf(Error);

    // A failure must not wedge the scheduler: the next request still runs.
    scheduler.request(2);
    await scheduler.idle();
    expect(calls).toBe(2);
  });

  it('idle resolves immediately when nothing is scheduled', async () => {
    const scheduler = createCoalescingRenderer(async () => {}, vi.fn());
    await expect(scheduler.idle()).resolves.toBeUndefined();
  });
});
