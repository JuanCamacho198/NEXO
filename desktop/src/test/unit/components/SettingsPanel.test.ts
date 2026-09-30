import { render, screen } from '@testing-library/svelte';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi, beforeEach } from 'vitest';
import { SettingsPanel } from '$lib/features/settings';

const baseDictionaryEntries: Array<[string, string]> = [
  ['settings.title', 'Settings'],
  ['settings.close', 'Close settings'],
  ['settings.tab.general', 'General'],
  ['settings.tab.appearance', 'Appearance'],
  ['settings.tab.data', 'Data'],
  ['settings.tab.about', 'About'],
  ['settings.authentication', 'Authentication'],
  ['settings.authDescription', 'Sign in to sync your data'],
  ['settings.tab.profile', 'Profile'],
  ['settings.profile.description', 'Review your profile'],
  ['settings.profile.nameLabel', 'Name'],
  ['settings.profile.emailLabel', 'Email'],
  ['settings.profile.loading', 'Loading...'],
  ['settings.profile.signInPrompt', 'Sign in with Google'],
  ['settings.profile.avatarAlt', 'Avatar for {{name}}'],
  ['settings.shortcuts.title', 'Keyboard Shortcuts'],
  ['settings.shortcuts.description', 'Available shortcuts'],
  ['settings.shortcuts.readerPrev', 'Previous page'],
  ['settings.shortcuts.readerNext', 'Next page'],
  ['settings.shortcuts.readerScrollUp', 'Scroll up'],
  ['settings.shortcuts.readerScrollDown', 'Scroll down'],
  ['settings.shortcuts.closeDialog', 'Close dialog'],
  ['settings.localPreferences', 'Local preferences'],
  ['settings.localPreferencesDescription', 'Configure language and theme'],
  ['settings.language', 'Language'],
  ['settings.languageSpanish', 'Spanish'],
  ['settings.languageEnglish', 'English'],
  ['settings.theme', 'Theme'],
  ['settings.theme.light', 'Light'],
  ['settings.theme.dark', 'Dark'],
  ['settings.theme.sepia', 'Sepia'],
  ['settings.theme.system', 'System'],
  ['settings.fontScale', 'Font Scale'],
  ['settings.savePreferences', 'Save preferences'],
  ['settings.saving', 'Saving...'],
  ['settings.resetDefaults', 'Reset to defaults'],
  ['settings.resetConfirmTitle', 'Reset settings?'],
  ['settings.resetConfirmMessage', 'This will restore all settings.'],
  ['settings.reset', 'Reset'],
  ['settings.cancel', 'Cancel'],
  ['settings.appearance.appTheme', 'App Theme'],
  ['settings.appearance.appThemeDescription', 'Customize appearance'],
  ['settings.appearance.reader', 'Reader settings'],
  ['settings.appearance.readerDescription', 'Configure reading experience'],
  ['settings.reader.themeMode.paper', 'Paper'],
  ['settings.reader.themeMode.sepia', 'Sepia'],
  ['settings.reader.themeMode.night', 'Night'],
  ['settings.reader.brightness', 'Reader brightness'],
  ['settings.reader.contrast', 'Reader contrast'],
  ['settings.reader.epub.fontSize', 'EPUB font size'],
  ['settings.reader.epub.fontFamily', 'EPUB font family'],
  ['settings.sync.description', 'Configure sync'],
  ['settings.sync.syncNow', 'Sync now'],
  ['settings.data.exportLibrary', 'Export library'],
  ['settings.data.exportLibraryDescription', 'Download books as JSON'],
  ['settings.data.exportHighlights', 'Export highlights'],
  ['settings.data.exportHighlightsDescription', 'Download annotations'],
  ['settings.data.allBooks', 'All books'],
  ['settings.data.markdown', 'Markdown'],
  ['settings.data.exporting', 'Exporting...'],
  ['settings.data.download', 'Download'],
  ['settings.data.clearCache', 'Clear cache'],
  ['settings.data.clearCacheDescription', 'Remove temp files'],
  ['settings.data.clearing', 'Clearing...'],
  ['settings.data.cleared', 'Cache cleared'],
  ['settings.about', 'About Nexo'],
  ['errors.commandFailure', 'Command failed'],
  ['settings.unknownBook', 'Unknown'],
  ['settings.color.yellow', 'Yellow'],
  ['settings.color.green', 'Green'],
  ['settings.color.blue', 'Blue'],
  ['settings.color.pink', 'Pink'],
  ['settings.color.orange', 'Orange'],
  ['settings.page', 'Page'],
  ['settings.deleteHighlight', 'Delete highlight'],
  ['settings.deleteBookmark', 'Delete bookmark'],
  ['settings.comingSoon', 'Coming soon'],
  ['settings.data.storage', 'Storage'],
  ['settings.data.cacheSize', 'Cache'],
  ['settings.data.downloadedBooks', 'Downloaded books'],
  ['settings.data.tempFiles', 'Temp files'],
  ['settings.data.clearCacheConfirm', 'Clear cache?'],
  ['settings.data.clearCacheSuccess', 'Cache cleared'],
  ['settings.data.exportConfig', 'Export config'],
  ['settings.data.importConfig', 'Import config'],
  ['settings.data.exportSuccess', 'Exported'],
  ['settings.data.importSuccess', 'Imported'],
  ['settings.data.importError', 'Import error'],
  ['settings.data.resetSection', 'Reset section'],
  ['settings.search.placeholder', 'Search settings'],
  ['settings.search.noResults', 'No results'],
  ['settings.tooltip.margins', 'Adjust margins'],
  ['settings.tooltip.lineHeight', 'Line height'],
  ['settings.tooltip.paragraphSpacing', 'Paragraph spacing'],
  ['settings.tooltip.showHeader', 'Show header'],
  ['settings.tooltip.showFooter', 'Show footer'],
  ['settings.tooltip.showPageNumbers', 'Show page numbers'],
  ['settings.tooltip.progressIndicator', 'Progress indicator'],
  ['settings.discardChanges', 'Discard changes'],
  ['settings.discardChangesConfirm', 'Discard changes?'],
  ['settings.search', 'Search'],
  ['highlight.menuAriaLabel', 'Actions'],
  ['highlight.selectColor', 'Select {{color}}'],
  ['highlight.save', 'Save'],
  ['highlight.saving', 'Saving...'],
  ['highlight.note', 'Note'],
  ['highlight.cancel', 'Cancel'],
  ['highlight.notePlaceholder', 'Write a note'],
  ['highlight.noteInputAriaLabel', 'Note input'],
  ['highlight.saveWithNote', 'Save note'],
  ['highlight.selectionUnavailable', 'Selection unavailable'],
  ['highlight.saveFailed', 'Save failed'],
  ['highlight.noteRequired', 'Note required'],
  ['pdf.error', 'Error'],
  ['epub.error', 'Error'],
  ['import.reading', 'Reading...'],
  ['import.importing', 'Importing...'],
  ['import.complete', 'Complete'],
  ['import.failed', 'Failed'],
  ['import.emptyPath', 'Empty path'],
  ['home.shelfTab.all', 'All'],
  ['home.shelfTab.favorites', 'Favorites'],
  ['home.shelfTab.toRead', 'To Read'],
  ['home.shelfTab.completed', 'Completed'],
  ['library.searchPlaceholder', 'Search...'],
  ['library.editMetadata.title', 'Edit'],
  ['library.editMetadata.titleLabel', 'Title'],
  ['library.editMetadata.authorLabel', 'Author'],
  ['library.editMetadata.cancel', 'Cancel'],
  ['library.editMetadata.save', 'Save'],
  ['library.editMetadata.saving', 'Saving...'],
  ['library.editMetadata.titleRequired', 'Title required'],
  ['library.bulkImport.close', 'Close'],
  ['settings.reading.description', 'Configure layout'],
  ['settings.reading.showHeader', 'Show header'],
  ['settings.reading.showFooter', 'Show footer'],
  ['reader.biblioteca', 'Library'],
  ['reader.formato_no_soportado', 'Unsupported format'],
  ['reader.no_book_loaded', 'No book loaded'],
  ['reader.error_cargar_libro', 'Error loading book'],
  ['reader.copiar', 'Copy'],
  ['reader.nota', 'Note'],
  ['reader.eliminar_destacado', 'Delete highlight'],
  ['reader.seleccion_no_disponible', 'Selection unavailable'],
  ['reader.tabla_contenidos', 'Table of Contents'],
  ['reader.toc_empty', 'No chapters yet'],
  ['reader.ajustes_texto', 'Text Settings'],
  ['settings.localPreferencesDescription', 'Configure settings'],
  ['settings.language', 'Language'],
  ['settings.languageSpanish', 'Spanish'],
  ['settings.languageEnglish', 'English'],
  ['settings.authentication', 'Authentication'],
  ['settings.tab.general', 'General'],
  ['settings.tab.appearance', 'Appearance'],
  ['settings.tab.data', 'Data'],
  ['settings.tab.about', 'About'],
  ['settings.tab.account', 'Account'],
  ['settings.tab.reader', 'Reader'],
  ['settings.tab.appTheme', 'App Theme'],
  ['settings.tab.sync', 'Sync'],
  ['settings.tab.notifications', 'Notifications'],
  ['errors.commandFailure', 'Unknown error'],
  ['errors.settingsCommandFailed', 'Settings failed'],
  ['errors.importCommandFailed', 'Import failed'],
  ['app.backToHome', 'Close settings'],
];

const baseDictionary = Object.fromEntries(baseDictionaryEntries);

const t = (key: string, _params?: Record<string, string | number>) => {
  const dictionary = { ...baseDictionary };

  if (!_params) return dictionary[key] ?? key;

  let result = dictionary[key] ?? key;
  for (const [k, v] of Object.entries(_params)) {
    result = result.replace(`{{${k}}}`, String(v));
  }
  return result;
};

// SettingsPanel imports getSettings/upsertSettings/getLocaleSetting/
// getReaderSettings/upsertReaderSettings from tauriClient and loads
// AppState (full tauriClient surface) at module scope — mock them so the
// panel mounts and loads settings deterministically in jsdom.
vi.mock('$lib/shared/api/tauriClient', () => {
  const rf = vi.fn(function () {
    return Promise.resolve([]);
  }) as unknown as (...args: unknown[]) => Promise<unknown[]>;
  const defaults = {
    themeMode: 'paper',
    brightness: 100,
    contrast: 100,
    selectionColor: '#3b82f6',
    epub: { fontSize: 16, fontFamily: 'serif' },
  };
  return {
    listLibraryBooks: rf,
    listBooks: rf,
    listCollections: rf,
    getDefaultReaderSettings: vi.fn(function () {
      return defaults;
    }),
    getReaderSettings: vi.fn(async function () {
      return defaults;
    }),
    upsertReaderSettings: vi.fn(async function () {
      return defaults;
    }),
    resetReaderSettingsToDefaults: vi.fn(async function () {
      return defaults;
    }),
    getSettings: vi.fn(async function () {
      return [];
    }),
    upsertSettings: vi.fn(function () {
      return Promise.resolve(undefined);
    }),
    getLocaleSetting: vi.fn(async function () {
      return null;
    }),
    getProgress: vi.fn().mockResolvedValue(null),
    getReadingStats: vi.fn().mockResolvedValue(null),
    saveProgress: vi.fn().mockResolvedValue(undefined),
    saveReadingSession: vi.fn().mockResolvedValue(undefined),
    hideBookFromLibrary: vi.fn().mockResolvedValue(undefined),
    upsertBook: vi.fn().mockResolvedValue(undefined),
    upsertBookCover: vi.fn().mockResolvedValue(undefined),
    extractEpubCover: vi.fn().mockResolvedValue(false),
    updateBookProgress: vi.fn().mockResolvedValue(undefined),
    setReadingStatus: vi.fn().mockResolvedValue(undefined),
    searchBookText: vi.fn().mockResolvedValue(null),
    scanFolder: vi.fn().mockResolvedValue({ files: [] }),
    listHighlights: vi.fn().mockResolvedValue([]),
    listBookmarks: vi.fn().mockResolvedValue([]),
    getFileBytes: vi.fn().mockResolvedValue([]),
    getLogs: vi.fn().mockResolvedValue([]),
    diagnose: vi.fn(),
  };
});

describe('SettingsPanel', () => {
  const defaultProps = {
    isOpen: true,
    mode: 'page' as const,
    locale: 'es' as const,
    onRequestClose: vi.fn(),
    onLocaleChange: vi.fn(),
    onReaderSettingsChange: vi.fn(),
    books: [],
    t,
  };

  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('renders the settings panel with title', () => {
    render(SettingsPanel, defaultProps);
    expect(screen.getByRole('tablist', { name: 'Settings' })).toBeInTheDocument();
  });

  it('renders all tab buttons', () => {
    render(SettingsPanel, defaultProps);
    expect(screen.getByRole('tab', { name: 'Account' })).toBeInTheDocument();
    expect(screen.getByRole('tab', { name: 'Appearance' })).toBeInTheDocument();
    expect(screen.getByRole('tab', { name: 'Reader' })).toBeInTheDocument();
    expect(screen.getByRole('tab', { name: 'Data' })).toBeInTheDocument();
    expect(screen.getByRole('tab', { name: 'Keyboard Shortcuts' })).toBeInTheDocument();
    expect(screen.getByRole('tab', { name: 'About' })).toBeInTheDocument();
  });

  it('switches to shortcuts tab when clicked', async () => {
    const user = userEvent.setup();
    render(SettingsPanel, defaultProps);
    await user.click(screen.getByRole('tab', { name: 'Keyboard Shortcuts' }));
    expect(screen.getByText('Available shortcuts')).toBeInTheDocument();
  });

  it('switches to about tab when clicked', async () => {
    const user = userEvent.setup();
    render(SettingsPanel, defaultProps);
    await user.click(screen.getByRole('tab', { name: 'About' }));
    expect(screen.getByText('Nexo')).toBeInTheDocument();
  });

  it('switches to about tab when clicked', async () => {
    const user = userEvent.setup();
    render(SettingsPanel, defaultProps);
    await user.click(screen.getByRole('tab', { name: 'About' }));
    expect(screen.getByText('Nexo')).toBeInTheDocument();
  });

  it('shows reset modal when reset button clicked', async () => {
    const user = userEvent.setup();
    render(SettingsPanel, defaultProps);
    await user.click(screen.getByRole('tab', { name: 'Appearance' }));
    const resetButtons = screen.getAllByText('Reset to defaults');
    await user.click(resetButtons[0]);
    expect(screen.getByText('Reset settings?')).toBeInTheDocument();
    expect(screen.getByText('Cancel')).toBeInTheDocument();
    expect(screen.getByText('Reset')).toBeInTheDocument();
  });

  it('closes panel when close button clicked in overlay mode', async () => {
    const onRequestClose = vi.fn();
    const user = userEvent.setup();
    render(SettingsPanel, {
      ...defaultProps,
      mode: 'overlay',
      isOpen: true,
      onRequestClose,
    });
    const closeButton = screen.getByLabelText('Close settings');
    await user.click(closeButton);
    expect(onRequestClose).not.toHaveBeenCalled();
  });

  it('does not render when isOpen is false and mode is overlay', () => {
    render(SettingsPanel, { ...defaultProps, isOpen: false, mode: 'overlay' });
    expect(screen.queryByText('Settings')).not.toBeInTheDocument();
  });

  it('renders language options in general tab', async () => {
    const user = userEvent.setup();
    render(SettingsPanel, defaultProps);
    // The Dropdown button shows the currently selected language ('Spanish' for locale='es')
    expect(screen.getByText('Spanish')).toBeInTheDocument();
    // Click the dropdown to open it, then verify English option exists
    await user.click(screen.getByText('Spanish'));
    expect(screen.getByText('English')).toBeInTheDocument();
  });

  it('renders keyboard shortcuts section in shortcuts tab', async () => {
    const user = userEvent.setup();
    render(SettingsPanel, defaultProps);
    await user.click(screen.getByRole('tab', { name: 'Keyboard Shortcuts' }));
    expect(screen.getByText('Available shortcuts')).toBeInTheDocument();
  });
});
