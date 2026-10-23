import { describe, expect, it } from 'vitest';
import {
  MAX_COLLECTION_ENTRIES,
  parseCollection,
  serializeCollection,
  type CollectionEntry,
} from '../src/collection';
import { TYPE_BOOK } from '../src/constants';
import type { AddonManifestV3 } from '../src/manifest';

function manifest(overrides: Record<string, unknown> = {}): Record<string, unknown> {
  return {
    id: 'org.example.gutendex',
    version: '3.0.0',
    name: 'Gutendex',
    types: [TYPE_BOOK],
    catalogs: [{ type: TYPE_BOOK, id: 'popular', name: 'Popular' }],
    resources: ['catalog', 'meta'],
    ...overrides,
  };
}

/** A stream-only manifest: no catalog, so no `meta` pairing rule applies. */
function streamManifest(id = 'org.example.streams'): Record<string, unknown> {
  return {
    id,
    version: '3.0.0',
    name: 'Streams',
    types: [TYPE_BOOK],
    catalogs: [],
    resources: [{ name: 'stream', types: [TYPE_BOOK] }],
  };
}

function entry(transportUrl: string, body: Record<string, unknown> = manifest()) {
  return { transportUrl, transportName: 'http', manifest: body, flags: {} };
}

function collection(addons: unknown[]): unknown {
  return { addons };
}

describe('parseCollection', () => {
  it('parses the Stremio collection shape into entries', () => {
    const parsed = parseCollection(
      collection([
        entry('https://a.example/manifest.json'),
        entry('https://b.example/manifest.json', manifest({ id: 'org.example.b', name: 'B' })),
      ]),
    );
    expect(parsed.skipped).toBe(0);
    expect(parsed.entries).toHaveLength(2);
    expect(parsed.entries[0].transportUrl).toBe('https://a.example/manifest.json');
    expect(parsed.entries[0].manifest.id).toBe('org.example.gutendex');
    expect(parsed.entries[1].manifest.id).toBe('org.example.b');
  });

  it('accepts the bare array stremio-addon-client serializes', () => {
    const parsed = parseCollection([entry('https://a.example/manifest.json')]);
    expect(parsed.skipped).toBe(0);
    expect(parsed.entries).toHaveLength(1);
  });

  it('accepts a raw JSON string, as it arrives from a fetched link', () => {
    const parsed = parseCollection(JSON.stringify(collection([entry('https://a.example/m.json')])));
    expect(parsed.entries).toHaveLength(1);
    expect(parsed.entries[0].transportUrl).toBe('https://a.example/m.json');
  });

  it('returns nothing for input that is not a collection', () => {
    for (const input of ['not json', null, 42, {}, { addons: 'nope' }, []]) {
      expect(parseCollection(input)).toEqual({ entries: [], skipped: 0 });
    }
  });

  it('skips non-object entries instead of failing the collection', () => {
    const parsed = parseCollection(
      collection([
        null,
        'https://a.example/manifest.json',
        ['https://b.example/manifest.json'],
        entry('https://c.example/manifest.json'),
      ]),
    );
    expect(parsed.skipped).toBe(3);
    expect(parsed.entries.map((e) => e.transportUrl)).toEqual([
      'https://c.example/manifest.json',
    ]);
  });

  it('skips an http: transportUrl and never downgrades it', () => {
    const parsed = parseCollection(
      collection([entry('http://plain.example/manifest.json'), entry('https://ok.example/m.json')]),
    );
    expect(parsed.skipped).toBe(1);
    expect(parsed.entries.map((e) => e.transportUrl)).toEqual(['https://ok.example/m.json']);
  });

  it('skips non-http schemes and unparseable transportUrls', () => {
    const parsed = parseCollection(
      collection([
        entry('file:///etc/passwd'),
        entry('ftp://example.com/m.json'),
        entry('not a url'),
        entry(''),
        { transportUrl: 42, manifest: manifest() },
        { manifest: manifest() },
      ]),
    );
    expect(parsed.skipped).toBe(6);
    expect(parsed.entries).toEqual([]);
  });

  it('skips an entry whose embedded manifest fails v3 validation and keeps the rest', () => {
    const parsed = parseCollection(
      collection([
        entry('https://bad.example/m.json', { id: 'org.example.bad', types: [TYPE_BOOK] }),
        entry('https://catalog-no-meta.example/m.json', {
          ...manifest({ id: 'org.example.noMeta' }),
          resources: ['catalog'],
        }),
        entry('https://reserved.example/m.json', manifest({ types: ['audiobook'] })),
        entry('https://ok.example/m.json'),
      ]),
    );
    expect(parsed.skipped).toBe(3);
    expect(parsed.entries.map((e) => e.transportUrl)).toEqual(['https://ok.example/m.json']);
  });

  it(`caps at ${MAX_COLLECTION_ENTRIES} entries and counts the overflow as skipped`, () => {
    const addons = Array.from({ length: MAX_COLLECTION_ENTRIES + 1 }, (_unused, index) =>
      entry(`https://addon-${index}.example/m.json`, manifest({ id: `org.example.a${index}` })),
    );
    const parsed = parseCollection(collection(addons));
    expect(parsed.entries).toHaveLength(MAX_COLLECTION_ENTRIES);
    expect(parsed.skipped).toBe(1);
    expect(parsed.entries[MAX_COLLECTION_ENTRIES - 1].manifest.id).toBe(
      `org.example.a${MAX_COLLECTION_ENTRIES - 1}`,
    );
  });

  it('collapses duplicate transportUrls to one entry, first occurrence winning', () => {
    const parsed = parseCollection(
      collection([
        entry('https://a.example/m.json', manifest({ id: 'org.example.first' })),
        entry('https://a.example/m.json', manifest({ id: 'org.example.second' })),
        entry('https://b.example/m.json'),
      ]),
    );
    expect(parsed.skipped).toBe(0);
    expect(parsed.entries).toHaveLength(2);
    expect(parsed.entries[0].manifest.id).toBe('org.example.first');
  });

  it('ignores flags: a collection cannot grant an addon elevated trust', () => {
    const parsed = parseCollection(
      collection([
        {
          transportUrl: 'https://a.example/m.json',
          transportName: 'http',
          manifest: streamManifest(),
          flags: {
            official: true,
            trusted: true,
            verified: true,
            consent: true,
            streamConsent: true,
            stream: true,
          },
        },
      ]),
    );
    expect(parsed.skipped).toBe(0);
    expect(parsed.entries).toHaveLength(1);
    expect(Object.keys(parsed.entries[0]).sort()).toEqual(['manifest', 'transportUrl']);
    // The manifest is untouched by the flags around it.
    expect(parsed.entries[0].manifest.resources).toEqual([
      { name: 'stream', types: [TYPE_BOOK] },
    ]);
  });

  it('never marks a manifest as consenting, whatever the collection says', () => {
    const parsed = parseCollection(
      collection([
        {
          transportUrl: 'https://a.example/m.json',
          manifest: { ...streamManifest(), consent: true, streamConsent: true },
          flags: { consent: true },
        },
      ]),
    );
    const entry = parsed.entries[0] as unknown as Record<string, unknown>;
    expect(entry.consent).toBeUndefined();
    expect((entry.manifest as unknown as Record<string, unknown>).consent).toBeUndefined();
    expect((entry.manifest as unknown as Record<string, unknown>).streamConsent).toBeUndefined();
  });
});

describe('serializeCollection', () => {
  function roundTrip(entries: CollectionEntry[]): string {
    return serializeCollection(
      entries.map((e) => ({ manifest: e.manifest, installUrl: e.transportUrl })),
    );
  }

  it('round-trips what parseCollection produced', () => {
    const parsed = parseCollection(
      collection([
        entry('https://a.example/m.json'),
        entry('https://b.example/m.json', manifest({ id: 'org.example.b', name: 'B' })),
        entry('https://streams.example/m.json', streamManifest()),
      ]),
    );
    const again = parseCollection(roundTrip(parsed.entries));
    expect(again.skipped).toBe(0);
    expect(again.entries).toEqual(parsed.entries);
  });

  it('emits the Stremio collection shape with no trust flags', () => {
    const parsed = parseCollection(collection([entry('https://a.example/m.json')]));
    const wire = JSON.parse(roundTrip(parsed.entries)) as {
      addons: Array<Record<string, unknown>>;
    };
    expect(wire.addons).toHaveLength(1);
    expect(wire.addons[0].transportUrl).toBe('https://a.example/m.json');
    expect(wire.addons[0].manifest).toBeDefined();
    expect(wire.addons[0].flags).toBeUndefined();
    expect(wire.addons[0].trusted).toBeUndefined();
  });

  it('drops non-https install urls and duplicate urls on export', () => {
    const parsed = parseCollection(collection([entry('https://a.example/m.json')]));
    const manifestV3 = parsed.entries[0].manifest as AddonManifestV3;
    const wire = JSON.parse(
      serializeCollection([
        { manifest: manifestV3, installUrl: 'http://plain.example/m.json' },
        { manifest: manifestV3, installUrl: 'https://a.example/m.json' },
        { manifest: manifestV3, installUrl: 'https://a.example/m.json' },
      ]),
    ) as { addons: unknown[] };
    expect(wire.addons).toHaveLength(1);
  });

  it(`exports at most ${MAX_COLLECTION_ENTRIES} entries`, () => {
    const parsed = parseCollection(
      collection([entry('https://seed.example/m.json')]),
    );
    const manifestV3 = parsed.entries[0].manifest;
    const wire = JSON.parse(
      serializeCollection(
        Array.from({ length: MAX_COLLECTION_ENTRIES + 5 }, (_unused, index) => ({
          manifest: manifestV3,
          installUrl: `https://addon-${index}.example/m.json`,
        })),
      ),
    ) as { addons: unknown[] };
    expect(wire.addons).toHaveLength(MAX_COLLECTION_ENTRIES);
  });
});
