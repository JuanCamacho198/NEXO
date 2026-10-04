import { describe, expect, it } from 'vitest';
import { messagesEn } from '$lib/shared/i18n/messages.en';
import { messagesEs } from '$lib/shared/i18n/messages.es';

describe('i18n es/en parity (REQ-X-Cross-2)', () => {
  it('messages.es exposes exactly the same keys as messages.en', () => {
    const enKeys = Object.keys(messagesEn).sort();
    const esKeys = Object.keys(messagesEs).sort();

    expect(esKeys).toEqual(enKeys);
  });

  it('includes the 8 home-redesign keys in both locales', () => {
    const homeKeys = [
      'home.greetingImport',
      'home.metrics.dailyGoalLabel',
      'home.metrics.minutesFormat',
      'home.continue.progress',
      'home.continue.liveBadge',
      'home.continue.countAria',
      'home.continue.nextBook',
      'home.continue.prevBook',
    ] as const;

    for (const key of homeKeys) {
      expect(messagesEn[key]).toBeDefined();
      expect(messagesEs[key]).toBeDefined();
    }

    // Copy parity for the carousel/greeting keys: `home.continue.progress`
    // mirrors the pre-existing `home.shelfSort.progress` pair (Progreso/Progress)
    expect(messagesEs['home.continue.progress']).toBe('Progreso');
    expect(messagesEn['home.continue.progress']).toBe('Progress');
    expect(messagesEs['home.continue.nextBook']).toBe('Siguiente');
    expect(messagesEn['home.continue.nextBook']).toBe('Next');
    expect(messagesEs['home.continue.prevBook']).toBe('Anterior');
    expect(messagesEn['home.continue.prevBook']).toBe('Previous');
  });

  it('includes the reader capture-feedback keys in both locales', () => {
    const feedbackKeys = [
      'reader.dictionaryEvidenceUpdated',
      'reader.dictionaryNoMatch',
      'reader.dictionarySeveralMatches',
      'reader.dictionaryAlreadyInDictionary',
    ] as const;

    for (const key of feedbackKeys) {
      expect(messagesEn[key]).toBeDefined();
      expect(messagesEs[key]).toBeDefined();
    }

    // English copy is the spec's wording (REQ-DRE-004); the locale carries it
    // verbatim, without the frame's unbalanced closing quotation mark.
    expect(messagesEn['reader.dictionaryEvidenceUpdated']).toBe('Evidence updated');
    expect(messagesEn['reader.dictionaryNoMatch']).toBe(
      'No dictionary entry matches this selection',
    );
    expect(messagesEn['reader.dictionarySeveralMatches']).toBe(
      'Several entries match - select a single word',
    );
    expect(messagesEn['reader.dictionaryAlreadyInDictionary']).toBe('Already in your dictionary');

    // Spanish is a real translation, never the English fallback.
    for (const key of feedbackKeys) {
      expect(messagesEs[key]).not.toBe(messagesEn[key]);
    }
  });

  it('includes the dictionary screen keys in both locales', () => {
    const screenKeys = [
      'dictionary.title',
      'dictionary.subtitle',
      'dictionary.searchPlaceholder',
      'dictionary.newWord',
      'dictionary.tabAll',
      'dictionary.tabRecent',
      'dictionary.tabAz',
      'dictionary.kpiTotal',
      'dictionary.kpiThisWeek',
      'dictionary.kpiReferencedBooks',
      'dictionary.description',
      'dictionary.personalExample',
      'dictionary.bookReference',
      'dictionary.viewBook',
      'dictionary.edit',
      'dictionary.partOfSpeechLabel',
      'dictionary.phoneticLabel',
      'dictionary.save',
      'dictionary.cancel',
      'dictionary.evidenceNote',
      'dictionary.selectWordTitle',
      'dictionary.selectWordDescription',
      'dictionary.noDetailTitle',
      'dictionary.noDetailDescription',
    ] as const;

    for (const key of screenKeys) {
      expect(messagesEn[key]).toBeDefined();
      expect(messagesEs[key]).toBeDefined();
    }

    // KPI labels are binding: the Spanish copy is the frame's wording verbatim,
    // English is the faithful equivalent.
    expect(messagesEs['dictionary.kpiTotal']).toBe('Palabras guardadas');
    expect(messagesEs['dictionary.kpiThisWeek']).toBe('Esta semana');
    expect(messagesEs['dictionary.kpiReferencedBooks']).toBe('Libros referenciados');
    expect(messagesEn['dictionary.kpiTotal']).toBe('Saved words');
    expect(messagesEn['dictionary.kpiThisWeek']).toBe('This week');
    expect(messagesEn['dictionary.kpiReferencedBooks']).toBe('Books referenced');

    // The frame's subtitle + search placeholder are the pen copy, corrected to
    // a balanced `...` rather than the frame's typographic run.
    expect(messagesEs['dictionary.subtitle']).toBe(
      'Tus palabras guardadas con descripción y referencia.',
    );
    expect(messagesEs['dictionary.searchPlaceholder']).toBe('Buscar palabra...');
    expect(messagesEs['dictionary.newWord']).toBe('Nueva palabra');
  });

  it('includes the Data-tab dictionary transfer keys in both locales', () => {
    const transferKeys = [
      'settings.data.dictionary.title',
      'settings.data.dictionary.description',
      'settings.data.dictionary.exportJson',
      'settings.data.dictionary.exportCsv',
      'settings.data.dictionary.import',
      'settings.data.dictionary.imported',
      'settings.data.dictionary.rowError',
      'settings.data.dictionary.exportFailed',
      'settings.data.dictionary.importFailed',
    ] as const;

    for (const key of transferKeys) {
      expect(messagesEn[key]).toBeDefined();
      expect(messagesEs[key]).toBeDefined();
    }

    // The group names the dictionary explicitly so it cannot be confused with
    // the cold-backup pair that shares the tab.
    expect(messagesEn['settings.data.dictionary.title']).toBe('Dictionary');
    expect(messagesEs['settings.data.dictionary.title']).toBe('Diccionario');
    expect(messagesEn['settings.data.dictionary.exportJson']).toBe('Export JSON');
    expect(messagesEs['settings.data.dictionary.exportJson']).toBe('Exportar JSON');

    // Spanish is a real translation, never the English fallback.
    for (const key of transferKeys) {
      expect(messagesEs[key]).not.toBe(messagesEn[key]);
    }
  });

  it('includes the addons route keys in both locales', () => {
    const addonsKeys = ['sidebar.addons', 'discover.manageAddons'] as const;

    for (const key of addonsKeys) {
      expect(messagesEn[key]).toBeDefined();
      expect(messagesEs[key]).toBeDefined();
    }

    // Exact copy: the sidebar label is identical in both locales,
    // the Discover CTA uses the verbatim spec labels.
    expect(messagesEn['sidebar.addons']).toBe('Addons');
    expect(messagesEs['sidebar.addons']).toBe('Addons');
    expect(messagesEn['discover.manageAddons']).toBe('Manage addons');
    expect(messagesEs['discover.manageAddons']).toBe('Gestionar addons');
  });

  it('includes the addons screen keys in both locales', () => {
    const screenKeys = [
      'addons.title',
      'addons.subtitle',
      'addons.firstParty.title',
      'addons.firstParty.builtinTitle',
      'addons.firstParty.curatedTitle',
      'addons.firstParty.readOnly',
      'addons.firstParty.builtinBadge',
      'addons.install.errorInline',
      'addons.install.offline',
      'addons.install.retry',
      'addons.install.installing',
      'addons.consent.label',
      'addons.consent.granted',
      'addons.consent.title',
      'addons.consent.body',
      'addons.consent.allow',
      'addons.consent.deny',
    ] as const;

    for (const key of screenKeys) {
      expect(messagesEn[key]).toBeDefined();
      expect(messagesEs[key]).toBeDefined();
    }

    // Exact copy: screen title + badge + progress states + consent actions.
    expect(messagesEn['addons.title']).toBe('Addons');
    expect(messagesEs['addons.title']).toBe('Addons');
    expect(messagesEn['addons.firstParty.builtinBadge']).toBe('Built-in');
    expect(messagesEs['addons.firstParty.builtinBadge']).toBe('Integrada');
    expect(messagesEn['addons.install.retry']).toBe('Retry');
    expect(messagesEs['addons.install.retry']).toBe('Reintentar');
    expect(messagesEn['addons.install.installing']).toBe('Installing…');
    expect(messagesEs['addons.install.installing']).toBe('Instalando…');
    expect(messagesEn['addons.consent.allow']).toBe('Allow');
    expect(messagesEs['addons.consent.allow']).toBe('Permitir');
    expect(messagesEn['addons.consent.deny']).toBe('Deny');
    expect(messagesEs['addons.consent.deny']).toBe('Denegar');
  });

  it('includes the addon capability keys in both locales', () => {
    const capabilityKeys = [
      'addons.capabilities.title',
      'addons.capabilities.detail',
      'addons.capabilities.resolve',
      'discover.accessOpen',
    ] as const;

    for (const key of capabilityKeys) {
      expect(messagesEn[key]).toBeDefined();
      expect(messagesEs[key]).toBeDefined();
    }

    // Exact copy: capability chrome + the external-open link label.
    expect(messagesEn['addons.capabilities.title']).toBe('Capabilities');
    expect(messagesEs['addons.capabilities.title']).toBe('Capacidades');
    expect(messagesEn['addons.capabilities.resolve']).toBe('Resolve reading access');
    expect(messagesEs['addons.capabilities.resolve']).toBe('Resolver acceso de lectura');
    expect(messagesEn['discover.accessOpen']).toBe('Open');
    expect(messagesEs['discover.accessOpen']).toBe('Abrir');
  });

  it('includes the honest export scopes and file-boundary keys in both locales', () => {
    const exportKeys = [
      'settings.data.exportLibraryDescription',
      'settings.data.exportHighlightsDescription',
      'settings.data.exportFilesNotIncluded',
      'settings.data.exportEverything',
      'settings.data.exportEverythingDescription',
      'settings.data.exportEverythingButton',
      'settings.data.exportedEverything',
      'settings.data.exportEverythingEmpty',
      'settings.data.exportEverythingFailed',
      'settings.data.exportCollections',
      'settings.data.exportCollectionsDescription',
      'settings.data.exportCollectionsButton',
      'settings.data.collectionsExported',
      'settings.data.collectionsExportEmpty',
      'settings.data.collectionsExportFailed',
      'settings.data.exportOneBook',
      'settings.data.exportOneBookDescription',
      'settings.data.exportOneBookButton',
      'settings.data.selectBook',
      'settings.data.bookExported',
      'settings.data.bookExportNone',
      'settings.data.bookExportFailed',
      'settings.data.annotationsOnlyWithNote',
    ] as const;

    for (const key of exportKeys) {
      expect(messagesEn[key]).toBeDefined();
      expect(messagesEs[key]).toBeDefined();
      // Spanish is a real translation, never the English fallback.
      expect(messagesEs[key]).not.toBe(messagesEn[key]);
    }

    // The old lossy promise must be gone: the library file is metadata only.
    expect(messagesEn['settings.data.exportLibraryDescription']).not.toContain(
      'Download all your books',
    );
    expect(messagesEs['settings.data.exportLibraryDescription']).not.toContain(
      'Descarga todos tus libros',
    );
    // Both locales state plainly that book files are not included.
    expect(messagesEn['settings.data.exportFilesNotIncluded']).toContain('never the book files');
    expect(messagesEs['settings.data.exportFilesNotIncluded']).toContain(
      'nunca los archivos de los libros',
    );
  });

  it('includes the addon read-sheet keys in both locales', () => {
    const readSheetKeys = [
      'addons.readSheet.resolving',
      'addons.readSheet.downloading',
      'addons.readSheet.error',
      'addons.readSheet.legalNotice',
      'addons.readSheet.empty.title',
      'addons.readSheet.empty.body',
    ] as const;

    for (const key of readSheetKeys) {
      expect(messagesEn[key]).toBeDefined();
      expect(messagesEs[key]).toBeDefined();
    }

    // Exact copy: progress states + the empty state.
    expect(messagesEn['addons.readSheet.downloading']).toBe('Downloading…');
    expect(messagesEs['addons.readSheet.downloading']).toBe('Descargando…');
    expect(messagesEn['addons.readSheet.empty.title']).toBe('No reading access');
    expect(messagesEs['addons.readSheet.empty.title']).toBe('Sin acceso de lectura');
  });
});
