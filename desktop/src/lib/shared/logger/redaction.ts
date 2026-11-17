import type { LogRecord } from './Logger';

/**
 * Universal redaction for retained log records.
 *
 * Extracted from `Logger.ts` (which sits at its size limit) — this is the
 * pure-function half of the redaction boundary: no state, no sink, no ring.
 * The behaviour is unchanged; the tests that lock it live with the logger.
 */

const SENSITIVE_KEY_PATTERNS: readonly string[] = [
  'password',
  'token',
  'secret',
  'api_key',
  'api-key',
  'apikey',
  'accesstoken',
  'refreshtoken',
  'idtoken',
  'authorization',
  'supabase',
  'dsn',
  'highlight',
  'book_content',
  'bookcontent',
];

const SECRET_ASSIGNMENT_RE = new RegExp(
  `(${SENSITIVE_KEY_PATTERNS.map((pattern) => pattern.replace(/[-_]/g, '[-_]')).join('|')})\\s*[:=]\\s*\\S+`,
  'gi',
);
const BEARER_RE = /(Bearer\s+)[A-Za-z0-9._-]+/gi;

/**
 * Shape-based catch for secrets that appear without a key in front of them.
 *
 * The key/anchor rules above only fire when the secret is introduced by
 * `token=`, `api_key:` or `Bearer `. A JWT logged in prose
 * (`token exchanged for eyJhbGci...`) carries no such anchor, so it survived
 * every rule — measured on the real `logger.error` path in
 * `loggerRedactionEndToEnd.test.ts`. The base64url alphabet and the
 * header.payload.signature shape are specific enough that matching them does
 * not swallow ordinary words, and the length floor keeps short dotted
 * identifiers out.
 */
const JWT_RE = /\beyJ[A-Za-z0-9_-]{4,}\.[A-Za-z0-9_-]{4,}\.[A-Za-z0-9_-]{4,}/g;
/** Stripe-style vendor keys (`sk-live-…`, `pk-test-…`) pasted into prose. */
const VENDOR_KEY_RE = /\b(?:sk|pk|rk)-[A-Za-z0-9]{8,}\b/g;
const DSN_RE = /(https?:\/\/)[A-Za-z0-9]+@/gi;
const WINDOWS_PATH_RE = /(^|[^\w:])([a-zA-Z]:[\\/][^\s,"'\]}]*)/g;
const UNIX_PATH_RE =
  /(^|[^:\w/])(\/(?:home|users|tmp|var|etc|opt|data|storage|mnt|root|private)[^\s,"'\]}]*)/gi;
const NESTED_PATH_RE = /(^|[^:\w/])(\/(?:[\w.-]+\/)+[\w.~-]*)/g;
const HOME_RE = /(^|[^\w])(~(?:\/[^\s,"'\]}]*)?)/g;

const REDACTED = '[REDACTED]';
const REDACTED_PATH = '[REDACTED_PATH]';

export function redactText(input: string): string {
  if (input.length === 0) {
    return input;
  }

  let result = input;
  result = result.replace(SECRET_ASSIGNMENT_RE, (match) => {
    const separatorIndex = match.search(/[:=]/);
    const key = separatorIndex >= 0 ? match.slice(0, separatorIndex) : match;
    // Trailing whitespace after the separator is preserved so an already
    // redacted `token: [REDACTED]` stays byte-identical on a second pass
    // instead of collapsing to `token:[REDACTED]`.
    const separator = match.slice(separatorIndex, match.length).match(/^[:=]\s*/)?.[0] ?? ':';
    return `${key}${separator}${REDACTED}`;
  });
  result = result.replace(BEARER_RE, `$1${REDACTED}`);
  result = result.replace(JWT_RE, REDACTED);
  result = result.replace(VENDOR_KEY_RE, REDACTED);
  result = result.replace(DSN_RE, `$1${REDACTED}@`);
  result = result.replace(WINDOWS_PATH_RE, `$1${REDACTED_PATH}`);
  result = result.replace(UNIX_PATH_RE, `$1${REDACTED_PATH}`);
  result = result.replace(NESTED_PATH_RE, `$1${REDACTED_PATH}`);
  result = result.replace(HOME_RE, `$1${REDACTED_PATH}`);
  return result;
}

export function redactValue(value: unknown): unknown {
  if (Array.isArray(value)) {
    return value.map((entry) => redactValue(entry));
  }
  if (value !== null && typeof value === 'object') {
    const result: Record<string, unknown> = {};
    for (const [key, inner] of Object.entries(value as Record<string, unknown>)) {
      const lowerKey = key.toLowerCase();
      const sensitive = SENSITIVE_KEY_PATTERNS.some((pattern) => lowerKey.includes(pattern));
      result[key] = sensitive ? REDACTED : redactValue(inner);
    }
    return result;
  }
  if (typeof value === 'string') {
    return redactText(value);
  }
  return value;
}

/**
 * Redact secrets, DSN values, absolute paths, and sensitive keys from a
 * retained record. Applied before ring retention and before transfer
 * (defense in depth ahead of the authoritative Rust boundary).
 */
export function redactRecord(record: LogRecord): LogRecord {
  return {
    ...record,
    message: redactText(record.message),
    context: redactValue(record.context) as Record<string, unknown>,
  };
}
