import { describe, expect, it } from 'vitest';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';

/**
 * Guard: the webview `connect-src` is the blast radius of an XSS in the
 * renderer, so it carries only origins the renderer itself fetches. Rust has
 * no CSP; a host called only from `src-tauri` must NOT appear here.
 *
 * Derivation table and per-origin call sites: `docs/desktop-csp-origins.md`.
 * That document is the source of truth — update it before this list.
 *
 * Kept because a webview `fetch` reaches them:
 * - `accounts.google.com`   Drive OAuth consent launch (opener parity)
 * - `oauth2.googleapis.com` Drive token exchange + revoke (`DriveConnectService`)
 * - `www.googleapis.com`    Drive API (`GDriveProvider`) + Books API (`GoogleBooksDataSource`)
 * - `*.supabase.co`         supabase-js client (`$lib/services/supabase`)
 * - `gutendex.com`          `GutendexDataSource` default `fetch`
 * - `openlibrary.org`       `OpenLibraryDataSource` default `fetch`
 * - `covers.openlibrary.org` cover images
 *
 * Removed because only Rust calls them, or only an `<a target="_blank">` does:
 * standardebooks.org (Rust `fetchCatalogFeed`), *.archive.org and github.com
 * (navigation), wiktionary/mymemory (Rust reqwest).
 */
const CSP_CONNECT_SRC = [
  "'self'",
  'asset:',
  'http://asset.localhost',
  'https://asset.localhost',
  'https://accounts.google.com',
  'https://oauth2.googleapis.com',
  'https://www.googleapis.com',
  'https://*.supabase.co',
  'https://gutendex.com',
  'https://openlibrary.org',
  'https://covers.openlibrary.org',
  // odd/desktop-prod-csp-fixes U1: Tauri's own IPC origin.
  'http://ipc.localhost',
].join(' ');

/** Origins the renderer never fetches; re-adding one widens the XSS surface. */
const FORBIDDEN_CONNECT_SRC = [
  'https://standardebooks.org',
  'https://*.archive.org',
  'https://archive.org',
  'https://github.com',
  'https://www.wiktionary.org',
  'https://api.mymemory.translated.net',
];

function directive(name: 'connect-src' | 'img-src'): string {
  const conf = JSON.parse(
    readFileSync(join(__dirname, '../../../../../src-tauri/tauri.conf.json'), 'utf8'),
  );
  const csp: string = conf.app.security.csp;
  const found = csp
    .split(';')
    .map((part) => part.trim())
    .find((part) => part.startsWith(name));
  expect(found).toBeDefined();
  return found!;
}

describe('addon registry CSP guard', () => {
  it('tauri.conf.json connect-src matches the derived allowlist', () => {
    expect(directive('connect-src')).toBe(`connect-src ${CSP_CONNECT_SRC}`);
  });

  it('connect-src contains no wildcard addon host entries', () => {
    for (const entry of directive('connect-src').split(/\s+/).slice(1)) {
      expect(CSP_CONNECT_SRC.split(' ')).toContain(entry);
    }
  });

  it('connect-src carries no origin the webview does not call', () => {
    const entries = directive('connect-src').split(/\s+/).slice(1);
    for (const forbidden of FORBIDDEN_CONNECT_SRC) {
      expect(entries).not.toContain(forbidden);
    }
  });
});
