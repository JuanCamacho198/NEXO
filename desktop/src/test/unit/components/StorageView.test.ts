import { fireEvent, render, screen, waitFor } from '@testing-library/svelte';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import type { LibraryBookDto } from '$lib/shared/types/library';

const { storageStateMock } = vi.hoisted(() => ({
  storageStateMock: {
    stats: null as null | Record<string, number>,
    perBookSizes: [] as Array<{ id: string; title: string; bytes: number }>,
    isLoading: false,
    error: null as string | null,
    isClearing: false,
    driveUsage: null as unknown,
    isLoadingDriveUsage: false,
    loadStats: vi.fn(),
    loadDriveUsage: vi.fn(),
    clearCache: vi.fn(),
    getPerBookSizes: vi.fn(),
    deleteBookData: vi.fn(),
    cleanupOrphans: vi.fn(),
  },
}));

vi.mock('$lib/shared/stores/StorageState.svelte', () => ({ storageState: storageStateMock }));

import StorageView from '$lib/features/storage/components/StorageView.svelte';

const dictionary: Record<string, string> = {
  'storage.title': 'Storage',
  'storage.subtitle': 'Manage cached files and storage usage.',
  'storage.loading': 'Loading storage stats…',
  'storage.retry': 'Retry',
  'storage.usageSummary': 'NEXO uses {{size}} on this device',
  'storage.freeSpace': 'Free up space',
  'storage.freeingSpace': 'Freeing up space…',
  'storage.freedToast': 'Freed {{size}}',
  'storage.breakdown.temporary': 'Temporary',
  'storage.breakdown.bookFiles': 'Book files',
  'storage.advanced': 'Advanced',
  'storage.perBook.title': 'Books on this device',
  'storage.perBook.count': '{{count}} books',
  'storage.perBook.remove': 'Remove from this device',
  'storage.perBook.removeConfirm':
    'Remove “{{title}}” from this device? It stays in your library and can be downloaded again.',
  'storage.perBook.removedToast': '“{{title}}” removed from this device',
  'storage.perBook.refresh': 'Refresh',
  'storage.perBook.empty': 'No books on this device',
  'storage.perBook.loading': 'Loading…',
  'storage.confirm': 'Remove',
  'storage.cancel': 'Cancel',
  'storage.drive.measuring': 'Measuring Drive usage…',
  'storage.drive.failed': 'Drive usage unavailable',
  'storage.drive.onDrive': 'On Drive: {{size}}',
  'storage.drive.measuredAgo': 'measured {{age}} ago',
  'storage.drive.refresh': 'Refresh Drive usage',
  'storage.permissionDenied': "NEXO doesn't have permission to free up space.",
  'errors.commandFailure': 'Command failed',
  'settings.data.catalogSyncPartial': '{{count}} failed ({{codes}})',
};

const t = (key: string, params?: Record<string, string | number>): string => {
  let result = dictionary[key] ?? key;
  for (const [k, v] of Object.entries(params ?? {})) {
    result = result.replace(`{{${k}}}`, String(v));
  }
  return result;
};

const stats = {
  totalBytes: 2_147_483_648,
  dbBytes: 1_073_741_824,
  coversBytes: 536_870_912,
  tempBytes: 536_870_912,
  cacheBytes: 0,
  coverBytes: 0,
};

const book = {
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
} as LibraryBookDto;

function renderView(overrides: Partial<typeof storageStateMock> = {}) {
  Object.assign(storageStateMock, overrides);
  return render(StorageView, { t, books: [book] });
}

describe('StorageView simplification', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    storageStateMock.stats = stats;
    storageStateMock.perBookSizes = [];
    storageStateMock.driveUsage = { state: 'not_connected' };
    storageStateMock.isLoadingDriveUsage = false;
    storageStateMock.error = null;
    storageStateMock.cleanupOrphans.mockResolvedValue({ removed: 0 });
    storageStateMock.clearCache.mockResolvedValue({ freedBytes: 1_048_576 });
    storageStateMock.deleteBookData.mockResolvedValue(undefined);
    storageStateMock.getPerBookSizes.mockResolvedValue([]);
  });

  it('shows a single headline number and a two-line plain-language breakdown', () => {
    renderView();

    expect(screen.getByTestId('storage-usage-summary')).toHaveTextContent(
      'NEXO uses 2.0 GB on this device',
    );
    const breakdown = screen.getByTestId('storage-breakdown');
    expect(breakdown).toHaveTextContent('Temporary');
    expect(breakdown).toHaveTextContent('Book files');

    // The old technical vocabulary and the fake 0% "Hot" affordance are gone.
    expect(screen.queryByText(/SQLite/)).not.toBeInTheDocument();
    expect(screen.queryByText(/Hot/)).not.toBeInTheDocument();
    expect(screen.queryByText(/VACUUM/)).not.toBeInTheDocument();
    expect(screen.queryByText(/Cleanup orphans/)).not.toBeInTheDocument();
    expect(screen.queryByText(/Export to Drive/)).not.toBeInTheDocument();
  });

  it('offers one primary action that clears regenerable data, vacuums and reports the amount', async () => {
    renderView();

    await fireEvent.click(screen.getByRole('button', { name: 'Free up space' }));

    await waitFor(() => expect(storageStateMock.clearCache).toHaveBeenCalledWith('temp', true));
    expect(storageStateMock.cleanupOrphans).toHaveBeenCalledTimes(1);
    // VACUUM is automatic now: the deep flag is passed, never a checkbox.
    expect(storageStateMock.clearCache).toHaveBeenCalledTimes(1);
    await waitFor(() =>
      expect(screen.getByTestId('storage-freed')).toHaveTextContent('Freed 1.0 MB'),
    );
  });

  it('hides per-book sizes and removal behind an Advanced disclosure and deletes only after inline confirmation', async () => {
    renderView({ perBookSizes: [{ id: 'b1', title: 'Dune', bytes: 1024 }] });

    const details = document.querySelector('details');
    expect(details).not.toBeNull();
    expect(screen.getByText('Advanced')).toBeInTheDocument();
    expect(screen.getByText('Remove from this device')).toBeInTheDocument();

    await fireEvent.click(screen.getByText('Remove from this device'));
    expect(storageStateMock.deleteBookData).not.toHaveBeenCalled();
    expect(screen.getByTestId('per-book-confirm')).toBeInTheDocument();

    await fireEvent.click(screen.getByRole('button', { name: 'Remove' }));
    await waitFor(() => expect(storageStateMock.deleteBookData).toHaveBeenCalledWith('b1'));
  });

  it('cancels the per-book removal without deleting', async () => {
    renderView({ perBookSizes: [{ id: 'b1', title: 'Dune', bytes: 1024 }] });

    await fireEvent.click(screen.getByText('Remove from this device'));
    await fireEvent.click(screen.getByRole('button', { name: 'Cancel' }));

    expect(screen.queryByTestId('per-book-confirm')).not.toBeInTheDocument();
    expect(storageStateMock.deleteBookData).not.toHaveBeenCalled();
  });

  it('renders no Drive line when Drive is not connected', () => {
    renderView({ driveUsage: { state: 'not_connected' } });
    expect(screen.queryByTestId('drive-line')).not.toBeInTheDocument();
  });

  it('renders the measured Drive usage as one text line, never as a fraction of the device', () => {
    renderView({
      driveUsage: { state: 'measured', bytes: 1_073_741_824, fileCount: 3, measuredAt: Date.now() },
    });

    const line = screen.getByTestId('drive-line');
    expect(line).toHaveTextContent('On Drive: 1.0 GB');
    // No progressbar: the old bars compared incomparable magnitudes.
    expect(screen.queryByRole('progressbar')).not.toBeInTheDocument();
  });

  it('surfaces a failed Drive measurement with text, not colour alone', () => {
    renderView({ driveUsage: { state: 'failed', message: 'network down' } });

    const line = screen.getByTestId('drive-line');
    expect(line).toHaveTextContent('Drive usage unavailable');
    expect(line).toHaveTextContent('network down');
  });

  it('moves focus into the injected inline confirmation', async () => {
    renderView({ perBookSizes: [{ id: 'b1', title: 'Dune', bytes: 1024 }] });

    await fireEvent.click(screen.getByText('Remove from this device'));

    await waitFor(() => {
      const confirm = screen.getByTestId('per-book-confirm');
      expect(confirm.contains(document.activeElement)).toBe(true);
    });
  });
});
