/**
 * Courtesy + resilience policy for public catalog sources.
 * Lives in the provider layer (never UI) so both platforms share semantics.
 */
import { catalogError, mapHttpStatusToCode, isCatalogError } from './errors';

export const DEFAULT_PAGE_SIZE = 24;
export const MIN_PAGE_SIZE = 20;
export const MAX_PAGE_SIZE = 32;
/** Trailing-edge debounce window for burst searches (spec: 300–400ms). */
export const DEBOUNCE_MS = 350;
/** Minimum gap between Open Library calls (anonymous courtesy limit). */
export const OL_MIN_GAP_MS = 1000;
/** Exactly one delayed retry on 429/5xx — never more without delay. */
export const MAX_DELAYED_RETRIES = 1;
export const RETRY_BASE_DELAY_MS = 800;
/**
 * Hard ceiling for a single catalog attempt (15s reference parity).
 * Single externalized constant: the transport deadline below and the rail-level
 * total bound (`DiscoverRailsDomainState`) both read it, never a duplicated literal.
 */
export const REQUEST_DEADLINE_MS = 15_000;

export function buildUserAgent(platform: 'Desktop' | 'Android'): string {
  return `Nexo/${platform} (contact: TBD)`;
}

export const DESKTOP_USER_AGENT = buildUserAgent('Desktop');

/** Clamp a requested page size into the contractual 20–32 window. */
export function clampPageSize(requested: number): number {
  if (!Number.isFinite(requested)) return DEFAULT_PAGE_SIZE;
  return Math.min(MAX_PAGE_SIZE, Math.max(MIN_PAGE_SIZE, Math.floor(requested)));
}

export function shouldRetryStatus(status: number): boolean {
  return status === 429 || status >= 500;
}

/** Exponential backoff delay for the single delayed retry. */
export function backoffDelayMs(attempt: number): number {
  return RETRY_BASE_DELAY_MS * 2 ** Math.max(0, attempt);
}

/** A caller-deadline composed signal plus the disposer that releases its timer. */
export interface ComposedDeadline {
  /** Aborts when either the caller signal aborts or the deadline elapses. */
  signal: AbortSignal;
  /** Clear the timer and detach the listener once the attempt settles. */
  dispose: () => void;
}

/**
 * Compose a caller signal with a hard deadline so an attempt cannot hang forever.
 * Deliberately a manual `AbortController` + `setTimeout` (not
 * `AbortSignal.timeout`/`AbortSignal.any`) so jsdom + fake timers can drive it.
 */
export function composeDeadline(
  signal: AbortSignal | null | undefined,
  ms: number = REQUEST_DEADLINE_MS,
): ComposedDeadline {
  const controller = new AbortController();
  const abortFromCaller = (): void => controller.abort(signal?.reason);
  if (signal) {
    if (signal.aborted) abortFromCaller();
    else signal.addEventListener('abort', abortFromCaller, { once: true });
  }
  const handle = setTimeout(
    () => controller.abort(new Error(`catalog deadline of ${ms}ms elapsed`)),
    ms,
  );
  return {
    signal: controller.signal,
    dispose: () => {
      clearTimeout(handle);
      signal?.removeEventListener('abort', abortFromCaller);
    },
  };
}

/**
 * Fetch with exactly one delayed retry on 429/5xx, each attempt bounded by
 * `REQUEST_DEADLINE_MS` through `composeDeadline`.
 * Network failures (including deadline expiry and caller aborts) surface as
 * NETWORK_ERROR; HTTP failures as mapped codes.
 */
export async function fetchWithRetry(
  url: string,
  init: RequestInit,
  fetchFn: typeof fetch = fetch,
): Promise<Response> {
  const attempt = async (): Promise<Response> => {
    const deadline = composeDeadline(init.signal, REQUEST_DEADLINE_MS);
    try {
      return await fetchFn(url, { ...init, signal: deadline.signal });
    } finally {
      deadline.dispose();
    }
  };

  let response: Response;
  try {
    response = await attempt();
  } catch {
    throw catalogError('NETWORK_ERROR', 'catalog request failed');
  }
  if (response.ok) return response;
  if (!shouldRetryStatus(response.status)) {
    throw catalogError(mapHttpStatusToCode(response.status), `upstream status ${response.status}`);
  }
  await new Promise((resolve) => setTimeout(resolve, backoffDelayMs(0)));
  try {
    response = await attempt();
  } catch {
    throw catalogError('NETWORK_ERROR', 'catalog retry failed');
  }
  if (!response.ok) {
    throw catalogError(mapHttpStatusToCode(response.status), `upstream status ${response.status}`);
  }
  return response;
}

/** Re-throw unknown failures as NETWORK_ERROR, keep typed errors untouched. */
export function toCatalogError(err: unknown, fallbackDetail: string): never {
  if (isCatalogError(err)) throw err;
  throw catalogError('NETWORK_ERROR', fallbackDetail);
}

/** Enforces a minimum gap between calls (Open Library 1 req/s courtesy). */
export function createRateLimiter(
  minGapMs: number,
  now: () => number = Date.now,
): {
  waitForSlot: () => Promise<void>;
} {
  let lastCall = 0;
  let queue: Promise<void> = Promise.resolve();
  return {
    waitForSlot(): Promise<void> {
      const slot = queue.then(async () => {
        const wait = Math.max(0, lastCall + minGapMs - now());
        if (wait > 0) await new Promise((resolve) => setTimeout(resolve, wait));
        lastCall = now();
      });
      queue = slot.catch(() => undefined);
      return slot;
    },
  };
}

type PendingSearch<T> = {
  query: string;
  page: number;
  resolve: (value: T) => void;
  reject: (err: unknown) => void;
};

/**
 * Trailing-edge debouncer for provider search: rapid calls reset the window
 * and only the latest query issues network I/O; every caller resolves
 * with that latest result.
 */
export function createSearchDebouncer<T>(
  execute: (query: string, page: number) => Promise<T>,
  windowMs: number = DEBOUNCE_MS,
  timer: { set: (fn: () => void, ms: number) => unknown; clear: (h: unknown) => void } = {
    set: (fn, ms) => setTimeout(fn, ms),
    clear: (h) => clearTimeout(h as ReturnType<typeof setTimeout>),
  },
): { search: (query: string, page: number) => Promise<T>; cancel: () => void } {
  let handle: unknown = null;
  let pending: PendingSearch<T>[] = [];
  const fire = (): void => {
    handle = null;
    const batch = pending;
    pending = [];
    const latest = batch[batch.length - 1];
    execute(latest.query, latest.page).then(
      (result) => batch.forEach((p) => p.resolve(result)),
      (err) => batch.forEach((p) => p.reject(err)),
    );
  };
  return {
    search(query: string, page: number): Promise<T> {
      return new Promise<T>((resolve, reject) => {
        pending.push({ query, page, resolve, reject });
        if (handle !== null) timer.clear(handle);
        handle = timer.set(fire, windowMs);
      });
    },
    cancel(): void {
      if (handle !== null) timer.clear(handle);
      handle = null;
      pending = [];
    },
  };
}
