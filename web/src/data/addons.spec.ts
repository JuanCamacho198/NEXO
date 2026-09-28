import { describe, expect, it } from 'vitest';
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

  it('resources is an array of non-empty strings', () => {
    for (const [index, addon] of addons.entries()) {
      const { resources } = addon;
      expect(Array.isArray(resources), `${where(addon, index)}.resources must be an array`).toBe(
        true,
      );
      for (const [resourceIndex, resource] of (resources as unknown[]).entries()) {
        expect(
          typeof resource,
          `${where(addon, index)}.resources[${resourceIndex}] must be a string`,
        ).toBe('string');
        expect(
          (resource as string).trim(),
          `${where(addon, index)}.resources[${resourceIndex}] must not be blank`,
        ).not.toBe('');
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

  it('catalogs is a non-empty array of { type, id, name } with non-empty strings', () => {
    for (const [index, addon] of addons.entries()) {
      const { catalogs } = addon;
      expect(Array.isArray(catalogs), `${where(addon, index)}.catalogs must be an array`).toBe(
        true,
      );
      const list = catalogs as unknown[];
      expect(list.length, `${where(addon, index)}.catalogs must not be empty`).toBeGreaterThan(0);
      for (const [catalogIndex, catalog] of list.entries()) {
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
