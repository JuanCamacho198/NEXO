import { describe, expect, it } from 'vitest';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';

/**
 * Guard: addon fetching goes through the Rust command (reqwest), never the
 * webview. The Tauri CSP connect-src must remain byte-for-byte unchanged —
 * no addon hosts may be added to the webview allowlist.
 *
 * Exception (sdd/app-auto-update, desktop slice): `https://github.com` is the
 * release-feed host for the Tauri updater endpoints (tauri.conf
 * plugins.updater). It is a built-in allowlist entry, not an addon host.
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
  'https://*.archive.org',
  // sdd/app-auto-update: release-feed host for the Tauri updater endpoints.
  'https://github.com',
].join(' ');

describe('addon registry CSP guard', () => {
  it('tauri.conf.json connect-src is unchanged by addon-registry-v1', () => {
    const conf = JSON.parse(
      readFileSync(join(__dirname, '../../../../../src-tauri/tauri.conf.json'), 'utf8'),
    );
    const csp: string = conf.app.security.csp;
    const connectSrc = csp
      .split(';')
      .map((directive) => directive.trim())
      .find((directive) => directive.startsWith('connect-src'));
    expect(connectSrc).toBe(`connect-src ${CSP_CONNECT_SRC}`);
  });

  it('connect-src contains no wildcard addon host entries', () => {
    const conf = JSON.parse(
      readFileSync(join(__dirname, '../../../../../src-tauri/tauri.conf.json'), 'utf8'),
    );
    const csp: string = conf.app.security.csp;
    const connectSrc = csp
      .split(';')
      .map((directive) => directive.trim())
      .find((directive) => directive.startsWith('connect-src'));
    expect(connectSrc).toBeDefined();
    // Known built-in allowlist only; every other host would be an addon leak.
    for (const entry of connectSrc!.split(/\s+/).slice(1)) {
      expect([
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
        'https://*.archive.org',
        // sdd/app-auto-update: release-feed host for the Tauri updater endpoints.
        'https://github.com',
      ]).toContain(entry);
    }
  });
});
