import { readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { afterEach, describe, expect, it, vi } from 'vitest';
import {
  composeDeadline,
  DETAIL_DEADLINE_MS,
  fetchWithRetry,
  REQUEST_DEADLINE_MS,
  RETRY_BASE_DELAY_MS,
  SEARCH_DEADLINE_MS,
} from '$lib/shared/services/catalog/policy';
import { catalogError, isRetryableCatalogCode } from '$lib/shared/services/catalog';

const HERE = dirname(fileURLToPath(import.meta.url));
const POLICY_SOURCE = resolve(HERE, '../../../../lib/shared/services/catalog/policy.ts');

/** Fetch stub that never settles until its composed signal aborts. */
function hangingFetch(): { fetchFn: typeof fetch; signals: AbortSignal[] } {
  const signals: AbortSignal[] = [];
  const fetchFn = ((_input: unknown, init?: RequestInit) => {
    const signal = init?.signal ?? undefined;
    if (signal) signals.push(signal);
    return new Promise<Response>((_resolve, reject) => {
      const fail = (): void => reject(signal?.reason ?? new Error('aborted'));
      // Real fetch rejects immediately on an already-aborted signal.
      if (signal?.aborted) fail();
      else signal?.addEventListener('abort', fail);
    });
  }) as unknown as typeof fetch;
  return { fetchFn, signals };
}

describe('catalog transport — bounded request deadline', () => {
  afterEach(() => {
    vi.useRealTimers();
  });

  it('exposes the deadline as one externalized constant', () => {
    expect(REQUEST_DEADLINE_MS).toBe(15_000);
    const source = readFileSync(POLICY_SOURCE, 'utf8');
    expect(source).toContain('export const REQUEST_DEADLINE_MS = 15_000;');
    // Exactly one 15s literal in the file: the constant itself.
    expect(source.match(/15_000/g)).toHaveLength(1);
  });

  it('externalizes a dedicated budget per call shape', () => {
    expect(SEARCH_DEADLINE_MS).toBe(6_000);
    expect(DETAIL_DEADLINE_MS).toBe(5_000);
    const source = readFileSync(POLICY_SOURCE, 'utf8');
    expect(source).toContain('export const SEARCH_DEADLINE_MS = 6_000;');
    expect(source).toContain('export const DETAIL_DEADLINE_MS = 5_000;');
  });

  it('classifies a fired deadline as UPSTREAM_TIMEOUT, not NETWORK_ERROR', async () => {
    vi.useFakeTimers();
    const { fetchFn, signals } = hangingFetch();
    const assertion = expect(
      fetchWithRetry('https://example.test/hang', {}, fetchFn),
    ).rejects.toMatchObject({ code: 'UPSTREAM_TIMEOUT' });

    await vi.advanceTimersByTimeAsync(REQUEST_DEADLINE_MS - 1);
    expect(signals[0]?.aborted).toBe(false);

    await vi.advanceTimersByTimeAsync(1);
    await assertion;
    expect(signals[0]?.aborted).toBe(true);
  });

  it('honors a per-call deadline budget instead of the 15s default', async () => {
    vi.useFakeTimers();
    const { fetchFn, signals } = hangingFetch();
    const assertion = expect(
      fetchWithRetry('https://example.test/budget', {}, fetchFn, SEARCH_DEADLINE_MS),
    ).rejects.toMatchObject({ code: 'UPSTREAM_TIMEOUT' });

    await vi.advanceTimersByTimeAsync(SEARCH_DEADLINE_MS - 1);
    expect(signals[0]?.aborted).toBe(false);
    await vi.advanceTimersByTimeAsync(1);
    await assertion;
    expect(signals[0]?.aborted).toBe(true);
  });

  it('marks UPSTREAM_TIMEOUT retryable', () => {
    expect(isRetryableCatalogCode('UPSTREAM_TIMEOUT')).toBe(true);
    expect(catalogError('UPSTREAM_TIMEOUT').code).toBe('UPSTREAM_TIMEOUT');
  });

  it('still reports a genuine fetch rejection as NETWORK_ERROR', async () => {
    const fetchFn = (() =>
      Promise.reject(new TypeError('network down'))) as unknown as typeof fetch;
    await expect(fetchWithRetry('https://example.test/reject', {}, fetchFn)).rejects.toMatchObject({
      code: 'NETWORK_ERROR',
    });
  });

  it('composes a caller signal with the deadline', async () => {
    vi.useFakeTimers();
    const caller = new AbortController();
    const { fetchFn, signals } = hangingFetch();
    const assertion = expect(
      fetchWithRetry('https://example.test/caller', { signal: caller.signal }, fetchFn),
    ).rejects.toMatchObject({ code: 'NETWORK_ERROR' });

    await vi.advanceTimersByTimeAsync(RETRY_BASE_DELAY_MS);
    expect(signals[0]?.aborted).toBe(false);
    caller.abort();
    await assertion;
    expect(signals[0]?.aborted).toBe(true);
  });

  it('treats an already-aborted caller signal as an immediate NETWORK_ERROR', async () => {
    const caller = new AbortController();
    caller.abort();
    const { fetchFn } = hangingFetch();
    await expect(
      fetchWithRetry('https://example.test/aborted', { signal: caller.signal }, fetchFn),
    ).rejects.toMatchObject({ code: 'NETWORK_ERROR' });
  });

  it('still honors the single delayed retry, with one deadline per attempt', async () => {
    vi.useFakeTimers();
    let calls = 0;
    const seenSignals: (AbortSignal | null | undefined)[] = [];
    const fetchFn = ((_input: unknown, init?: RequestInit) => {
      calls += 1;
      seenSignals.push(init?.signal);
      return Promise.resolve(new Response('{}', { status: calls === 1 ? 429 : 200 }));
    }) as unknown as typeof fetch;

    const pending = fetchWithRetry('https://example.test/retry', {}, fetchFn);
    await vi.advanceTimersByTimeAsync(RETRY_BASE_DELAY_MS);
    const response = await pending;

    expect(response.ok).toBe(true);
    expect(calls).toBe(2);
    expect(seenSignals[0]).toBeDefined();
    expect(seenSignals[1]).toBeDefined();
    expect(seenSignals[0]).not.toBe(seenSignals[1]);
  });

  it('releases the deadline timer on dispose', async () => {
    vi.useFakeTimers();
    const composed = composeDeadline(undefined, REQUEST_DEADLINE_MS);
    expect(composed.signal.aborted).toBe(false);
    composed.dispose();
    await vi.advanceTimersByTimeAsync(REQUEST_DEADLINE_MS);
    expect(composed.signal.aborted).toBe(false);
    expect(vi.getTimerCount()).toBe(0);
  });
});
