import * as Sentry from '@sentry/browser';
import type { LoggerSink, LogLevel } from './Logger';
import type { ErrorEvent } from '../events/ErrorEvent';
import type { SentrySettings } from './sentryConfig';
import { scrubEvent, type SentryLikeEvent } from './sentryPiiScrubber';
import { captureBreadcrumb, breadcrumbsStore } from './BreadcrumbsStore';
import { BREADCRUMB_LABELS } from './breadcrumbTypes';
import { routeAlert } from './AlertRouter';

type SentrySeverityLevel = 'fatal' | 'error' | 'warning' | 'info' | 'debug';

const HIGH_SEVERITY = ['high', 'critical'];

/**
 * Reader-source severity floor (Phase 1 of `reader-error-enrichment`).
 *
 * Policy: any `ErrorEvent` with `source === 'reader'` AND a non-empty
 * `context` has a severity FLOOR of `high` — `low`/`medium` are bumped to
 * `high`, while `high`/`critical` pass through unchanged. The reader is a
 * high-signal surface — a missed highlight, a PDF selection glitch, or a
 * bookmark save failure is actionable for engineering and must not be
 * dropped by the HIGH_SEVERITY gate.
 *
 * Events with empty `context` fall through to the original severity, so the
 * existing low-severity behavior is preserved when no structured context is
 * attached (call sites that still use bare `console.error`).
 */
export function effectiveSeverity(event: ErrorEvent): ErrorEvent['severity'] {
  if (event.source === 'reader' && event.context && Object.keys(event.context).length > 0) {
    if (event.severity === 'low' || event.severity === 'medium') {
      return 'high';
    }
  }
  return event.severity;
}

export class SentrySink implements LoggerSink {
  private isEnabled: boolean = false;

  constructor(private settings?: SentrySettings) {
    this.isEnabled = this.shouldEnable();
    if (this.isEnabled) {
      this.initSentry();
    }
  }

  private shouldEnable(): boolean {
    if (this.settings?.enabled === false) {
      return false;
    }
    return !!(this.settings?.dsn && this.settings.dsn.length > 0);
  }

  private initSentry(): void {
    if (!this.settings?.dsn) {
      return;
    }

    try {
      Sentry.init({
        dsn: this.settings.dsn,
        release: this.settings.release,
        environment: this.settings.environment,
        tracesSampleRate: this.settings.tracesSampleRate ?? 0.1,
        sendDefaultPii: this.settings.sendDefaultPii ?? false,
        replaysSessionSampleRate: this.settings.replaysSessionSampleRate ?? 0,
        replaysOnErrorSampleRate: this.settings.replaysOnErrorSampleRate ?? 0.1,
        // `maskAllText` / `maskAllInputs` are replay-integration options, not
        // top-level init options in `@sentry/browser` v10. See
        // https://docs.sentry.io/platforms/javascript/session-replay/configuration/
        //
        // Spec C2 — explicit session tracking. `@sentry/browser` v10 removed
        // the `autoSessionTracking` top-level option; session health now
        // flows via `browserSessionIntegration()` (`lifecycle: 'route'` matches
        // the desktop SPA's history-instrumentation behavior). Without this,
        // the crash-free metric in Releases is empty and Phase 3 alerting has
        // no baseline to gate on.
        integrations: [
          Sentry.browserSessionIntegration(),
          Sentry.browserTracingIntegration(),
          Sentry.replayIntegration({
            maskAllText: this.settings.maskAllText ?? true,
            maskAllInputs: this.settings.maskAllInputs ?? true,
          }),
        ],
        beforeSend: (event) =>
          scrubEvent(event as unknown as SentryLikeEvent) as unknown as Sentry.ErrorEvent,
      });
    } catch (err) {
      // Init failure must NOT crash the app — fall back to no-op. The
      // outer `Logger.broadcast` try/catch would also catch this, but the
      // sink itself must be defensive so other sinks still fire.
      console.warn('Sentry init failed', err);
      this.isEnabled = false;
    }
  }

  log(event: ErrorEvent, level?: LogLevel): void {
    // ERROR-only forwarding: when the logger supplies its authoritative level,
    // WARN and below never reach the crash reporter (they stay ring-local and
    // file-bound). Without an explicit level the legacy severity gate below
    // still applies.
    if (level !== undefined && level !== 'error') {
      return;
    }

    // Apply the reader-source severity floor BEFORE any gate/decision so
    // breadcrumbs, alert routing, and the Sentry send all see the same
    // effective severity. See `effectiveSeverity` JSDoc above.
    const effective = effectiveSeverity(event);

    if (HIGH_SEVERITY.includes(effective)) {
      captureBreadcrumb('error', this.mapCodeToLabel(event.code), {
        message: event.message,
        code: event.code,
        source: event.source,
        severity: effective,
      });
      routeAlert(event);
    }

    if (!this.isEnabled) {
      return;
    }

    const sentryLevel = this.mapSeverity(effective);

    Sentry.withScope((scope: Sentry.Scope) => {
      scope.setLevel(sentryLevel);
      // Journey breadcrumbs (cap 100 FIFO in BreadcrumbsStore): forward the
      // buffered ids-only crumbs into the Sentry scope so the crash event
      // carries the ordered open→chapter→highlight journey. Buffered while
      // Sentry is uninit; flushed here on the single forwarding point.
      for (const crumb of breadcrumbsStore.getAll()) {
        scope.addBreadcrumb({
          category: crumb.label,
          message: crumb.label,
          level: 'info',
          data: crumb.data,
        });
      }
      scope.setExtra('category', event.category);
      scope.setExtra('code', event.code);
      scope.setExtra('source', event.source);
      scope.setExtra('recoverable', event.recoverable);
      scope.setExtra('correlationId', event.correlationId);

      if (event.context && Object.keys(event.context).length > 0) {
        for (const [key, value] of Object.entries(event.context)) {
          scope.setExtra(key, value);
        }
      }

      if (sentryLevel === 'error' || sentryLevel === 'fatal') {
        const error = new Error(event.message);
        error.name = event.code;
        Sentry.captureException(error);
      } else {
        Sentry.captureMessage(event.message, sentryLevel);
      }
    });
  }

  private mapCodeToLabel(code: string): string {
    if (code.includes('IMPORT')) return BREADCRUMB_LABELS.IMPORT_FAIL;
    if (code.includes('SYNC')) return 'sync_fail';
    if (code.includes('READER')) return 'reader_fail';
    return 'error_generic';
  }

  private mapSeverity(severity: ErrorEvent['severity']): SentrySeverityLevel {
    switch (severity) {
      case 'critical':
        return 'fatal';
      case 'high':
        return 'error';
      case 'medium':
        return 'warning';
      case 'low':
        return 'info';
      default:
        return 'info';
    }
  }
}

export const createSentrySink = (settings?: SentrySettings): LoggerSink => {
  return new SentrySink(settings);
};
