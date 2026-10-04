<script lang="ts">
  import type { MessageKey } from '$lib/shared/i18n';
  import type { LibraryBookDto } from '$lib/shared/types/library';
  import type { PerBookSize, StorageStats } from '$lib/shared/stores/StorageState.svelte';
  import Button from '$lib/shared/ui/forms/Button.svelte';
  import { pushToast } from '$lib/shared/stores/ToastQueue.svelte';
  import { storageState } from '$lib/shared/stores/StorageState.svelte';
  import { catalogSyncStatus } from '$lib/shared/stores/catalogSyncStatus.svelte';
  import StorageBreakdown from './StorageBreakdown.svelte';
  import { onMount } from 'svelte';

  type Props = {
    t: (key: MessageKey, params?: Record<string, string | number>) => string;
    books?: LibraryBookDto[];
  };

  let { t, books = [] }: Props = $props();

  let isFreeingSpace = $state(false);
  let freedMessage = $state<string | null>(null);
  let perBookLoading = $state(false);
  let confirmBookId = $state<string | null>(null);
  let confirmContainer = $state<HTMLDivElement | null>(null);

  onMount(() => {
    void storageState.loadStats();
    void storageState.loadDriveUsage();
    void loadPerBook();
  });

  const stats = $derived<StorageStats | null>(storageState.stats);
  const driveUsage = $derived(storageState.driveUsage);
  const catalogFailures = $derived(catalogSyncStatus.report);

  // The inline destructive confirm is injected, so move focus into it once it
  // exists — otherwise the keyboard user stays on the row they already acted on.
  $effect(() => {
    if (confirmBookId && confirmContainer) {
      confirmContainer.querySelector('button')?.focus();
    }
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

  function formatAge(ms: number): string {
    const seconds = Math.max(0, Math.floor(ms / 1000));
    if (seconds < 60) return `${seconds}s`;
    const minutes = Math.floor(seconds / 60);
    if (minutes < 60) return `${minutes}m`;
    const hours = Math.floor(minutes / 60);
    if (hours < 24) return `${hours}h`;
    return `${Math.floor(hours / 24)}d`;
  }

  function toastError(e: unknown, fallback: string): void {
    const msg = e instanceof Error ? e.message : String(e);
    if (msg.includes('storage.permission_denied')) {
      pushToast('error', t('storage.permissionDenied'));
    } else {
      pushToast('error', msg || fallback);
    }
  }

  // The app decides what is safe: regenerable temp/epub/parsed and orphaned
  // files go first, then a database VACUUM runs automatically. The freed
  // amount is the backend's own byte measure, never a computed guess.
  async function handleFreeSpace(): Promise<void> {
    if (isFreeingSpace) return;
    isFreeingSpace = true;
    freedMessage = null;
    try {
      const orphans = await storageState.cleanupOrphans();
      const res = await storageState.clearCache('temp', true);
      const freed = res.freedBytes + orphans.removed;
      freedMessage = t('storage.freedToast', { size: formatBytes(freed) });
      pushToast('success', freedMessage);
      await loadPerBook();
    } catch (e) {
      toastError(e, t('errors.commandFailure'));
    } finally {
      isFreeingSpace = false;
    }
  }

  async function confirmDeleteBook(book: PerBookSize): Promise<void> {
    confirmBookId = null;
    try {
      await storageState.deleteBookData(book.id);
      pushToast('success', t('storage.perBook.removedToast', { title: book.title }));
    } catch (e) {
      toastError(e, t('errors.commandFailure'));
    }
  }
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
      <p class="text-sm text-(--color-text-muted)">{t('storage.loading')}</p>
    </div>
  {:else if storageState.error}
    <div class="rounded-xl border border-(--color-error)/40 bg-(--color-error)/10 p-5 space-y-2">
      <p class="text-sm text-(--color-error)">{storageState.error}</p>
      <Button size="sm" variant="ghost" onclick={() => void storageState.loadStats()}>
        {t('storage.retry')}
      </Button>
    </div>
  {:else if stats}
    <div class="rounded-xl border border-(--color-border) bg-(--color-surface) p-5 space-y-4">
      <div class="flex flex-col gap-3">
        <p class="text-lg font-semibold text-(--color-primary)" data-testid="storage-usage-summary">
          {t('storage.usageSummary', { size: formatBytes(stats.totalBytes) })}
        </p>
        <StorageBreakdown
          {t}
          {formatBytes}
          tempBytes={stats.tempBytes}
          booksBytes={stats.dbBytes + stats.coversBytes}
        />
      </div>

      <div class="flex flex-wrap items-center gap-3">
        <Button
          variant="accent"
          size="md"
          disabled={isFreeingSpace}
          onclick={() => void handleFreeSpace()}
        >
          {isFreeingSpace ? t('storage.freeingSpace') : t('storage.freeSpace')}
        </Button>
        {#if freedMessage}
          <p
            class="text-sm text-(--color-success)"
            role="status"
            aria-live="polite"
            data-testid="storage-freed"
          >
            {freedMessage}
          </p>
        {/if}
      </div>

      {#if catalogFailures}
        <p class="text-xs text-(--color-warning)" data-testid="catalog-sync-status">
          {t('settings.data.catalogSyncPartial', {
            count: catalogFailures.failedCount,
            codes: catalogFailures.codes.join(', '),
          })}
        </p>
      {/if}
    </div>

    <!-- Drive: one honest line, only when a grant actually exists. -->
    {#if storageState.isLoadingDriveUsage}
      <div class="rounded-xl border border-(--color-border) bg-(--color-surface) px-5 py-3">
        <p class="text-sm text-(--color-text-muted)">{t('storage.drive.measuring')}</p>
      </div>
    {:else if driveUsage && driveUsage.state === 'measured'}
      <div
        class="flex flex-wrap items-center gap-3 rounded-xl border border-(--color-border) bg-(--color-surface) px-5 py-3"
        data-testid="drive-line"
      >
        <p class="text-sm text-(--color-primary)">
          {t('storage.drive.onDrive', { size: formatBytes(driveUsage.bytes) })}
          <span class="text-(--color-text-muted)"
            >· {t('storage.drive.measuredAgo', {
              age: formatAge(Date.now() - driveUsage.measuredAt),
            })}</span
          >
        </p>
        <Button size="sm" variant="ghost" onclick={() => void storageState.loadDriveUsage(true)}>
          {t('storage.drive.refresh')}
        </Button>
      </div>
    {:else if driveUsage && driveUsage.state === 'failed'}
      <div
        class="flex flex-wrap items-center gap-3 rounded-xl border border-(--color-warning)/40 bg-(--color-warning)/10 px-5 py-3"
        data-testid="drive-line"
      >
        <p class="text-sm text-(--color-warning)">
          {t('storage.drive.failed')} — {driveUsage.message}
        </p>
        <Button size="sm" variant="ghost" onclick={() => void storageState.loadDriveUsage(true)}>
          {t('storage.drive.refresh')}
        </Button>
      </div>
    {/if}

    <!-- Advanced: book-level sizes and per-book removal, out of the main flow. -->
    <details class="rounded-xl border border-(--color-border) bg-(--color-background)">
      <summary
        class="cursor-pointer select-none px-4 py-3 text-sm font-semibold text-(--color-primary)"
      >
        {t('storage.advanced')}
      </summary>
      <div class="space-y-3 border-t border-(--color-border) p-4">
        <div class="flex flex-wrap items-center justify-between gap-2">
          <h2 class="text-sm font-semibold text-(--color-primary)">
            {t('storage.perBook.title')} · {t('storage.perBook.count', { count: books.length })}
          </h2>
          <Button
            size="sm"
            variant="ghost"
            disabled={perBookLoading}
            onclick={() => void loadPerBook()}
          >
            {t('storage.perBook.refresh')}
          </Button>
        </div>
        {#if storageState.perBookSizes.length === 0}
          <p class="text-xs text-(--color-text-muted)">
            {perBookLoading ? t('storage.perBook.loading') : t('storage.perBook.empty')}
          </p>
        {:else}
          <ul class="divide-y divide-(--color-border)">
            {#each storageState.perBookSizes as b (b.id)}
              <li
                class="flex flex-wrap items-center justify-between gap-2 py-2"
                data-testid="per-book-row"
              >
                <div class="min-w-0">
                  <p class="text-xs font-medium text-(--color-primary) truncate">{b.title}</p>
                  <p class="text-2xs tabular-nums text-(--color-text-muted)">
                    {formatBytes(b.bytes)}
                  </p>
                </div>
                {#if confirmBookId === b.id}
                  <div
                    class="flex flex-wrap items-center gap-2"
                    role="alertdialog"
                    aria-label={t('storage.perBook.removeConfirm', { title: b.title })}
                    data-testid="per-book-confirm"
                    bind:this={confirmContainer}
                  >
                    <p class="text-xs text-(--color-primary)">
                      {t('storage.perBook.removeConfirm', { title: b.title })}
                    </p>
                    <Button size="sm" variant="danger" onclick={() => void confirmDeleteBook(b)}>
                      {t('storage.confirm')}
                    </Button>
                    <Button size="sm" variant="ghost" onclick={() => (confirmBookId = null)}>
                      {t('storage.cancel')}
                    </Button>
                  </div>
                {:else}
                  <Button size="sm" variant="ghost" onclick={() => (confirmBookId = b.id)}>
                    {t('storage.perBook.remove')}
                  </Button>
                {/if}
              </li>
            {/each}
          </ul>
        {/if}
      </div>
    </details>
  {:else}
    <!--
      Reached when the stats read settles without data and without an error
      (for example a bridge that answers with null). Rendering nothing here
      left the screen blank with no explanation, so state it and offer a retry.
    -->
    <div class="rounded-xl border border-(--color-border) bg-(--color-surface) p-5 space-y-2">
      <p class="text-sm text-(--color-text-muted)">{t('storage.unavailable')}</p>
      <Button size="sm" variant="ghost" onclick={() => void storageState.loadStats()}>
        {t('storage.retry')}
      </Button>
    </div>
  {/if}
</section>
