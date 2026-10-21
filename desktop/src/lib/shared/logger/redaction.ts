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
    return `${key}:${REDACTED}`;
  });
  result = result.replace(BEARER_RE, `$1${REDACTED}`);
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
