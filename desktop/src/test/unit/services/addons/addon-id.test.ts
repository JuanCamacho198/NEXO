import { createHash } from 'node:crypto';
import { describe, expect, it } from 'vitest';
import { addonIdFromUrl } from '$lib/shared/services/addons/addonId';

// Cross-platform parity vectors: Kotlin MessageDigest must produce the same
// values (see android .../data/remote/addons/AddonIdTest.kt).
const VECTORS: Array<[string, string]> = [
  ['https://example.com/manifest.json', '1eb50a3f96621a44'],
  ['HTTPS://EXAMPLE.COM/MANIFEST.JSON', 'a462b1f139b195fa'],
  ['https://example.com/manifest.json?v=2', 'f80b43dc80cace9a'],
];

describe('addonIdFromUrl', () => {
  it.each(VECTORS)(
    'returns the first 16 hex chars of sha256(url) for %s',
    async (url, expected) => {
      expect(await addonIdFromUrl(url)).toBe(expected);
    },
  );

  it('matches node crypto sha256 utf-8 digest directly', async () => {
    const url = 'https://addons.nexo.example/catalog.json';
    const expected = createHash('sha256').update(url, 'utf8').digest('hex').slice(0, 16);
    expect(await addonIdFromUrl(url)).toBe(expected);
  });

  it('returns a 16-char lowercase hex string for arbitrary urls', async () => {
    const id = await addonIdFromUrl('https://example.com/a/b?x=1#frag');
    expect(id).toMatch(/^[0-9a-f]{16}$/);
  });
});
