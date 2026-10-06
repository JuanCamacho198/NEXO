import { describe, expect, it } from 'vitest';
import { messagesEn } from '$lib/shared/i18n/messages.en';
import { messagesEs } from '$lib/shared/i18n/messages.es';

describe('discover i18n EN+ES parity (PR4)', () => {
  it('exposes every discover.* and sidebar.discover key in both locales', () => {
    const discoverKeys = [
      'sidebar.discover',
      'discover.search',
      'discover.searchPlaceholder',
      'discover.searchAriaLabel',
      'discover.searchShortcut',
      'discover.searchShortcutAria',
      'discover.idle',
      'discover.empty',
      'discover.endOfResults',
      'discover.retry',
      'discover.loading',
      'discover.loadingMore',
      'discover.offline',
      'discover.rateLimited',
      'discover.errorSlow',
      'discover.errorUpstream',
      'discover.errorInvalidPage',
      'discover.errorNotFound',
      'discover.detailNotFound',
      'discover.dismiss',
      'discover.download',
      'discover.downloadCancel',
      'discover.downloading',
      'discover.importing',
      'discover.imported',
      'discover.downloadCancelled',
      'discover.downloadFailed',
      'discover.inLibrary',
      'discover.openBook',
      'discover.descriptionShowMore',
      'discover.descriptionShowLess',
      'discover.formats',
      'discover.externalGutenberg',
      'discover.externalOpenLibrary',
      'discover.byAuthors',
      'discover.languages',
      'discover.subjects',
      'discover.accessTitle',
      'discover.accessGroupFree',
      'discover.accessGroupBuy',
      'discover.accessGroupSubscribe',
      'discover.accessDownload',
      'discover.accessOpenLibrary',
      'discover.accessInternetArchive',
      'discover.accessGooglePreview',
      'discover.accessGutenberg',
      'discover.accessWebSearch',
      'discover.accessBuy',
      'discover.accessSubscribe',
      'discover.rail.newest',
      'discover.rail.popular',
      'discover.rail.thematic.fiction',
      'discover.rail.thematic.classic',
      'discover.rail.thematic.adventure',
      'discover.rail.thematic.mystery',
      'discover.rail.thematic.romance',
      'discover.rail.thematic.science',
      'discover.rail.thematic.history',
      'discover.rail.viewAll',
      'discover.railScope.back',
      'discover.railScope.singlePage',
    ] as const;

    for (const key of discoverKeys) {
      expect(messagesEn[key]).toBeDefined();
      expect(messagesEs[key]).toBeDefined();
    }

    expect(messagesEs['discover.search']).toBe('Buscar');
    expect(messagesEs['sidebar.discover']).toBe('Descubrir');
    expect(messagesEn['discover.rail.newest']).toBe('Recently added');
    expect(messagesEn['discover.rail.popular']).toBe('Popular');
    expect(messagesEs['discover.rail.newest']).toBe('Recién agregados');
    expect(messagesEs['discover.rail.popular']).toBe('Populares');
    expect(messagesEs['discover.rail.thematic.science']).toBe('Ciencia ficción');
    expect(messagesEs['discover.rail.viewAll']).toBe('Ver todo');
    expect(messagesEn['discover.rail.viewAll']).toBe('View all');
    expect(messagesEs['discover.railScope.back']).toBeTruthy();
    expect(messagesEn['discover.railScope.back']).toBeTruthy();
    expect(messagesEs['discover.railScope.singlePage']).toContain('{{count}}');
    expect(messagesEn['discover.railScope.singlePage']).toContain('{{count}}');
    expect(messagesEn['discover.inLibrary']).toBe('Already in your library');
    expect(messagesEs['discover.inLibrary']).toBe('Ya está en tu biblioteca');
    expect(messagesEn['discover.openBook']).toBe('Open book');
    expect(messagesEs['discover.openBook']).toBe('Abrir libro');
    expect(messagesEn['discover.descriptionShowMore']).toBe('Show more');
    expect(messagesEs['discover.descriptionShowMore']).toBe('Mostrar más');
    expect(messagesEn['discover.descriptionShowLess']).toBe('Show less');
    expect(messagesEs['discover.descriptionShowLess']).toBe('Mostrar menos');
  });
});
