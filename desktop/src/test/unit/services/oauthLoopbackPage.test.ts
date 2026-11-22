import { afterEach, describe, expect, it } from 'vitest';
import { i18n } from '$lib/shared/i18n';
import { buildLoopbackPage } from '$lib/shared/services/oauthLoopbackPage';

describe('buildLoopbackPage', () => {
  afterEach(() => i18n.locale.set('en'));

  it('renders the Drive page in the app locale with the NEXO lockup', () => {
    i18n.locale.set('es');
    const html = buildLoopbackPage('driveConnected');

    expect(html).toContain('<html lang="es">');
    expect(html).toContain('Google Drive conectado');
    expect(html).toContain('aria-label="NEXO"');
    // Only the wordmark follows the page ink; the mark keeps its own colour.
    expect(html).toContain('id="wordmark" fill="currentColor"');
  });

  it('renders the sign-in page with its own copy', () => {
    i18n.locale.set('en');
    const html = buildLoopbackPage('signedIn');

    expect(html).toContain('You&#39;re signed in'.replace('&#39;', "'"));
    expect(html).toContain('You can close this tab and go back to NEXO.');
    expect(html).not.toContain('Google Drive connected');
  });
});
