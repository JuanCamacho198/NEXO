import { describe, expect, it } from 'vitest';
import { parseManifestV3, TYPE_BOOK } from '@nexo/addon-protocol';
import { toManifestV3 } from '@/data/addons';
import addonsData from '@/data/addons.json';
import type {
  AddonAvailability,
  AddonCategory,
  AddonKind,
  AddonLang,
  AddonSeed,
} from '@/data/addons';

// Runtime allow-lists. `satisfies` ties every entry back to the union declared
// in addons.ts, so widening the source of truth there forces this file to move
// with it instead of silently accepting the new value.
const AVAILABILITIES = [
  'builtin',
  'community',
  'planned',
] as const satisfies readonly AddonAvailability[];
const KINDS = ['builtin'] as const satisfies readonly AddonKind[];
const CATEGORIES = [
  'dominio-publico',
  'bibliotecas',
  'audiolibros',
  'wikis',
  'prensa',
  'independientes',
] as const satisfies readonly AddonCategory[];
const LANGS = ['es', 'en', 'multi'] as const satisfies readonly AddonLang[];
const ICONS = [
  'book',
  'library',
  'sparkles',
  'headphones',
  'globe',
  'archive',
] as const satisfies readonly AddonSeed['icon'][];

const REQUIRED_STRINGS = ['id', 'name', 'version', 'author', 'license', 'updatedAt'] as const;

// The closed resource set of the protocol. A seed entry naming anything else
// would be published and then contribute nothing at install time.
const PROTOCOL_RESOURCES = ['catalog', 'meta', 'stream', 'subtitles'] as const;

// These entries describe sources that exist in the directory but whose upstream
// catalog has NOT been proven to return results. Keeping them `planned` is the
// load-bearing part of this spec: promoting one is a product decision that needs
// evidence, not a data tidy-up. Flipping an entry here must fail loudly.
const HOLLOW_MUST_STAY_PLANNED = [
  'standard-ebooks',
  'librivox',
  'wikisource',
  'faded-page',
] as const;
const PROVEN_MUST_STAY_BUILTIN = ['gutendex', 'openlibrary'] as const;

type RawAddon = Record<string, unknown>;

// Read the seed as untrusted input. The `as unknown as AddonSeed[]` cast in
// addons.ts erases compile-time checking, so runtime evidence is the only thing
// standing between a malformed seed and a published, lying surface.
const addons = addonsData as unknown as RawAddon[];

function where(addon: RawAddon, index: number): string {
  return typeof addon.id === 'string' && addon.id.length > 0
    ? `addons.json[${index}] ("${addon.id}")`
    : `addons.json[${index}]`;
}

function isMember(allowed: readonly unknown[], value: unknown): boolean {
  return allowed.includes(value);
}

describe('addons.json seed contract', () => {
  it('is a non-empty array of objects', () => {
    expect(Array.isArray(addons), 'addons.json must contain an array').toBe(true);
    expect(addons.length, 'addons.json must contain at least one addon').toBeGreaterThan(0);
    for (const [index, addon] of addons.entries()) {
      expect(
        addon !== null && typeof addon === 'object',
        `addons.json[${index}] must be an object`,
      ).toBe(true);
    }
  });

  it('required string fields are present, non-empty strings', () => {
    for (const [index, addon] of addons.entries()) {
      for (const field of REQUIRED_STRINGS) {
        const value = addon[field];
        expect(typeof value, `${where(addon, index)}.${field} must be a string`).toBe('string');
        expect(
          (value as string).trim(),
          `${where(addon, index)}.${field} must not be blank`,
        ).not.toBe('');
      }
    }
  });

  it('id values are unique', () => {
    const ids = addons.map((addon) => String(addon.id));
    expect(new Set(ids).size, `addon ids must be unique, got: ${ids.join(', ')}`).toBe(ids.length);
  });

  it('availability, kind, category and icon are members of their declared unions', () => {
    for (const [index, addon] of addons.entries()) {
      expect(
        isMember(AVAILABILITIES, addon.availability),
        `${where(addon, index)}.availability "${String(addon.availability)}" is not one of ${AVAILABILITIES.join(' | ')}`,
      ).toBe(true);
      expect(
        isMember(KINDS, addon.kind),
        `${where(addon, index)}.kind "${String(addon.kind)}" is not one of ${KINDS.join(' | ')}`,
      ).toBe(true);
      expect(
        isMember(CATEGORIES, addon.category),
        `${where(addon, index)}.category "${String(addon.category)}" is not one of ${CATEGORIES.join(' | ')}`,
      ).toBe(true);
      expect(
        isMember(ICONS, addon.icon),
        `${where(addon, index)}.icon "${String(addon.icon)}" is not one of ${ICONS.join(' | ')}`,
      ).toBe(true);
    }
  });

  it('languages is a non-empty array drawn from the AddonLang union', () => {
    for (const [index, addon] of addons.entries()) {
      const { languages } = addon;
      expect(Array.isArray(languages), `${where(addon, index)}.languages must be an array`).toBe(
        true,
      );
      const list = languages as unknown[];
      expect(list.length, `${where(addon, index)}.languages must not be empty`).toBeGreaterThan(0);
      for (const lang of list) {
        expect(
          isMember(LANGS, lang),
          `${where(addon, index)}.languages contains "${String(lang)}", which is not one of ${LANGS.join(' | ')}`,
        ).toBe(true);
      }
    }
  });

  it('resources are v3 declarations: a name, optional types, optional idPrefixes', () => {
    for (const [index, addon] of addons.entries()) {
      const { resources } = addon;
      expect(Array.isArray(resources), `${where(addon, index)}.resources must be an array`).toBe(
        true,
      );
      expect(
        (resources as unknown[]).length,
        `${where(addon, index)}.resources must declare at least one resource: an addon that ` +
          'declares none can never contribute anything, so publishing it would be a lie',
      ).toBeGreaterThan(0);
      for (const [resourceIndex, resource] of (resources as unknown[]).entries()) {
        const entry = resource as Record<string, unknown> | null;
        expect(
          entry !== null && typeof entry === 'object' && !Array.isArray(entry),
          `${where(addon, index)}.resources[${resourceIndex}] must be an object (the v3 shape is ` +
            '{ name, types?, idPrefixes? }); a bare v2 string such as "search" is not a resource',
        ).toBe(true);
        const name = entry?.name;
        expect(
          typeof name,
          `${where(addon, index)}.resources[${resourceIndex}].name must be a string`,
        ).toBe('string');
        expect(
          isMember(PROTOCOL_RESOURCES, name),
          `${where(addon, index)}.resources[${resourceIndex}].name "${String(name)}" is not one of ` +
            `${PROTOCOL_RESOURCES.join(' | ')}`,
        ).toBe(true);
        for (const field of ['types', 'idPrefixes'] as const) {
          const value = entry?.[field];
          if (value === undefined) continue;
          expect(
            Array.isArray(value) && value.length > 0,
            `${where(addon, index)}.resources[${resourceIndex}].${field} must be a non-empty array`,
          ).toBe(true);
          for (const item of value as unknown[]) {
            expect(
              typeof item === 'string' && item.trim() !== '',
              `${where(addon, index)}.resources[${resourceIndex}].${field} must hold non-empty strings`,
            ).toBe(true);
          }
        }
      }
    }
  });

  it('installUrl is null or an https: URL', () => {
    for (const [index, addon] of addons.entries()) {
      const { installUrl } = addon;
      if (installUrl === null) continue;
      expect(typeof installUrl, `${where(addon, index)}.installUrl must be null or a string`).toBe(
        'string',
      );
      let parsed: URL | undefined;
      try {
        parsed = new URL(installUrl as string);
      } catch {
        parsed = undefined;
      }
      expect(
        parsed,
        `${where(addon, index)}.installUrl "${String(installUrl)}" must be a valid URL`,
      ).toBeInstanceOf(URL);
      expect(parsed?.protocol, `${where(addon, index)}.installUrl must use https:`).toBe('https:');
    }
  });

  it('catalogs are v3 entries: type "book", non-empty id and name', () => {
    for (const [index, addon] of addons.entries()) {
      const { catalogs } = addon;
      expect(Array.isArray(catalogs), `${where(addon, index)}.catalogs must be an array`).toBe(
        true,
      );
      // Empty is legal in v3 (a stream-only addon), so emptiness is not asserted
      // here; what each entry declares when present is.
      for (const [catalogIndex, catalog] of (catalogs as unknown[]).entries()) {
        expect(
          catalog !== null && typeof catalog === 'object',
          `${where(addon, index)}.catalogs[${catalogIndex}] must be an object`,
        ).toBe(true);
        const entry = catalog as Record<string, unknown>;
        for (const field of ['type', 'id', 'name'] as const) {
          expect(
            typeof entry[field],
            `${where(addon, index)}.catalogs[${catalogIndex}].${field} must be a string`,
          ).toBe('string');
          expect(
            (entry[field] as string).trim(),
            `${where(addon, index)}.catalogs[${catalogIndex}].${field} must not be blank`,
          ).not.toBe('');
        }
        expect(
          entry.type,
          `${where(addon, index)}.catalogs[${catalogIndex}].type "${String(entry.type)}" must be ` +
            `"${TYPE_BOOK}": v3 has exactly one book type, and a v2 "book-catalog" entry would be ` +
            'published as a type the client cannot route',
        ).toBe(TYPE_BOOK);
        const extra = entry.extra;
        if (extra === undefined) continue;
        expect(
          Array.isArray(extra),
          `${where(addon, index)}.catalogs[${catalogIndex}].extra must be an array`,
        ).toBe(true);
        for (const [extraIndex, arg] of (extra as unknown[]).entries()) {
          const parsed = arg as Record<string, unknown> | null;
          expect(
            typeof parsed?.name === 'string' && (parsed.name as string).trim() !== '',
            `${where(addon, index)}.catalogs[${catalogIndex}].extra[${extraIndex}].name must be a non-empty string`,
          ).toBe(true);
          if (parsed?.isRequired !== undefined) {
            expect(
              typeof parsed.isRequired,
              `${where(addon, index)}.catalogs[${catalogIndex}].extra[${extraIndex}].isRequired must be a boolean`,
            ).toBe('boolean');
          }
        }
      }
    }
  });

  it('description.es and description.en are non-empty strings', () => {
    for (const [index, addon] of addons.entries()) {
      const { description } = addon;
      expect(
        description !== null && typeof description === 'object',
        `${where(addon, index)}.description must be an object with es/en`,
      ).toBe(true);
      const localized = description as Record<string, unknown>;
      for (const locale of ['es', 'en'] as const) {
        const value = localized[locale];
        expect(typeof value, `${where(addon, index)}.description.${locale} must be a string`).toBe(
          'string',
        );
        expect(
          (value as string).trim(),
          `${where(addon, index)}.description.${locale} must not be blank`,
        ).not.toBe('');
      }
    }
  });

  it('hollow addons stay planned and proven addons stay builtin', () => {
    const byId = new Map<string, RawAddon>(addons.map((addon) => [String(addon.id), addon]));

    for (const id of HOLLOW_MUST_STAY_PLANNED) {
      const addon = byId.get(id);
      expect(addon, `"${id}" is a hollow addon and must remain in addons.json`).toBeDefined();
      expect(
        addon?.availability,
        `"${id}" is a hollow addon: its upstream catalog has not been proven to return results, so it ` +
          'must stay availability:"planned". Flipping it to an available state requires first implementing ' +
          'its catalog and proving a real search returns results; that proof is the reason this guard exists. ' +
          'Change this guard in two places only after the catalog is proven live.',
      ).toBe('planned');
    }

    for (const id of PROVEN_MUST_STAY_BUILTIN) {
      const addon = byId.get(id);
      expect(addon, `"${id}" is a proven addon and must remain in addons.json`).toBeDefined();
      expect(
        addon?.availability,
        `"${id}" has a working builtin catalog and must stay availability:"builtin"`,
      ).toBe('builtin');
    }
  });
});

// The registry publishes a manifest per entry. These assertions run the real v3
// parser over the real projection, so a seed edit that the protocol would
// reject fails here instead of shipping a manifest clients cannot install.
describe('addons.json publishes v3 manifests', () => {
  it('every seed entry projects to a manifest that parseManifestV3 accepts', () => {
    const encoder = new TextEncoder();

    for (const [index, addon] of addons.entries()) {
      const manifest = toManifestV3(addon as unknown as AddonSeed);
      const bytes = encoder.encode(JSON.stringify(manifest));
      const parse = () => parseManifestV3(bytes, 'application/json');

      expect(
        parse,
        `${where(addon, index)} does not project to a valid v3 manifest. Published shape: ` +
          `${JSON.stringify(manifest)}`,
      ).not.toThrow();

      const parsed = parse();
      expect(parsed.id, `${where(addon, index)} must round-trip its id`).toBe(addon.id);
      expect(parsed.name, `${where(addon, index)} must round-trip its name`).toBe(addon.name);
      expect(parsed.version, `${where(addon, index)} must round-trip its version`).toBe(
        addon.version,
      );
      expect(
        parsed.types,
        `${where(addon, index)} must declare the book type: every entry in this registry is a book addon`,
      ).toContain(TYPE_BOOK);
      expect(
        parsed.resources.length,
        `${where(addon, index)} must survive parsing with at least one resource`,
      ).toBeGreaterThan(0);
    }
  });

  it('no entry still declares the retired v2 vocabulary', () => {
    const published = JSON.stringify(addons);

    for (const legacy of [
      'book-catalog',
      'book-details',
      'searchUrl',
      'detailsUrl',
      'resolveUrl',
    ]) {
      expect(
        published.includes(legacy),
        `addons.json still contains "${legacy}", which belongs to the retired v2 manifest. v3 ` +
          'declares types/catalogs/resources and, for addons that cannot serve the protocol, ' +
          'nexo.templates with a nexo.shape. Publish the v3 form instead of the v2 field.',
      ).toBe(false);
    }
  });

  it('every catalog that is published declares the single book type', () => {
    for (const [index, addon] of addons.entries()) {
      for (const [catalogIndex, catalog] of (addon.catalogs as unknown[]).entries()) {
        const entry = catalog as Record<string, unknown>;
        expect(
          entry.type,
          `${where(addon, index)}.catalogs[${catalogIndex}] must declare type "${TYPE_BOOK}"`,
        ).toBe(TYPE_BOOK);
      }
    }
  });
});
