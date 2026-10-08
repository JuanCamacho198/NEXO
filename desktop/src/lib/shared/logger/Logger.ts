import {
  createErrorEvent,
  type ErrorEvent,
  type ErrorSeverity,
  type ErrorSource,
} from '../events/ErrorEvent';

export type LogLevel = 'debug' | 'info' | 'warn' | 'error';

/**
 * Retained log record kept in the bounded in-memory ring. This is the
 * level-aware, redacted view the frontend retains locally; the typed
 * {@link ErrorEvent} stays the transport shape the sinks receive.
 */
export interface LogRecord {
  level: LogLevel;
  message: string;
  context: Record<string, unknown>;
  source: string;
  timestamp: string;
}

export interface LoggerSink {
  log(event: ErrorEvent, level?: LogLevel): void;
  flush?(): void | Promise<void>;
}

/** Bounded in-memory ring capacity (oldest-evict). Mirrors the Android 500-event ring. */
export const LOG_RING_CAP = 500;

/** Batch flush trigger: transfer accumulated events once this many are buffered. */
export const BATCH_FLUSH_EVENT_CAP = 50;

/** Batch flush trigger: transfer accumulated events after this interval elapses. */
export const BATCH_FLUSH_INTERVAL_MS = 5000;

const LEVEL_ORDER: Record<LogLevel, number> = { debug: 0, info: 1, warn: 2, error: 3 };

/**
 * Severity used for the typed transport event a message-form log call is
 * normalized into. The logger level (method) stays authoritative for gating
 * and for sinks that accept an explicit level.
 */
const SEVERITY_BY_LEVEL: Record<LogLevel, ErrorSeverity> = {
  debug: 'low',
  info: 'low',
  warn: 'medium',
  error: 'high',
};

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

/** Level gate: true when `level` meets or exceeds `threshold`. */
export function levelPasses(level: LogLevel, threshold: LogLevel): boolean {
  return LEVEL_ORDER[level] >= LEVEL_ORDER[threshold];
}

function redactText(input: string): string {
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

function redactValue(value: unknown): unknown {
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

function defaultThreshold(): LogLevel {
  return import.meta.env.PROD ? 'info' : 'debug';
}

class LoggerImpl {
  private sinks: LoggerSink[] = [];
  private ring: LogRecord[] = [];
  private threshold: LogLevel = defaultThreshold();
  private acceptedSinceFlush = 0;

  registerSink(sink: LoggerSink): void {
    this.sinks.push(sink);
  }

  /** Runtime threshold change. Applies to new events only; retention is not re-evaluated. */
  setThreshold(level: LogLevel): void {
    this.threshold = level;
  }

  getThreshold(): LogLevel {
    return this.threshold;
  }

  /** Snapshot of the bounded ring (oldest first). */
  records(): LogRecord[] {
    return [...this.ring];
  }

  clearRing(): void {
    this.ring = [];
  }

  /** Flush every flushable sink (batch transfer). */
  async flush(): Promise<void> {
    for (const sink of this.sinks) {
      try {
        await sink.flush?.();
      } catch {
        // sink failures should not break the app
      }
    }
  }

  error(message: string, context?: Record<string, unknown>, source?: ErrorSource): void;
  error(event: ErrorEvent): void;
  error(
    eventOrMessage: ErrorEvent | string,
    context?: Record<string, unknown>,
    source?: ErrorSource,
  ): void {
    this.record('error', eventOrMessage, context, source);
  }

  warn(message: string, context?: Record<string, unknown>, source?: ErrorSource): void;
  warn(event: ErrorEvent): void;
  warn(
    eventOrMessage: ErrorEvent | string,
    context?: Record<string, unknown>,
    source?: ErrorSource,
  ): void {
    this.record('warn', eventOrMessage, context, source);
  }

  info(message: string, context?: Record<string, unknown>, source?: ErrorSource): void;
  info(event: ErrorEvent): void;
  info(
    eventOrMessage: ErrorEvent | string,
    context?: Record<string, unknown>,
    source?: ErrorSource,
  ): void {
    this.record('info', eventOrMessage, context, source);
  }

  debug(message: string, context?: Record<string, unknown>, source?: ErrorSource): void;
  debug(event: ErrorEvent): void;
  debug(
    eventOrMessage: ErrorEvent | string,
    context?: Record<string, unknown>,
    source?: ErrorSource,
  ): void {
    this.record('debug', eventOrMessage, context, source);
  }

  private record(
    level: LogLevel,
    eventOrMessage: ErrorEvent | string,
    context?: Record<string, unknown>,
    source?: ErrorSource,
  ): void {
    if (!levelPasses(level, this.threshold)) {
      return;
    }

    const event =
      typeof eventOrMessage === 'string'
        ? this.buildEvent(level, eventOrMessage, context, source)
        : eventOrMessage;

    const record = redactRecord({
      level,
      message: event.message,
      context: event.context,
      source: event.source,
      timestamp: event.timestamp,
    });
    this.pushRing(record);

    const safeEvent: ErrorEvent = {
      ...event,
      message: record.message,
      context: record.context,
    };
    this.broadcast(safeEvent, level);

    this.acceptedSinceFlush += 1;
    if (this.acceptedSinceFlush >= BATCH_FLUSH_EVENT_CAP) {
      this.acceptedSinceFlush = 0;
      void this.flush();
    }
  }

  private buildEvent(
    level: LogLevel,
    message: string,
    context?: Record<string, unknown>,
    source?: ErrorSource,
  ): ErrorEvent {
    return createErrorEvent({
      severity: SEVERITY_BY_LEVEL[level],
      category: 'runtime',
      code: `FRONTEND_${level.toUpperCase()}`,
      message,
      context: context ?? {},
      source: source ?? 'app_shell',
      recoverable: true,
    });
  }

  private pushRing(record: LogRecord): void {
    if (this.ring.length >= LOG_RING_CAP) {
      this.ring.shift();
    }
    this.ring.push(record);
  }

  private broadcast(event: ErrorEvent, level: LogLevel): void {
    for (const sink of this.sinks) {
      try {
        sink.log(event, level);
      } catch {
        // sink failures should not break the app
      }
    }
  }
}

export const logger = new LoggerImpl();
