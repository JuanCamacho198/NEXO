import { describe, expect, it } from 'vitest';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';

/**
 * Guard: catalog cover images are loaded by the webview, so every host the
 * mappers can produce must be allowlisted in `img-src` — otherwise the cover
 * silently degrades to the initial-letter fallback even though `coverUrl` is
 * correct.
 *
 * Hosts mirror the mapper sources:
 * - `gutenbergCoverUrl()` -> www.gutenberg.org
 * - `googleBooksCoverUrl()` -> books.google.com
 * - Open Library docs -> covers.openlibrary.org
 * - `mapStandardEbooksAtomFeed()` -> standardebooks.org (the feed's `<media:thumbnail>`)
 *
 * Only the exact hosts are allowed: no wildcard may widen the image allowlist.
 */
const COVER_HOSTS = [
  'https://covers.openlibrary.org',
  'https://www.gutenberg.org',
  'https://books.google.com',
  'https://standardebooks.org',
];

function imgSrcDirective(): string {
  const conf = JSON.parse(
    readFileSync(join(__dirname, '../../../../../src-tauri/tauri.conf.json'), 'utf8'),
  );
  const csp: string = conf.app.security.csp;
  const directive = csp
    .split(';')
    .map((part) => part.trim())
    .find((part) => part.startsWith('img-src'));
  expect(directive).toBeDefined();
  return directive!;
}

describe('catalog cover hosts CSP guard', () => {
  it('img-src allows every catalog cover host', () => {
    const entries = imgSrcDirective().split(/\s+/).slice(1);
    for (const host of COVER_HOSTS) {
      expect(entries).toContain(host);
    }
  });

  it('img-src contains no wildcard host entries', () => {
    for (const entry of imgSrcDirective().split(/\s+/).slice(1)) {
      expect(entry).not.toContain('*');
    }
  });
});
