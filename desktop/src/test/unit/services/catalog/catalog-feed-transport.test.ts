/**
 * Rust-backed catalog fetch transport.
 *
 * standardebooks.org sends no `Access-Control-Allow-Origin`, so the webview
 * cannot read the Atom feed at all. `catalogFeedFetch` replaces the wire, not
 * the policy: a success must still arrive as a `Response` so `fetchWithRetry`
 * keeps owning retry, `Retry-After`, the deadline, and status → catalog code.
 */
import { beforeEach, describe, expect, it, vi } from 'vitest';

vi.mock('$lib/shared/api/invokeWrapper', () => ({
  invoke: vi.fn(),
}));

const { invoke } = await import('$lib/shared/api/invokeWrapper');
const { CATALOG_FETCH_COMMAND, catalogFeedFetch, catalogFetchStatus } =
  await import('$lib/shared/services/catalog/catalogFeedTransport');
const { fetchWithRetry } = await import('$lib/shared/services/catalog/policy');

const FEED_URL = 'https://standardebooks.org/feeds/atom/new-releases';
const BODY = '<?xml version="1.0"?><feed xmlns="http://www.w3.org/2005/Atom"/>';

describe('catalogFeedFetch — invoke round trip', () => {
  beforeEach(() => {
    vi.mocked(invoke).mockReset();
  });

  it('calls the fetchCatalogFeed command and returns the body as a 200 Response', async () => {
    vi.mocked(invoke).mockResolvedValue(BODY);

    const response = await catalogFeedFetch(FEED_URL);

    expect(invoke).toHaveBeenCalledWith(CATALOG_FETCH_COMMAND, { url: FEED_URL });
    expect(CATALOG_FETCH_COMMAND).toBe('fetchCatalogFeed');
    expect(response.ok).toBe(true);
    expect(response.status).toBe(200);
    expect(await response.text()).toBe(BODY);
  });

  it('accepts a Request-shaped input without losing the URL', async () => {
    vi.mocked(invoke).mockResolvedValue(BODY);

    await catalogFeedFetch(new Request(FEED_URL));

    expect(invoke).toHaveBeenCalledWith(CATALOG_FETCH_COMMAND, { url: FEED_URL });
  });

  it('maps a Rust HTTP status error back to a Response so retry policy still applies', async () => {
    vi.mocked(invoke).mockRejectedValue(`CATALOG_FETCH_HTTP_429: catalog feed responded 429`);

    const response = await catalogFeedFetch(FEED_URL);

    expect(response.ok).toBe(false);
    expect(response.status).toBe(429);
  });

  it('lets fetchWithRetry classify a 401 as UPSTREAM_ERROR, not a blank rail', async () => {
    vi.mocked(invoke).mockRejectedValue(
      `CATALOG_FETCH_HTTP_401: catalog feed responded 401 Unauthorized`,
    );

    await expect(fetchWithRetry(FEED_URL, {}, catalogFeedFetch, 50)).rejects.toMatchObject({
      code: 'UPSTREAM_ERROR',
    });
  });

  it('rethrows a transport fault so the caller maps it to NETWORK_ERROR', async () => {
    vi.mocked(invoke).mockRejectedValue('CATALOG_FETCH_NETWORK: catalog request failed');

    await expect(catalogFeedFetch(FEED_URL)).rejects.toBe(
      'CATALOG_FETCH_NETWORK: catalog request failed',
    );
    await expect(fetchWithRetry(FEED_URL, {}, catalogFeedFetch, 50)).rejects.toMatchObject({
      code: 'NETWORK_ERROR',
    });
  });

  it('rejects as soon as the composed deadline aborts, so UPSTREAM_TIMEOUT stays reachable', async () => {
    vi.mocked(invoke).mockImplementation(() => new Promise<string>(() => undefined));
    const controller = new AbortController();

    const pending = catalogFeedFetch(FEED_URL, { signal: controller.signal });
    controller.abort(new Error('catalog deadline of 6ms elapsed'));

    await expect(pending).rejects.toThrow('catalog deadline of 6ms elapsed');
  });
});

describe('catalogFetchStatus', () => {
  it('extracts a 3-digit status from the Rust error string', () => {
    expect(catalogFetchStatus('CATALOG_FETCH_HTTP_503: catalog feed responded 503')).toBe(503);
  });

  it('returns null for anything that is not an HTTP status error', () => {
    expect(catalogFetchStatus('CATALOG_FETCH_NETWORK: boom')).toBeNull();
    expect(catalogFetchStatus('')).toBeNull();
    expect(catalogFetchStatus(new Error('CATALOG_FETCH_HTTPS_REQUIRED: not https'))).toBeNull();
    expect(catalogFetchStatus(undefined)).toBeNull();
  });
});
