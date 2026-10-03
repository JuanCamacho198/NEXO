// Must evaluate before any Tauri plugin call: in a plain `bun run dev` browser
// there is no IPC bridge, so without it the shell stalls on the welcome screen.
import './lib/dev/browserTauriShim';
import './styles.css';
import App from './App.svelte';
import { mount } from 'svelte';
import { onOpenUrl } from '@tauri-apps/plugin-deep-link';
import { registerSupabaseCallbackHandler } from './lib/shared/services';
import { handleDeepLinkUrls } from './lib/features/addons/installDeepLink';
import { registerDeepLinkListener } from './lib/shared/ports/adapters/tauri/tauriDeepLink';
import { logger } from './lib/shared/logger/Logger';
import { consoleSink } from './lib/shared/logger/ConsoleSink';
import { tauriSink } from './lib/shared/logger/TauriSink';
import { SentrySink } from './lib/shared/logger/SentrySink';
import { SentryMetricsSink } from './lib/shared/logger/SentryMetricsSink';
import { metricsStore } from './lib/shared/logger/MetricsStore';
import { getSentrySettings } from './lib/shared/logger/sentryConfig';
import { createErrorEvent, type ErrorEvent } from './lib/shared/events/ErrorEvent';
import { METRIC_NAMES } from './lib/shared/logger/metricTypes';
import { bucketDurationMs } from './lib/shared/logger/metricBuckets';
import * as Sentry from '@sentry/browser';

// D1 — cold start origin: first statement of the entry module.
const coldStartOrigin = performance.now();
let coldStartEmitted = false;

let handlersRegistered = false;

const initLogger = async (): Promise<void> => {
  logger.registerSink(consoleSink);
  logger.registerSink(tauriSink);

  const sentrySettings = await getSentrySettings();
  if (sentrySettings.dsn) {
    const sentrySink = new SentrySink(sentrySettings);
    logger.registerSink(sentrySink);

    // D9 — metrics egress: stream buffered metrics to Sentry. Registered only
    // when DSN is present and enabled; the sink re-checks `enabled` per-emit
    // so a mid-session disable stops egress immediately.
    if (sentrySettings.enabled !== false) {
      const metricsSink = new SentryMetricsSink(sentrySettings);
      metricsStore.onRecord((event) => metricsSink.handle(event));
    }
  }
};

// One-shot flush of Sentry's internal metric buffer on page exit.
const flushOnce = (): void => {
  void Sentry.flush(2000);
  metricsStore.flush();
  window.removeEventListener('pagehide', flushOnce);
  window.removeEventListener('beforeunload', flushOnce);
};
window.addEventListener('pagehide', flushOnce);
window.addEventListener('beforeunload', flushOnce);

const handleGlobalError = (event: ErrorEvent): void => {
  const errorEvent = createErrorEvent({
    severity: 'high',
    category: 'runtime',
    code: 'UNCAUGHT_ERROR',
    message: event.message,
    context: {
      filename: (event as unknown as { filename?: string }).filename,
      lineno: (event as unknown as { lineno?: number }).lineno,
      colno: (event as unknown as { colno?: number }).colno,
    },
    source: 'app_shell',
    recoverable: false,
  });

  logger.error(errorEvent);
};

const handleUnhandledRejection = (event: PromiseRejectionEvent): void => {
  const errorMessage = event.reason instanceof Error ? event.reason.message : String(event.reason);

  const errorEvent = createErrorEvent({
    severity: 'high',
    category: 'promise_rejection',
    code: 'UNHANDLED_REJECTION',
    message: errorMessage,
    context: {
      reason:
        event.reason instanceof Error
          ? { name: event.reason.name, stack: event.reason.stack }
          : String(event.reason),
    },
    source: 'app_shell',
    recoverable: false,
  });

  logger.error(errorEvent);
};

const registerGlobalHandlers = async (): Promise<void> => {
  if (handlersRegistered) {
    return;
  }

  await initLogger();

  window.onerror = (message, source, lineno, colno, error) => {
    const errorEvent = createErrorEvent({
      severity: 'high',
      category: 'runtime',
      code: 'UNCAUGHT_ERROR',
      message: typeof message === 'string' ? message : 'Unknown error',
      context: { source, lineno, colno, error: error?.stack },
      source: 'app_shell',
      recoverable: false,
    });
    handleGlobalError(errorEvent);
    return false;
  };

  window.onunhandledrejection = (event) => {
    handleUnhandledRejection(event);
  };

  handlersRegistered = true;
};

onOpenUrl((urls) => {
  // sdd/addon-deeplink-v1: cold-start routing. handleDeepLinkUrls is the
  // single entry point shared with the warm-start single-instance event —
  // non-install URLs stay ignored (REQ-7: deep-link reserved, OAuth loopback).
  void handleDeepLinkUrls(urls);
});

// Warm start: single-instance plugin forwards the scheme argv (nexo:// and the
// legacy nextpage*://) via this event.
// The listener is Tauri wiring and lives in the adapter; routing stays in the
// pure feature module, which is what keeps that module free of Tauri imports.
void registerDeepLinkListener((url) => {
  void handleDeepLinkUrls([url]);
});

// Supabase OAuth wiring: listen for OAuth callback on loopback URL.
registerSupabaseCallbackHandler();

const app = mount(App, {
  target: document.getElementById('app') as HTMLElement,
});

// D2 — cold start end boundary: one-shot rAF after mount (≈ first paint).
// Exactly one `app_cold_start` emission per launch; nothing in steady state.
requestAnimationFrame(() => {
  if (coldStartEmitted) return;
  coldStartEmitted = true;
  const elapsed = performance.now() - coldStartOrigin;
  metricsStore.record({
    name: METRIC_NAMES.APP_COLD_START,
    durationMs: Math.round(elapsed),
    bucketedDurationMs: bucketDurationMs(elapsed),
    count: 1,
    success: true,
    tags: { platform: 'desktop', source: 'app_shell' },
  });
});

registerGlobalHandlers();

export default app;
