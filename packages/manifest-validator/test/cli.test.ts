import { describe, expect, it } from 'vitest';
import {
  AddonFetchError,
  AddonFetchErrorCode,
  MAX_MANIFEST_BYTES,
} from '@nexo/manifest-validator';
import {
  CLI_USER_AGENT,
  fetchAndValidateManifest,
  readBodyBounded,
  runValidateCli,
  type ManifestFetcher,
  type ManifestFetchResponse,
  type ManifestReader,
} from '../src/cli';

const VALID_MANIFEST = {
  id: 'example-books',
  name: 'Example Books',
  version: '1.0.0',
  catalogs: [{ type: 'book-catalog', id: 'main', name: 'Example Catalog' }],
  resources: ['search', 'book-details'],
};

const URL_OK = 'https://example.com/manifest.json';

function encode(value: unknown): Uint8Array {
  return new TextEncoder().encode(typeof value === 'string' ? value : JSON.stringify(value));
}

function jsonResponse(body: unknown, contentType = 'application/json'): ManifestFetchResponse {
  return responseFromBytes(encode(body), contentType);
}

function responseFromBytes(
  bytes: Uint8Array,
  contentType: string | null,
  init: { ok?: boolean; status?: number } = {},
): ManifestFetchResponse {
  return responseFromChunks([bytes], contentType, init);
}

function responseFromChunks(
  chunks: Uint8Array[],
  contentType: string | null,
  init: { ok?: boolean; status?: number } = {},
): ManifestFetchResponse {
  let index = 0;
  const reader: ManifestReader = {
    async read() {
      if (index >= chunks.length) return { done: true, value: undefined };
      return { done: false, value: chunks[index++] };
    },
    async cancel() {},
  };
  return {
    ok: init.ok ?? true,
    status: init.status ?? 200,
    headers: {
      get: (name) => (name.toLowerCase() === 'content-type' ? contentType : null),
    },
    body: { getReader: () => reader },
  };
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
    io: {
      out: (line) => stdout.push(line),
      err: (line) => stderr.push(line),
    },
  };
}

async function codeOf(promise: Promise<unknown>): Promise<string> {
  try {
    await promise;
    throw new Error('expected promise to reject');
  } catch (err) {
    expect(err).toBeInstanceOf(AddonFetchError);
    return (err as AddonFetchError).code;
  }
}

describe('readBodyBounded', () => {
  it('accepts a body of exactly MAX_MANIFEST_BYTES', async () => {
    const bytes = await readBodyBounded(
      responseFromBytes(new Uint8Array(MAX_MANIFEST_BYTES), 'application/json'),
      MAX_MANIFEST_BYTES,
    );
    expect(bytes.byteLength).toBe(MAX_MANIFEST_BYTES);
  });

  it('rejects one byte over the cap with TOO_LARGE', async () => {
    const code = await codeOf(
      readBodyBounded(
        responseFromBytes(new Uint8Array(MAX_MANIFEST_BYTES + 1), 'application/json'),
        MAX_MANIFEST_BYTES,
      ),
    );
    expect(code).toBe(AddonFetchErrorCode.TOO_LARGE);
  });

  it('treats a missing body as zero bytes', async () => {
    const bytes = await readBodyBounded(
      { ok: true, status: 200, headers: { get: () => 'application/json' }, body: null },
      MAX_MANIFEST_BYTES,
    );
    expect(bytes.byteLength).toBe(0);
  });

  it('maps a mid-stream read error to NETWORK', async () => {
    const reader: ManifestReader = {
      async read() {
        throw new Error('socket closed');
      },
    };
    const response: ManifestFetchResponse = {
      ok: true,
      status: 200,
      headers: { get: () => 'application/json' },
      body: { getReader: () => reader },
    };
    expect(await codeOf(readBodyBounded(response, MAX_MANIFEST_BYTES))).toBe(
      AddonFetchErrorCode.NETWORK,
    );
  });

  // Regression guard: an attacker-sized body must stop the read loop at the
  // cap and cancel the stream, never draining (and buffering) the payload.
  it('stops reading and cancels the stream at the cap without draining it', async () => {
    const chunk = new Uint8Array(1024);
    const stats = { reads: 0, cancelled: 0 };
    const reader: ManifestReader = {
      async read() {
        stats.reads += 1;
        if (stats.reads > 10_000) {
          throw new Error('safety bound exceeded: read loop did not stop at the cap');
        }
        return { done: false, value: chunk };
      },
      async cancel() {
        stats.cancelled += 1;
      },
    };
    const response: ManifestFetchResponse = {
      ok: true,
      status: 200,
      headers: { get: () => 'application/json' },
      body: { getReader: () => reader },
    };

    expect(await codeOf(readBodyBounded(response, MAX_MANIFEST_BYTES))).toBe(
      AddonFetchErrorCode.TOO_LARGE,
    );
    // 64 chunks fill the cap; the 65th crosses it and stops the loop.
    expect(stats.reads).toBeLessThanOrEqual(66);
    expect(stats.cancelled).toBe(1);
  });
});

describe('fetchAndValidateManifest', () => {
  it('returns the parsed manifest for a valid https response', async () => {
    const manifest = await fetchAndValidateManifest(URL_OK, {
      fetcher: fetcherReturning(jsonResponse(VALID_MANIFEST)),
    });
    expect(manifest.id).toBe('example-books');
    expect(manifest.catalogs).toHaveLength(1);
  });

  it('sends the project User-Agent and an Accept header', async () => {
    let seenInit: { headers: Record<string, string> } | undefined;
    const fetcher: ManifestFetcher = async (_url, init) => {
      seenInit = { headers: init.headers };
      return jsonResponse(VALID_MANIFEST);
    };
    await fetchAndValidateManifest(URL_OK, { fetcher });
    expect(seenInit?.headers['user-agent']).toBe(CLI_USER_AGENT);
    expect(seenInit?.headers['user-agent']).toContain('NEXO');
    expect(seenInit?.headers.accept).toBe('application/json');
  });

  // Regression guard: the https gate runs before any network call.
  it('rejects a non-https URL without ever invoking the fetcher', async () => {
    let calls = 0;
    const fetcher: ManifestFetcher = async () => {
      calls += 1;
      return jsonResponse(VALID_MANIFEST);
    };
    expect(await codeOf(fetchAndValidateManifest('http://example.com/manifest.json', { fetcher }))).toBe(
      AddonFetchErrorCode.HTTPS_REQUIRED,
    );
    expect(calls).toBe(0);
  });

  it('rejects a non-JSON content type with BAD_CONTENT_TYPE', async () => {
    const response = responseFromBytes(encode(VALID_MANIFEST), 'text/plain; charset=utf-8');
    expect(await codeOf(fetchAndValidateManifest(URL_OK, { fetcher: fetcherReturning(response) }))).toBe(
      AddonFetchErrorCode.BAD_CONTENT_TYPE,
    );
  });

  it('rejects malformed JSON with INVALID_MANIFEST', async () => {
    const response = responseFromBytes(encode('{not json'), 'application/json');
    expect(await codeOf(fetchAndValidateManifest(URL_OK, { fetcher: fetcherReturning(response) }))).toBe(
      AddonFetchErrorCode.INVALID_MANIFEST,
    );
  });

  it('rejects a non-object JSON body with INVALID_MANIFEST', async () => {
    for (const body of ['[]', '42', '"a string"']) {
      const response = responseFromBytes(encode(body), 'application/json');
      expect(
        await codeOf(fetchAndValidateManifest(URL_OK, { fetcher: fetcherReturning(response) })),
      ).toBe(AddonFetchErrorCode.INVALID_MANIFEST);
    }
  });

  it('rejects a manifest with missing required fields with INVALID_MANIFEST', async () => {
    const response = jsonResponse({ ...VALID_MANIFEST, version: undefined });
    expect(await codeOf(fetchAndValidateManifest(URL_OK, { fetcher: fetcherReturning(response) }))).toBe(
      AddonFetchErrorCode.INVALID_MANIFEST,
    );
  });

  it('rejects an oversize streamed body with TOO_LARGE and bounded reads', async () => {
    const chunk = new Uint8Array(1024);
    const stats = { reads: 0 };
    const reader: ManifestReader = {
      async read() {
        stats.reads += 1;
        if (stats.reads > 10_000) throw new Error('safety bound exceeded');
        return { done: false, value: chunk };
      },
      async cancel() {},
    };
    const response: ManifestFetchResponse = {
      ok: true,
      status: 200,
      headers: { get: () => 'application/json' },
      body: { getReader: () => reader },
    };
    expect(await codeOf(fetchAndValidateManifest(URL_OK, { fetcher: fetcherReturning(response) }))).toBe(
      AddonFetchErrorCode.TOO_LARGE,
    );
    expect(stats.reads).toBeLessThanOrEqual(66);
  });

  it('maps a non-2xx response to NETWORK', async () => {
    const response = responseFromBytes(encode(VALID_MANIFEST), 'application/json', {
      ok: false,
      status: 503,
    });
    const code = await codeOf(fetchAndValidateManifest(URL_OK, { fetcher: fetcherReturning(response) }));
    expect(code).toBe(AddonFetchErrorCode.NETWORK);
  });

  it('maps a transport rejection to NETWORK', async () => {
    const fetcher: ManifestFetcher = async () => {
      throw new Error('getaddrinfo ENOTFOUND example.invalid');
    };
    expect(await codeOf(fetchAndValidateManifest(URL_OK, { fetcher }))).toBe(
      AddonFetchErrorCode.NETWORK,
    );
  });

  it('maps a host that never answers to NETWORK after the timeout', async () => {
    const fetcher: ManifestFetcher = (_url, init) =>
      new Promise((_resolve, reject) => {
        init.signal.addEventListener('abort', () => {
          const err = new Error('aborted');
          err.name = 'AbortError';
          reject(err);
        });
      });
    const code = await codeOf(fetchAndValidateManifest(URL_OK, { fetcher, timeoutMs: 5 }));
    expect(code).toBe(AddonFetchErrorCode.NETWORK);
  });

  // Regression guard: "unreachable" must not collapse into "invalid manifest".
  it('keeps NETWORK distinct from INVALID_MANIFEST', async () => {
    const networkFetcher: ManifestFetcher = async () => {
      throw new Error('offline');
    };
    const invalidFetcher = fetcherReturning(responseFromBytes(encode('{oops'), 'application/json'));

    const networkCode = await codeOf(fetchAndValidateManifest(URL_OK, { fetcher: networkFetcher }));
    const invalidCode = await codeOf(fetchAndValidateManifest(URL_OK, { fetcher: invalidFetcher }));

    expect(networkCode).toBe(AddonFetchErrorCode.NETWORK);
    expect(invalidCode).toBe(AddonFetchErrorCode.INVALID_MANIFEST);
    expect(networkCode).not.toBe(invalidCode);
  });
});

describe('runValidateCli', () => {
  it('exits 0 and prints a summary for a valid manifest', async () => {
    const { io, stdout, stderr } = captureIO();
    const manifest = {
      ...VALID_MANIFEST,
      resolveUrl: 'https://example.com/resolve?isbn={isbn}',
      capabilities: ['resolve'],
    };
    const exitCode = await runValidateCli([URL_OK], io, {
      fetcher: fetcherReturning(jsonResponse(manifest)),
    });

    expect(exitCode).toBe(0);
    expect(stderr).toEqual([]);
    const output = stdout.join('\n');
    expect(output).toContain('id: example-books');
    expect(output).toContain('name: Example Books');
    expect(output).toContain('version: 1.0.0');
    expect(output).toContain('catalogs: 1');
    expect(output).toContain('capabilities: resolve');
  });

  it('omits the capabilities line when the manifest declares none', async () => {
    const { io, stdout } = captureIO();
    const exitCode = await runValidateCli([URL_OK], io, {
      fetcher: fetcherReturning(jsonResponse(VALID_MANIFEST)),
    });
    expect(exitCode).toBe(0);
    expect(stdout.some((line) => line.startsWith('capabilities:'))).toBe(false);
  });

  // Regression guard: http must fail closed with no fetch attempt at all.
  it('exits 1 for a non-https URL with no fetch call', async () => {
    const { io, stderr } = captureIO();
    let calls = 0;
    const fetcher: ManifestFetcher = async () => {
      calls += 1;
      return jsonResponse(VALID_MANIFEST);
    };
    const exitCode = await runValidateCli(['http://example.com/manifest.json'], io, { fetcher });

    expect(exitCode).toBe(1);
    expect(calls).toBe(0);
    expect(stderr.join('\n')).toContain('ADDON_FETCH_HTTPS_REQUIRED');
  });

  it('exits 1 and prints BAD_CONTENT_TYPE on stderr for a non-JSON body', async () => {
    const { io, stdout, stderr } = captureIO();
    const exitCode = await runValidateCli([URL_OK], io, {
      fetcher: fetcherReturning(responseFromBytes(encode('User-agent: *'), 'text/plain')),
    });

    expect(exitCode).toBe(1);
    expect(stdout).toEqual([]);
    expect(stderr.join('\n')).toContain('ADDON_FETCH_BAD_CONTENT_TYPE');
  });

  it('exits 1 and prints TOO_LARGE for an oversize body', async () => {
    const { io, stderr } = captureIO();
    const exitCode = await runValidateCli([URL_OK], io, {
      fetcher: fetcherReturning(
        responseFromBytes(new Uint8Array(MAX_MANIFEST_BYTES + 1), 'application/json'),
      ),
    });
    expect(exitCode).toBe(1);
    expect(stderr.join('\n')).toContain('ADDON_FETCH_TOO_LARGE');
  });

  it('exits 1 and prints INVALID_MANIFEST for a bad shape', async () => {
    const { io, stderr } = captureIO();
    const exitCode = await runValidateCli([URL_OK], io, {
      fetcher: fetcherReturning(jsonResponse({ id: 'x', name: 'X', version: '1.0.0' })),
    });
    expect(exitCode).toBe(1);
    expect(stderr.join('\n')).toContain('ADDON_FETCH_INVALID_MANIFEST');
  });

  it('exits 1 and prints NETWORK when the transport fails', async () => {
    const { io, stderr } = captureIO();
    const fetcher: ManifestFetcher = async () => {
      throw new Error('TLS handshake failed');
    };
    const exitCode = await runValidateCli([URL_OK], io, { fetcher });
    expect(exitCode).toBe(1);
    expect(stderr.join('\n')).toContain('ADDON_FETCH_NETWORK');
    expect(stderr.join('\n')).not.toContain('ADDON_FETCH_INVALID_MANIFEST');
  });

  it('exits 1 with usage when no URL is given', async () => {
    const { io, stderr } = captureIO();
    const exitCode = await runValidateCli([], io);
    expect(exitCode).toBe(1);
    expect(stderr.join('\n')).toContain('usage:');
  });
});
