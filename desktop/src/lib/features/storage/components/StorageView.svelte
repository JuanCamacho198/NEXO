<script lang="ts">
  import type { MessageKey } from '$lib/shared/i18n';
  import type { LibraryBookDto } from '$lib/shared/types/library';
  import Button from '$lib/shared/ui/forms/Button.svelte';
  import { pushToast } from '$lib/shared/stores/ToastQueue.svelte';
  import { DriveColdBackupService } from '$lib/shared/services';
  import { authState } from '$lib/shared/stores/AuthState.svelte';
  import { storageState } from '$lib/shared/stores/StorageState.svelte';
  import StorageBreakdown from './StorageBreakdown.svelte';
  import { onMount } from 'svelte';

  type Props = {
    t: (key: MessageKey, params?: Record<string, string | number>) => string;
    books?: LibraryBookDto[];
  };

  let { t, books = [] }: Props = $props();

  let isExporting = $state(false);
  let isImporting = $state(false);
  let isOrphanCleaning = $state(false);
  let showClearConfirm = $state<null | 'covers' | 'temp' | 'all'>(null);
  let deepVacuum = $state(false);
  let perBookLoading = $state(false);

  onMount(() => {
    void storageState.loadStats();
    void storageState.loadDriveUsage();
    void loadPerBook();
  });

  async function loadPerBook(): Promise<void> {
    perBookLoading = true;
    try {
      await storageState.getPerBookSizes();
    } catch {
    } finally {
      perBookLoading = false;
    }
  }

  function formatBytes(bytes: number): string {
    if (bytes === 0) return '0 B';
    const k = 1024;
    const sizes = ['B', 'KB', 'MB', 'GB'];
    const i = Math.floor(Math.log(bytes) / Math.log(k));
    const val = bytes / Math.pow(k, i);
    return `${val.toFixed(i === 0 ? 0 : 1)} ${sizes[i]}`;
  }

  async function handleClearCache(kind: 'covers' | 'temp' | 'all'): Promise<void> {
    showClearConfirm = null;
    try {
      const res = await storageState.clearCache(kind, deepVacuum);
      pushToast(
        'success',
        `${t('settings.data.cacheClearedToast')} (${formatBytes(res.freedBytes)})`,
      );
      await loadPerBook();
    } catch (e) {
      const msg = e instanceof Error ? e.message : String(e);
      if (msg.includes('storage.permission_denied')) {
        pushToast('error', 'storage.permission_denied');
      } else {
        pushToast('error', msg);
      }
    }
  }

  async function handleDeleteBook(bookId: string): Promise<void> {
    if (!confirm('Delete book data?')) return;
    try {
      await storageState.deleteBookData(bookId);
      pushToast('success', 'Book data deleted');
    } catch (e) {
      pushToast('error', e instanceof Error ? e.message : String(e));
    }
  }

  async function handleCleanupOrphans(): Promise<void> {
    isOrphanCleaning = true;
    try {
      const res = await storageState.cleanupOrphans();
      pushToast('success', `Orphans cleaned: ${res.removed}`);
      await loadPerBook();
    } catch (e) {
      pushToast('error', e instanceof Error ? e.message : String(e));
    } finally {
      isOrphanCleaning = false;
    }
  }

  async function handleExportCold(): Promise<void> {
    if (!authState.userId) {
      pushToast('error', t('errors.commandFailure'));
      return;
    }
    isExporting = true;
    try {
      await DriveColdBackupService.exportColdBackup(authState.userId);
      pushToast('success', t('settings.data.exportSuccess'));
    } catch (e) {
      pushToast('error', e instanceof Error ? e.message : t('errors.commandFailure'));
    } finally {
      isExporting = false;
    }
  }

  async function handleImportCold(): Promise<void> {
    if (!authState.userId) {
      pushToast('error', t('errors.commandFailure'));
      return;
    }
    isImporting = true;
    try {
      const result = await DriveColdBackupService.importColdBackup(authState.userId);
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
      pushToast('error', e instanceof Error ? e.message : t('errors.importCommandFailed'));
    } finally {
      isImporting = false;
    }
  }

  const stats = $derived(storageState.stats);
</script>

<section class="space-y-5 w-full max-w-none">
  <header class="flex flex-col gap-1">
    <h1 class="text-3xl font-semibold tracking-tight text-(--color-primary)">
      {t('storage.title')}
    </h1>
    <p class="text-sm text-(--color-text-muted)">{t('storage.subtitle')}</p>
  </header>

  {#if storageState.isLoading}
    <div class="rounded-xl border border-(--color-border) bg-(--color-surface) p-5">
      <p class="text-sm text-(--color-text-muted)">Loading storage stats...</p>
    </div>
  {:else if storageState.error}
    <div class="rounded-xl border border-red-300 bg-red-50 p-5">
      <p class="text-sm text-red-800">{storageState.error}</p>
      <Button size="sm" variant="ghost" onclick={() => void storageState.loadStats()}>Retry</Button>
    </div>
  {:else if stats}
    <div class="rounded-xl border border-(--color-border) bg-(--color-surface) p-5 space-y-4">
      <div class="flex items-center justify-between">
        <div>
          <h3 class="text-sm font-semibold text-(--color-primary)">{t('settings.data.storage')}</h3>
          <p class="text-xs text-(--color-text-muted)">
            {t('settings.data.cacheSize')}: {formatBytes(stats.totalBytes)} · {t(
              'settings.data.downloadedBooks',
            )}: {books.length}
          </p>
          <p class="text-2xs text-(--color-text-muted)">
            DB {formatBytes(stats.dbBytes)} · Covers {formatBytes(stats.coversBytes)} · Temp {formatBytes(
              stats.tempBytes,
            )}
          </p>
        </div>
        <span
          class="rounded-full border border-(--color-border) bg-(--color-background) px-3 py-1 text-xs text-(--color-text-muted)"
          >{books.length} libros</span
        >
      </div>

      <StorageBreakdown
        {t}
        {formatBytes}
        totalBytes={stats.totalBytes}
        dbBytes={stats.dbBytes}
        coversBytes={stats.coversBytes}
        tempBytes={stats.tempBytes}
        driveUsage={storageState.driveUsage}
        isLoadingDriveUsage={storageState.isLoadingDriveUsage}
        onRefreshDrive={() => void storageState.loadDriveUsage(true)}
      />

      <div class="flex flex-wrap items-center gap-2">
        <Button
          size="sm"
          variant="ghost"
          disabled={storageState.isClearing}
          onclick={() => (showClearConfirm = 'temp')}
        >
          {storageState.isClearing ? t('settings.data.clearing') : t('settings.data.clearCache')}
        </Button>
        <label class="flex items-center gap-1 text-xs text-(--color-text-muted)">
          <input type="checkbox" bind:checked={deepVacuum} class="accent-(--color-primary)" />
          VACUUM (deep)
        </label>
        {#if storageState.clearProgress !== null}
          <span class="text-xs text-(--color-text-muted)">{storageState.clearProgress}%</span>
        {/if}
        <Button
          size="sm"
          variant="ghost"
          disabled={isOrphanCleaning}
          onclick={() => void handleCleanupOrphans()}
        >
          {isOrphanCleaning ? 'Cleaning...' : 'Cleanup orphans'}
        </Button>
        <Button
          size="sm"
          disabled={isExporting || isImporting}
          onclick={() => void handleExportCold()}
        >
          {isExporting ? t('settings.data.exporting') : t('settings.data.coldExport')}
        </Button>
        <Button
          size="sm"
          variant="ghost"
          disabled={isExporting || isImporting}
          onclick={() => void handleImportCold()}
        >
          {isImporting ? t('settings.data.importing') : t('settings.data.coldImport')}
        </Button>
      </div>

      {#if showClearConfirm}
        <div class="rounded border border-amber-300 bg-amber-50 p-3 flex items-center gap-2">
          <p class="text-xs text-amber-900">
            Clear {showClearConfirm} cache{deepVacuum ? ' + VACUUM' : ''}?
          </p>
          <Button
            size="sm"
            variant="danger"
            onclick={() => void handleClearCache(showClearConfirm!)}>Confirm</Button
          >
          <Button size="sm" variant="ghost" onclick={() => (showClearConfirm = null)}>Cancel</Button
          >
        </div>
      {/if}

      {#if !authState.isAuthenticated}
        <p class="text-xs text-amber-600">
          {t('settings.sync.signedOut')} — {t('settings.authDescription')}
        </p>
      {/if}
    </div>

    <!-- Per-book sizes -->
    <div class="rounded-xl border border-(--color-border) bg-(--color-background) p-4">
      <div class="flex items-center justify-between mb-2">
        <h3 class="text-sm font-semibold text-(--color-primary)">
          {t('library.title')} · Per-book
        </h3>
        <Button
          size="sm"
          variant="ghost"
          disabled={perBookLoading}
          onclick={() => void loadPerBook()}>{perBookLoading ? '...' : 'Refresh'}</Button
        >
      </div>
      {#if storageState.perBookSizes.length === 0}
        <p class="text-xs text-(--color-text-muted)">
          {perBookLoading ? 'Loading...' : 'No books'}
        </p>
      {:else}
        <ul class="divide-y divide-(--color-border)">
          {#each storageState.perBookSizes as b (b.id)}
            <li class="flex items-center justify-between py-2">
              <div class="min-w-0">
                <p class="text-xs font-medium text-(--color-primary) truncate">{b.title}</p>
                <p class="text-2xs text-(--color-text-muted)">{formatBytes(b.bytes)}</p>
              </div>
              <Button size="sm" variant="ghost" onclick={() => void handleDeleteBook(b.id)}
                >Delete</Button
              >
            </li>
          {/each}
        </ul>
      {/if}
    </div>
  {/if}
</section>
