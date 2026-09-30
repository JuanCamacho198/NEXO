import { describe, expect, it } from 'vitest';
import {
  AddonFetchError,
  AddonFetchErrorCode,
  declaredCapabilities,
  validateManifest,
  assertHttpsInstallUrl,
  MAX_MANIFEST_BYTES,
} from '@nexo/manifest-validator';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';

const fixturesDir = join(__dirname, 'fixtures');

function loadFixture(name: string) {
  return JSON.parse(readFileSync(join(fixturesDir, `${name}.json`), 'utf8'));
}

const VALID_MANIFEST = {
  id: 'example-books',
  name: 'Example Books',
  version: '1.0.0',
  catalogs: [{ type: 'book-catalog', id: 'main', name: 'Example Catalog' }],
  resources: ['search', 'book-details'],
};

function encode(value: unknown): Uint8Array {
  return new TextEncoder().encode(JSON.stringify(value));
}

describe('AddonFetchErrorCode', () => {
  it('exports the stable additive error codes', () => {
    expect(AddonFetchErrorCode.HTTPS_REQUIRED).toBe('ADDON_FETCH_HTTPS_REQUIRED');
    expect(AddonFetchErrorCode.TOO_LARGE).toBe('ADDON_FETCH_TOO_LARGE');
    expect(AddonFetchErrorCode.BAD_CONTENT_TYPE).toBe('ADDON_FETCH_BAD_CONTENT_TYPE');
    expect(AddonFetchErrorCode.INVALID_MANIFEST).toBe('ADDON_FETCH_INVALID_MANIFEST');
    expect(AddonFetchErrorCode.NETWORK).toBe('ADDON_FETCH_NETWORK');
  });
});

describe('assertHttpsInstallUrl', () => {
  it('accepts https URLs', () => {
    expect(assertHttpsInstallUrl('https://example.com/manifest.json')).toBe(true);
  });

  it('rejects http:// with HTTPS_REQUIRED', () => {
    try {
      assertHttpsInstallUrl('http://example.com/manifest.json');
      throw new Error('should have thrown');
    } catch (err) {
      expect(err).toBeInstanceOf(AddonFetchError);
      expect((err as AddonFetchError).code).toBe(AddonFetchErrorCode.HTTPS_REQUIRED);
    }
  });

  it('rejects non-HTTP(S) schemes with HTTPS_REQUIRED', () => {
    for (const url of [
      'ftp://example.com/m.json',
      'file:///etc/manifest.json',
      'javascript:alert(1)',
    ]) {
      try {
        assertHttpsInstallUrl(url);
        throw new Error(`should have thrown for ${url}`);
      } catch (err) {
        expect((err as AddonFetchError).code).toBe(AddonFetchErrorCode.HTTPS_REQUIRED);
      }
    }
  });
});

describe('validateManifest', () => {
  it('accepts a valid manifest and returns the parsed object', () => {
    const manifest = validateManifest(encode(VALID_MANIFEST), 'application/json');
    expect(manifest.id).toBe('example-books');
    expect(manifest.name).toBe('Example Books');
    expect(manifest.version).toBe('1.0.0');
    expect(manifest.catalogs).toHaveLength(1);
    expect(manifest.resources).toEqual(['search', 'book-details']);
  });

  it('rejects >64KB before parsing with TOO_LARGE', () => {
    const oversized = new Uint8Array(MAX_MANIFEST_BYTES + 1);
    try {
      validateManifest(oversized, 'application/json');
      throw new Error('should have thrown');
    } catch (err) {
      expect((err as AddonFetchError).code).toBe(AddonFetchErrorCode.TOO_LARGE);
    }
  });

  it('accepts exactly 64KB bytes', () => {
    const exact = encode({ ...VALID_MANIFEST });
    const padded = new Uint8Array(MAX_MANIFEST_BYTES);
    padded.set(exact, 0);
    expect(() => validateManifest(padded, 'application/json')).toThrow(AddonFetchError);
  });

  it('rejects text/html content type with BAD_CONTENT_TYPE', () => {
    try {
      validateManifest(encode(VALID_MANIFEST), 'text/html; charset=utf-8');
      throw new Error('should have thrown');
    } catch (err) {
      expect((err as AddonFetchError).code).toBe(AddonFetchErrorCode.BAD_CONTENT_TYPE);
    }
  });

  it('rejects non-JSON content types with BAD_CONTENT_TYPE', () => {
    expect(() => validateManifest(encode(VALID_MANIFEST), 'text/plain')).toThrow(AddonFetchError);
  });

  it('accepts +json structured suffix content types', () => {
    expect(() =>
      validateManifest(encode(VALID_MANIFEST), 'application/manifest+json'),
    ).not.toThrow();
  });

  it('rejects malformed JSON with INVALID_MANIFEST', () => {
    const bytes = new TextEncoder().encode('{not json');
    try {
      validateManifest(bytes, 'application/json');
      throw new Error('should have thrown');
    } catch (err) {
      expect((err as AddonFetchError).code).toBe(AddonFetchErrorCode.INVALID_MANIFEST);
    }
  });

  it('rejects missing required fields with INVALID_MANIFEST', () => {
    for (const key of ['id', 'name', 'version', 'catalogs', 'resources'] as const) {
      const broken: Record<string, unknown> = { ...VALID_MANIFEST };
      delete broken[key];
      try {
        validateManifest(encode(broken), 'application/json');
        throw new Error(`should have thrown for missing ${key}`);
      } catch (err) {
        expect((err as AddonFetchError).code).toBe(AddonFetchErrorCode.INVALID_MANIFEST);
      }
    }
  });

  it('rejects wrong-typed required fields with INVALID_MANIFEST', () => {
    const cases = [
      { ...VALID_MANIFEST, id: 42 },
      { ...VALID_MANIFEST, name: '' },
      { ...VALID_MANIFEST, version: null },
      { ...VALID_MANIFEST, catalogs: 'main' },
      { ...VALID_MANIFEST, catalogs: [] },
      { ...VALID_MANIFEST, resources: 'search' },
    ];
    for (const broken of cases) {
      try {
        validateManifest(encode(broken), 'application/json');
        throw new Error(`should have thrown for ${JSON.stringify(broken)}`);
      } catch (err) {
        expect((err as AddonFetchError).code).toBe(AddonFetchErrorCode.INVALID_MANIFEST);
      }
    }
  });

  it('rejects catalog entries missing type/id/name with INVALID_MANIFEST', () => {
    const cases = [
      [{ type: 'book-catalog', id: 'main' }],
      [{ type: 'book-catalog', name: 'Example' }],
      [{ id: 'main', name: 'Example' }],
      [{ type: 1, id: 'main', name: 'Example' }],
    ];
    for (const catalogs of cases) {
      try {
        validateManifest(encode({ ...VALID_MANIFEST, catalogs }), 'application/json');
        throw new Error('should have thrown');
      } catch (err) {
        expect((err as AddonFetchError).code).toBe(AddonFetchErrorCode.INVALID_MANIFEST);
      }
    }
  });

  it('ignores unknown top-level fields', () => {
    const extra = { ...VALID_MANIFEST, futureField: { nested: true }, another: 7 };
    const manifest = validateManifest(encode(extra), 'application/json');
    expect(manifest.id).toBe('example-books');
  });

  it('ignores unknown fields on catalog entries', () => {
    const catalogs = [{ ...VALID_MANIFEST.catalogs[0], extra: 'ignored' }];
    const manifest = validateManifest(encode({ ...VALID_MANIFEST, catalogs }), 'application/json');
    expect(manifest.catalogs[0].id).toBe('main');
  });

  describe('optional endpoint fields', () => {
    it('parses https searchUrl/detailsUrl templates when present', () => {
      const manifest = validateManifest(
        encode({
          ...VALID_MANIFEST,
          searchUrl: 'https://example.com/search?q={query}&page={page}',
          detailsUrl: 'https://example.com/book/{bookId}',
        }),
        'application/json',
      );
      expect(manifest.searchUrl).toBe('https://example.com/search?q={query}&page={page}');
      expect(manifest.detailsUrl).toBe('https://example.com/book/{bookId}');
    });

    it('leaves absent endpoint fields undefined', () => {
      const manifest = validateManifest(encode(VALID_MANIFEST), 'application/json');
      expect(manifest.searchUrl).toBeUndefined();
      expect(manifest.detailsUrl).toBeUndefined();
    });

    it('rejects non-https endpoint templates', () => {
      for (const bad of ['http://example.com/s', 'ftp://example.com/s', 'not a url']) {
        try {
          validateManifest(encode({ ...VALID_MANIFEST, searchUrl: bad }), 'application/json');
          throw new Error('should have thrown');
        } catch (err) {
          expect((err as AddonFetchError).code).toBe(AddonFetchErrorCode.INVALID_MANIFEST);
        }
      }
    });

    it('rejects non-string endpoint templates', () => {
      for (const bad of [42, null, {}]) {
        try {
          validateManifest(encode({ ...VALID_MANIFEST, detailsUrl: bad }), 'application/json');
          throw new Error('should have thrown');
        } catch (err) {
          expect((err as AddonFetchError).code).toBe(AddonFetchErrorCode.INVALID_MANIFEST);
        }
      }
    });
  });

  describe('manifest v2 (slice 9)', () => {
    it('a v1 manifest (no capabilities/resolveUrl) stays valid and installs unchanged', () => {
      const manifest = validateManifest(encode(VALID_MANIFEST), 'application/json');
      expect(manifest.resolveUrl).toBeUndefined();
      expect(manifest.capabilities).toBeUndefined();
      expect(declaredCapabilities(manifest)).toEqual([]);
    });

    it('a v2 manifest with well-formed capabilities + https resolveUrl is accepted with both retained', () => {
      const manifest = validateManifest(
        encode({
          ...VALID_MANIFEST,
          resolveUrl: 'https://example.com/resolve?isbn={isbn}&title={title}',
          capabilities: ['resolve'],
        }),
        'application/json',
      );
      expect(manifest.resolveUrl).toBe('https://example.com/resolve?isbn={isbn}&title={title}');
      expect(manifest.capabilities).toEqual(['resolve']);
      expect(declaredCapabilities(manifest)).toEqual(['resolve']);
    });

    it('malformed capabilities reject with INVALID_MANIFEST', () => {
      const cases = [
        { ...VALID_MANIFEST, capabilities: 'resolve' },
        { ...VALID_MANIFEST, capabilities: [] },
        { ...VALID_MANIFEST, capabilities: [''] },
        { ...VALID_MANIFEST, capabilities: ['ok', 42] },
        { ...VALID_MANIFEST, capabilities: ['x'.repeat(65)] },
        { ...VALID_MANIFEST, capabilities: Array.from({ length: 17 }, (_, i) => `c${i}`) },
      ];
      for (const broken of cases) {
        try {
          validateManifest(encode(broken), 'application/json');
          throw new Error(`should have thrown for ${JSON.stringify(broken.capabilities)}`);
        } catch (err) {
          expect((err as AddonFetchError).code).toBe(AddonFetchErrorCode.INVALID_MANIFEST);
        }
      }
    });

    it('a non-https resolveUrl is rejected pre-I/O with INVALID_MANIFEST', () => {
      for (const bad of ['http://example.com/r', 'ftp://example.com/r', 'not a url']) {
        try {
          validateManifest(
            encode({ ...VALID_MANIFEST, resolveUrl: bad, capabilities: ['resolve'] }),
            'application/json',
          );
          throw new Error('should have thrown');
        } catch (err) {
          expect((err as AddonFetchError).code).toBe(AddonFetchErrorCode.INVALID_MANIFEST);
        }
      }
    });

    it('unknown v2-adjacent fields are ignored and ineffective', () => {
      const manifest = validateManifest(
        encode({
          ...VALID_MANIFEST,
          resolveUrl: 'https://example.com/resolve?isbn={isbn}',
          capabilities: ['resolve'],
          futureTopLevelField: { nested: true },
        }),
        'application/json',
      );
      expect(manifest.resolveUrl).toBe('https://example.com/resolve?isbn={isbn}');
      expect(declaredCapabilities(manifest)).toEqual(['resolve']);
      expect((manifest as Record<string, unknown>)['futureTopLevelField']).toBeUndefined();
    });

    it('v2 parity fixtures yield the shared expected outcome', () => {
      for (const name of ['v2-valid', 'v2-unknown-fields']) {
        const fixture = loadFixture(name);
        const manifest = validateManifest(
          encode(fixture.manifest),
          fixture.contentType ?? 'application/json',
        );
        expect(manifest.id).toBe(fixture.manifest.id);
        expect(manifest.resolveUrl).toBe(fixture.manifest.resolveUrl);
        expect(declaredCapabilities(manifest)).toEqual(fixture.manifest.capabilities);
      }
      for (const name of ['v2-malformed-capabilities', 'v2-http-resolve-url']) {
        const fixture = loadFixture(name);
        try {
          validateManifest(encode(fixture.manifest), fixture.contentType ?? 'application/json');
          throw new Error(`fixture ${name} should have been rejected`);
        } catch (err) {
          expect(err).toBeInstanceOf(AddonFetchError);
          expect((err as AddonFetchError).code).toBe(fixture.expectedCode);
        }
      }
    });
  });

  describe('parity fixtures', () => {    const fixtureNames = [
      'valid',
      'unknown-fields',
      'http-url',
      'oversize',
      'html-content-type',
      'missing-fields',
      'catalog-entry-missing-name',
    ];

    for (const name of fixtureNames) {
      it(`fixture ${name} yields the shared expected outcome`, () => {
        const fixture = loadFixture(name);
        if (fixture.expect === 'pass') {
          const manifest = validateManifest(
            encode(fixture.manifest),
            fixture.contentType ?? 'application/json',
          );
          expect(manifest.id).toBe(fixture.manifest.id);
          return;
        }
        try {
          if (fixture.url !== undefined) {
            assertHttpsInstallUrl(fixture.url);
          } else if (fixture.rawBytesLength !== undefined) {
            validateManifest(
              new Uint8Array(fixture.rawBytesLength),
              fixture.contentType ?? 'application/json',
            );
          } else {
            validateManifest(encode(fixture.manifest), fixture.contentType ?? 'application/json');
          }
          throw new Error(`fixture ${name} should have been rejected`);
        } catch (err) {
          expect(err).toBeInstanceOf(AddonFetchError);
          expect((err as AddonFetchError).code).toBe(fixture.expectedCode);
        }
      });
    }
  });
});
