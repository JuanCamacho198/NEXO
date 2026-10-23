import { describe, expect, it } from 'vitest';
import type { AddonManifestV3 } from '../src/manifest';
import {
  declaredExtras,
  declaresResource,
  selectAddons,
  type AddonDescriptor,
} from '../src/router';

function manifest(overrides: Partial<AddonManifestV3> = {}): AddonManifestV3 {
  return {
    id: 'org.example.gutendex',
    version: '3.0.0',
    name: 'Gutendex',
    types: ['book'],
    catalogs: [],
    resources: [{ name: 'catalog' }],
    ...overrides,
  };
}

function addon(id: string, overrides: Partial<AddonDescriptor> = {}): AddonDescriptor {
  return {
    id,
    installUrl: `https://${id}.test/manifest.json`,
    addedAt: 1,
    enabled: true,
    manifest: manifest({ id }),
    ...overrides,
  };
}

describe('declaresResource', () => {
  it('a string-form resource matches every type', () => {
    const declared = manifest({ resources: [{ name: 'meta' }] });
    expect(declaresResource(declared, 'meta', { type: 'book', id: 'isbn:9780141439518' })).toBe(
      true,
    );
    expect(declaresResource(declared, 'meta', { type: 'movie', id: 'isbn:9780141439518' })).toBe(
      true,
    );
    // Object form without `types` is the same "all types" declaration.
    expect(declaresResource(declared, 'meta', { type: 'series', id: 'x' })).toBe(true);
  });

  it('an object-form resource filters by types', () => {
    const declared = manifest({ resources: [{ name: 'meta', types: ['movie'] }] });
    expect(declaresResource(declared, 'meta', { type: 'movie', id: 'x' })).toBe(true);
    expect(declaresResource(declared, 'meta', { type: 'book', id: 'x' })).toBe(false);

    // A resource name that is not declared at all never matches.
    expect(declaresResource(declared, 'stream', { type: 'movie', id: 'x' })).toBe(false);
  });

  it('idPrefixes filter meta and stream requests', () => {
    const declared = manifest({
      resources: [
        { name: 'meta', types: ['book'], idPrefixes: ['isbn:'] },
        { name: 'stream', types: ['book'], idPrefixes: ['gutenberg:', 'isbn:'] },
      ],
    });
    expect(declaresResource(declared, 'meta', { type: 'book', id: 'isbn:9780141439518' })).toBe(
      true,
    );
    expect(declaresResource(declared, 'meta', { type: 'book', id: 'gutenberg:1342' })).toBe(false);
    expect(declaresResource(declared, 'stream', { type: 'book', id: 'gutenberg:1342' })).toBe(true);
    expect(declaresResource(declared, 'stream', { type: 'book', id: 'isbn:9780141439518' })).toBe(
      true,
    );
    expect(declaresResource(declared, 'stream', { type: 'book', id: 'archive:pride' })).toBe(false);

    // No per-resource list falls back to the manifest list, then to no filter.
    const inherited = manifest({
      resources: [{ name: 'stream', types: ['book'] }],
      idPrefixes: ['isbn:'],
    });
    expect(declaresResource(inherited, 'stream', { type: 'book', id: 'isbn:9780141439518' })).toBe(
      true,
    );
    expect(declaresResource(inherited, 'stream', { type: 'book', id: 'gutenberg:1342' })).toBe(
      false,
    );

    const unfiltered = manifest({ resources: [{ name: 'stream', types: ['book'] }] });
    expect(declaresResource(unfiltered, 'stream', { type: 'book', id: 'archive:pride' })).toBe(
      true,
    );

    // An empty per-resource list is not a filter either.
    const empty = manifest({
      resources: [{ name: 'stream', types: ['book'], idPrefixes: [] }],
    });
    expect(declaresResource(empty, 'stream', { type: 'book', id: 'archive:pride' })).toBe(true);
  });

  it('idPrefixes never filter catalog requests', () => {
    const declared = manifest({
      resources: [{ name: 'catalog', idPrefixes: ['isbn:'] }],
      idPrefixes: ['isbn:'],
    });
    expect(declaresResource(declared, 'catalog', { type: 'book', id: 'popular' })).toBe(true);
    expect(declaresResource(declared, 'catalog', { type: 'book', id: 'isbn:9780141439518' })).toBe(
      true,
    );
    expect(
      selectAddons([addon('a', { manifest: declared })], {
        resource: 'catalog',
        type: 'book',
        id: 'recent',
      }),
    ).toHaveLength(1);
  });

  it('a templated addon routes without a resources match', () => {
    const declared = manifest({
      resources: [{ name: 'meta', types: ['movie'] }],
      nexo: { templates: { stream: 'https://a.test/stream/{id}' } },
    });
    expect(declared.resources.some((entry) => entry.name === 'stream')).toBe(false);
    expect(declaresResource(declared, 'stream', { type: 'book', id: 'gutenberg:1342' })).toBe(true);
    expect(selectAddons([addon('a', { manifest: declared })], {
      resource: 'stream',
      type: 'book',
      id: 'gutenberg:1342',
    })).toHaveLength(1);

    // The template tier is per resource: no template for meta, no match.
    expect(declaresResource(declared, 'meta', { type: 'book', id: 'gutenberg:1342' })).toBe(false);
  });

  it('excludes disabled addons', () => {
    const declared = manifest({ resources: [{ name: 'meta' }] });
    const addons = [
      addon('enabled', { manifest: declared, addedAt: 1 }),
      addon('disabled', { manifest: declared, addedAt: 2, enabled: false }),
    ];
    const selected = selectAddons(addons, { resource: 'meta', type: 'book', id: 'x' });
    expect(selected.map((entry) => entry.id)).toEqual(['enabled']);
  });

  it('orders by addedAt then id', () => {
    const declared = manifest({ resources: [{ name: 'meta' }] });
    const addons = [
      addon('late', { manifest: declared, addedAt: 30 }),
      addon('zulu', { manifest: declared, addedAt: 10 }),
      addon('alpha', { manifest: declared, addedAt: 10 }),
      addon('middle', { manifest: declared, addedAt: 20 }),
      addon('wrong-resource', { manifest: manifest({ resources: [{ name: 'stream' }] }) }),
    ];
    const selected = selectAddons(addons, { resource: 'meta', type: 'book', id: 'x' });
    expect(selected.map((entry) => entry.id)).toEqual(['alpha', 'zulu', 'middle', 'late']);

    // Deterministic regardless of input order.
    const shuffled = [addons[2], addons[4], addons[1], addons[3], addons[0]];
    expect(selectAddons(shuffled, { resource: 'meta', type: 'book', id: 'x' })).toEqual(selected);
    expect(selected).not.toBe(addons);
  });
});

describe('declaredExtras', () => {
  it('declaredExtras returns only the catalog names', () => {
    const declared = manifest({
      catalogs: [
        {
          type: 'book',
          id: 'popular',
          name: 'Popular',
          extra: [{ name: 'search' }, { name: 'skip', isRequired: false }],
        },
        { type: 'book', id: 'recent', name: 'Recent', extra: [{ name: 'lang' }] },
        { type: 'book', id: 'bare', name: 'Bare' },
      ],
    });
    expect(declaredExtras(declared, 'popular')).toEqual(['search', 'skip']);
    expect(declaredExtras(declared, 'recent')).toEqual(['lang']);
    expect(declaredExtras(declared, 'bare')).toEqual([]);
    expect(declaredExtras(declared, 'missing')).toEqual([]);
  });
});
