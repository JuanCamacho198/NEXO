export type MetricName =
  | 'app_cold_start'
  | 'reader_open'
  | 'reader_ttfp_web'
  | 'reader_ttfp_native'
  | 'sync_flush'
  | 'outbox_depth'
  | 'book_import'
  | 'ipc_call'
  // Retained DebugPanel counters (no P0 instrument egresses them yet).
  | 'import_start'
  | 'import_complete'
  | 'sync_start'
  | 'sync_complete';

export interface MetricEvent {
  id: string;
  sessionId: string;
  timestamp: string;
  name: MetricName;
  /** Exact duration, kept for the in-app debug panel. Never leaves the process. */
  durationMs?: number;
  /**
   * Bucketed duration — what the egress sink actually sends. Bucketing happens at
   * the instrumentation site so an exact value cannot reach Sentry.
   */
  bucketedDurationMs?: number;
  /** Low-cardinality tags forwarded to Sentry as metric attributes. */
  tags?: Record<string, string>;
  feature?: string;
  count: number;
  success: boolean;
  errorCode?: string;
}

export const METRIC_NAMES = {
  APP_COLD_START: 'app_cold_start',
  READER_OPEN: 'reader_open',
  READER_TTFP_WEB: 'reader_ttfp_web',
  READER_TTFP_NATIVE: 'reader_ttfp_native',
  SYNC_FLUSH: 'sync_flush',
  OUTBOX_DEPTH: 'outbox_depth',
  BOOK_IMPORT: 'book_import',
  IPC_CALL: 'ipc_call',
  IMPORT_START: 'import_start',
  IMPORT_COMPLETE: 'import_complete',
  SYNC_START: 'sync_start',
  SYNC_COMPLETE: 'sync_complete',
} as const;

/**
 * Shared metric vocabulary (metric-vocabulary spec). The Kotlin mirror lives
 * in `android/app/src/main/java/com/nexo/debug/MetricVocabulary.kt`;
 * `metricVocabulary.lockstep.test.ts` fails the build on drift.
 */
export const SHARED_METRIC_VOCABULARY: ReadonlyArray<string> = [
  'app_cold_start',
  'reader_open',
  'reader_ttfp_web',
  'reader_ttfp_native',
  'sync_flush',
  'outbox_depth',
  'book_import',
  'ipc_call',
];

/**
 * Tag contract (metric-vocabulary spec): every emitted metric tag key is in
 * TAG_DOMAINS and every value within the enumerated domain.
 */
export const TAG_DOMAINS = {
  source: ['reader', 'app_shell', 'sync', 'import'],
  format: ['epub', 'pdf'],
  platform: ['desktop', 'android'],
  engine: ['epubjs', 'readium', 'pdfjs'],
} as const;

export type MetricTagKey = keyof typeof TAG_DOMAINS | 'feature' | 'event';

export const METRIC_TAG_KEYS: ReadonlyArray<string> = [
  'source',
  'event',
  'feature',
  'format',
  'platform',
  'engine',
];
