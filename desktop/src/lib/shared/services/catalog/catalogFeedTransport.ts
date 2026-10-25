/**
 * Catalog feed transport: the Rust `fetchCatalogFeed` command, shaped as `fetch`.
 *
 * standardebooks.org sends no `Access-Control-Allow-Origin`, so a webview
 * `fetch` of the Atom feed is blocked before a single byte arrives — the rail
 * could be routed perfectly and still render nothing. The command performs the
 * download in Rust (no CORS) with the `update_feed` posture: HTTPS-only,
 * bounded redirects, explicit timeouts, typed error codes.
 *
 * This module deliberately keeps the `typeof fetch` boundary the datasources
 * already use, so `fetchWithRetry` remains the single place that classifies a
 * catalog failure (retry, `Retry-After`, deadline, status → catalog code).
 * Only the transport changes; no datasource loses its retry or timeout budget.
 */
import { invoke } from '$lib/shared/api/invokeWrapper';

export const CATALOG_FETCH_COMMAND = 'fetchCatalogFeed';

/** The media type the feed actually serves; Rust sends the full Accept list. */
const ATOM_FEED_CONTENT_TYPE = 'application/atom+xml';

/**
 * Rust reports a non-2xx status as `CATALOG_FETCH_HTTP_{status}: {detail}`
 * (this is the `Display` impl of its error type). Parsing the status back out
 * lets the shim hand `fetchWithRetry` a real `Response`, so 429/5xx still retry
 * and 401/404 still map to the catalog code they always did.
 */
const HTTP_STATUS_RE = /CATALOG_FETCH_HTTP_(\d{3})/;

function requestUrl(input: RequestInfo | URL): string {
  if (typeof input === 'string') return input;
  if (input instanceof URL) return input.toString();
  return input.url;
}

/** The status embedded in a Rust error string, or null for a transport fault. */
export function catalogFetchStatus(error: unknown): number | null {
  const raw = typeof error === 'string' ? error : error instanceof Error ? error.message : '';
  const match = HTTP_STATUS_RE.exec(raw);
  if (match === null) return null;
  const status = Number.parseInt(match[1] ?? '', 10);
  return Number.isInteger(status) && status >= 100 && status <= 599 ? status : null;
}

/** Rejection that mirrors a real `fetch` abort, keeping the caller's reason. */
function abortReason(signal: AbortSignal): unknown {
  return signal.reason ?? new DOMException('catalog feed fetch aborted', 'AbortError');
}

/**
 * `invoke` cannot be cancelled, so the deadline is honoured by racing it: once
 * the composed signal aborts, the attempt rejects immediately and
 * `composeDeadline().timedOut()` turns it into `UPSTREAM_TIMEOUT` as usual.
 * The Rust request is bounded by its own 30 s timeout, so it cannot outlive the
 * rail by much even when nobody is listening for the answer.
 */
function invokeFeed(url: string, signal: AbortSignal | null | undefined): Promise<string> {
  if (!signal) return invoke<string>(CATALOG_FETCH_COMMAND, { url });
  const abortSignal = signal;
  if (abortSignal.aborted) return Promise.reject(abortReason(abortSignal));
  return new Promise<string>((resolve, reject) => {
    const onAbort = (): void => reject(abortReason(abortSignal));
    abortSignal.addEventListener('abort', onAbort, { once: true });
    invoke<string>(CATALOG_FETCH_COMMAND, { url }).then(
      (body) => {
        abortSignal.removeEventListener('abort', onAbort);
        resolve(body);
      },
      (error: unknown) => {
        abortSignal.removeEventListener('abort', onAbort);
        reject(error);
      },
    );
  });
}

/**
 * `typeof fetch`-compatible transport. A success becomes a 200 `Response`
 * carrying the raw Atom body (the parser stays in TS); a Rust HTTP-status error
 * becomes a `Response` with that status so `fetchWithRetry` classifies it
 * exactly like a direct upstream response.
 */
export const catalogFeedFetch: typeof fetch = async (
  input: RequestInfo | URL,
  init?: RequestInit,
): Promise<Response> => {
  const url = requestUrl(input);
  try {
    const body = await invokeFeed(url, init?.signal);
    return new Response(body, {
      status: 200,
      headers: { 'content-type': ATOM_FEED_CONTENT_TYPE },
    });
  } catch (error) {
    // An abort must reach the caller unchanged: only the deadline's own timer
    // may become UPSTREAM_TIMEOUT, never a caller cancel.
    if (init?.signal?.aborted === true) throw error;
    const status = catalogFetchStatus(error);
    if (status === null) throw error;
    return new Response('', { status });
  }
};
