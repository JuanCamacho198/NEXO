import { render, screen } from '@testing-library/svelte';
import userEvent from '@testing-library/user-event';
import { readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import SettingsAboutTab from '$lib/features/settings/components/SettingsAboutTab.svelte';
import { openUrl } from '@tauri-apps/plugin-opener';

vi.mock('@tauri-apps/plugin-opener', () => ({ openUrl: vi.fn() }));

vi.mock('$lib/features/settings/update/updateChecker', () => ({
  checkForUpdates: vi.fn(async () => ({ status: 'upToDate', installedVersion: '1.2.3' })),
  defaultPluginUpdatePorts: vi.fn(() => ({})),
  defaultUpdateCheckDeps: vi.fn(() => ({ feedUrl: 'https://feed.example/latest.json' })),
  getInstalledAppVersion: vi.fn(async () => '1.2.3'),
  performPluginUpdateNow: vi.fn(async () => 'installed'),
  resolveUpdateFeedUrl: vi.fn(() => 'https://feed.example/latest.json'),
}));

const REPOSITORY_URL = 'https://github.com/JuanCamacho198/NEXO';
const ISSUES_URL = 'https://github.com/JuanCamacho198/NEXO/issues';
const LICENSE_URL = 'https://github.com/JuanCamacho198/NEXO/blob/main/LICENSE';
const HERE = dirname(fileURLToPath(import.meta.url));
const NOTICES_PATH = resolve(HERE, '../../../../../../THIRD-PARTY-NOTICES.md');

const dictionary: Record<string, string> = {
  'settings.about': 'Acerca de Nexo',
  'settings.about.appName': 'Nexo',
  'settings.about.version': 'Versión {{version}}',
  'settings.about.versionUnknown': 'Versión no disponible',
  'settings.about.versionLoading': 'Versión…',
  'settings.about.copyVersion': 'Copiar versión',
  'settings.about.versionCopied': 'Versión copiada',
  'settings.about.channel': 'Canal: {{channel}}',
  'settings.about.channelStable': 'estable',
  'settings.about.tagline': 'Un lector moderno para tus bibliotecas EPUB y PDF.',
  'settings.about.quoteTranslated': 'traducción',
  'settings.about.quoteOriginal': 'Original ({{lang}})',
  'settings.about.quoteSource': 'Fuente',
  'settings.about.license': 'Licencia',
  'settings.about.licenseName': 'Apache-2.0',
  'settings.about.viewLicense': 'Ver LICENSE',
  'settings.about.links': 'Enlaces',
  'settings.about.github': 'GitHub',
  'settings.about.reportIssue': 'Reportar un problema',
  'settings.about.starBody':
    'Si NEXO te sirve, una estrella en GitHub ayuda a que más gente lo encuentre.',
  'settings.about.starCta': 'Danos una estrella en GitHub',
  'update.check': 'Buscar actualizaciones',
  'update.checking': 'Buscando actualizaciones...',
  'update.upToDate': 'Tienes la última versión',
  'update.errorUnreachable': 'No se pudo contactar.',
  'update.errorOffline': 'Sin conexión.',
  'update.errorMalformed': 'Feed inválido.',
};

const t = (key: string, params?: Record<string, string | number>): string => {
  let result = dictionary[key] ?? key;
  for (const [k, v] of Object.entries(params ?? {})) {
    result = result.replace(`{{${k}}}`, String(v));
  }
  return result;
};

const renderAbout = () =>
  render(SettingsAboutTab, {
    t,
    locale: 'es',
    updateDeps: { getInstalledVersion: async () => '1.2.3' },
  });

describe('SettingsAboutTab', () => {
  beforeEach(() => {
    vi.mocked(openUrl).mockClear();
    vi.spyOn(window, 'open').mockClear();
  });

  it('shows the real version and channel with no hard-coded English', async () => {
    renderAbout();
    expect(await screen.findByText('Versión 1.2.3')).toBeInTheDocument();
    expect(screen.getByText('Canal: estable')).toBeInTheDocument();
    expect(screen.queryByText('Credits')).toBeNull();
    expect(screen.queryByText('Links')).toBeNull();
    expect(screen.queryByText('Report Issue')).toBeNull();
  });

  it('opens the repository and issue pages through the Tauri opener, not window.open', async () => {
    const user = userEvent.setup();
    renderAbout();
    await user.click(screen.getByRole('button', { name: 'GitHub' }));
    expect(openUrl).toHaveBeenCalledWith(REPOSITORY_URL);
    await user.click(screen.getByRole('button', { name: 'Reportar un problema' }));
    expect(openUrl).toHaveBeenCalledWith(ISSUES_URL);
    expect(window.open).not.toHaveBeenCalled();
  });

  it('links the product license to the real LICENSE file', async () => {
    const user = userEvent.setup();
    renderAbout();
    expect(screen.getByText(/Licencia/)).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Ver LICENSE' }));
    expect(openUrl).toHaveBeenCalledWith(LICENSE_URL);
  });

  it('offers a star CTA that opens the repository through the Tauri opener', async () => {
    const user = userEvent.setup();
    renderAbout();
    await user.click(screen.getByRole('button', { name: 'Danos una estrella en GitHub' }));
    expect(openUrl).toHaveBeenCalledWith(REPOSITORY_URL);
    expect(window.open).not.toHaveBeenCalled();
  });

  it('no longer renders the third-party credits list or a core-team row', () => {
    renderAbout();
    expect(screen.queryByText('Créditos')).toBeNull();
    expect(screen.queryByText('Equipo principal')).toBeNull();
    expect(screen.queryByText(/PDF\.js/)).toBeNull();
  });

  it('preserves the bundled-library notices in THIRD-PARTY-NOTICES.md', () => {
    const notices = readFileSync(NOTICES_PATH, 'utf8');
    expect(notices).toContain('PDF.js (pdfjs-dist)');
    expect(notices).toContain('Apache-2.0');
    expect(notices).toContain('BSD-2-Clause');
    expect(notices).toContain('no longer lists the libraries');
    const rows = notices
      .split('\n')
      .filter((line) => line.startsWith('|'))
      .filter((line) => !line.includes('---') && !line.includes('Library'));
    expect(rows).toHaveLength(15);
  });

  it('renders the daily quote with author, source and a working source link', async () => {
    const user = userEvent.setup();
    const { container } = renderAbout();
    const sourceButton = screen.getByRole('button', { name: 'Fuente' });
    expect(sourceButton).toBeInTheDocument();
    await user.click(sourceButton);
    expect(openUrl).toHaveBeenCalledTimes(1);
    expect(vi.mocked(openUrl).mock.calls[0]?.[0]).toMatch(/^https:\/\//);
    expect(container.querySelector('blockquote')).not.toBeNull();
  });

  it('renders the version-copy and source controls through the Button atom', async () => {
    renderAbout();

    const copy = await screen.findByRole('button', { name: 'Copiar versión' });
    expect(copy).toHaveClass('text-sm', 'font-medium', 'rounded-lg', 'inline-flex');
    expect(copy).toHaveAttribute('aria-label', 'Copiar versión');

    const source = screen.getByRole('button', { name: 'Fuente' });
    expect(source).toHaveClass('text-sm', 'font-medium', 'rounded-lg', 'inline-flex');
  });

  it('renders no raw buttons and folds the quote figure into a Panel', async () => {
    const { container } = renderAbout();
    await screen.findByText('Versión 1.2.3');

    for (const button of container.querySelectorAll('button')) {
      expect(button.classList.contains('font-medium')).toBe(true);
    }
    expect(container.querySelector('figure')).toBeNull();
    expect(container.querySelector('blockquote')).not.toBeNull();
  });

  it('keeps descending heading levels (no H4 before an H3)', () => {
    const { container } = renderAbout();
    // The Panel container (its h2 title) is gone: the page header is now the h1
    // and the first sub-heading is the h3 "Enlaces" section, so include h1.
    const headings = Array.from(container.querySelectorAll('h1, h2, h3, h4, h5, h6')).map(
      (node) => ({
        level: Number(node.tagName.slice(1)),
        text: node.textContent?.trim() ?? '',
      }),
    );
    expect(headings[0]?.level).toBe(1);
    const firstNonOne = headings.find((heading) => heading.level !== 1);
    expect(firstNonOne?.level).toBe(3);
  });
});
