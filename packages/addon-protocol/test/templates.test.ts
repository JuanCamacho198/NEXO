import { describe, expect, it } from 'vitest';
import { AddonFetchError, AddonFetchErrorCode } from '@nexo/manifest-validator';
import type { AddonManifestV3 } from '../src/manifest';
import {
  buildRoutedUrl,
  decodeExtra,
  encodeExtra,
  encodeSegment,
  resolveAddonBase,
} from '../src/resourceUrl';
import { buildRequestUrl, fillTemplate } from '../src/templates';

const INSTALL_URL = 'https://a.test/manifest.json';

function codeOf(run: () => unknown): string {
  try {
    run();
  } catch (error) {
    if (error instanceof AddonFetchError) return error.code;
    return `NOT_ADDON_FETCH_ERROR: ${String(error)}`;
  }
  return 'NO_THROW';
}

function manifest(overrides: Partial<AddonManifestV3> = {}): AddonManifestV3 {
  return {
    id: 'org.example.gutendex',
    version: '3.0.0',
    name: 'Gutendex',
    types: ['book'],
    catalogs: [{ type: 'book', id: 'popular', name: 'Popular' }],
    resources: [{ name: 'catalog' }, { name: 'meta', types: ['book'] }],
    ...overrides,
  };
}

describe('resolveAddonBase', () => {
  it('strips a trailing manifest.json and trailing slashes', () => {
    expect(resolveAddonBase('https://a.test/manifest.json')).toBe('https://a.test');
    expect(resolveAddonBase('https://a.test/addons/one/')).toBe('https://a.test/addons/one');
    expect(resolveAddonBase('https://a.test/addons/one/manifest.json/')).toBe(
      'https://a.test/addons/one',
    );
  });
});

describe('encodeSegment', () => {
  it('keeps the colon readable and encodes spaces as %20', () => {
    expect(encodeSegment('isbn:9780141439518')).toBe('isbn:9780141439518');
    expect(encodeSegment('pride and prejudice')).toBe('pride%20and%20prejudice');
  });

  it('encodes path separators and query delimiters', () => {
    expect(encodeSegment('a/b')).toBe('a%2Fb');
    expect(encodeSegment('a&b=c')).toBe('a%26b%3Dc');
  });
});

describe('encodeExtra', () => {
  it('keeps insertion order, skips nullish and empty values, and never emits +', () => {
    expect(
      encodeExtra({ search: 'pride and prejudice', skip: 100, genre: '', lang: null }),
    ).toBe('search=pride%20and%20prejudice&skip=100');
    expect(encodeExtra({ genre: undefined, lang: 'en' })).toBe('lang=en');
    expect(encodeExtra({})).toBe('');
    expect(encodeExtra(undefined)).toBe('');
  });

  it('round-trips through decodeExtra', () => {
    expect(decodeExtra('search=pride%20and%20prejudice&skip=100')).toEqual({
      search: 'pride and prejudice',
      skip: '100',
    });
    expect(decodeExtra('')).toEqual({});
  });
});

describe('buildRoutedUrl', () => {
  it('builds a routed catalog URL without extra', () => {
    expect(buildRoutedUrl(INSTALL_URL, { resource: 'catalog', type: 'book', id: 'popular' })).toBe(
      'https://a.test/catalog/book/popular.json',
    );
  });

  it('appends extra with %20 for spaces, never +', () => {
    expect(
      buildRoutedUrl(INSTALL_URL, {
        resource: 'catalog',
        type: 'book',
        id: 'popular',
        extra: { search: 'pride and prejudice', skip: 100 },
      }),
    ).toBe('https://a.test/catalog/book/popular/search=pride%20and%20prejudice&skip=100.json');
  });

  it('keeps the colon in a namespaced id unencoded', () => {
    expect(
      buildRoutedUrl(INSTALL_URL, { resource: 'stream', type: 'book', id: 'isbn:9780141439518' }),
    ).toBe('https://a.test/stream/book/isbn:9780141439518.json');
  });

  it('rejects an id containing a slash', () => {
    expect(
      codeOf(() => buildRoutedUrl(INSTALL_URL, { resource: 'meta', type: 'book', id: 'a/b' })),
    ).toBe(AddonFetchErrorCode.INVALID_MANIFEST);
  });

  it('rejects a type containing a slash', () => {
    expect(
      codeOf(() => buildRoutedUrl(INSTALL_URL, { resource: 'meta', type: 'book/x', id: 'a' })),
    ).toBe(AddonFetchErrorCode.INVALID_MANIFEST);
  });

  it('rejects an install URL that has no base', () => {
    expect(
      codeOf(() => buildRoutedUrl('/', { resource: 'meta', type: 'book', id: 'a' })),
    ).toBe(AddonFetchErrorCode.INVALID_MANIFEST);
  });
});

describe('fillTemplate', () => {
  it('fills a template with encoded id and query', () => {
    expect(
      fillTemplate('https://g.test/books?q={search}&page={skip}', {
        resource: 'catalog',
        type: 'book',
        id: 'popular',
        extra: { search: 'pride & prejudice', skip: 2 },
      }),
    ).toBe('https://g.test/books?q=pride%20%26%20prejudice&page=2');
  });

  it('fills id and bookId and leaves the colon readable', () => {
    expect(fillTemplate('https://g.test/books/{id}', { resource: 'meta', type: 'book', id: 'isbn:9780141439518' })).toBe(
      'https://g.test/books/isbn:9780141439518',
    );
    expect(
      fillTemplate('https://g.test/books/{bookId}/formats', {
        resource: 'stream',
        type: 'book',
        id: 'isbn:9780141439518',
      }),
    ).toBe('https://g.test/books/isbn:9780141439518/formats');
  });

  it('reads {query} and {page} as legacy aliases of search and skip', () => {
    expect(
      fillTemplate('https://g.test/books?q={query}&page={page}', {
        resource: 'catalog',
        type: 'book',
        id: 'popular',
        extra: { search: 'a b', skip: 5 },
      }),
    ).toBe('https://g.test/books?q=a%20b&page=5');
  });

  it('substitutes an empty string for an argument the request omits', () => {
    expect(
      fillTemplate('https://g.test/books?q={search}', { resource: 'catalog', type: 'book', id: 'popular' }),
    ).toBe('https://g.test/books?q=');
  });

  it('rejects an unknown template placeholder', () => {
    expect(
      codeOf(() =>
        fillTemplate('https://g.test/books?p={publisher}', {
          resource: 'meta',
          type: 'book',
          id: 'a',
        }),
      ),
    ).toBe(AddonFetchErrorCode.INVALID_MANIFEST);
  });
});

describe('buildRequestUrl', () => {
  it('builds the routed form when the manifest declares no template', () => {
    expect(
      buildRequestUrl(
        { installUrl: INSTALL_URL, manifest: manifest() },
        { resource: 'meta', type: 'book', id: 'isbn:9780141439518' },
      ),
    ).toBe('https://a.test/meta/book/isbn:9780141439518.json');
  });

  it('prefers the template over the routed form', () => {
    const templated = manifest({
      nexo: {
        templates: {
          meta: 'https://gutendex.test/books/{id}',
          catalog: 'https://gutendex.test/books?q={search}',
        },
      },
    });
    expect(
      buildRequestUrl(
        { installUrl: INSTALL_URL, manifest: templated },
        { resource: 'meta', type: 'book', id: '12' },
      ),
    ).toBe('https://gutendex.test/books/12');
    expect(
      buildRequestUrl(
        { installUrl: INSTALL_URL, manifest: templated },
        { resource: 'catalog', type: 'book', id: 'popular', extra: { search: 'dickens' } },
      ),
    ).toBe('https://gutendex.test/books?q=dickens');
    expect(
      buildRequestUrl(
        { installUrl: INSTALL_URL, manifest: templated },
        { resource: 'stream', type: 'book', id: '12' },
      ),
    ).toBe('https://a.test/stream/book/12.json');
  });
});
