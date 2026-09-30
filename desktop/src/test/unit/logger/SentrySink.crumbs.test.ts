/**
 * PR1 signal — SentrySink breadcrumb flush loop (task 1.5). Buffered
 * `BreadcrumbsStore` journey crumbs MUST forward via `scope.addBreadcrumb`
 * as `{category: label, message: label, level: 'info', data}` (ids-only).
 */
import { beforeEach, describe, expect, it, vi } from 'vitest';

const sentryInit = vi.fn();
const scopeAddBreadcrumb = vi.fn();
const captureException = vi.fn();
const captureMessage = vi.fn();

vi.mock('@sentry/browser', () => ({
  init: (...args: unknown[]) => sentryInit(...args),
  browserTracingIntegration: () => ({ name: 'BrowserTracing' }),
  // PR2 spec C2 — sink now wires `browserSessionIntegration` in the
  // `integrations` array. The mock mirrors the real export so `SentrySink`'s
  // init path stays a no-op-throw-success under test.
  browserSessionIntegration: () => ({ name: 'BrowserSession' }),
  replayIntegration: () => ({ name: 'Replay' }),
  withScope: (cb: (scope: unknown) => void) => {
    cb({ setLevel: vi.fn(), setExtra: vi.fn(), addBreadcrumb: scopeAddBreadcrumb });
  },
  captureException: (...args: unknown[]) => captureException(...args),
  captureMessage: (...args: unknown[]) => captureMessage(...args),
}));

import { SentrySink } from '$lib/shared/logger/SentrySink';
import { breadcrumbsStore } from '$lib/shared/logger/BreadcrumbsStore';
import type { SentrySettings } from '$lib/shared/logger/sentryConfig';
import type { ErrorEvent } from '$lib/shared/events/ErrorEvent';

const SETTINGS: SentrySettings = {
  dsn: 'https://valid@x.ingest.sentry.io/1',
  enabled: true,
  tracesSampleRate: 0,
  release: 'nexo-desktop@0.1.0+abc1234',
  environment: 'test',
  sendDefaultPii: false,
  replaysSessionSampleRate: 0,
  replaysOnErrorSampleRate: 0,
  maskAllText: true,
  maskAllInputs: true,
};

const HIGH_EVENT: ErrorEvent = {
  timestamp: new Date().toISOString(),
  severity: 'high',
  category: 'runtime',
  code: 'READER_IFRAME_ERROR',
  message: 'iframe boom',
  correlationId: 'corr-crumbs',
  source: 'reader',
  recoverable: true,
  context: { format: 'epub', bookId: 'book-1' },
};

describe('SentrySink breadcrumb flush', () => {
  beforeEach(() => {
    breadcrumbsStore.clear();
    scopeAddBreadcrumb.mockReset();
  });

  it('forwards buffered journey crumbs as scope breadcrumbs (ids-only)', () => {
    breadcrumbsStore.add('action', 'highlight_create', {
      bookId: 'book-1',
      highlightId: 'hl-1',
      pageNumber: 2,
      textLength: 42,
    });
    breadcrumbsStore.add('navigation', 'chapter_change', { bookId: 'book-1', chapterIndex: 2 });

    new SentrySink(SETTINGS).log(HIGH_EVENT);

    const forwarded = scopeAddBreadcrumb.mock.calls.map((c) => c[0] as Record<string, unknown>);
    const byCategory = Object.fromEntries(forwarded.map((b) => [b['category'], b]));
    expect(scopeAddBreadcrumb).toHaveBeenCalledTimes(3); // 2 journey + 1 error crumb
    expect(byCategory['highlight_create']).toMatchObject({
      message: 'highlight_create',
      level: 'info',
      data: { bookId: 'book-1', highlightId: 'hl-1', pageNumber: 2, textLength: 42 },
    });
    expect(byCategory['chapter_change']).toMatchObject({
      message: 'chapter_change',
      level: 'info',
    });
  });

  it('sends only the error crumb itself when the buffer is empty', () => {
    new SentrySink(SETTINGS).log(HIGH_EVENT);
    expect(scopeAddBreadcrumb).toHaveBeenCalledTimes(1);
  });
});
