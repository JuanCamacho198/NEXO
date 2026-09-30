/**
 * Node/Bun manifest-validator entry point.
 *
 * Fetches an addon manifest over HTTPS and validates it, so CI can gate a
 * submission with a stable exit code. The network layer is an injectable seam
 * (`ManifestFetcher`) so tests can drive every outcome deterministically
 * without real network I/O.
 *
 * Exit-code contract (the CLI wrapper maps the thrown `AddonFetchError.code`):
 *   ADDON_FETCH_HTTPS_REQUIRED   → 1, before any network request
 *   ADDON_FETCH_BAD_CONTENT_TYPE → 1
 *   ADDON_FETCH_TOO_LARGE        → 1, detected while reading, body not buffered
 *   ADDON_FETCH_INVALID_MANIFEST → 1
 *   ADDON_FETCH_NETWORK          → 1 (transport failure; distinct from shape)
 *   valid manifest               → 0
 */

import {
  AddonFetchError,
  AddonFetchErrorCode,
  MAX_MANIFEST_BYTES,
  assertHttpsInstallUrl,
  declaredCapabilities,
  validateManifest,
  type AddonManifest,
} from './validateManifest';

/** Names the project, not a browser identity. */
export const CLI_USER_AGENT = 'NEXO/manifest-validator-cli';

/** Hard cap so CI cannot hang on a host that never answers. */
export const DEFAULT_TIMEOUT_MS = 10_000;

/** Minimal structural view of a `Response` body reader. */
export interface ManifestReader {
  read(): Promise<{ done: boolean; value?: Uint8Array }>;
  cancel?(reason?: unknown): Promise<void>;
}

export interface ManifestBody {
  getReader(): ManifestReader;
}

export interface ManifestResponseHeaders {
  get(name: string): string | null;
}

/** Minimal structural view of a `Response`, so tests can stub it. */
export interface ManifestFetchResponse {
  ok: boolean;
  status: number;
  headers: ManifestResponseHeaders;
  body: ManifestBody | null;
}

export interface ManifestFetchInit {
  headers: Record<string, string>;
  signal: AbortSignal;
}

export type ManifestFetcher = (
  url: string,
  init: ManifestFetchInit,
) => Promise<ManifestFetchResponse>;

export interface FetchAndValidateOptions {
  /** Injectable transport. Defaults to the runtime `fetch`. */
  fetcher?: ManifestFetcher;
  /** Abort the whole request + body read after this many ms. */
  timeoutMs?: number;
}

export interface CliIO {
  out(line: string): void;
  err(line: string): void;
}

const defaultFetcher: ManifestFetcher = (url, init) =>
  (globalThis.fetch as unknown as ManifestFetcher)(url, init);

function errorDetail(err: unknown): string {
  return err instanceof Error ? err.message : String(err);
}

function isAddonFetchError(err: unknown): err is AddonFetchError {
  return err instanceof AddonFetchError;
}

/**
 * Read a response body into a single `Uint8Array`, refusing to buffer more
 * than `maxBytes`.
 *
 * The loop stops the moment accumulated bytes exceed the cap (at most one
 * chunk past it) and then cancels the stream, so an attacker's 500 MB body is
 * never fully materialised. Exceeding the cap is `ADDON_FETCH_TOO_LARGE`; a
 * read that fails mid-stream is `ADDON_FETCH_NETWORK`.
 */
export async function readBodyBounded(
  response: ManifestFetchResponse,
  maxBytes: number = MAX_MANIFEST_BYTES,
): Promise<Uint8Array> {
  const body = response.body;
  if (!body) return new Uint8Array(0);

  const reader = body.getReader();
  const chunks: Uint8Array[] = [];
  let total = 0;

  for (;;) {
    let result: { done: boolean; value?: Uint8Array };
    try {
      result = await reader.read();
    } catch (err) {
      throw new AddonFetchError(
        AddonFetchErrorCode.NETWORK,
        `manifest stream failed: ${errorDetail(err)}`,
      );
    }
    if (result.done) break;

    const chunk = result.value;
    if (!chunk || chunk.byteLength === 0) continue;

    total += chunk.byteLength;
    if (total > maxBytes) {
      // Stop reading and release the connection without draining the body.
      if (reader.cancel) {
        try {
          await reader.cancel();
        } catch {
          // Cancelling a stream that is already failing is not an error here.
        }
      }
      throw new AddonFetchError(
        AddonFetchErrorCode.TOO_LARGE,
        `manifest exceeds ${maxBytes} bytes`,
      );
    }
    chunks.push(chunk);
  }

  const bytes = new Uint8Array(total);
  let offset = 0;
  for (const chunk of chunks) {
    bytes.set(chunk, offset);
    offset += chunk.byteLength;
  }
  return bytes;
}

/**
 * Fetch `url` over HTTPS and validate the body as an addon manifest.
 *
 * Order mirrors `validateManifest`: HTTPS gate (pre-I/O) → transport →
 * bounded read (size cap) → content type → JSON parse → shape.
 * Transport failures, non-2xx responses and timeouts are `ADDON_FETCH_NETWORK`
 * and are never conflated with an invalid manifest.
 */
export async function fetchAndValidateManifest(
  url: string,
  options: FetchAndValidateOptions = {},
): Promise<AddonManifest> {
  const fetcher = options.fetcher ?? defaultFetcher;
  const timeoutMs = options.timeoutMs ?? DEFAULT_TIMEOUT_MS;

  // Reject non-https before any network I/O. Do not move this below the fetch.
  assertHttpsInstallUrl(url);

  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), timeoutMs);
  try {
    let response: ManifestFetchResponse;
    try {
      response = await fetcher(url, {
        headers: {
          'user-agent': CLI_USER_AGENT,
          accept: 'application/json',
        },
        signal: controller.signal,
      });
    } catch (err) {
      if (isAddonFetchError(err)) throw err;
      const detail = controller.signal.aborted
        ? `request timed out after ${timeoutMs}ms: ${url}`
        : `request failed: ${errorDetail(err)}`;
      throw new AddonFetchError(AddonFetchErrorCode.NETWORK, detail);
    }

    if (!response.ok) {
      throw new AddonFetchError(
        AddonFetchErrorCode.NETWORK,
        `request failed with HTTP ${response.status}: ${url}`,
      );
    }

    const contentType = response.headers.get('content-type');
    const bytes = await readBodyBounded(response, MAX_MANIFEST_BYTES);
    return validateManifest(bytes, contentType);
  } finally {
    clearTimeout(timer);
  }
}

function summaryLines(manifest: AddonManifest, url: string): string[] {
  const lines = [
    `Valid manifest: ${url}`,
    `id: ${manifest.id}`,
    `name: ${manifest.name}`,
    `version: ${manifest.version}`,
    `catalogs: ${manifest.catalogs.length}`,
  ];
  const capabilities = declaredCapabilities(manifest);
  if (capabilities.length > 0) {
    lines.push(`capabilities: ${capabilities.join(', ')}`);
  }
  return lines;
}

/**
 * Run the CLI against `argv` (arguments after the script name) and return the
 * process exit code. Writing is routed through `io` so tests can capture it.
 */
export async function runValidateCli(
  argv: string[],
  io: CliIO,
  options: FetchAndValidateOptions = {},
): Promise<number> {
  const url = argv.find((arg) => arg !== '--' && arg.length > 0);
  if (!url) {
    io.err('usage: bun run --cwd packages/manifest-validator validate -- <manifest-url>');
    return 1;
  }

  try {
    const manifest = await fetchAndValidateManifest(url, options);
    for (const line of summaryLines(manifest, url)) io.out(line);
    return 0;
  } catch (err) {
    // `AddonFetchError.message` is `CODE: detail`, so this prints the stable
    // code a human reads on stderr; the exit code is what CI reads.
    const addonError = isAddonFetchError(err)
      ? err
      : new AddonFetchError(AddonFetchErrorCode.NETWORK, errorDetail(err));
    io.err(addonError.message);
    return 1;
  }
}
