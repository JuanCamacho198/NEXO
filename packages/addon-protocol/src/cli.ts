/**
 * Node/Bun v3 manifest entry point.
 *
 * Same transport contract as `@nexo/manifest-validator`'s CLI — HTTPS gate,
 * timeout, bounded read, stable exit codes — but the shape check runs through
 * `loadManifest`, so a v3 manifest is validated as v3 instead of being rejected
 * for shapes v2 never had (`types`, object `resources`, and an empty `catalogs`
 * array for a stream-only addon). A v2 manifest still passes, adapted on the way
 * in, so the submission workflow never refuses a manifest the client installs.
 *
 * Exit-code contract (the thrown `AddonFetchError.code` maps to exit 1):
 *   ADDON_FETCH_HTTPS_REQUIRED   → 1, before any network request
 *   ADDON_FETCH_BAD_CONTENT_TYPE → 1
 *   ADDON_FETCH_TOO_LARGE        → 1, detected while reading, body not buffered
 *   ADDON_FETCH_INVALID_MANIFEST → 1
 *   ADDON_FETCH_UNSUPPORTED_TYPE → 1 (a reserved type was declared)
 *   ADDON_FETCH_NETWORK          → 1 (transport failure; distinct from shape)
 *   valid manifest               → 0
 */

import {
  AddonFetchError,
  AddonFetchErrorCode,
  MAX_MANIFEST_BYTES,
  assertHttpsInstallUrl,
} from '@nexo/manifest-validator';
import {
  CLI_USER_AGENT,
  DEFAULT_TIMEOUT_MS,
  readBodyBounded,
  type ManifestFetcher,
  type ManifestFetchResponse,
} from '@nexo/manifest-validator/cli';
import { loadManifest } from './legacy';
import type { AddonManifestV3 } from './manifest';

export { CLI_USER_AGENT, DEFAULT_TIMEOUT_MS };

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
 * Fetch `url` over HTTPS and validate it as a v2 or v3 manifest.
 *
 * Order mirrors the v2 CLI: HTTPS gate (pre-I/O) → transport → bounded read
 * (size cap) → content type → JSON parse → shape. The last step is the only
 * difference: it dispatches on manifest shape instead of assuming v2.
 */
export async function fetchAndValidateManifest(
  url: string,
  options: FetchAndValidateOptions = {},
): Promise<AddonManifestV3> {
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
    return loadManifest(bytes, contentType);
  } finally {
    clearTimeout(timer);
  }
}

function summaryLines(manifest: AddonManifestV3, url: string): string[] {
  const resources = manifest.resources.map((entry) => entry.name);
  const lines = [
    `Valid manifest: ${url}`,
    `id: ${manifest.id}`,
    `name: ${manifest.name}`,
    `version: ${manifest.version}`,
    `types: ${manifest.types.join(', ')}`,
    `catalogs: ${manifest.catalogs.length}`,
    `resources: ${resources.join(', ')}`,
  ];
  const shape = manifest.nexo?.shape;
  if (shape !== undefined) lines.push(`shape: ${shape}`);
  const templates = Object.keys(manifest.nexo?.templates ?? {});
  if (templates.length > 0) lines.push(`templates: ${templates.join(', ')}`);
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
    io.err('usage: bun run --cwd packages/addon-protocol validate -- <manifest-url>');
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

// Executed directly (`bun src/cli.ts -- <url>`, or the `validate` script) this
// wires stdio and sets the exit code; imported by tests it stays side-effect free.
if (import.meta.main) {
  process.exitCode = await runValidateCli(process.argv.slice(2), {
    out: (line) => process.stdout.write(`${line}\n`),
    err: (line) => process.stderr.write(`${line}\n`),
  });
}
