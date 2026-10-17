/**
 * Single-flight, latest-wins scheduling for chapter renders.
 *
 * ## The defect this exists for
 *
 * A burst of navigation (a held arrow, a fast Next click) changes the requested
 * chapter several times before the first render has finished. Every request ran
 * its own backend extraction (`invoke('get_epub_chapter')`), so ten presses
 * queued ten reads behind one Rust process and the per-press cost climbed
 * instead of staying flat. The epoch guard already discarded a stale *result*;
 * it never stopped the stale *work*.
 *
 * ## The contract
 *
 * - **Leading edge runs immediately.** The first request in a burst is not
 *   delayed: a single deliberate press must move exactly one chapter and must
 *   not feel slower than before. This is also why there is no debounce
 *   *before* the first call — a debounce would add latency to the common case.
 * - **Middle requests collapse.** While a render is in flight, a new request
 *   does not start a second one. It overwrites `pending`, so only the *latest*
 *   destination survives.
 * - **Trailing edge renders the last destination once.** When the in-flight
 *   render settles, the coalesced `pending` becomes the next run. Ten requests
 *   become at most two renders (first + final), never ten.
 *
 * The scheduler does not know what a render is: it is handed an
 * already-decomposed "run this index" function, which keeps the epoch logic and
 * the DOM work where they belong.
 */

export type CoalescingRenderer = {
  /** Requests a render of `index`, coalescing redundant requests behind it. */
  request(index: number): void;
  /** Waits for the in-flight render and any coalesced follow-up to settle. */
  idle(): Promise<void>;
  /** The index of the render currently in flight, or null when idle. */
  inflightIndex(): number | null;
  /** Whether a coalesced follow-up is waiting to run. */
  hasPending(): boolean;
};

/**
 * Builds a single-flight scheduler around `run`.
 *
 * `run` is awaited; a rejection is routed to `onError` rather than becoming an
 * unhandled rejection, so a failing render never wedges the scheduler.
 *
 * **Precondition:** `run` must settle. The chain is released when `run`'s
 * promise settles (resolve or reject), which is what makes "at most one backend
 * extraction at a time" hold — but it also means a `run` that never settles
 * (an `await` on an IPC call that never answers) strands the coalesced
 * destination behind it. That is a deliberate trade: the alternative, releasing
 * on a timer, would re-open the door to overlapping extractions, which is the
 * defect being fixed. Callers must therefore keep `run` bounded.
 */
export function createCoalescingRenderer(
  run: (index: number) => Promise<void>,
  onError: (error: unknown) => void,
): CoalescingRenderer {
  let inflightIndex: number | null = null;
  let pending: number | null = null;
  let idleWaiters: Array<() => void> = [];

  function settle(): void {
    inflightIndex = null;
    if (pending !== null) {
      const next = pending;
      pending = null;
      start(next);
      return;
    }
    // Nothing coalesced behind us: the chain is genuinely idle.
    const waiters = idleWaiters;
    idleWaiters = [];
    for (const resolve of waiters) resolve();
  }

  function start(index: number): void {
    inflightIndex = index;
    void run(index)
      .catch(onError)
      .then(() => {
        settle();
      });
  }

  function request(index: number): void {
    if (inflightIndex === null) {
      // Leading edge: no render is running, so this one starts now. The first
      // request of a burst is never swallowed.
      start(index);
      return;
    }
    // A render is in flight: keep only the latest destination.
    pending = index;
  }

  function idle(): Promise<void> {
    if (inflightIndex === null) return Promise.resolve();
    return new Promise<void>((resolve) => {
      idleWaiters.push(resolve);
    });
  }

  return {
    request,
    idle,
    inflightIndex: () => inflightIndex,
    hasPending: () => pending !== null,
  };
}
