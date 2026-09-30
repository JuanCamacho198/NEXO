/**
 * Unit tests for the Sentry PII scrubber.
 *
 * Source of truth: `desktop/src/lib/shared/logger/sentryPiiScrubber.ts`.
 * Mirrors the Rust redaction regex set in
 * `desktop/src-tauri/src/logger.rs::Logger::redact_event` (Phase 2 of the
 * `sentry-cross-platform` change).
 *
 * The scrubber is wired to `@sentry/browser`'s `beforeSend` hook in
 * `SentrySink.ts`. If any of these golden-file expectations change, BOTH
 * the TS scrubber and the Rust regex set must move together.
 */
import { describe, expect, it } from 'vitest';
import { scrubEvent, type SentryLikeEvent } from '$lib/shared/logger/sentryPiiScrubber';

const REDACTED = '[Redacted]';

describe('scrubEvent — Sentry PII scrubber', () => {
  it('redacts `code` and `state` query values inside an OAuth loopback callback URL while preserving the URL', () => {
    const event: SentryLikeEvent = {
      extra: {
        oauthCallbackUrl: 'https://127.0.0.1:54321/callback?code=abc123&state=xyz',
      },
    };

    const out = scrubEvent(event);

    // `code` and `state` are sensitive OAuth params — both must be redacted
    // to prevent CSRF replay and code-exfiltration attacks.
    expect(out.extra?.['oauthCallbackUrl']).toBe(
      'https://127.0.0.1:[Redacted]/callback?code=[Redacted]&state=[Redacted]',
    );
  });

  it('reduces an EPUB path to basename only', () => {
    const event: SentryLikeEvent = {
      extra: { epubPath: '/Users/juan/Library/Books/xyz.epub' },
    };

    const out = scrubEvent(event);

    expect(out.extra?.['epubPath']).toBe('xyz.epub');
  });

  it('reduces Windows-style book paths to basename only', () => {
    const event: SentryLikeEvent = {
      extra: { bookPath: 'C:\\Users\\juan\\Library\\Books\\pride-and-prejudice.epub' },
    };

    const out = scrubEvent(event);

    expect(out.extra?.['bookPath']).toBe('pride-and-prejudice.epub');
  });

  it('redacts a Supabase JWT in extra.supabaseToken', () => {
    const jwt =
      'eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiIxMjM0NTY3ODkwIiwibmFtZSI6IkpvaG4ifQ.SflKxwRJSMeKKF2QT4fwpMeJf36POk6yJV_adQssw5c';

    const event: SentryLikeEvent = {
      extra: { supabaseToken: jwt },
    };

    const out = scrubEvent(event);

    expect(out.extra?.['supabaseToken']).toBe(REDACTED);
  });

  it('redacts extra.password regardless of the value content', () => {
    const event: SentryLikeEvent = {
      extra: { password: 'hunter2-strong-passphrase' },
    };

    const out = scrubEvent(event);

    expect(out.extra?.['password']).toBe(REDACTED);
  });

  it('redacts extra fields whose keys include access_token / refresh_token / api_key / secret', () => {
    const event: SentryLikeEvent = {
      extra: {
        accessToken: 'ya29.access',
        refreshToken: '1//refresh',
        apiKey: 'sk-test-12345',
        clientSecret: 's3cr3t-value',
      },
    };

    const out = scrubEvent(event);

    expect(out.extra?.['accessToken']).toBe(REDACTED);
    expect(out.extra?.['refreshToken']).toBe(REDACTED);
    expect(out.extra?.['apiKey']).toBe(REDACTED);
    expect(out.extra?.['clientSecret']).toBe(REDACTED);
  });

  it('is idempotent: scrubbing twice equals scrubbing once', () => {
    const event: SentryLikeEvent = {
      extra: {
        password: 'pw-abc',
        epubPath: '/Users/juan/Library/Books/x.epub',
        oauthCallbackUrl: 'https://127.0.0.1:54321/callback?code=secret-code',
        theme: 'dark',
      },
      user: { ip_address: '192.168.1.1' },
    };

    const once = scrubEvent(event);
    const twice = scrubEvent(once);

    expect(twice).toEqual(once);
  });

  it('passes non-PII extra values through unchanged', () => {
    const event: SentryLikeEvent = {
      extra: { theme: 'dark', bookCount: 12, locale: 'es-AR' },
    };

    const out = scrubEvent(event);

    expect(out.extra?.['theme']).toBe('dark');
    expect(out.extra?.['bookCount']).toBe(12);
    expect(out.extra?.['locale']).toBe('es-AR');
  });

  it('redacts user.ip_address', () => {
    const event: SentryLikeEvent = {
      user: { ip_address: '192.168.1.1', email: 'juan@example.com' },
    };

    const out = scrubEvent(event);

    expect(out.user?.['ip_address']).toBe(REDACTED);
  });

  it('redacts the request.cookies field', () => {
    const event: SentryLikeEvent = {
      request: { url: 'https://app.example.com/path', cookies: 'session=abc; csrf=xyz' },
    };

    const out = scrubEvent(event);

    expect(out.request?.['cookies']).toBe(REDACTED);
    expect(out.request?.['url']).toBe('https://app.example.com/path');
  });

  it('redacts sensitive `key:value` patterns inside a free-form message', () => {
    // Mirrors Rust redact_string: `key:value` (colon separator). The
    // equals-separated query-param shape is handled by the URL pass above.
    const event: SentryLikeEvent = {
      message: 'OAuth failed: access_token:ya29.abc, refresh_token:1//xyz',
    };

    const out = scrubEvent(event);

    expect(out.message).toBe(`OAuth failed: access_token:${REDACTED}, refresh_token:${REDACTED}`);
  });

  it('returns a NEW object — does not mutate the input event', () => {
    const event: SentryLikeEvent = {
      extra: { password: 'pw', theme: 'dark' },
    };
    const originalJson = JSON.stringify(event);

    scrubEvent(event);

    expect(JSON.stringify(event)).toBe(originalJson);
  });

  it('handles an empty event object without throwing', () => {
    const out = scrubEvent({});

    expect(out).toEqual({});
  });

  it('redacts sensitive patterns inside exception values', () => {
    const event: SentryLikeEvent = {
      exception: {
        values: [
          { value: 'Error while signing in with access_token:ya29.abc and password:secret' },
        ],
      },
    };

    const out = scrubEvent(event);

    const value = out.exception?.['values']?.[0]?.['value'];
    expect(value).toBe(
      `Error while signing in with access_token:${REDACTED} and password:${REDACTED}`,
    );
  });

  // `reader-error-enrichment` Phase 1: tag names and highlight notes are
  // user-typed text. Even if a caller forgets to use `*Length` fields, the
  // scrubber MUST redact the raw values before they reach Sentry.
  it('redacts extra.note values (highlight notes are user-typed text)', () => {
    const event: SentryLikeEvent = {
      extra: { note: 'user-typed private note that must not leak' },
    };

    const out = scrubEvent(event);

    expect(out.extra?.['note']).toBe(REDACTED);
  });

  it('redacts extra.tagName values (tag names are user-typed text)', () => {
    const event: SentryLikeEvent = {
      extra: { tagName: 'my-secret-tag-name' },
    };

    const out = scrubEvent(event);

    expect(out.extra?.['tagName']).toBe(REDACTED);
  });

  // `sentry-observability-v2` PR1 (task 1.4): iframe error context keys.
  it('reduces extra.iframeSource to basename only', () => {
    const out = scrubEvent({
      extra: { iframeSource: '/data/user/0/com.nexo/files/OEBPS/Text/ch9.xhtml' },
    });

    expect(out.extra?.['iframeSource']).toBe('ch9.xhtml');
  });

  it('pattern-redacts sensitive key:value pairs inside an iframe error message', () => {
    const out = scrubEvent({ message: 'iframe chap9: token:abc123 failed to load note:private' });

    expect(out.message).toBe(`iframe chap9: token:${REDACTED} failed to load note:${REDACTED}`);
  });

  // `sentry-observability-v2` PR3 (task 4.2 — spec D2 policy split):
  // bookTitle / chapterLabel may travel ONLY on feedback events. Every
  // other event (captureException, breadcrumbs, replay) must strip them.
  it('STRIPS extra.bookTitle + extra.chapterLabel on a non-feedback event', () => {
    const out = scrubEvent({
      extra: {
        bookTitle: 'La Odisea',
        chapterLabel: 'Canto III',
        theme: 'dark',
      },
    });

    expect(out.extra).not.toHaveProperty('bookTitle');
    expect(out.extra).not.toHaveProperty('chapterLabel');
    // Other keys must still pass through.
    expect(out.extra?.['theme']).toBe('dark');
  });

  it('KEEPS extra.bookTitle + extra.chapterLabel when contexts.feedback is set', () => {
    const out = scrubEvent({
      contexts: { feedback: { message: 'estaba leyendo' } },
      extra: {
        bookTitle: 'La Odisea',
        chapterLabel: 'Canto III',
        theme: 'dark',
      },
    });

    expect(out.extra?.['bookTitle']).toBe('La Odisea');
    expect(out.extra?.['chapterLabel']).toBe('Canto III');
    expect(out.extra?.['theme']).toBe('dark');
  });
});
