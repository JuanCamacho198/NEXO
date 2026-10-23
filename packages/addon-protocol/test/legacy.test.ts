import { describe, expect, it } from 'vitest';
import { AddonFetchError, AddonFetchErrorCode } from '@nexo/manifest-validator';
import { adaptLegacyManifest, loadManifest } from '../src/legacy';

const JSON_CONTENT_TYPE = 'application/json; charset=utf-8';

function encode(value: unknown): Uint8Array {
  return new TextEncoder().encode(JSON.stringify(value));
}

function codeOf(run: () => unknown): string {
  try {
    run();
  } catch (error) {
    if (error instanceof AddonFetchError) return error.code;
    return `NOT_ADDON_FETCH_ERROR: ${String(error)}`;
  }
  return 'NO_THROW';
}

describe('adaptLegacyManifest', () => {
  it('adapts a v2 search+details+resolve addon to templates', () => {
    const v3 = adaptLegacyManifest({
      id: 'gutendex',
      name: 'Gutendex',
      version: '1.0.0',
      catalogs: [{ type: 'book-catalog', id: 'gutendex', name: 'Project Gutenberg' }],
      resources: ['search', 'book-details'],
      searchUrl: 'https://gutendex.com/books?q={query}&page={page}',
      detailsUrl: 'https://gutendex.com/books/{bookId}',
      resolveUrl: 'https://gutendex.com/books/{bookId}/formats',
    });

    expect(v3.id).toBe('gutendex');
    expect(v3.name).toBe('Gutendex');
    expect(v3.version).toBe('1.0.0');
    expect(v3.types).toEqual(['book']);
    expect(v3.nexo?.shape).toBe('legacy-v2');
    expect(v3.nexo?.templates).toEqual({
      catalog: 'https://gutendex.com/books?q={search}&page={skip}',
      meta: 'https://gutendex.com/books/{id}',
      stream: 'https://gutendex.com/books/{id}/formats',
    });
    expect(v3.catalogs).toEqual([
      {
        type: 'book',
        id: 'gutendex',
        name: 'Project Gutenberg',
        extra: [{ name: 'search' }, { name: 'skip' }],
      },
    ]);
    expect(v3.resources).toEqual([
      { name: 'catalog' },
      { name: 'meta', types: ['book'] },
      { name: 'stream', types: ['book'] },
    ]);
  });

  it('maps capabilities ["resolve"] to a stream resource', () => {
    const v3 = adaptLegacyManifest({
      id: 'openlibrary',
      name: 'Open Library',
      version: '1.0.0',
      catalogs: [{ type: 'book-catalog', id: 'openlibrary', name: 'Open Library' }],
      resources: ['search'],
      capabilities: ['resolve'],
    });

    expect(v3.resources).toEqual([{ name: 'catalog' }, { name: 'stream', types: ['book'] }]);
    expect(v3.nexo?.templates).toBeUndefined();
    expect(v3.catalogs[0]?.extra).toBeUndefined();
  });

  it('drops the catalog extra arguments when the addon has no searchUrl', () => {
    const v3 = adaptLegacyManifest({
      id: 'librivox',
      name: 'LibriVox',
      version: '1.0.0',
      catalogs: [{ type: 'book-catalog', id: 'librivox', name: 'LibriVox' }],
      resources: ['search'],
      detailsUrl: 'https://librivox.test/books/{bookId}',
    });

    expect(v3.resources).toEqual([{ name: 'catalog' }, { name: 'meta', types: ['book'] }]);
    expect(v3.nexo?.templates).toEqual({ meta: 'https://librivox.test/books/{id}' });
  });
});

describe('loadManifest', () => {
  it('picks v3 for a manifest with types', () => {
    const v3 = loadManifest(
      encode({
        id: 'org.example.tpb',
        version: '1.0.0',
        name: 'TPB',
        types: ['movie'],
        catalogs: [{ type: 'movie', id: 'top', name: 'Top' }],
        resources: ['catalog', 'meta', 'stream'],
      }),
      JSON_CONTENT_TYPE,
    );

    expect(v3.types).toEqual(['movie']);
    expect(v3.catalogs).toEqual([]);
    expect(v3.nexo).toBeUndefined();
  });

  it('adapts a stored v2 manifest', () => {
    const v3 = loadManifest(
      encode({
        id: 'gutendex',
        name: 'Gutendex',
        version: '1.0.0',
        catalogs: [
          { type: 'book-catalog', id: 'gutendex', name: 'Project Gutenberg (via Gutendex)' },
        ],
        resources: ['search', 'book-details'],
      }),
      JSON_CONTENT_TYPE,
    );

    expect(v3.types).toEqual(['book']);
    expect(v3.catalogs).toEqual([
      { type: 'book', id: 'gutendex', name: 'Project Gutenberg (via Gutendex)' },
    ]);
    expect(v3.resources).toEqual([{ name: 'catalog' }, { name: 'meta', types: ['book'] }]);
    expect(v3.nexo?.shape).toBe('legacy-v2');
  });

  it('rejects a non-JSON content type before choosing an adapter', () => {
    const bytes = encode({
      id: 'gutendex',
      name: 'Gutendex',
      version: '1.0.0',
      catalogs: [{ type: 'book-catalog', id: 'gutendex', name: 'Project Gutenberg' }],
      resources: ['search'],
    });

    expect(codeOf(() => loadManifest(bytes, 'text/html'))).toBe(
      AddonFetchErrorCode.BAD_CONTENT_TYPE,
    );
  });

  it('rejects invalid JSON with an invalid-manifest error', () => {
    const bytes = new TextEncoder().encode('{ not json');

    expect(codeOf(() => loadManifest(bytes, JSON_CONTENT_TYPE))).toBe(
      AddonFetchErrorCode.INVALID_MANIFEST,
    );
  });
});
