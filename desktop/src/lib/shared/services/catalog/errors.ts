/**
 * Stable catalog error codes shared by desktop and Android.
 * Messages are redacted at the boundary; only `code` is contractual.
 */
export type CatalogErrorCode =
  | 'INVALID_PAGE'
  | 'UNAVAILABLE_DOWNLOAD'
  | 'NOT_FOUND'
  | 'RATE_LIMITED'
  | 'UPSTREAM_ERROR'
  | 'UPSTREAM_TIMEOUT'
  | 'NETWORK_ERROR'
  | 'CONSENT_REQUIRED';

export class CatalogError extends Error {
  readonly code: CatalogErrorCode;
  readonly retryable: boolean;

  constructor(code: CatalogErrorCode, message: string, retryable = false) {
    super(message);
    this.name = 'CatalogError';
    this.code = code;
    this.retryable = retryable;
  }
}

/** Build a typed catalog error without leaking upstream details. */
export function catalogError(code: CatalogErrorCode, detail?: string): CatalogError {
  // UPSTREAM_TIMEOUT is retryable (the source was slow, not unreachable), but it
  // is deliberately NOT the offline code: `isOfflineCatalogCode` keys on
  // NETWORK_ERROR alone, so a timeout renders as a slow-source error.
  const retryable =
    code === 'RATE_LIMITED' || code === 'NETWORK_ERROR' || code === 'UPSTREAM_TIMEOUT';
  return new CatalogError(code, detail ? `${code}: ${detail}` : code, retryable);
}

export function isCatalogError(err: unknown): err is CatalogError {
  return err instanceof CatalogError;
}

/**
 * Code-level view of the same retryable classification `catalogError` applies,
 * for callers that kept only the code (e.g. the Discover rail machine). Derived
 * from `catalogError` so there is a single source of truth, never a second list.
 */
export function isRetryableCatalogCode(code: CatalogErrorCode): boolean {
  return catalogError(code).retryable;
}

/** Map an upstream HTTP status to a stable contract code. */
export function mapHttpStatusToCode(status: number): CatalogErrorCode {
  if (status === 404) return 'NOT_FOUND';
  if (status === 429) return 'RATE_LIMITED';
  if (status >= 500) return 'UPSTREAM_ERROR';
  return 'UPSTREAM_ERROR';
}
