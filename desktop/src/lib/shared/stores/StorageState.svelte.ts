import { invoke } from '$lib/shared/api/invokeWrapper';
import { getDriveUsage, type DriveUsage } from '$lib/shared/services/storage/DriveUsageService';

export type StorageStats = {
  totalBytes: number;
  dbBytes: number;
  coversBytes: number;
  tempBytes: number;
  cacheBytes: number;
  coverBytes: number;
};

export type PerBookSize = {
  id: string;
  title: string;
  bytes: number;
};

export function createStorageState() {
  let stats = $state<StorageStats | null>(null);
  let perBookSizes = $state<PerBookSize[]>([]);
  let isLoading = $state(false);
  let error = $state<string | null>(null);
  let isClearing = $state(false);
  let driveUsage = $state<DriveUsage | null>(null);
  let isLoadingDriveUsage = $state(false);

  async function loadStats(): Promise<void> {
    isLoading = true;
    error = null;
    try {
      const res = await invoke<StorageStats>('getStorageStats');
      stats = res;
    } catch (e) {
      const msg = e instanceof Error ? e.message : String(e);
      error = msg;
      stats = null;
    } finally {
      isLoading = false;
    }
  }

  async function loadDriveUsage(force = false): Promise<void> {
    isLoadingDriveUsage = true;
    try {
      driveUsage = await getDriveUsage({ force });
    } finally {
      isLoadingDriveUsage = false;
    }
  }

  async function clearCache(
    kind: 'covers' | 'temp' | 'all',
    deep = false,
  ): Promise<{ freedBytes: number }> {
    isClearing = true;
    error = null;
    try {
      const res = await invoke<{ freedBytes: number }>('clearCache', { kind, deep });
      await loadStats();
      return res;
    } catch (e) {
      const msg = e instanceof Error ? e.message : String(e);
      error = msg;
      throw e;
    } finally {
      isClearing = false;
    }
  }

  async function getPerBookSizes(): Promise<PerBookSize[]> {
    try {
      const res = await invoke<PerBookSize[]>('getPerBookSizes');
      perBookSizes = res;
      return res;
    } catch (e) {
      error = e instanceof Error ? e.message : String(e);
      throw e;
    }
  }

  async function deleteBookData(bookId: string): Promise<void> {
    await invoke('deleteBookData', { bookId });
    await Promise.all([loadStats(), getPerBookSizes()]);
  }

  async function cleanupOrphans(): Promise<{ removed: number }> {
    const res = await invoke<{ removed: number }>('cleanupOrphans');
    await loadStats();
    return res;
  }

  return {
    get stats() {
      return stats;
    },
    get perBookSizes() {
      return perBookSizes;
    },
    get isLoading() {
      return isLoading;
    },
    get error() {
      return error;
    },
    get isClearing() {
      return isClearing;
    },
    get driveUsage() {
      return driveUsage;
    },
    get isLoadingDriveUsage() {
      return isLoadingDriveUsage;
    },
    loadStats,
    loadDriveUsage,
    clearCache,
    getPerBookSizes,
    deleteBookData,
    cleanupOrphans,
  };
}

export const storageState = createStorageState();
