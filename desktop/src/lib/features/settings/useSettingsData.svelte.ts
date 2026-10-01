import { storageState as defaultStorageState } from '$lib/shared/stores/StorageState.svelte';
import { DriveColdBackupService as DefaultDriveColdBackupService } from '$lib/shared/services';
import { isDriveAuthorized as defaultIsDriveAuthorized } from '$lib/shared/services/DriveConnectService';
import { authState as defaultAuthState } from '$lib/shared/stores/AuthState.svelte';
import { pushToast as defaultPushToast } from '$lib/shared/stores/ToastQueue.svelte';
import {
  dictionaryState as defaultDictionaryState,
  type DictionaryStateApi,
} from '$lib/shared/stores/DictionaryState.svelte';

export type DriveAuthDep = {
  isAuthorized: () => Promise<boolean>;
};

/** The two store operations the dictionary transfer needs; nothing else. */
export type DictionaryTransferDep = Pick<DictionaryStateApi, 'exportData' | 'importData'>;

export type DataDeps = {
  storageState?: typeof defaultStorageState;
  DriveColdBackupService?: typeof DefaultDriveColdBackupService;
  drive?: DriveAuthDep;
  authState?: typeof defaultAuthState;
  pushToast?: typeof defaultPushToast;
  t?: (key: string, params?: Record<string, string | number>) => string;
  dictionaryState?: DictionaryTransferDep;
};

export function createSettingsData(deps: DataDeps = {}): {
  isClearingCache: boolean;
  cacheCleared: boolean;
  selectedExportBook: string;
  selectedExportFormat: 'json' | 'markdown';
  isExportingHighlights: boolean;
  isExportingColdBackup: boolean;
  isImportingColdBackup: boolean;
  isExportingDictionary: boolean;
  isImportingDictionary: boolean;
  dictionaryExportError: string | null;
  dictionaryImportResult: string | null;
  dictionaryImportError: string | null;
  isSaving: boolean;
  isDirty: boolean;
  handleClearCache: () => Promise<void>;
  handleExportHighlights: () => Promise<void>;
  handleExportColdBackup: () => Promise<void>;
  handleImportColdBackup: () => Promise<void>;
  handleExportDictionary: (format: 'json' | 'csv') => Promise<void>;
  handleImportDictionary: (file: File) => Promise<void>;
  handleSelectedExportBookChange: (value: string) => void;
  handleSelectedExportFormatChange: (value: 'json' | 'markdown') => void;
} {
  const storage = deps.storageState ?? defaultStorageState;
  const ColdBackup = deps.DriveColdBackupService ?? DefaultDriveColdBackupService;
  const drive: DriveAuthDep = deps.drive ?? { isAuthorized: defaultIsDriveAuthorized };
  const auth = deps.authState ?? defaultAuthState;
  const pushToast = deps.pushToast ?? defaultPushToast;
  const t = deps.t ?? ((k: string) => k);
  const dictionary = deps.dictionaryState ?? defaultDictionaryState;

  let isClearingCache = $state(false);
  let cacheCleared = $state(false);
  let selectedExportBook = $state('all');
  let selectedExportFormat = $state<'json' | 'markdown'>('json');
  let isExportingHighlights = $state(false);
  let isExportingColdBackup = $state(false);
  let isImportingColdBackup = $state(false);
  let isExportingDictionary = $state(false);
  let isImportingDictionary = $state(false);
  let dictionaryExportError = $state<string | null>(null);
  let dictionaryImportResult = $state<string | null>(null);
  let dictionaryImportError = $state<string | null>(null);

  const isSaving = $derived(
    isClearingCache || isExportingHighlights || isExportingColdBackup || isImportingColdBackup,
  );
  const isDirty = $derived(selectedExportBook !== 'all' || selectedExportFormat !== 'json');

  async function handleClearCache(): Promise<void> {
    isClearingCache = true;
    try {
      await storage.clearCache('temp', false);
      cacheCleared = true;
      pushToast('success', t('settings.data.cacheClearedToast'));
    } catch (e) {
      const msg = e instanceof Error ? e.message : String(e);
      if (msg.includes('storage.permission_denied')) {
        pushToast('error', 'storage.permission_denied');
      } else {
        pushToast('error', msg);
      }
    } finally {
      isClearingCache = false;
    }
  }

  async function handleExportHighlights(): Promise<void> {
    isExportingHighlights = true;
    try {
      await new Promise((resolve) => setTimeout(resolve, 500));
    } finally {
      isExportingHighlights = false;
    }
  }

  // Q4 decided: CTA-only. Export/import handlers never raise a modal —
  // the DriveConnectPrompt dialog stays scoped to the cloud-download
  // pre-prompt. Unauthorized cold backup routes to the Data-tab connect CTA
  // via this toast; the tab CTA performs the connect.
  function driveNotConnectedToast(): void {
    pushToast('error', t('settings.data.driveNotConnected'));
  }

  function isDriveConnectError(e: unknown): boolean {
    const code = (e as { code?: unknown } | null)?.code;
    return code === 'DRIVE_NOT_CONNECTED' || code === 'AUTH_REQUIRED';
  }

  async function handleExportColdBackup(): Promise<void> {
    const userId = auth.userId;
    if (!userId) {
      pushToast('error', t('errors.commandFailure'));
      return;
    }
    let authorized = false;
    try {
      authorized = await drive.isAuthorized();
    } catch {
      authorized = false;
    }
    if (!authorized) {
      driveNotConnectedToast();
      return;
    }
    isExportingColdBackup = true;
    try {
      await ColdBackup.exportColdBackup(userId);
      pushToast('success', t('settings.data.exportSuccess'));
    } catch (e) {
      if (isDriveConnectError(e)) driveNotConnectedToast();
      else pushToast('error', e instanceof Error ? e.message : t('errors.commandFailure'));
    } finally {
      isExportingColdBackup = false;
    }
  }

  async function handleImportColdBackup(): Promise<void> {
    const userId = auth.userId;
    if (!userId) {
      pushToast('error', t('errors.commandFailure'));
      return;
    }
    let authorized = false;
    try {
      authorized = await drive.isAuthorized();
    } catch {
      authorized = false;
    }
    if (!authorized) {
      driveNotConnectedToast();
      return;
    }
    isImportingColdBackup = true;
    try {
      const result = await ColdBackup.importColdBackup(userId);
      const failures = result?.failures ?? [];
      if (failures.length > 0) {
        const codes = [...new Set(failures.map((f) => f.error.code))].join(', ');
        pushToast(
          'error',
          t('settings.data.importPartialFailure', { count: failures.length, codes }),
        );
      } else {
        pushToast('success', t('settings.data.importSuccess'));
      }
    } catch (e) {
      if (isDriveConnectError(e)) driveNotConnectedToast();
      else pushToast('error', e instanceof Error ? e.message : t('errors.importCommandFailed'));
    } finally {
      isImportingColdBackup = false;
    }
  }

  /**
   * The dictionary transfer lives here (not on the dictionary screen) and is
   * deliberately independent from the cold-backup handlers above: it never
   * touches Drive, auth or the library — it only calls the dictionary store.
   */
  async function handleExportDictionary(format: 'json' | 'csv'): Promise<void> {
    dictionaryExportError = null;
    isExportingDictionary = true;
    try {
      const data = await dictionary.exportData(format);
      const blob = new Blob([data], {
        type: format === 'json' ? 'application/json' : 'text/csv',
      });
      const url = URL.createObjectURL(blob);
      const a = document.createElement('a');
      a.href = url;
      a.download = `dictionary.${format}`;
      a.click();
      URL.revokeObjectURL(url);
    } catch (e) {
      dictionaryExportError =
        e instanceof Error ? e.message : t('settings.data.dictionary.exportFailed');
    } finally {
      isExportingDictionary = false;
    }
  }

  async function handleImportDictionary(file: File): Promise<void> {
    dictionaryImportError = null;
    dictionaryImportResult = null;
    isImportingDictionary = true;
    try {
      const text = await file.text();
      const format = file.name.endsWith('.csv') ? 'csv' : 'json';
      const res = await dictionary.importData(text, format);
      dictionaryImportResult = t('settings.data.dictionary.imported', {
        imported: res.imported,
        errors: res.errors.length,
      });
      if (res.errors.length) {
        dictionaryImportError = res.errors
          .map((x) => t('settings.data.dictionary.rowError', { row: x.row, reason: x.reason }))
          .join('; ');
      }
    } catch (err) {
      dictionaryImportError =
        err instanceof Error ? err.message : t('settings.data.dictionary.importFailed');
    } finally {
      isImportingDictionary = false;
    }
  }

  function handleSelectedExportBookChange(value: string): void {
    selectedExportBook = value;
  }

  function handleSelectedExportFormatChange(value: 'json' | 'markdown'): void {
    selectedExportFormat = value;
  }

  return {
    get isClearingCache() {
      return isClearingCache;
    },
    set isClearingCache(v: boolean) {
      isClearingCache = v;
    },
    get cacheCleared() {
      return cacheCleared;
    },
    set cacheCleared(v: boolean) {
      cacheCleared = v;
    },
    get selectedExportBook() {
      return selectedExportBook;
    },
    set selectedExportBook(v: string) {
      selectedExportBook = v;
    },
    get selectedExportFormat() {
      return selectedExportFormat;
    },
    set selectedExportFormat(v: 'json' | 'markdown') {
      selectedExportFormat = v;
    },
    get isExportingHighlights() {
      return isExportingHighlights;
    },
    set isExportingHighlights(v: boolean) {
      isExportingHighlights = v;
    },
    get isExportingColdBackup() {
      return isExportingColdBackup;
    },
    set isExportingColdBackup(v: boolean) {
      isExportingColdBackup = v;
    },
    get isImportingColdBackup() {
      return isImportingColdBackup;
    },
    set isImportingColdBackup(v: boolean) {
      isImportingColdBackup = v;
    },
    get isExportingDictionary() {
      return isExportingDictionary;
    },
    set isExportingDictionary(v: boolean) {
      isExportingDictionary = v;
    },
    get isImportingDictionary() {
      return isImportingDictionary;
    },
    set isImportingDictionary(v: boolean) {
      isImportingDictionary = v;
    },
    get dictionaryExportError() {
      return dictionaryExportError;
    },
    set dictionaryExportError(v: string | null) {
      dictionaryExportError = v;
    },
    get dictionaryImportResult() {
      return dictionaryImportResult;
    },
    set dictionaryImportResult(v: string | null) {
      dictionaryImportResult = v;
    },
    get dictionaryImportError() {
      return dictionaryImportError;
    },
    set dictionaryImportError(v: string | null) {
      dictionaryImportError = v;
    },
    get isSaving() {
      return isSaving;
    },
    get isDirty() {
      return isDirty;
    },
    handleClearCache,
    handleExportHighlights,
    handleExportColdBackup,
    handleImportColdBackup,
    handleExportDictionary,
    handleImportDictionary,
    handleSelectedExportBookChange,
    handleSelectedExportFormatChange,
  };
}
