import { invoke } from '@tauri-apps/api/core';
import type { LoggerSink, LogLevel } from './Logger';
import { BATCH_FLUSH_EVENT_CAP, BATCH_FLUSH_INTERVAL_MS } from './Logger';
import type { ErrorEvent } from '../events/ErrorEvent';

interface QueuedLog {
  event: ErrorEvent;
  level: LogLevel;
}

/** Upper bound on events retained for a retry, so a dead backend cannot grow the queue forever. */
export const RETRY_QUEUE_CAP = BATCH_FLUSH_EVENT_CAP * 4;

/**
 * Batched Tauri IPC sink.
 *
 * Routine log events accumulate locally and transfer in bounded batches once
 * {@link BATCH_FLUSH_EVENT_CAP} events are buffered or {@link BATCH_FLUSH_INTERVAL_MS}
 * elapses — no routine event costs a dedicated IPC call at record time.
 *
 * Flush shape:
 *   - Lower levels (debug/info/warn) travel as **one** `logEvents` call carrying
 *     the whole batch. The previous `Promise.all(batch.map(invoke))` was N calls
 *     in parallel against a backend that serialises them behind a global mutex,
 *     which held the `ipc.localhost` queue for seconds and stalled every other
 *     command (see `odd/tasks/log-ipc-hang.md`).
 *   - ERROR events keep the typed per-event `reportErrorEvent` command: it
 *     forwards to Sentry with a structured DTO, errors are rare, and they must
 *     never be dropped.
 *
 * Redaction already ran in the logger before the event reached this sink.
 * Failures do not break the app — but they are not silent either: see
 * {@link flush}.
 */
export class BatchedTauriSink implements LoggerSink {
  private queue: QueuedLog[] = [];
  private timer: ReturnType<typeof setTimeout> | null = null;

  log(event: ErrorEvent, level: LogLevel = 'error'): void {
    this.queue.push({ event, level });

    if (this.queue.length >= BATCH_FLUSH_EVENT_CAP) {
      void this.flush();
      return;
    }

    if (this.timer === null) {
      this.timer = setTimeout(() => {
        this.timer = null;
        void this.flush();
      }, BATCH_FLUSH_INTERVAL_MS);
    }
  }

  /**
   * Transfer the buffered batch.
   *
   * Failure contract: a flush that fails **retains** the events it could not
   * deliver, at the front of the queue, so the next flush retries them rather
   * than dropping them. The retained set is bounded by {@link RETRY_QUEUE_CAP}
   * — beyond that the oldest are dropped, because a permanently dead backend
   * must not become an unbounded memory leak inside the app. Errors are handled
   * individually (one `reportErrorEvent` each) so one failing error forward does
   * not block the rest of the batch.
   */
  async flush(): Promise<void> {
    if (this.timer !== null) {
      clearTimeout(this.timer);
      this.timer = null;
    }
    if (this.queue.length === 0) {
      return;
    }

    const batch = this.queue;
    this.queue = [];

    const errors = batch.filter((entry) => entry.level === 'error');
    const generic = batch.filter((entry) => entry.level !== 'error');

    const failed: QueuedLog[] = [];

    if (generic.length > 0) {
      const delivered = await this.transferBatch(generic);
      if (!delivered) {
        failed.push(...generic);
      }
    }

    for (const entry of errors) {
      const delivered = await this.transferError(entry);
      if (!delivered) {
        failed.push(entry);
      }
    }

    this.retainForRetry(failed);
  }

  /** Re-queue events a failed flush could not deliver, oldest-evict past the cap. */
  private retainForRetry(failed: QueuedLog[]): void {
    if (failed.length === 0) {
      return;
    }
    if (failed.length >= RETRY_QUEUE_CAP) {
      this.queue = failed.slice(-RETRY_QUEUE_CAP);
      return;
    }
    this.queue = [...failed, ...this.queue].slice(-RETRY_QUEUE_CAP);
  }

  /** One IPC call for the whole generic batch. Returns false when it failed. */
  private async transferBatch(entries: QueuedLog[]): Promise<boolean> {
    const events = entries.map(({ event, level }) => ({
      timestamp: event.timestamp,
      level,
      message: event.message,
      context: event.context,
      source: event.source,
    }));

    try {
      await invoke('logEvents', { events });
      return true;
    } catch {
      return false;
    }
  }

  /** Typed per-event error transfer. Returns false when it failed. */
  private async transferError({ event }: QueuedLog): Promise<boolean> {
    try {
      await invoke('reportErrorEvent', { event: this.toErrorDto(event) });
      return true;
    } catch {
      return false;
    }
  }

  private toErrorDto(event: ErrorEvent): Record<string, unknown> {
    return {
      timestamp: event.timestamp,
      severity: event.severity,
      category: event.category,
      code: event.code,
      message: event.message,
      context: event.context,
      correlationId: event.correlationId,
      source: event.source,
      recoverable: event.recoverable,
    };
  }
}

export const tauriSink = new BatchedTauriSink();
