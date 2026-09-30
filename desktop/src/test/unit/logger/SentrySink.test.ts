/**
 * Unit tests for {@link $lib/shared/logger/SentrySink}.
 *
 * Validates the `sentry-cross-platform` spec scenario "Renderer init failure":
 * `Sentry.init` throwing MUST NOT crash the sink — the sink falls back to
 * a no-op. Also verifies the success path wires `browserTracingIntegration`,
 * `browserSessionIntegration` (spec C2 — explicit session tracking), and
 * `replayIntegration` correctly when init succeeds.
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

// The `@sentry/browser` module is mocked globally so we can drive both the
// success path (init resolves) and the failure path (init throws).
const sentryInit = vi.fn();
const browserTracingIntegration = vi.fn((_opts?: unknown) => ({ name: 'BrowserTracing' }));
const browserSessionIntegration = vi.fn((_opts?: unknown) => ({ name: 'BrowserSession' }));
const replayIntegration = vi.fn((_opts?: unknown) => ({ name: 'Replay' }));
const withScope = vi.fn();
const captureException = vi.fn();
const captureMessage = vi.fn();
const setLevel = vi.fn();
const setExtra = vi.fn();
const addBreadcrumb = vi.fn();

vi.mock('@sentry/browser', () => ({
  init: (...args: unknown[]) => sentryInit(...args),
  browserTracingIntegration: (opts?: unknown) => browserTracingIntegration(opts),
  browserSessionIntegration: (opts?: unknown) => browserSessionIntegration(opts),
  replayIntegration: (opts?: unknown) => replayIntegration(opts),
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
import { breadcrumbsStore } from '$lib/shared/logger/BreadcrumbsStore';
import type { SentrySettings } from '$lib/shared/logger/sentryConfig';
import type { ErrorEvent } from '$lib/shared/events/ErrorEvent';

const FULL_SETTINGS: SentrySettings = {
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

const ERROR_EVENT: ErrorEvent = {
  timestamp: new Date().toISOString(),
  severity: 'high',
  category: 'runtime',
  code: 'TEST_CODE',
  message: 'synthetic test error',
  correlationId: 'corr-1',
  source: 'app_shell',
  recoverable: false,
  context: {},
};

describe('SentrySink', () => {
  beforeEach(() => {
    breadcrumbsStore.clear();
    sentryInit.mockReset();
    browserTracingIntegration.mockClear();
    browserSessionIntegration.mockClear();
    replayIntegration.mockClear();
    withScope.mockReset();
    captureException.mockReset();
    captureMessage.mockReset();
    setLevel.mockReset();
    setExtra.mockReset();
  });

  afterEach(() => {
    vi.restoreAllMocks();
  });

  it('does not crash when Sentry.init throws — sink falls back to no-op', () => {
    sentryInit.mockImplementation(() => {
      throw new Error('init exploded');
    });

    let sink: SentrySink | null = null;
    expect(() => {
      sink = new SentrySink(FULL_SETTINGS);
    }).not.toThrow();

    // `log()` MUST be a no-op (no captureException/captureMessage called).
    expect(() => sink!.log(ERROR_EVENT)).not.toThrow();
    expect(captureException).not.toHaveBeenCalled();
    expect(captureMessage).not.toHaveBeenCalled();
  });

  it('wires browserTracingIntegration + browserSessionIntegration + replayIntegration on the success path', () => {
    sentryInit.mockImplementation(() => undefined);

    const sink = new SentrySink(FULL_SETTINGS);

    expect(sentryInit).toHaveBeenCalledTimes(1);
    expect(browserTracingIntegration).toHaveBeenCalledTimes(1);
    // Spec C2 — explicit session tracking. The session integration MUST be
    // included in the `integrations` array so session health flows to
    // Sentry's release-health surface (prerequisite for Phase 3 alerting).
    expect(browserSessionIntegration).toHaveBeenCalledTimes(1);
    expect(replayIntegration).toHaveBeenCalledTimes(1);

    // Replay integration MUST receive the masking config (PII policy).
    expect(replayIntegration).toHaveBeenCalledWith(
      expect.objectContaining({
        maskAllText: true,
        maskAllInputs: true,
      }),
    );

    // `defaultIntegrations` MUST NOT be explicitly disabled — see design
    // decision #1 (`@sentry/browser` v10 applies defaults unless set to false).
    const initArg = sentryInit.mock.calls[0]?.[0] as Record<string, unknown>;
    expect(initArg['defaultIntegrations']).not.toBe(false);

    // beforeSend MUST be wired (the scrubber).
    expect(typeof initArg['beforeSend']).toBe('function');

    // log() forwards via captureException (severity=high → level=error).
    sink.log(ERROR_EVENT);
    expect(captureException).toHaveBeenCalledTimes(1);
  });

  it('skips init entirely when dsn is empty', () => {
    const sink = new SentrySink({ ...FULL_SETTINGS, dsn: '', enabled: true });
    expect(sentryInit).not.toHaveBeenCalled();
    expect(() => sink.log(ERROR_EVENT)).not.toThrow();
    expect(captureException).not.toHaveBeenCalled();
  });

  it('skips init entirely when enabled=false', () => {
    const sink = new SentrySink({ ...FULL_SETTINGS, enabled: false });
    expect(sentryInit).not.toHaveBeenCalled();
    expect(() => sink.log(ERROR_EVENT)).not.toThrow();
    expect(captureException).not.toHaveBeenCalled();
  });
});
