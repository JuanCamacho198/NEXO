<script lang="ts">
  import { Button, Modal } from '$lib/shared/ui';
  import type { BulkImportSummary, ScanFolderResult } from '$lib/shared/types';
  import type { BulkImportProgress } from '$lib/shared/services/BulkImportService';
  import type { MessageKey } from '$lib/shared/i18n';
  import { BULK_IMPORT_STATUS, getBulkImportStatusKey, getBulkImportStatusClass } from '../utils';

  let {
    open,
    folderName,
    folderPath,
    scanResult,
    isScanning,
    scanError,
    isImporting,
    importProgress,
    importSummary,
    onClose,
    onPickFolder,
    onScan,
    onStartImport,
    onCancelImport,
    t,
  }: {
    open: boolean;
    folderName: string | null;
    folderPath: string | null;
    scanResult: ScanFolderResult | null;
    isScanning: boolean;
    scanError: string | null;
    isImporting: boolean;
    importProgress: BulkImportProgress | null;
    importSummary: BulkImportSummary | null;
    onClose: () => void;
    onPickFolder: () => void;
    onScan: () => void;
    onStartImport: () => void;
    onCancelImport: () => void;
    t: (key: MessageKey, params?: Record<string, string | number>) => string;
  } = $props();

  const effectiveSummary = $derived(importProgress?.summary ?? importSummary ?? null);
  const currentFileName = $derived(importProgress?.currentFile?.fileName ?? null);
  const canStartImport = $derived(
    !isScanning && !isImporting && !!folderPath && !!scanResult && scanResult.files.length > 0,
  );

  const fallbackRows = $derived.by(() => {
    if (!scanResult) {
      return [];
    }

    return scanResult.files.map((file) => ({
      file,
      status: file.isDuplicate ? BULK_IMPORT_STATUS.SKIPPED : BULK_IMPORT_STATUS.QUEUED,
      message: file.isDuplicate ? t('library.bulkImport.duplicate') : null,
    }));
  });
</script>

<Modal
  bind:open
  title={t('library.bulkImport.title')}
  onOpenChange={(v) => {
    if (!v) onClose();
  }}
>
  {#snippet children()}
    <div class="mb-4 rounded-xl bg-(--color-surface-dim) p-3">
      <div class="mb-2 flex flex-wrap items-center gap-2">
        <Button
          variant="secondary"
          size="sm"
          onclick={onPickFolder}
          disabled={isScanning || isImporting}
        >
          {t('library.bulkImport.selectFolder')}
        </Button>
        <Button
          variant="secondary"
          size="sm"
          onclick={onScan}
          disabled={!folderPath || isScanning || isImporting}
        >
          {isScanning ? t('library.bulkImport.scanning') : t('library.bulkImport.scan')}
        </Button>
        <Button
          onclick={onStartImport}
          size="sm"
          disabled={!canStartImport}
          variant={canStartImport ? 'primary' : 'secondary'}
        >
          {isImporting ? t('library.bulkImport.importing') : t('library.bulkImport.confirm')}
        </Button>
        {#if isImporting}
          <Button variant="danger" size="sm" onclick={onCancelImport}>
            {t('library.bulkImport.cancel')}
          </Button>
        {/if}
      </div>

      <p class="text-xs text-(--color-text-muted)">
        {folderName
          ? t('library.bulkImport.selectedFolder', { name: folderName })
          : t('library.bulkImport.noFolder')}
      </p>

      {#if scanResult}
        <div class="mt-2 flex flex-wrap gap-2 text-xs text-(--color-text-muted)">
          <span>{t('library.bulkImport.filesFound', { count: scanResult.files.length })}</span>
          <span
            >{t('library.bulkImport.skippedUnsupported', {
              count: scanResult.skippedUnsupportedCount,
            })}</span
          >
          <span
            >{t('library.bulkImport.skippedUnreadable', {
              count: scanResult.skippedUnreadableCount,
            })}</span
          >
        </div>
      {/if}
    </div>

    {#if scanError}
      <p class="mb-3 rounded-xl bg-(--color-error-soft) px-3 py-2 text-sm text-(--color-error)">
        {scanError}
      </p>
    {/if}

    {#if effectiveSummary}
      <div class="mb-3 rounded-xl bg-(--color-surface-dim) p-3 text-xs text-(--color-text-muted)">
        <div class="mb-2 flex flex-wrap gap-2">
          <span>{t('library.bulkImport.summary.total', { count: effectiveSummary.total })}</span>
          <span>{t('library.bulkImport.summary.queued', { count: effectiveSummary.queued })}</span>
          <span
            >{t('library.bulkImport.summary.importing', {
              count: effectiveSummary.importing,
            })}</span
          >
          <span>{t('library.bulkImport.summary.success', { count: effectiveSummary.success })}</span
          >
          <span>{t('library.bulkImport.summary.skipped', { count: effectiveSummary.skipped })}</span
          >
          <span>{t('library.bulkImport.summary.failed', { count: effectiveSummary.failed })}</span>
          <span
            >{t('library.bulkImport.summary.cancelled', {
              count: effectiveSummary.cancelled,
            })}</span
          >
        </div>
        {#if currentFileName}
          <p>{t('library.bulkImport.currentFile', { name: currentFileName })}</p>
        {/if}
      </div>
    {/if}

    <div>
      {#if (effectiveSummary?.results.length ?? 0) > 0}
        <ul class="divide-y divide-(--color-border)">
          {#each effectiveSummary?.results ?? [] as row}
            <li class="p-3">
              <div class="flex flex-wrap items-center justify-between gap-2">
                <p class="truncate text-sm font-medium text-(--color-primary)">
                  {row.file.fileName}
                </p>
                <span
                  class={`text-xs font-semibold uppercase ${getBulkImportStatusClass(row.status)}`}
                >
                  {t(getBulkImportStatusKey(row.status) as import('$lib/shared/i18n').MessageKey)}
                </span>
              </div>
              <p class="text-xs text-(--color-text-muted)">{row.file.format.toUpperCase()}</p>
              {#if row.message}
                <p
                  class={`mt-1 text-xs ${row.status === BULK_IMPORT_STATUS.FAILED ? 'text-(--color-error)' : 'text-(--color-text-muted)'}`}
                >
                  {row.message}
                </p>
              {/if}
            </li>
          {/each}
        </ul>
      {:else if fallbackRows.length > 0}
        <ul class="divide-y divide-(--color-border)">
          {#each fallbackRows as row}
            <li class="p-3">
              <div class="flex flex-wrap items-center justify-between gap-2">
                <p class="truncate text-sm font-medium text-(--color-primary)">
                  {row.file.fileName}
                </p>
                <span
                  class={`text-xs font-semibold uppercase ${getBulkImportStatusClass(row.status)}`}
                >
                  {t(getBulkImportStatusKey(row.status) as import('$lib/shared/i18n').MessageKey)}
                </span>
              </div>
              <p class="text-xs text-(--color-text-muted)">{row.file.format.toUpperCase()}</p>
              {#if row.message}
                <p class="mt-1 text-xs text-(--color-text-muted)">{row.message}</p>
              {/if}
            </li>
          {/each}
        </ul>
      {:else}
        <p class="p-4 text-sm text-(--color-text-muted)">{t('library.bulkImport.noFiles')}</p>
      {/if}
    </div>
  {/snippet}

  {#snippet footer()}
    <Button variant="secondary" size="sm" onclick={onClose}>
      {t('library.bulkImport.close')}
    </Button>
  {/snippet}
</Modal>
