import {
  DEFAULT_PAGE_SIZE,
  GOOGLE_BOOKS_MAX_START_INDEX,
  isCatalogError,
  isRetryableCatalogCode,
  liveCatalogProvider,
  REQUEST_DEADLINE_MS,
  type CatalogBook,
  type CatalogErrorCode,
  type CatalogProvider,
  type CatalogSource,
} from '$lib/shared/services/catalog';
import type { MessageKey } from '$lib/shared/i18n/messages.en';
import {
  boundRailPage,
  buildRailSpecs,
  DISCOVER_RAIL_FETCH_LIMIT,
  loadRail,
  railSourceOf,
  withDeadline,
  type DiscoverRailSource,
  type DiscoverRailSpec,
} from './railPlan';
import { logRailHidden, logRailLoaded, type RailLogContext } from './railLog';

/** Rail diagnostics identity: the kind, the upstream, and the selector term. */
function railLogContext(spec: DiscoverRailSpec): RailLogContext {
  switch (spec.kind) {
    case 'topic':
      return { rail: 'topic', source: 'gutendex', term: spec.term };
    case 'popular':
      return { rail: 'popular', source: 'gutendex', term: 'sort=popular' };
    case 'opds':
      return { rail: 'standard-ebooks', source: 'standard-ebooks', term: null };
  }
}

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
 * shelf's own term, browsed against the source that served the shelf.
 */
export interface DiscoverBrowseScope {
  kind: 'term';
  term: string;
  titleKey: MessageKey;
  /** Source the scope pages through; defaults to the Gutendex topic source. */
  sourceId: CatalogSource;
  /**
   * True when this scope must page the Gutendex `?topic=` selector instead of a
   * free-text search. A topic shelf's "Ver todo" has to keep asking the same
   * question its rail asked: the same words through `search` answer 6 books
   * where `?topic=` answers 3 530.
   */
  topic?: boolean;
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
 * Sources whose EVERY rail settled as `Error`: the only condition that earns a
 * grouped notice. A single failing rail keeps its own inline error and retry,
 * so it deliberately never appears here — repeating the same sentence in a
 * page-level box would say nothing the rail did not already say.
 *
 * Rails still `Loading` are not `Error`, so a group in flight can never be
 * reported as failed. Order follows the plan, so the notice never reshuffles.
 */
export function failedRailGroupsOf(
  specs: readonly DiscoverRailSpec[],
  rails: readonly DiscoverRailState[],
): DiscoverRailSource[] {
  const groups = specs.map(railSourceOf);
  const seen: DiscoverRailSource[] = [];
  for (const source of groups) if (!seen.includes(source)) seen.push(source);
  return seen.filter((source) => {
    const indexes = groups
      .map((group, index) => (group === source ? index : -1))
      .filter((i) => i >= 0);
    return indexes.length > 0 && indexes.every((index) => rails[index]?.kind === 'Error');
  });
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
  /**
   * True once the depth cap truncated the open scope while the provider still
   * offered more: the UI says only the first titles are shown (never invented
   * results). False on natural exhaustion.
   */
  scopeAtDepthCap = $state(false);
  /**
   * Sources whose every rail settled as `Error`. Empty while any rail of the
   * source is still `Loading`, so the grouped notice can never appear over a
   * shelf that may still land.
   */
  failedGroups = $state<DiscoverRailSource[]>([]);
  /**
   * Groups whose notice the reader dismissed, for this session only, so a
   * permanently rate-limited source does not become permanent noise. Cleared
   * when the group starts failing again on a fresh refresh.
   */
  dismissedGroups = $state<DiscoverRailSource[]>([]);

  private readonly provider: CatalogProvider;
  private readonly now: () => Date;
  private readonly timeoutMs: number;
  private scopePage = 0;
  /**
   * A scope page already in flight. The scroller fires per scroll event, so
   * without this guard one threshold crossing issues one request per event
   * and burns quota on duplicate pages.
   */
  private scopeLoading = false;
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

  /** Groups whose single notice is currently shown (failed and not dismissed). */
  get visibleFailedGroups(): DiscoverRailSource[] {
    return this.failedGroups.filter((group) => !this.dismissedGroups.includes(group));
  }

  /** Hide one grouped notice for the rest of the session; no I/O, no refetch. */
  dismissRailGroup(group: DiscoverRailSource): void {
    if (!this.dismissedGroups.includes(group)) this.dismissedGroups.push(group);
  }

  /**
   * Re-resolve ONLY the rails of `group` that are currently in `Error`; every
   * other rail — including a sibling of the same source that is working — keeps
   * its state and is never refetched. The two topic rails share one Gutendex
   * quota, so refetching a rail that already answered would burn a request for
   * nothing. A manual retry re-arms the group's notice, since the reader asked
   * to see the outcome again.
   */
  async retryRailGroup(group: DiscoverRailSource): Promise<void> {
    const failed = this.specs
      .map((spec, index) => (railSourceOf(spec) === group ? index : -1))
      .filter((index) => index >= 0 && this.rails[index]?.kind === 'Error');
    // Nothing failed in this group: a retry has no rail to re-resolve and must
    // not touch the sibling that is already serving books.
    if (failed.length === 0) return;
    this.dismissedGroups = this.dismissedGroups.filter((dismissed) => dismissed !== group);
    const generation = this.generation;
    for (const index of failed) {
      // Supersede any pending automatic retry so a manual attempt never leaves
      // a duplicate timer behind for the same rail.
      this.clearAutoRetry(index);
      this.rails[index] = { kind: 'Loading' };
    }
    this.publishFailedGroups();
    // Every failed rail goes in flight together (the `map` starts them), and each
    // is then awaited in turn: no `Promise.all` gate, so the group notice is
    // published only once all of them settled, without serializing them.
    const pending = failed.map((index) => this.loadOne(this.specs[index]!));
    const settled: DiscoverRailState[] = [];
    for (const rail of pending) settled.push(await rail);
    if (generation !== this.generation) return;
    failed.forEach((index, position) => {
      this.rails[index] = settled[position]!;
    });
    this.recomputeConnectivity();
    this.publishFailedGroups();
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
    // A refresh starts from a clean slate: no group is failed while its rails
    // are Loading, so the notice cannot survive a reload it no longer describes.
    this.failedGroups = [];
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
   * Open a rail-scoped browse view: a term scope pages through the source that
   * served its rail (Gutendex for the topic shelves). A failure lands in
   * `scopeError` instead of throwing at the caller.
   */
  async openScope(scope: DiscoverBrowseScope): Promise<void> {
    this.scope = scope;
    this.scopeBooks = [];
    this.scopePage = 0;
    this.scopeExhausted = false;
    this.scopeAtDepthCap = false;
    this.scopeLoading = false;
    this.scopeError = null;
    await this.loadScopeNextPage();
  }

  /**
   * Append the scope's next page through the scope's own source. The scope
   * stops once a page reports no next page, once the next page would start past
   * `GOOGLE_BOOKS_MAX_START_INDEX` (no request is issued — the prod
   * `startIndex=216` is unreachable), and concurrent calls join the page in
   * flight instead of duplicating it. When the cap truncates a scope the
   * provider would still extend, `scopeAtDepthCap` tells the UI to say so.
   */
  async loadScopeNextPage(): Promise<void> {
    const scope = this.scope;
    if (!scope || this.scopeExhausted || this.scopeLoading) return;
    if (this.scopePage * DEFAULT_PAGE_SIZE > GOOGLE_BOOKS_MAX_START_INDEX) {
      this.scopeExhausted = true;
      this.scopeAtDepthCap = true;
      return;
    }
    this.scopeLoading = true;
    this.scopeError = null;
    try {
      // A topic scope keeps asking the `?topic=` selector the rail used; every
      // other scope pages its own source's free-text search.
      const request = scope.topic
        ? this.provider.topic(scope.term, this.scopePage + 1)
        : this.provider.searchSource(scope.sourceId, scope.term, this.scopePage + 1);
      const page = await withDeadline(request, this.timeoutMs);
      if (this.scope !== scope) return;
      const seen = new Set(this.scopeBooks.map((book) => book.id));
      for (const book of page.results) {
        if (!seen.has(book.id)) {
          seen.add(book.id);
          this.scopeBooks.push(book);
        }
      }
      this.scopePage += 1;
      if (page.nexoPage === null) {
        this.scopeExhausted = true;
      } else if (this.scopePage * DEFAULT_PAGE_SIZE > GOOGLE_BOOKS_MAX_START_INDEX) {
        this.scopeExhausted = true;
        this.scopeAtDepthCap = true;
      }
    } catch (err) {
      this.scopeError = catalogCodeOf(err);
    } finally {
      this.scopeLoading = false;
    }
  }

  /** Leave the scoped view and drop its books. */
  closeScope(): void {
    this.scope = null;
    this.scopeBooks = [];
    this.scopePage = 0;
    this.scopeExhausted = false;
    this.scopeAtDepthCap = false;
    this.scopeLoading = false;
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
    // Same reason for the grouped notice: it is a projection of the whole
    // group, so it is republished on every settle rather than on the last one.
    this.publishFailedGroups();
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
   * Republish the failed-group set from the current rail states. Derived from
   * the published rails, never tracked incrementally, so a rail that recovers
   * clears its notice with no second source of truth.
   */
  private publishFailedGroups(): void {
    this.failedGroups = failedRailGroupsOf(this.specs, this.rails);
    // A dismissal only outlives the failure it hid. Once nothing is failing the
    // slate is clean, so the same group failing again later speaks up again.
    if (this.failedGroups.length === 0) this.dismissedGroups = [];
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
      const bounded = boundRailPage(page, DISCOVER_RAIL_FETCH_LIMIT);
      // Coverless books are KEPT: a rail card degrades to a text card (title and
      // author on a theme surface) instead of rendering an empty frame. Only a
      // genuinely empty page hides the shelf, so an all-coverless rail still has
      // something to show. Search is untouched by any of this.
      if (bounded.results.length === 0) {
        // Say WHY the shelf disappears: an empty page is not an exception, and
        // without this line a hidden rail is indistinguishable from a failure.
        logRailHidden(
          railLogContext(spec),
          `empty page (upstream returned ${page.results.length})`,
        );
        return { kind: 'Hidden' };
      }
      logRailLoaded(railLogContext(spec), bounded.results.length);
      return { kind: 'Loaded', books: bounded.results, totalCount: page.totalCount };
    } catch (err) {
      const code = catalogCodeOf(err);
      logRailHidden(railLogContext(spec), `exception ${code}: ${String(err)}`);
      return { kind: 'Error', code, offline: isOfflineCatalogCode(code) };
    }
  }
}
