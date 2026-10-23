import { describe, expect, it } from 'vitest';
import { AddonFetchErrorCode } from '@nexo/manifest-validator';
import type {
  ManifestFetcher,
  ManifestFetchResponse,
  ManifestReader,
} from '@nexo/manifest-validator/cli';
import { CLI_USER_AGENT, fetchAndValidateManifest, runValidateCli } from '../src/cli';

// A stream-only addon: no catalogs at all. The v2 validator rejects this shape,
// which is exactly why the submission gate moved to the v3 parser.
const STREAM_ONLY_MANIFEST = {
  id: 'thepiratebay-plus',
  name: 'ThePirateBay+',
  version: '3.0.0',
  types: ['book'],
  catalogs: [],
  resources: [{ name: 'stream', types: ['book'], idPrefixes: ['isbn:'] }],
};

const LEGACY_V2_MANIFEST = {
  id: 'legacy-books',
  name: 'Legacy Books',
  version: '1.0.0',
  catalogs: [{ type: 'book-catalog', id: 'main', name: 'Main' }],
  resources: ['search', 'book-details'],
  searchUrl: 'https://example.com/search?q={query}&page={page}',
  detailsUrl: 'https://example.com/book/{bookId}',
};

const URL_OK = 'https://example.com/manifest.json';

function encode(value: unknown): Uint8Array {
  return new TextEncoder().encode(typeof value === 'string' ? value : JSON.stringify(value));
}

function responseFromBytes(
  bytes: Uint8Array,
  contentType: string | null,
  init: { ok?: boolean; status?: number } = {},
): ManifestFetchResponse {
  let sent = false;
  const reader: ManifestReader = {
    async read() {
      if (sent) return { done: true, value: undefined };
      sent = true;
      return { done: false, value: bytes };
    },
    async cancel() {},
  };
  return {
    ok: init.ok ?? true,
    status: init.status ?? 200,
    headers: { get: (name) => (name.toLowerCase() === 'content-type' ? contentType : null) },
    body: { getReader: () => reader },
  };
}

function jsonResponse(body: unknown, contentType = 'application/json'): ManifestFetchResponse {
  return responseFromBytes(encode(body), contentType);
}

function fetcherReturning(response: ManifestFetchResponse): ManifestFetcher {
  return async () => response;
}

function captureIO(): {
  stdout: string[];
  stderr: string[];
  io: { out(line: string): void; err(line: string): void };
} {
  const stdout: string[] = [];
  const stderr: string[] = [];
  return {
    stdout,
    stderr,
    io: { out: (line) => stdout.push(line), err: (line) => stderr.push(line) },
  };
}

describe('v3 validate CLI', () => {
  it('accepts a stream-only manifest with an empty catalogs array', async () => {
    const { io, stdout, stderr } = captureIO();
    const code = await runValidateCli(['--', URL_OK], io, {
      fetcher: fetcherReturning(jsonResponse(STREAM_ONLY_MANIFEST)),
    });

    expect(stderr).toEqual([]);
    expect(code).toBe(0);
    expect(stdout.join('\n')).toContain('thepiratebay-plus');
    expect(stdout.join('\n')).toContain('stream');
  });

  it('sends the project user agent and the requested URL', async () => {
    const seen: { url?: string; userAgent?: string } = {};
    const fetcher: ManifestFetcher = async (url, init) => {
      seen.url = url;
      seen.userAgent = init.headers['user-agent'];
      return jsonResponse(STREAM_ONLY_MANIFEST);
    };

    await fetchAndValidateManifest(URL_OK, { fetcher });

    expect(seen.url).toBe(URL_OK);
    expect(seen.userAgent).toBe(CLI_USER_AGENT);
  });

  it('still accepts a v2 manifest by adapting it', async () => {
    const { io, stdout } = captureIO();
    const code = await runValidateCli(['--', URL_OK], io, {
      fetcher: fetcherReturning(jsonResponse(LEGACY_V2_MANIFEST)),
    });

    expect(code).toBe(0);
    // Adapted resources are v3 names, never the v2 endpoint vocabulary.
    expect(stdout.join('\n')).toContain('legacy-v2');
  });

  it('rejects a reserved type with UNSUPPORTED_TYPE', async () => {
    const { io, stderr } = captureIO();
    const code = await runValidateCli(['--', URL_OK], io, {
      fetcher: fetcherReturning(
        jsonResponse({ ...STREAM_ONLY_MANIFEST, types: ['audiobook'] }),
      ),
    });

    expect(code).toBe(1);
    expect(stderr.join('\n')).toContain(AddonFetchErrorCode.UNSUPPORTED_TYPE);
  });

  it('rejects a v3 manifest with no resources as INVALID_MANIFEST', async () => {
    const { io, stderr } = captureIO();
    const code = await runValidateCli(['--', URL_OK], io, {
      fetcher: fetcherReturning(jsonResponse({ ...STREAM_ONLY_MANIFEST, resources: [] })),
    });

    expect(code).toBe(1);
    expect(stderr.join('\n')).toContain(AddonFetchErrorCode.INVALID_MANIFEST);
  });

  it('rejects a non-JSON content type before parsing', async () => {
    const { io, stderr } = captureIO();
    const code = await runValidateCli(['--', URL_OK], io, {
      fetcher: fetcherReturning(jsonResponse(STREAM_ONLY_MANIFEST, 'text/html')),
    });

    expect(code).toBe(1);
    expect(stderr.join('\n')).toContain(AddonFetchErrorCode.BAD_CONTENT_TYPE);
  });

  it('rejects a non-https URL without any network request', async () => {
    let fetched = false;
    const fetcher: ManifestFetcher = async () => {
      fetched = true;
      return jsonResponse(STREAM_ONLY_MANIFEST);
    };
    const { io, stderr } = captureIO();

    const code = await runValidateCli(['--', 'http://example.com/manifest.json'], io, { fetcher });

    expect(fetched).toBe(false);
    expect(code).toBe(1);
    expect(stderr.join('\n')).toContain(AddonFetchErrorCode.HTTPS_REQUIRED);
  });

  it('maps a transport failure to NETWORK, never to an invalid manifest', async () => {
    const { io, stderr } = captureIO();
    const code = await runValidateCli(['--', URL_OK], io, {
      fetcher: async () => {
        throw new Error('socket hang up');
      },
    });

    expect(code).toBe(1);
    expect(stderr.join('\n')).toContain(AddonFetchErrorCode.NETWORK);
    expect(stderr.join('\n')).not.toContain(AddonFetchErrorCode.INVALID_MANIFEST);
  });

  it('prints usage and exits 1 without a URL', async () => {
    const { io, stderr } = captureIO();
    const code = await runValidateCli(['--'], io);

    expect(code).toBe(1);
    expect(stderr.join('\n')).toContain('usage:');
  });
});
