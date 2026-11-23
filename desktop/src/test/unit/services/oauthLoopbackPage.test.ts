import { afterEach, describe, expect, it } from 'vitest';
import { i18n } from '$lib/shared/i18n';
import { OPEN_APP_URL, buildLoopbackPage } from '$lib/shared/services/oauthLoopbackPage';

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
    expect(html).toContain('If NEXO does not open, switch back to the app');
    expect(html).not.toContain('Google Drive connected');
  });

  // Like desktop apps: the page hands the reader back through the app's own
  // scheme, with a button for when the browser's prompt is dismissed.
  it('offers an Open NEXO button and tries the app link on load', () => {
    i18n.locale.set('es');
    const html = buildLoopbackPage('signedIn');

    expect(html).toContain(`<a class="open" href="${OPEN_APP_URL}">Abrir NEXO</a>`);
    expect(html).toContain(`window.location.href = '${OPEN_APP_URL}'`);
    expect(OPEN_APP_URL).toBe('nexo://open');
  });
});
