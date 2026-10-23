import { describe, expect, it } from 'vitest';
import {
  AddonFetchError,
  AddonFetchErrorCode,
  MAX_MANIFEST_BYTES,
} from '@nexo/manifest-validator';
import {
  EXTRA_NAMES,
  ID_PREFIXES,
  MAX_NEXO_CHARS,
  RESERVED_TYPES,
  SHAPES,
  TYPE_BOOK,
} from '../src/constants';
import { isManifestV3, parseManifestV3 } from '../src/manifest';

const JSON_CONTENT_TYPE = 'application/json; charset=utf-8';

function encode(value: unknown): Uint8Array {
  return new TextEncoder().encode(JSON.stringify(value));
}

function manifest(overrides: Record<string, unknown> = {}): Record<string, unknown> {
  return {
    id: 'org.example.gutendex',
    version: '3.0.0',
    name: 'Gutendex',
    description: 'Project Gutenberg catalog',
    types: [TYPE_BOOK],
    catalogs: [
      {
        type: 'book',
        id: 'popular',
        name: 'Popular',
        extra: [
          { name: 'search', isRequired: false },
          { name: 'skip', isRequired: false },
        ],
      },
    ],
    resources: ['catalog', { name: 'meta', types: ['book'] }],
    ...overrides,
  };
}

function parse(value: unknown, contentType: string | null = JSON_CONTENT_TYPE) {
  return parseManifestV3(encode(value), contentType);
}

function errorCodeOf(run: () => unknown): string {
  try {
    run();
  } catch (error) {
    if (error instanceof AddonFetchError) return error.code;
    return `NOT_ADDON_FETCH_ERROR: ${String(error)}`;
  }
  return 'NO_THROW';
}

function errorOf(run: () => unknown): AddonFetchError | null {
  try {
    run();
  } catch (error) {
    return error instanceof AddonFetchError ? error : null;
  }
  return null;
}

describe('parseManifestV3', () => {
  it('parses a book manifest with mixed resource forms', () => {
    const resources = [
      'catalog',
      'meta',
      { name: 'stream', types: ['book'], idPrefixes: ['gutenberg:', 'isbn:'] },
    ];
    const parsed = parseManifestV3(encode(manifest({ resources })), JSON_CONTENT_TYPE);

    expect(parsed.id).toBe('org.example.gutendex');
    expect(parsed.version).toBe('3.0.0');
    expect(parsed.types).toEqual(['book']);
    expect(parsed.resources[0]).toEqual({ name: 'catalog' });
    expect(parsed.resources[1]).toEqual({ name: 'meta' });
    expect(parsed.resources[2]).toEqual({
      name: 'stream',
      types: ['book'],
      idPrefixes: ['gutenberg:', 'isbn:'],
    });
    expect(parsed.catalogs[0]?.extra).toEqual([
      { name: 'search', isRequired: false },
      { name: 'skip', isRequired: false },
    ]);
    expect(isManifestV3(manifest({ resources }))).toBe(true);
    expect(isManifestV3({ types: ['book'] })).toBe(false);
  });

  it('accepts an empty catalogs array (stream-only addon)', () => {
    const parsed = parse(
      manifest({
        catalogs: [],
        resources: ['stream', { name: 'meta', types: ['book'] }],
      }),
    );

    expect(parsed.catalogs).toEqual([]);
    expect(parsed.resources).toHaveLength(2);
  });

  it('accepts catalogs that the meta resource can describe', () => {
    const parsed = parse(manifest({ resources: ['catalog', 'meta'] }));

    expect(parsed.catalogs.map((entry) => entry.id)).toEqual(['popular']);
    expect(parsed.resources.map((entry) => entry.name)).toEqual(['catalog', 'meta']);
  });

  it('accepts an empty catalogs array without a meta resource', () => {
    const parsed = parse(manifest({ catalogs: [], resources: ['stream'] }));

    expect(parsed.catalogs).toEqual([]);
    expect(parsed.resources.map((entry) => entry.name)).toEqual(['stream']);
  });

  it('rejects catalogs when no meta resource can describe them', () => {
    const error = errorOf(() =>
      parse(manifest({ resources: ['catalog', { name: 'stream', types: ['book'] }] })),
    );

    expect(error?.code).toBe(AddonFetchErrorCode.INVALID_MANIFEST);
    // The message names the missing resource, so the author knows what to add.
    expect(error?.message).toContain('meta');
  });

  it('preserves the nexo object verbatim', () => {
    const nexo = {
      homepage: 'https://example.org/gutendex',
      license: 'CC0-1.0',
      templates: {
        catalog: 'https://example.org/catalog?q={search}&skip={skip}',
        meta: 'https://example.org/meta/{id}',
      },
      shape: 'raw',
    };

    const parsed = parse(manifest({ nexo }));

    expect(parsed.nexo).toEqual(nexo);
  });

  it('rejects a nexo object over 8 KiB', () => {
    const templates: Record<string, string> = {};
    for (let index = 0; index < 6; index += 1) {
      templates[`resource${index}`] = `https://example.org/${'a'.repeat(2000)}`;
    }

    const code = errorCodeOf(() =>
      parse(manifest({ nexo: { templates } })),
    );

    expect(MAX_NEXO_CHARS).toBe(8 * 1024);
    expect(code).toBe(AddonFetchErrorCode.INVALID_MANIFEST);
  });

  it('rejects a manifest with no resources', () => {
    const missing = manifest();
    delete missing.resources;

    expect(errorCodeOf(() => parse(missing))).toBe(AddonFetchErrorCode.INVALID_MANIFEST);
    expect(errorCodeOf(() => parse(manifest({ resources: [] })))).toBe(
      AddonFetchErrorCode.INVALID_MANIFEST,
    );
  });

  it('rejects a non-https template', () => {
    const code = errorCodeOf(() =>
      parse(
        manifest({
          nexo: { templates: { catalog: 'http://example.org/catalog?q={search}' } },
        }),
      ),
    );

    expect(code).toBe(AddonFetchErrorCode.INVALID_MANIFEST);
  });

  it('rejects a reserved type', () => {
    const code = errorCodeOf(() => parse(manifest({ types: ['book', 'audiobook'] })));

    expect(RESERVED_TYPES).toContain('audiobook');
    expect(code).toBe(AddonFetchErrorCode.UNSUPPORTED_TYPE);
  });

  it('drops a non-book catalog entry without failing', () => {
    const parsed = parse(
      manifest({
        catalogs: [
          { type: 'movie', id: 'films', name: 'Films' },
          { type: 'book', id: 'popular', name: 'Popular' },
        ],
      }),
    );

    expect(parsed.catalogs).toHaveLength(1);
    expect(parsed.catalogs[0]?.id).toBe('popular');
    expect(parse(manifest({ catalogs: [{ type: 'series', id: 's', name: 'S' }] })).catalogs).toEqual(
      [],
    );
  });

  it('ignores unknown top-level fields', () => {
    const parsed = parse(manifest({ foo: 1, extraTopLevel: { bar: true } })) as unknown as Record<
      string,
      unknown
    >;

    expect('foo' in parsed).toBe(false);
    expect('extraTopLevel' in parsed).toBe(false);
  });

  it('rejects a body over MAX_MANIFEST_BYTES', () => {
    const oversized = new Uint8Array(MAX_MANIFEST_BYTES + 1).fill(0x20);

    const code = errorCodeOf(() => parseManifestV3(oversized, JSON_CONTENT_TYPE));

    expect(MAX_MANIFEST_BYTES).toBe(64 * 1024);
    expect(code).toBe(AddonFetchErrorCode.TOO_LARGE);
  });
});

describe('constants', () => {
  it('freezes the id prefix list', () => {
    expect(TYPE_BOOK).toBe('book');
    expect([...RESERVED_TYPES]).toEqual(['audiobook', 'comic']);
    expect([...ID_PREFIXES]).toEqual([
      'gutenberg:',
      'isbn:',
      'openlibrary:',
      'googlebooks:',
      'archive:',
      'standardebooks:',
      'librivox:',
    ]);
    expect([...EXTRA_NAMES]).toEqual(['search', 'skip', 'genre', 'lang']);
    expect([...SHAPES]).toEqual([
      'raw',
      'gutendex',
      'openlibrary',
      'standard-ebooks',
      'librivox',
      'legacy-v2',
    ]);
    expect(MAX_NEXO_CHARS).toBe(8 * 1024);
  });
});
