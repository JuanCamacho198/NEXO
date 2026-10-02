/**
 * SyncOutboxService — timer-based processor for the SQLite sync outbox.
 *
 * Lifecycle:
 *   start() → flush immediately, then every 30s
 *   stop()  → clear interval
 *
 * On each flush:
 *   gate()   → no live session? skip the ENTIRE flush (SR-1.1): rows keep
 *              their retry state, no request fires, no markFailed, no prune
 *   breaker  → auth-class failure armed the pause window? skip the ENTIRE
 *              flush (SR-4.2): rows keep retry state, no request fires
 *   listReady() → for each row (FIFO):
 *     call handler(entityType, entityId, payloadJson)
 *     on success → delete row
 *     on failure → surface typed auth errors to the banner, then markFailed
 *                  (exponential backoff); auth-class failures (PostgrestError
 *                  400/401, SyncError AUTH_REQUIRED/AUTH_EXPIRED) ALSO arm the
 *                  circuit breaker: pause = min(300, 60·2^(streak−1)) seconds
 *                  (D4, SR-2.1). Rows are never dropped.
 *
 * The breaker resets on a fully-successful flush and via resetAuthBreaker()
 * (wired to SIGNED_IN / TOKEN_REFRESHED — fresh tokens mean the pause no
 * longer applies, D4).
 *
 * The session gate is injected (default: hot `hasLiveSession()` + one
 * `getSession()` re-check — D1 "stale without event"). The handler is injected
 * so SupabaseProgressSync or any future sync target can register without
 * coupling this service to Supabase.
 */
import { SyncOutboxDao, isAuthClassError } from './SyncOutboxDao';
import { recheckLiveSession } from '$lib/services/supabase';
import { reportAuthError } from '$lib/shared/stores/syncAlert.svelte';
import {
  recordCatalogSyncFailure,
  recordCatalogSyncSuccess,
} from '$lib/shared/stores/catalogSyncStatus.svelte';
import { toSyncError } from '$lib/shared/recovery/desktopRecoveryImport';
import { captureBreadcrumb } from '$lib/shared/logger/BreadcrumbsStore';
import { BREADCRUMB_LABELS } from '$lib/shared/logger/breadcrumbTypes';
import { metricsStore } from '$lib/shared/logger/MetricsStore';
import { METRIC_NAMES } from '$lib/shared/logger/metricTypes';
import { bucketDepth, bucketDurationMs } from '$lib/shared/logger/metricBuckets';

/** Max breaker pause in seconds (D4): pause = min(300, 60·2^(streak−1)). */
const BREAKER_MAX_PAUSE_SECONDS = 300;
const BREAKER_BASE_PAUSE_SECONDS = 60;

export type OutboxHandler = (
  entityType: string,
  entityId: string | null,
  operation: 'UPSERT' | 'DELETE',
  payloadJson: string,
) => Promise<void>;

/**
 * Session gate: true while a live session can be verified. Default re-checks
 * via `getSession()` once for silent session drops (DA-1 "stale without
 * event"); tests inject a deterministic gate.
 */
export type SessionGate = () => Promise<boolean>;

const defaultSessionGate: SessionGate = async () => recheckLiveSession();

export class SyncOutboxService {
  constructor(
    private readonly dao: SyncOutboxDao = new SyncOutboxDao(),
    private readonly gate: SessionGate = defaultSessionGate,
  ) {}
  private intervalId: ReturnType<typeof setInterval> | null = null;
  private handler: OutboxHandler | null = null;
  private flushing = false;
  /** D7: last emitted outbox-depth bucket (emit only on bucket change). */
  private lastEmittedDepthBucket: number | null = null;
  /** Auth-class circuit breaker (D4): consecutive auth failures back off. */
  private authFailureStreak = 0;
  /** Epoch ms until which the flush is paused; null when not armed. */
  private breakerPausedUntil: number | null = null;

  /**
   * True while the auth circuit breaker pause window is active. The flush is
   * skipped entirely while armed — rows keep their retry state (SR-4.2).
   */
  isBreakerPaused(): boolean {
    return this.breakerPausedUntil !== null && Date.now() < this.breakerPausedUntil;
  }

  /**
   * Pause duration in ms for the current streak: min(300, 60·2^(streak−1))s (D4).
   */
  private pauseMsForStreak(streak: number): number {
    const seconds = Math.min(
      BREAKER_MAX_PAUSE_SECONDS,
      BREAKER_BASE_PAUSE_SECONDS * 2 ** (streak - 1),
    );
    return seconds * 1000;
  }

  /**
   * Arm (or extend) the breaker after an auth-class failure. Called with the
   * already-incremented streak; pause is monotonic per consecutive failure and
   * capped at 300s.
   */
  private armBreaker(): void {
    this.authFailureStreak += 1;
    this.breakerPausedUntil = Date.now() + this.pauseMsForStreak(this.authFailureStreak);
  }

  /**
   * Reset the breaker. Wired to SIGNED_IN / TOKEN_REFRESHED (fresh tokens mean
   * the auth problem is gone — D4) and called after a fully-successful flush.
   */
  resetAuthBreaker(): void {
    this.authFailureStreak = 0;
    this.breakerPausedUntil = null;
  }

  /**
   * Register the handler that processes each outbox row.
   * Call before start().
   */
  setHandler(handler: OutboxHandler): void {
    this.handler = handler;
  }

  /**
   * Start the timer-based processor.
   * Flushes immediately, then every 30 seconds.
   */
  start(): void {
    if (this.intervalId !== null) return;
    void this.flush();
    this.intervalId = setInterval(() => void this.flush(), 30_000);
  }

  /**
   * Stop the processor.
   */
  stop(): void {
    if (this.intervalId !== null) {
      clearInterval(this.intervalId);
      this.intervalId = null;
    }
  }

  /**
   * Flush all pending outbox items.
   * Public so it can be called manually (e.g., on online event).
   */
  async flush(): Promise<void> {
    if (this.flushing || !this.handler) return;
    this.flushing = true;

    // D6 - sync flush duration: timestamp taken after the gate/breaker
    // short-circuits so skipped flushes emit nothing.
    const flushStart = performance.now();
    let gatePassed = false;

    try {
      // SR-1.1: no live session → skip the entire flush. Rows keep their retry
      // state; no request fires; no markFailed; no prune.
      if (!(await this.gate())) return;

      // SR-4.2: auth circuit breaker armed → skip the entire flush. Rows keep
      // their retry state; no request fires; no markFailed. The pause window
      // (60–300s backoff) absorbs RLS-400/401 storms without a hot retry loop.
      if (this.isBreakerPaused()) return;

      gatePassed = true;

      const items = await this.dao.listReady();
      let hadAnyFailure = false;

      // D7 - outbox depth gauge: bucketed, emitted only on bucket change.
      const depthBucket = bucketDepth(items.length);
      if (depthBucket !== this.lastEmittedDepthBucket) {
        this.lastEmittedDepthBucket = depthBucket;
        metricsStore.record({
          name: METRIC_NAMES.OUTBOX_DEPTH,
          durationMs: depthBucket,
          bucketedDurationMs: depthBucket,
          count: 1,
          success: true,
          tags: { source: 'sync', platform: 'desktop' },
        });
      }

      if (items.length > 0) {
        // Journey crumb: sync attempted with FIFO queue depth (ids/enums only).
        captureBreadcrumb('action', BREADCRUMB_LABELS.SYNC_TRIGGER, {
          queueDepth: items.length,
        });
      }

      for (const item of items) {
        const isBook = item.entityType === 'BOOK';
        try {
          await this.handler(item.entityType, item.entityId, item.operation, item.payloadJson);
          await this.dao.delete(item.id);
          if (isBook && item.entityId) recordCatalogSyncSuccess(item.entityId);
        } catch (err) {
          hadAnyFailure = true;
          // BOOK rows feed the background catalog status line (aggregated, not
          // a per-cycle toast); the retry/backoff/breaker behaviour below is
          // unchanged.
          if (isBook && item.entityId) {
            recordCatalogSyncFailure(item.entityId, toSyncError(err, item.entityId).code);
          }
          captureBreadcrumb('action', BREADCRUMB_LABELS.SYNC_FAIL, {
            entityType: item.entityType,
            queueDepth: items.length,
          });
          // SR-3: typed AUTH_REQUIRED/AUTH_EXPIRED must surface to the banner,
          // never be console.error-only. Non-auth failures are left untouched.
          reportAuthError(err);
          const msg = err instanceof Error ? err.message : String(err);
          await this.dao.markFailed(item.id, msg);

          // D4/SR-2.1: auth-class failures (RLS-400/401, AUTH_REQUIRED/EXPIRED)
          // arm the breaker. Rows are NEVER dropped — the failing row keeps its
          // retry state via markFailed, and the pause is delay-only (R2). Once
          // the breaker arms, stop the loop: every remaining row would fail the
          // same way (dead JWT) and each call is another PostgREST request.
          if (isAuthClassError(err)) {
            this.armBreaker();
            break;
          }
        }
      }

      // D4: a fully-successful flush (every row processed with no failure) resets
      // the breaker so the next auth problem starts backoff from 60s again.
      // Non-auth failures keep per-row backoff and leave the breaker untouched
      // (R2) — they only prevent the "fully successful" reset.
      if (!hadAnyFailure) this.resetAuthBreaker();

      // Prune stale failed items once per flush cycle
      try {
        const pruned = await this.dao.prune();
        if (pruned > 0) {
          console.debug(`SyncOutbox: pruned ${pruned} stale failed items`);
        }
      } catch {
        // Prune is best-effort
      }
    } finally {
      this.flushing = false;

      if (gatePassed) {
        const flushElapsed = performance.now() - flushStart;
        metricsStore.record({
          name: METRIC_NAMES.SYNC_FLUSH,
          durationMs: Math.round(flushElapsed),
          bucketedDurationMs: bucketDurationMs(flushElapsed),
          count: 1,
          success: true,
          tags: { source: 'sync', platform: 'desktop' },
        });
      }
    }
  }
}
