import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { createSettingsData } from '$lib/features/settings/useSettingsData.svelte';

describe('useSettingsData', () => {
  it('defaults and isDirty false', () => {
    const d = createSettingsData();
    expect(d.selectedExportBook).toBe('all');
    expect(d.selectedExportFormat).toBe('json');
    expect(d.isDirty).toBe(false);
    expect(d.isSaving).toBe(false);
  });

  it('export book/format change marks dirty', () => {
    const d = createSettingsData();
    d.handleSelectedExportBookChange('book-1');
    expect(d.isDirty).toBe(true);
    d.handleSelectedExportBookChange('all');
    d.handleSelectedExportFormatChange('markdown');
    expect(d.isDirty).toBe(true);
  });

  it('handleClearCache calls storageState.clearCache and toasts success', async () => {
    const clearCache = vi.fn().mockResolvedValue({ freedBytes: 123 });
    const pushToast = vi.fn();
    const t = vi.fn((k: string) => k);
    const storageState = { clearCache } as unknown as Parameters<
      typeof createSettingsData
    >[0] extends { storageState?: infer S }
      ? S
      : never;
    const d = createSettingsData({
      storageState: storageState as never,
      pushToast: pushToast as never,
      t: t as never,
    });
    await d.handleClearCache();
    expect(clearCache).toHaveBeenCalledWith('temp', false);
    expect(d.cacheCleared).toBe(true);
    expect(pushToast).toHaveBeenCalledWith('success', expect.any(String));
  });

  it('handleClearCache toasts permission_denied on that error', async () => {
    const clearCache = vi.fn().mockRejectedValue(new Error('storage.permission_denied'));
    const pushToast = vi.fn();
    const d = createSettingsData({
      storageState: { clearCache } as never,
      pushToast: pushToast as never,
    });
    await d.handleClearCache();
    expect(pushToast).toHaveBeenCalledWith('error', 'storage.permission_denied');
  });

  it('handleExportColdBackup calls Drive service when userId present and Drive authorized', async () => {
    const exportColdBackup = vi.fn().mockResolvedValue(undefined);
    const pushToast = vi.fn();
    const t = vi.fn((k: string) => k);
    const authState = { userId: 'user-1' } as never;
    const DriveColdBackupService = { exportColdBackup } as never;
    const d = createSettingsData({
      authState,
      DriveColdBackupService,
      drive: { isAuthorized: async () => true },
      pushToast: pushToast as never,
      t: t as never,
    });
    await d.handleExportColdBackup();
    expect(exportColdBackup).toHaveBeenCalledWith('user-1');
    expect(pushToast).toHaveBeenCalledWith('success', expect.any(String));
  });

  it('handleImportColdBackup calls import when userId present and Drive authorized', async () => {
    const importColdBackup = vi.fn().mockResolvedValue(undefined);
    const pushToast = vi.fn();
    const t = vi.fn((k: string) => k);
    const authState = { userId: 'u1' } as never;
    const DriveColdBackupService = { importColdBackup } as never;
    const d = createSettingsData({
      authState,
      DriveColdBackupService,
      drive: { isAuthorized: async () => true },
      pushToast: pushToast as never,
      t: t as never,
    });
    await d.handleImportColdBackup();
    expect(importColdBackup).toHaveBeenCalledWith('u1');
  });

  it('handleImportColdBackup surfaces ONE aggregated toast when some rows fail', async () => {
    const importColdBackup = vi.fn().mockResolvedValue({
      books: 1,
      progress: 0,
      highlights: 0,
      bookmarks: 0,
      sessions: 0,
      totalImported: 1,
      failures: [
        { entity: 'book', id: 'b2', error: { code: 'AUTH_REQUIRED' } },
        { entity: 'book', id: 'b3', error: { code: 'UNAVAILABLE' } },
      ],
    });
    const pushToast = vi.fn();
    const t = vi.fn((k: string) => k);
    const d = createSettingsData({
      authState: { userId: 'u1' } as never,
      DriveColdBackupService: { importColdBackup } as never,
      drive: { isAuthorized: async () => true },
      pushToast: pushToast as never,
      t: t as never,
    });
    await d.handleImportColdBackup();
    expect(importColdBackup).toHaveBeenCalledWith('u1');
    // Exactly one notification for the whole operation — not one per row.
    expect(pushToast).toHaveBeenCalledTimes(1);
    expect(pushToast).toHaveBeenCalledWith('error', 'settings.data.importPartialFailure');
    expect(t).toHaveBeenCalledWith('settings.data.importPartialFailure', {
      count: 2,
      codes: 'AUTH_REQUIRED, UNAVAILABLE',
    });
  });

  it('handleImportColdBackup stays silent about failures on a fully successful run', async () => {
    const importColdBackup = vi.fn().mockResolvedValue({ totalImported: 3, failures: [] });
    const pushToast = vi.fn();
    const t = vi.fn((k: string) => k);
    const d = createSettingsData({
      authState: { userId: 'u1' } as never,
      DriveColdBackupService: { importColdBackup } as never,
      drive: { isAuthorized: async () => true },
      pushToast: pushToast as never,
      t: t as never,
    });
    await d.handleImportColdBackup();
    // No failure notification; the existing success toast is unchanged.
    expect(pushToast).toHaveBeenCalledTimes(1);
    expect(pushToast).toHaveBeenCalledWith('success', 'settings.data.importSuccess');
  });

  it('handleExportColdBackup routes unauthorized to the connect CTA toast without Drive I/O', async () => {
    const exportColdBackup = vi.fn().mockResolvedValue(undefined);
    const pushToast = vi.fn();
    const t = vi.fn((k: string) => k);
    const d = createSettingsData({
      authState: { userId: 'user-1' } as never,
      DriveColdBackupService: { exportColdBackup } as never,
      drive: { isAuthorized: async () => false },
      pushToast: pushToast as never,
      t: t as never,
    });
    await d.handleExportColdBackup();
    expect(exportColdBackup).not.toHaveBeenCalled();
    expect(pushToast).toHaveBeenCalledWith('error', 'settings.data.driveNotConnected');
    expect(d.isExportingColdBackup).toBe(false);
  });

  it('handleImportColdBackup routes unauthorized to the connect CTA toast without Drive I/O', async () => {
    const importColdBackup = vi.fn().mockResolvedValue(undefined);
    const pushToast = vi.fn();
    const t = vi.fn((k: string) => k);
    const d = createSettingsData({
      authState: { userId: 'u1' } as never,
      DriveColdBackupService: { importColdBackup } as never,
      drive: { isAuthorized: async () => false },
      pushToast: pushToast as never,
      t: t as never,
    });
    await d.handleImportColdBackup();
    expect(importColdBackup).not.toHaveBeenCalled();
    expect(pushToast).toHaveBeenCalledWith('error', 'settings.data.driveNotConnected');
    expect(d.isImportingColdBackup).toBe(false);
  });

  it('handleExportColdBackup routes a mid-flight DRIVE_NOT_CONNECTED to the connect CTA toast', async () => {
    const exportColdBackup = vi
      .fn()
      .mockRejectedValue(
        Object.assign(new Error('not connected'), { code: 'DRIVE_NOT_CONNECTED' }),
      );
    const pushToast = vi.fn();
    const t = vi.fn((k: string) => k);
    const d = createSettingsData({
      authState: { userId: 'user-1' } as never,
      DriveColdBackupService: { exportColdBackup } as never,
      drive: { isAuthorized: async () => true },
      pushToast: pushToast as never,
      t: t as never,
    });
    await d.handleExportColdBackup();
    expect(pushToast).toHaveBeenCalledWith('error', 'settings.data.driveNotConnected');
  });

  it('handleExportColdBackup errors when no userId', async () => {
    const pushToast = vi.fn();
    const t = vi.fn((k: string) => k);
    const authState = { userId: null } as never;
    const d = createSettingsData({ authState, pushToast: pushToast as never, t: t as never });
    await d.handleExportColdBackup();
    expect(pushToast).toHaveBeenCalledWith('error', expect.any(String));
  });
});

describe('useSettingsData dictionary transfer', () => {
  let downloaded: string | null;

  beforeEach(() => {
    downloaded = null;
    (URL as unknown as Record<string, unknown>).createObjectURL = vi.fn(() => 'blob:mock');
    (URL as unknown as Record<string, unknown>).revokeObjectURL = vi.fn();
    vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(function (
      this: HTMLAnchorElement,
    ) {
      downloaded = this.download;
    });
  });

  afterEach(() => {
    vi.restoreAllMocks();
  });

  it('exports JSON through the dictionary store and downloads dictionary.json', async () => {
    const exportData = vi.fn().mockResolvedValue('{"words":[]}');
    const d = createSettingsData({
      dictionaryState: { exportData, importData: vi.fn() } as never,
    });

    await d.handleExportDictionary('json');

    expect(exportData).toHaveBeenCalledWith('json');
    expect(downloaded).toBe('dictionary.json');
    expect(d.isExportingDictionary).toBe(false);
    expect(d.dictionaryExportError).toBeNull();
  });

  it('exports CSV through the dictionary store and downloads dictionary.csv', async () => {
    const exportData = vi.fn().mockResolvedValue('word,tags');
    const d = createSettingsData({
      dictionaryState: { exportData, importData: vi.fn() } as never,
    });

    await d.handleExportDictionary('csv');

    expect(exportData).toHaveBeenCalledWith('csv');
    expect(downloaded).toBe('dictionary.csv');
    expect(d.isExportingDictionary).toBe(false);
  });

  it('imports a csv file, reports counts and joins row errors with the row formatter', async () => {
    const importData = vi.fn().mockResolvedValue({
      imported: 3,
      errors: [
        { row: 2, reason: 'bad word' },
        { row: 5, reason: 'bad tags' },
      ],
    });
    const t = vi.fn((k: string, params?: Record<string, string | number>) =>
      params ? `${k}:${JSON.stringify(params)}` : k,
    );
    const d = createSettingsData({
      dictionaryState: { exportData: vi.fn(), importData } as never,
      t: t as never,
    });
    const file = { name: 'words.csv', text: async () => 'a,b' } as unknown as File;

    await d.handleImportDictionary(file);

    expect(importData).toHaveBeenCalledWith('a,b', 'csv');
    expect(d.dictionaryImportResult).toContain('settings.data.dictionary.imported');
    expect(d.dictionaryImportResult).toContain('"imported":3');
    expect(d.dictionaryImportResult).toContain('"errors":2');
    expect(d.dictionaryImportError).toContain('settings.data.dictionary.rowError');
    expect(d.dictionaryImportError).toContain('; ');
    expect(d.isImportingDictionary).toBe(false);
  });

  it('defaults a non-csv file to json and leaves no error when the import is clean', async () => {
    const importData = vi.fn().mockResolvedValue({ imported: 1, errors: [] });
    const d = createSettingsData({
      dictionaryState: { exportData: vi.fn(), importData } as never,
    });
    const file = { name: 'words.json', text: async () => '[]' } as unknown as File;

    await d.handleImportDictionary(file);

    expect(importData).toHaveBeenCalledWith('[]', 'json');
    expect(d.dictionaryImportError).toBeNull();
    expect(d.isImportingDictionary).toBe(false);
  });

  it('surfaces the store error message when the import rejects', async () => {
    const importData = vi.fn().mockRejectedValue(new Error('bad payload'));
    const d = createSettingsData({
      dictionaryState: { exportData: vi.fn(), importData } as never,
    });
    const file = { name: 'words.json', text: async () => '[]' } as unknown as File;

    await d.handleImportDictionary(file);

    expect(d.dictionaryImportError).toBe('bad payload');
    expect(d.dictionaryImportResult).toBeNull();
  });
});
