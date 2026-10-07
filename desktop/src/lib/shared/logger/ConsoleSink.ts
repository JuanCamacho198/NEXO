import type { LoggerSink } from './Logger';
import type { ErrorEvent } from '../events/ErrorEvent';

/**
 * Developer-console sink, gated to development builds.
 *
 * Production builds construct it with `enabled = false` (`import.meta.env.DEV`
 * is `false`), so any recorded event writes nothing to the console. Tests can
 * force either mode through the constructor.
 */
export class ConsoleSink implements LoggerSink {
  constructor(private readonly enabled: boolean = import.meta.env.DEV) {}

  log(event: ErrorEvent): void {
    if (!this.enabled) {
      return;
    }

    const level =
      event.severity === 'critical' || event.severity === 'high'
        ? 'error'
        : event.severity === 'medium'
          ? 'warn'
          : 'log';

    const prefix = `[${event.category}]${event.recoverable ? '' : ' [FATAL]'}`;

    console[level](prefix, `[${event.code}]`, event.message, event.context);
  }
}

export const consoleSink = new ConsoleSink();
