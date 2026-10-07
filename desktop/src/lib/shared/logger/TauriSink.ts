import { invoke } from '@tauri-apps/api/core';
import type { LoggerSink, LogLevel } from './Logger';
import { BATCH_FLUSH_EVENT_CAP, BATCH_FLUSH_INTERVAL_MS } from './Logger';
import type { ErrorEvent } from '../events/ErrorEvent';

interface QueuedLog {
  event: ErrorEvent;
  level: LogLevel;
}

/**
 * Batched Tauri IPC sink.
 *
 * Routine log events accumulate locally and transfer in bounded batches once
 * {@link BATCH_FLUSH_EVENT_CAP} events are buffered or {@link BATCH_FLUSH_INTERVAL_MS}
 * elapses — no routine event costs a dedicated IPC call at record time. ERROR
 * events use the typed `reportErrorEvent` command; lower levels use the generic
 * `logEvent` command. Redaction already ran in the logger before the event
 * reached this sink. Failures are swallowed: logging never breaks the app.
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
    await Promise.all(batch.map((entry) => this.transfer(entry)));
  }

  private async transfer({ event, level }: QueuedLog): Promise<void> {
    try {
      if (level === 'error') {
        await invoke('reportErrorEvent', { event: this.toErrorDto(event) });
        return;
      }
      await invoke('logEvent', {
        event: {
          timestamp: event.timestamp,
          level,
          message: event.message,
          context: event.context,
          source: event.source,
        },
      });
    } catch {
      // logging should never break the app
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
