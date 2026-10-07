/**
 * Crash-reporter forwarding gate (SDD `observability-logging`, phase 3.3/3.5).
 *
 * Locks the spec scenario "Only errors reach the crash reporter": an ERROR
 * event is forwarded to the crash reporter, while a WARN event is never
 * forwarded (it stays ring-local and file-bound).
 */
import { beforeEach, describe, expect, it, vi } from 'vitest';

const sentryInit = vi.fn();
const withScope = vi.fn();
const captureException = vi.fn();
const captureMessage = vi.fn();
const setLevel = vi.fn();
const setExtra = vi.fn();
const addBreadcrumb = vi.fn();

vi.mock('@sentry/browser', () => ({
  init: (...args: unknown[]) => sentryInit(...args),
  browserTracingIntegration: () => ({ name: 'BrowserTracing' }),
  browserSessionIntegration: () => ({ name: 'BrowserSession' }),
  replayIntegration: () => ({ name: 'Replay' }),
  withScope: (cb: (scope: unknown) => void) =>
    withScope(
      cb({
        setLevel: (...args: unknown[]) => setLevel(...args),
        setExtra: (...args: unknown[]) => setExtra(...args),
        addBreadcrumb: (...args: unknown[]) => addBreadcrumb(...args),
      }),
    ),
  captureException: (...args: unknown[]) => captureException(...args),
  captureMessage: (...args: unknown[]) => captureMessage(...args),
}));

import { SentrySink } from '$lib/shared/logger/SentrySink';
import type { SentrySettings } from '$lib/shared/logger/sentryConfig';
import { breadcrumbsStore } from '$lib/shared/logger/BreadcrumbsStore';
import type { ErrorEvent } from '$lib/shared/events/ErrorEvent';

const SETTINGS: SentrySettings = {
  dsn: 'https://valid@x.ingest.sentry.io/1',
  enabled: true,
  tracesSampleRate: 0.1,
  release: 'nexo-desktop@0.1.0+abc1234',
  environment: 'production',
  sendDefaultPii: false,
  replaysSessionSampleRate: 0,
  replaysOnErrorSampleRate: 0.1,
  maskAllText: true,
  maskAllInputs: true,
};

function makeEvent(overrides: Partial<ErrorEvent> = {}): ErrorEvent {
  return {
    timestamp: '2026-10-07T00:00:00.000Z',
    severity: 'medium',
    category: 'runtime',
    code: 'TEST_CODE',
    message: 'synthetic',
    correlationId: 'corr-1',
    source: 'app_shell',
    recoverable: true,
    context: {},
    ...overrides,
  };
}

describe('SentrySink ERROR-only forwarding', () => {
  beforeEach(() => {
    breadcrumbsStore.clear();
    withScope.mockClear();
    captureException.mockClear();
    captureMessage.mockClear();
  });

  it('does not forward WARN events to the crash reporter', () => {
    const sink = new SentrySink(SETTINGS);

    sink.log(makeEvent({ severity: 'medium' }), 'warn');

    expect(withScope).not.toHaveBeenCalled();
    expect(captureException).not.toHaveBeenCalled();
    expect(captureMessage).not.toHaveBeenCalled();
  });

  it('forwards ERROR events to the crash reporter', () => {
    const sink = new SentrySink(SETTINGS);

    sink.log(makeEvent({ severity: 'high' }), 'error');

    expect(withScope).toHaveBeenCalledTimes(1);
    expect(captureException).toHaveBeenCalledTimes(1);
  });
});
