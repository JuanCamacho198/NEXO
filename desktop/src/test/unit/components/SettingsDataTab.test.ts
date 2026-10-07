import { fireEvent, render, screen, waitFor } from '@testing-library/svelte';
import { describe, expect, it, vi } from 'vitest';
import SettingsDataTab from '$lib/features/settings/components/SettingsDataTab.svelte';
import type { CollectionDto, LibraryBookDto } from '$lib/shared/types';

const messages: Record<string, string> = {
  'settings.data.dictionary.title': 'Dictionary',
  'settings.data.dictionary.description': 'Export or import your saved words as JSON or CSV.',
  'settings.data.dictionary.exportJson': 'Export JSON',
  'settings.data.dictionary.exportCsv': 'Export CSV',
  'settings.data.dictionary.import': 'Import',
  'settings.data.coldBackup': 'Cold Backup (Drive)',
  'settings.data.coldBackupDescription': 'Cold backup description',
  'settings.data.coldExport': 'Export to Drive',
  'settings.data.coldImport': 'Import from Drive',
  'settings.data.clearCache': 'Clear cache',
  'settings.data.clearCacheDescription': 'Remove temp files',
  'settings.data.clearing': 'Clearing...',
  'settings.data.cleared': 'Cache cleared',
  'settings.data.exportLibrary': 'Export library',
  'settings.data.exportLibraryTitle': 'Export your library',
  'settings.data.exportLibraryDescription': 'Download books',
  'settings.data.exportLibraryButton': 'Export library',
  'settings.data.exportFilesNotIncluded': 'These exports never include the book files.',
  'settings.data.exportEverything': 'Export everything',
  'settings.data.exportEverythingDescription': 'One file with every module',
  'settings.data.exportEverythingButton': 'Export everything',
  'settings.data.exportCollections': 'Export collections',
  'settings.data.exportCollectionsDescription': 'Your collections',
  'settings.data.exportCollectionsButton': 'Export collections',
  'settings.data.exportOneBook': 'Export one book',
  'settings.data.exportOneBookDescription': 'One book with its highlights',
  'settings.data.exportOneBookButton': 'Export book',
  'settings.data.selectBook': 'Select a book',
  'settings.data.annotationsOnlyWithNote': 'Only entries with a note',
  'settings.data.group.backup': 'Backup',
  'settings.data.group.export': 'Export',
  'settings.data.group.maintenance': 'Maintenance',
  'settings.data.group.privacyAddons': 'Privacy & advanced',
  'settings.data.group.notifications': 'Notifications',
  'settings.notifications.delivery.title': 'Notifications',
  'settings.notifications.delivery.description': 'Choose which notifications can interrupt you.',
  'settings.notifications.delivery.system': 'System notifications',
  'settings.notifications.delivery.systemHint': 'Imports, sync, addons and updates.',
  'settings.notifications.delivery.nudges': 'Nudges',
  'settings.notifications.delivery.nudgesHint': 'Streak and goal reminders.',
  'settings.notifications.delivery.quietHours': 'Quiet hours',
  'settings.notifications.delivery.quietHoursHint': 'During quiet hours nothing sounds.',
  'settings.notifications.delivery.quietStart': 'Start',
  'settings.notifications.delivery.quietEnd': 'End',
  'settings.data.libraryExported': 'Library exported',
  'settings.data.libraryExportEmpty': 'No books to export',
  'settings.data.libraryExportFailed': 'Could not export the library',
  'settings.data.highlightsExported': 'Highlights exported',
  'settings.data.highlightsExportEmpty': 'No highlights to export',
  'settings.data.highlightsExportFailed': 'Could not export highlights',
  'settings.data.cancel': 'Cancel',
  'settings.data.storageOptions': 'More options in Storage',
  'settings.data.exportHighlights': 'Export highlights',
  'settings.data.exportHighlightsDescription': 'Download annotations',
  'settings.data.download': 'Download',
  'settings.data.allBooks': 'All books',
  'settings.data.markdown': 'Markdown',
  'settings.privacy.title': 'Privacy',
  'settings.privacy.description': 'Telemetry',
  'settings.privacy.sendTelemetry': 'Send telemetry',
  'settings.privacy.telemetryOn': 'On',
  'settings.privacy.telemetryOff': 'Off',
};

const t = (key: string, params?: Record<string, string | number>): string => {
  const template = messages[key] ?? key;
  if (!params) return template;
  return Object.entries(params).reduce(
    (acc, [name, value]) => acc.replace(`{{${name}}}`, String(value)),
    template,
  );
};

function baseProps() {
  return {
    t,
    books: [] as LibraryBookDto[],
    collections: [] as CollectionDto[],
    isClearingCache: false,
    cacheCleared: false,
    selectedExportBook: 'all',
    selectedExportFormat: 'json' as 'json' | 'markdown',
    isExportingLibrary: false,
    isExportingHighlights: false,
    isExportingDictionary: false,
    isImportingDictionary: false,
    dictionaryExportError: null as string | null,
    dictionaryImportResult: null as string | null,
    dictionaryImportError: null as string | null,
    onClearCache: vi.fn(),
    onExportLibrary: vi.fn(),
    onExportHighlights: vi.fn(),
    onExportCollections: vi.fn(),
    onExportBook: vi.fn(),
    onExportEverything: vi.fn(),
    onAnnotationsOnlyWithNoteChange: vi.fn(),
    onExportDictionary: vi.fn(),
    onImportDictionary: vi.fn(),
    onNavigateToStorage: vi.fn(),
    onSelectedExportBookChange: vi.fn(),
    onSelectedExportFormatChange: vi.fn(),
  };
}

function renderTab(overrides: Partial<ReturnType<typeof baseProps>> = {}) {
  const props = { ...baseProps(), ...overrides };
  return { ...render(SettingsDataTab, { props }), props };
}

describe('SettingsDataTab dictionary transfer group', () => {
  it('renders the dictionary group beside the cold-backup pair, not merged with it', () => {
    renderTab();

    const group = screen.getByTestId('dictionary-transfer-actions');
    expect(screen.getByText('Dictionary')).toBeInTheDocument();
    expect(
      screen.getByText('Export or import your saved words as JSON or CSV.'),
    ).toBeInTheDocument();

    expect(screen.getByText('Export JSON')).toBeInTheDocument();
    expect(screen.getByText('Export CSV')).toBeInTheDocument();
    expect(screen.getByText('Import')).toBeInTheDocument();

    // The cold-backup controls stay outside the dictionary group.
    expect(screen.getByText('Export to Drive')).toBeInTheDocument();
    expect(screen.getByText('Import from Drive')).toBeInTheDocument();
    expect(group.contains(screen.getByText('Export to Drive'))).toBe(false);
    expect(group.contains(screen.getByText('Import from Drive'))).toBe(false);
  });

  it('renders the three dictionary controls at one weight and one height', () => {
    renderTab();

    // The reported defect: Export JSON carried Label weight while Export CSV
    // did not, and Import was a hand-rolled flex label. All three now go
    // through the Button atom, so they share one type role and one size.
    const json = screen.getByText('Export JSON').closest('button') as HTMLElement;
    const csv = screen.getByText('Export CSV').closest('button') as HTMLElement;
    const importControl = screen.getByText('Import').closest('label') as HTMLElement;
    const controls = [json, csv, importControl];

    for (const control of controls) {
      expect(control).not.toBeNull();
      expect(control).toHaveClass('text-sm', 'font-medium', 'inline-flex', 'px-4', 'py-2');
      expect(control).not.toHaveClass('text-xs');
    }

    const weights = controls.map((control) =>
      Array.from(control.classList).find((token) =>
        /^font-(normal|medium|semibold|bold)$/.test(token),
      ),
    );
    expect(weights).toEqual(['font-medium', 'font-medium', 'font-medium']);

    const verticalPadding = controls.map((control) =>
      Array.from(control.classList).find((token) => token.startsWith('py-')),
    );
    expect(verticalPadding).toEqual(['py-2', 'py-2', 'py-2']);
  });

  it('forwards each export format to the same handler', async () => {
    const { props } = renderTab();

    await fireEvent.click(screen.getByText('Export JSON'));
    expect(props.onExportDictionary).toHaveBeenCalledWith('json');

    await fireEvent.click(screen.getByText('Export CSV'));
    expect(props.onExportDictionary).toHaveBeenCalledWith('csv');
  });

  it('forwards the chosen file to the import handler and clears the input', async () => {
    const { props } = renderTab();
    const input = screen.getByTestId('dictionary-import-input') as HTMLInputElement;
    const file = new File(['a,b'], 'words.csv', { type: 'text/csv' });

    await fireEvent.change(input, { target: { files: [file] } });

    expect(props.onImportDictionary).toHaveBeenCalledWith(file);
    expect(input.value).toBe('');
  });

  it('reports export errors, import results and import errors distinctly', () => {
    renderTab({
      dictionaryExportError: 'disk full',
      dictionaryImportResult: 'Imported 3, errors 1',
      dictionaryImportError: 'row 2: bad word',
    });

    expect(screen.getByTestId('dictionary-export-error')).toHaveTextContent('disk full');
    expect(screen.getByTestId('dictionary-import-result')).toHaveTextContent(
      'Imported 3, errors 1',
    );
    expect(screen.getByTestId('dictionary-import-error')).toHaveTextContent('row 2: bad word');
  });

  it('disables the transfer controls while a transfer is in flight', () => {
    renderTab({ isExportingDictionary: true });

    expect(screen.getByText('Export JSON').closest('button')).toBeDisabled();
    expect(screen.getByText('Export CSV').closest('button')).toBeDisabled();
    expect(screen.getByTestId('dictionary-import-input')).toBeDisabled();
  });

  it('announces the dictionary import result and errors to assistive tech', () => {
    renderTab({
      dictionaryExportError: 'disk full',
      dictionaryImportResult: 'Imported 3, errors 1',
      dictionaryImportError: 'row 2: bad word',
    });

    expect(screen.getByTestId('dictionary-import-result')).toHaveAttribute('role', 'status');
    expect(screen.getByTestId('dictionary-import-result')).toHaveAttribute('aria-live', 'polite');
    expect(screen.getByTestId('dictionary-export-error')).toHaveAttribute('role', 'alert');
    expect(screen.getByTestId('dictionary-import-error')).toHaveAttribute('role', 'alert');
  });
});

describe('SettingsDataTab grouping and order', () => {
  it('renders the five group headings in safe-to-dangerous order', () => {
    renderTab();

    const headings = screen
      .getAllByRole('heading', { level: 3 })
      .map((heading) => heading.textContent?.trim());
    expect(headings).toEqual([
      'Backup',
      'Export',
      'Maintenance',
      'Privacy & advanced',
      'Notifications',
    ]);
  });

  it('places the cold backup before the export group and clear cache last', () => {
    const { container } = renderTab();
    const order = Array.from(container.querySelectorAll('[data-testid^="settings-group-"]')).map(
      (h) => h.getAttribute('data-testid'),
    );
    expect(order).toEqual([
      'settings-group-backup',
      'settings-group-export',
      'settings-group-maintenance',
      'settings-group-privacy-addons',
      'settings-group-notifications',
    ]);
  });
});

describe('SettingsDataTab clear-cache confirmation', () => {
  it('arms an inline confirmation on first click and only clears on confirm', async () => {
    const { props } = renderTab();

    await fireEvent.click(screen.getByTestId('clear-cache-button'));
    expect(screen.getByTestId('clear-cache-confirm')).toBeInTheDocument();
    expect(props.onClearCache).not.toHaveBeenCalled();

    await fireEvent.click(screen.getByTestId('clear-cache-confirm-button'));
    expect(props.onClearCache).toHaveBeenCalledTimes(1);
    expect(screen.queryByTestId('clear-cache-confirm')).not.toBeInTheDocument();
  });

  it('cancels without clearing', async () => {
    const { props } = renderTab();

    await fireEvent.click(screen.getByTestId('clear-cache-button'));
    await fireEvent.click(screen.getByTestId('clear-cache-cancel-button'));

    expect(screen.queryByTestId('clear-cache-confirm')).not.toBeInTheDocument();
    expect(props.onClearCache).not.toHaveBeenCalled();
  });

  it('separates the action label from the cache status and announces it', () => {
    renderTab({ cacheCleared: true });

    expect(screen.getByTestId('clear-cache-status')).toHaveTextContent('Cache cleared');
    expect(screen.getByTestId('clear-cache-status')).toHaveAttribute('role', 'status');
    // The action label is not replaced by the past-tense status.
    expect(screen.getByTestId('clear-cache-button')).toHaveTextContent('Clear cache');
  });

  it('links to the rich storage options', async () => {
    const { props } = renderTab();

    await fireEvent.click(screen.getByTestId('open-storage-tab'));
    expect(props.onNavigateToStorage).toHaveBeenCalledTimes(1);
  });
});

describe('SettingsDataTab export actions', () => {
  it('forwards the library export', async () => {
    const { props } = renderTab();
    await fireEvent.click(screen.getByTestId('export-library-button'));
    expect(props.onExportLibrary).toHaveBeenCalledTimes(1);
  });

  it('disables the library export button while exporting', () => {
    renderTab({ isExportingLibrary: true });
    expect(screen.getByTestId('export-library-button')).toBeDisabled();
  });

  it('forwards the highlights export', async () => {
    const { props } = renderTab();
    await fireEvent.click(screen.getByTestId('export-highlights-button'));
    expect(props.onExportHighlights).toHaveBeenCalledTimes(1);
  });
});

describe('SettingsDataTab export scopes and boundary (EXP-03/06/07)', () => {
  it('states plainly that book files are not included', () => {
    renderTab();
    expect(screen.getByTestId('export-files-boundary')).toHaveTextContent(
      'These exports never include the book files.',
    );
  });

  it('exposes the three scopes: everything, per domain, per book', () => {
    renderTab();
    expect(screen.getByTestId('export-everything-button')).toBeInTheDocument();
    expect(screen.getByTestId('export-library-button')).toBeInTheDocument();
    expect(screen.getByTestId('export-highlights-button')).toBeInTheDocument();
    expect(screen.getByTestId('export-collections-button')).toBeInTheDocument();
    expect(screen.getByTestId('export-book-button')).toBeInTheDocument();
  });

  it('forwards the everything export', async () => {
    const { props } = renderTab();
    await fireEvent.click(screen.getByTestId('export-everything-button'));
    expect(props.onExportEverything).toHaveBeenCalledTimes(1);
  });

  it('forwards the collections export and disables it when there are none', async () => {
    const { props } = renderTab({
      collections: [
        {
          id: 1,
          name: 'Favorites',
          color: null,
          isSystem: true,
          createdAt: '2026-01-01T00:00:00Z',
        },
      ],
    });
    await fireEvent.click(screen.getByTestId('export-collections-button'));
    expect(props.onExportCollections).toHaveBeenCalledTimes(1);
  });

  it('disables the collections export when empty', () => {
    renderTab({ collections: [] });
    expect(screen.getByTestId('export-collections-button')).toBeDisabled();
  });

  it('forwards the per-book export with the chosen book and stays disabled until one is picked', async () => {
    const books = [
      {
        id: 'b1',
        title: 'Dune',
        author: 'Frank Herbert',
        format: 'epub',
        currentPage: 1,
        totalPages: 10,
        progressPercentage: 10,
        coverPath: null,
        minutesRead: 0,
        updatedAt: '2026-01-01T00:00:00Z',
        createdAt: '2026-01-01T00:00:00Z',
      },
    ] as LibraryBookDto[];
    const { props } = renderTab({ books });
    expect(screen.getByTestId('export-book-button')).toBeDisabled();

    // bits-ui opens a Select on pointerdown (a click only refocuses), so the
    // opening and the pick dispatch the same pointer pair as Dropdown.test.
    const trigger = screen
      .getByTestId('export-book-scope')
      .querySelector('[aria-haspopup="listbox"]') as HTMLElement;
    await fireEvent.pointerDown(trigger, { pointerId: 1, pointerType: 'mouse', button: 0 });
    await fireEvent.pointerUp(trigger, { pointerId: 1, pointerType: 'mouse', button: 0 });
    await waitFor(() => expect(screen.queryByRole('listbox')).not.toBeNull());

    const option = screen.getByRole('option', { name: 'Dune' });
    await fireEvent.pointerDown(option, { pointerId: 1, pointerType: 'mouse', button: 0 });
    await fireEvent.pointerUp(option, { pointerId: 1, pointerType: 'mouse', button: 0 });
    await fireEvent.click(option);
    await waitFor(() => expect(screen.queryByRole('listbox')).toBeNull());

    await fireEvent.click(screen.getByTestId('export-book-button'));
    expect(props.onExportBook).toHaveBeenCalledWith('b1');
  });

  it('forwards the only-with-note filter toggle', async () => {
    const { props } = renderTab();
    const toggle = screen.getByTestId('annotations-only-with-note') as HTMLInputElement;
    expect(toggle.checked).toBe(false);

    await fireEvent.click(toggle);
    expect(props.onAnnotationsOnlyWithNoteChange).toHaveBeenCalledWith(true);
  });
});

describe('SettingsDataTab privacy switch', () => {
  it('exposes the telemetry control as a switch with aria-checked', () => {
    renderTab();

    const toggle = screen.getByRole('switch', { name: /Send telemetry/ });
    expect(toggle).toHaveAttribute('aria-checked', 'false');
  });
});
