import {
  BUILTIN_GOOGLEBOOKS,
  isCatalogError,
  isRetryableCatalogCode,
  liveCatalogProvider,
  REQUEST_DEADLINE_MS,
  type CatalogBook,
  type CatalogErrorCode,
  type CatalogProvider,
} from '$lib/shared/services/catalog';
import type { MessageKey } from '$lib/shared/i18n/messages.en';
import {
  buildRailSpecs,
  DISCOVER_RAIL_FETCH_LIMIT,
  loadRail,
  matchesAuthorQuery,
  withDeadline,
  type DiscoverRailSpec,
} from './railPlan';

/**
 * Per-rail machine: `Hidden` renders nothing (empty or unserved), `Loaded`
 * carries the page, and `Error` keeps a stable code plus a retry affordance.
 * A failure in one rail never mutates another rail.
 */
export type DiscoverRailState =
  | { kind: 'Hidden' }
  | { kind: 'Loading' }
  | { kind: 'Loaded'; books: CatalogBook[]; totalCount: number }
  | { kind: 'Error'; code: CatalogErrorCode; offline: boolean };

/**
 * Rail-scoped browse target opened from a rail header ("Ver todo"): the
 * shelf's author term, browsed against Google Books.
 */
export interface DiscoverBrowseScope {
  kind: 'term';
  term: string;
  titleKey: MessageKey;
}

/**
 * Automatic retry backoff for a failed rail: attempt 1 at 2s, attempt 2 at 5s,
 * attempt 3 at 15s, then stop permanently. The budget resets on a successful
 * load and on a manual retry.
 */
export const AUTO_RETRY_DELAYS_MS: readonly number[] = [2_000, 5_000, 15_000];

export interface DiscoverRailsDeps {
  /** Defaults to the live composite catalog; tests inject a recording fake. */
  provider?: CatalogProvider;
  /** Injected clock, so rotation determinism is pinned instead of mocked. */
  now?: () => Date;
  /**
   * Total bound for one rail attempt. Defaults to the shared
   * `REQUEST_DEADLINE_MS`; tests pin a smaller value with fake timers.
   */
  timeoutMs?: number;
}

/**
 * Connectivity failures that must flip the hero pill to offline. `RATE_LIMITED`
 * is deliberately NOT here: a 429 means the catalog is throttling, not that the
 * user is offline.
 */
export function isOfflineCatalogCode(code: CatalogErrorCode): boolean {
  return code === 'NETWORK_ERROR';
}

/** Unknown throwables redact to UPSTREAM_ERROR; typed catalog errors keep their code. */
export function catalogCodeOf(err: unknown): CatalogErrorCode {
  return isCatalogError(err) ? err.code : 'UPSTREAM_ERROR';
}

/**
 * Owns the 3-rail set and everything a rail needs: the index-stable plan, the
 * per-rail machine, isolated retry, remount dedup, and the rail-scoped browse
 * state. There is deliberately no screen-level rail status.
 */
export class DiscoverRailsDomainState {
  /** Index-stable rail plan; rebuilt per refresh so the day rolls forward. */
  specs = $state<DiscoverRailSpec[]>([]);
  /** Parallel to `specs`: index `i` always belongs to `specs[i]`. */
  rails = $state<DiscoverRailState[]>([]);
  /** False once any rail settles with a connectivity failure. */
  isOnline = $state(true);
  /** Non-null while a rail-scoped browse view is open. */
  scope = $state<DiscoverBrowseScope | null>(null);
  /** Books of the open scope, deduplicated across pages. */
  scopeBooks = $state<CatalogBook[]>([]);
  /** True once a refresh settled; remounts reuse it instead of refetching. */
  settled = $state(false);
  /** Stable code of the last failed scoped-browse page, or null. */
  scopeError = $state<CatalogErrorCode | null>(null);
  /** True once the open scope has no further page to offer. */
  scopeExhausted = $state(false);

  private readonly provider: CatalogProvider;
  private readonly now: () => Date;
  private readonly timeoutMs: number;
  private scopePage = 0;
  /** Guards a stale refresh/retry from overwriting a newer publish. */
  private generation = 0;
  /**
   * Automatic retries consumed per rail index. Bounded by
   * `AUTO_RETRY_DELAYS_MS.length`, reset on `Loaded` and on a manual retry.
   */
  private retryAttempts: number[] = [];
  /** Pending automatic-retry timers, keyed by rail index (never more than one each). */
  private readonly retryTimers = new Map<number, ReturnType<typeof setTimeout>>();
  /** True once `dispose()` ran: no new timers, no further listener work. */
  private disposed = false;
  /**
   * The rail load currently in flight, if any. Every mount joins this promise,
   * so a remount (or any repeat call on the load path) during `Loading` never
   * issues a second round of rail requests.
   */
  private inFlight: Promise<void> | null = null;

  constructor(deps: DiscoverRailsDeps = {}) {
    this.provider = deps.provider ?? liveCatalogProvider;
    this.now = deps.now ?? (() => new Date());
    this.timeoutMs = deps.timeoutMs ?? REQUEST_DEADLINE_MS;
    this.specs = [...buildRailSpecs(this.now())];
    this.rails = this.specs.map(() => ({ kind: 'Hidden' }) as DiscoverRailState);
    this.retryAttempts = this.specs.map(() => 0);
    if (typeof window !== 'undefined') window.addEventListener('online', this.onOnline);
  }

  /**
   * Release every pending automatic-retry timer and detach the connectivity
   * listener, so a torn-down instance cannot fire late or duplicate. The
   * app-lifetime singleton never disposes; tests and future teardown paths do.
   */
  dispose(): void {
    this.disposed = true;
    this.clearAllAutoRetries();
    if (typeof window !== 'undefined') window.removeEventListener('online', this.onOnline);
  }

  /** Automatic retry attempts consumed by the rail at `index` (diagnostics/tests). */
  autoRetryCount(index: number): number {
    return this.retryAttempts[index] ?? 0;
  }

  /**
   * Mount hook. Remount dedup (spec: "Remount does not refetch resolved rails"):
   * a settled rail set is reused, and a load already in flight is joined, so the
   * load path short-circuits without a new provider request either way.
   */
  async ensureLoaded(): Promise<void> {
    if (this.settled) return;
    if (this.inFlight) return this.inFlight;
    await this.refreshRails();
  }

  /**
   * Rebuild the rail plan and publish each rail the moment it settles: a fast
   * rail renders while a slow one is still `Loading`, and positions stay
   * index-stable. Each rail is isolated — a throw or the deadline becomes that
   * rail's `Error` and empty results collapse to `Hidden`.
   *
   * An explicit refresh always resolves a fresh plan once the previous load has
   * finished; a refresh requested while one is already running joins it instead
   * of duplicating every rail request.
   */
  async refreshRails(): Promise<void> {
    if (this.inFlight) return this.inFlight;
    const run = this.runRefresh();
    this.inFlight = run;
    try {
      await run;
    } finally {
      if (this.inFlight === run) this.inFlight = null;
    }
  }

  private async runRefresh(): Promise<void> {
    this.specs = [...buildRailSpecs(this.now())];
    this.rails = this.specs.map(() => ({ kind: 'Loading' }) as DiscoverRailState);
    this.retryAttempts = this.specs.map(() => 0);
    this.clearAllAutoRetries();
    this.isOnline = true;
    this.settled = false;
    const generation = (this.generation += 1);
    // Every rail is in flight immediately; awaiting them one by one lets
    // `refreshRails` settle the whole set without gating any publish on the
    // slowest rail (no all-settled publish).
    const pending = this.specs.map((spec, index) => this.loadInto(index, spec, generation));
    for (const rail of pending) await rail;
    if (generation !== this.generation) return;
    this.settled = true;
  }

  /** Re-resolve ONLY the failing rail; every other rail keeps its books and state. */
  async retryRail(index: number): Promise<void> {
    // A manual retry cancels the pending automatic timer and refreshes the budget.
    this.clearAutoRetry(index);
    this.retryAttempts[index] = 0;
    await this.resolveRail(index);
  }

  /** Re-resolve a rail currently in `Error`; a non-`Error` rail is never touched. */
  private async resolveRail(index: number): Promise<void> {
    const spec = this.specs[index];
    const current = this.rails[index];
    if (!spec || !current || current.kind !== 'Error') return;
    // Any resolution supersedes a pending automatic retry for this rail, so a
    // manual retry or an `online` event can never leave a duplicate timer.
    this.clearAutoRetry(index);
    const generation = this.generation;
    this.rails[index] = { kind: 'Loading' };
    const settled = await this.loadOne(spec);
    if (generation !== this.generation) return;
    this.rails[index] = settled;
    this.afterSettle(index, settled);
  }

  /**
   * Open a rail-scoped browse view: term scopes page through the Google Books
   * source search. A failure lands in `scopeError` instead of throwing at the
   * caller.
   */
  async openScope(scope: DiscoverBrowseScope): Promise<void> {
    this.scope = scope;
    this.scopeBooks = [];
    this.scopePage = 0;
    this.scopeExhausted = false;
    this.scopeError = null;
    await this.loadScopeNextPage();
  }

  /**
   * Append the scope's next page through the Google Books source. The scope
   * stops once a page reports no next page.
   */
  async loadScopeNextPage(): Promise<void> {
    const scope = this.scope;
    if (!scope) return;
    this.scopeError = null;
    try {
      if (this.scopeExhausted) return;
      const page = await withDeadline(
        this.provider.searchSource(BUILTIN_GOOGLEBOOKS, scope.term, this.scopePage + 1),
        this.timeoutMs,
      );
      if (this.scope !== scope) return;
      const seen = new Set(this.scopeBooks.map((book) => book.id));
      for (const book of page.results) {
        if (!seen.has(book.id)) {
          seen.add(book.id);
          this.scopeBooks.push(book);
        }
      }
      this.scopePage += 1;
      this.scopeExhausted = page.nexoPage === null;
    } catch (err) {
      this.scopeError = catalogCodeOf(err);
    }
  }

  /** Leave the scoped view and drop its books. */
  closeScope(): void {
    this.scope = null;
    this.scopeBooks = [];
    this.scopePage = 0;
    this.scopeExhausted = false;
    this.scopeError = null;
  }

  /** Resolve one rail and publish it in place; a stale generation never writes. */
  private async loadInto(index: number, spec: DiscoverRailSpec, generation: number): Promise<void> {
    const settled = await this.loadOne(spec);
    if (generation !== this.generation) return;
    this.rails[index] = settled;
    this.afterSettle(index, settled);
  }

  /**
   * Post-publish bookkeeping: connectivity projection, the auto-retry budget
   * reset on success, and the bounded auto-retry schedule for retryable
   * failures. Non-retryable failures stop permanently for that rail.
   */
  private afterSettle(index: number, settled: DiscoverRailState): void {
    if (this.disposed) return;
    // The pill is a projection of the whole rail set, not of whichever rail
    // settled last: recompute it on EVERY settle (the array already carries
    // `settled`), so an offline rail cannot be masked by a later Loaded rail
    // and a retry that decays to a non-offline error can clear it again.
    this.recomputeConnectivity();
    if (settled.kind === 'Loaded') {
      this.retryAttempts[index] = 0;
      this.clearAutoRetry(index);
      return;
    }
    if (settled.kind === 'Error' && isRetryableCatalogCode(settled.code)) {
      this.scheduleAutoRetry(index);
    }
  }

  /**
   * Offline iff at least one rail is currently `Error` with `offline === true`.
   * Order-independent, so the pill always matches the rails the user sees.
   */
  private recomputeConnectivity(): void {
    this.isOnline = !this.rails.some((rail) => rail.kind === 'Error' && rail.offline);
  }

  /** Schedule the next bounded automatic retry, or stop once the budget is spent. */
  private scheduleAutoRetry(index: number): void {
    if (this.disposed) return;
    const attempt = this.retryAttempts[index] ?? 0;
    const delay = AUTO_RETRY_DELAYS_MS[attempt];
    if (delay === undefined) return;
    // A rail owns at most one pending timer: drop any previous handle before
    // installing the new one so the map can never orphan a live timer.
    this.clearAutoRetry(index);
    this.retryAttempts[index] = attempt + 1;
    const handle = setTimeout(() => {
      this.retryTimers.delete(index);
      if (this.disposed) return;
      void this.resolveRail(index);
    }, delay);
    this.retryTimers.set(index, handle);
  }

  private clearAutoRetry(index: number): void {
    const handle = this.retryTimers.get(index);
    if (handle === undefined) return;
    clearTimeout(handle);
    this.retryTimers.delete(index);
  }

  private clearAllAutoRetries(): void {
    for (const handle of this.retryTimers.values()) clearTimeout(handle);
    this.retryTimers.clear();
  }

  /**
   * A browser `online` event re-triggers ONLY rails currently in `Error` — never
   * `Loaded`, never `Loading` — and clears the pill, since it is direct evidence
   * that connectivity came back. Descheduled rails get one fresh attempt; the
   * automatic backoff budget is not reset by incidental events.
   */
  private readonly onOnline = (): void => {
    if (this.disposed) return;
    this.isOnline = true;
    this.rails.forEach((rail, index) => {
      if (rail.kind === 'Error') void this.resolveRail(index);
    });
  };

  private async loadOne(spec: DiscoverRailSpec): Promise<DiscoverRailState> {
    try {
      // TOTAL rail bound: one attempt — including its single delayed retry — can
      // never leave the rail `Loading`, even for a provider that ignores signals.
      const page = await withDeadline(
        loadRail(this.provider, spec, DISCOVER_RAIL_FETCH_LIMIT),
        this.timeoutMs,
      );
      // Free-text rail search ranks criticism and biographies above the works,
      // so the shelf is the authored subset, bounded to the fetch limit. An
      // all-foreign page hides the rail instead of rendering a mislabelled row.
      const books = page.results
        .filter((book) => matchesAuthorQuery(book, spec.term))
        .slice(0, DISCOVER_RAIL_FETCH_LIMIT);
      if (books.length === 0) return { kind: 'Hidden' };
      return { kind: 'Loaded', books, totalCount: page.totalCount };
    } catch (err) {
      const code = catalogCodeOf(err);
      return { kind: 'Error', code, offline: isOfflineCatalogCode(code) };
    }
  }
}
