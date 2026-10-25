import { describe, expect, it } from 'vitest';
import { CatalogError } from '$lib/shared/services/catalog/errors';
import {
  BUILTIN_GUTENDEX,
  BUILTIN_OPENLIBRARY,
  BUILTIN_STANDARD_EBOOKS,
  addonSource,
  addonSourceIdOf,
  parseCatalogSource,
} from '$lib/shared/services/catalog/CatalogProvider';

const ADDON_ID = 'a1b2c3d4e5f60718';

describe('parseCatalogSource (strict source ids)', () => {
  it('accepts the exact built-in ids', () => {
    expect(parseCatalogSource('builtin:gutendex')).toBe(BUILTIN_GUTENDEX);
    expect(parseCatalogSource('builtin:openlibrary')).toBe(BUILTIN_OPENLIBRARY);
    expect(parseCatalogSource('builtin:standard-ebooks')).toBe(BUILTIN_STANDARD_EBOOKS);
  });

  it('rejects the removed sources that have no provider', () => {
    // Each of these once parsed as a valid source and could never answer a
    // request; the registry is closed so the id now fails fast.
    for (const removed of ['builtin:librivox', 'builtin:wikisource', 'builtin:faded-page']) {
      expect(() => parseCatalogSource(removed)).toThrowError(CatalogError);
    }
  });

  it('accepts addon sources with a 16-hex addonId', () => {
    expect(parseCatalogSource(`addon:${ADDON_ID}`)).toBe(`addon:${ADDON_ID}`);
    expect(addonSource(ADDON_ID)).toBe(`addon:${ADDON_ID}`);
    expect(addonSourceIdOf(`addon:${ADDON_ID}`)).toBe(ADDON_ID);
  });

  it('rejects bare names without a namespace prefix', () => {
    for (const raw of ['gutendex', 'openlibrary']) {
      expect(() => parseCatalogSource(raw)).toThrowError(CatalogError);
    }
  });

  it('rejects empty addon ids', () => {
    expect(() => parseCatalogSource('addon:')).toThrowError(CatalogError);
  });

  it('rejects unknown builtin ids (no tolerant parsing)', () => {
    expect(() => parseCatalogSource('builtin:other')).toThrowError(CatalogError);
  });

  it('rejects trailing junk on builtin ids', () => {
    expect(() => parseCatalogSource('builtin:gutendex:x')).toThrowError(CatalogError);
    expect(() => parseCatalogSource('builtin:gutendex ')).toThrowError(CatalogError);
    expect(() => parseCatalogSource('builtin:GUTENDEX')).toThrowError(CatalogError);
  });

  it('rejects malformed addon ids', () => {
    expect(() => parseCatalogSource('addon:xyz')).toThrowError(CatalogError);
    expect(() => parseCatalogSource('addon:ABCDEF0123456789')).toThrowError(CatalogError);
    expect(() => parseCatalogSource(`addon:${ADDON_ID}:extra`)).toThrowError(CatalogError);
  });

  it('rejects unknown namespaces', () => {
    expect(() => parseCatalogSource('curated:gutendex')).toThrowError(CatalogError);
    expect(() => parseCatalogSource('')).toThrowError(CatalogError);
  });
});
