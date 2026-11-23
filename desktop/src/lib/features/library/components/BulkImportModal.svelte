<script lang="ts">
  import Check from 'lucide-svelte/icons/check';
  import FileText from 'lucide-svelte/icons/file-text';
  import FolderOpen from 'lucide-svelte/icons/folder-open';
  import FolderSearch from 'lucide-svelte/icons/folder-search';
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

  const rows = $derived(
    (effectiveSummary?.results.length ?? 0) > 0 ? (effectiveSummary?.results ?? []) : fallbackRows,
  );

  // Where the reader is in the flow: choose a folder, scan it, import it.
  // A finished (or running) import counts as the third step.
  const step = $derived<1 | 2 | 3>(!folderPath ? 1 : !scanResult && !effectiveSummary ? 2 : 3);
  const steps = $derived([
    { n: 1, label: t('library.bulkImport.step.folder') },
    { n: 2, label: t('library.bulkImport.step.scan') },
    { n: 3, label: t('library.bulkImport.step.import') },
  ]);

  const doneCount = $derived(
    effectiveSummary
      ? effectiveSummary.success +
          effectiveSummary.skipped +
          effectiveSummary.failed +
          effectiveSummary.cancelled
      : 0,
  );
  const progressPct = $derived(
    effectiveSummary && effectiveSummary.total > 0
      ? Math.round((doneCount / effectiveSummary.total) * 100)
      : 0,
  );

  /** The outcome counts worth showing: only the non-zero ones, coloured by meaning. */
  const outcomeChips = $derived(
    effectiveSummary
      ? [
          {
            key: 'library.bulkImport.summary.success',
            count: effectiveSummary.success,
            tone: 'text-(--color-success)',
          },
          {
            key: 'library.bulkImport.summary.skipped',
            count: effectiveSummary.skipped,
            tone: 'text-(--color-text-muted)',
          },
          {
            key: 'library.bulkImport.summary.failed',
            count: effectiveSummary.failed,
            tone: 'text-(--color-error)',
          },
          {
            key: 'library.bulkImport.summary.cancelled',
            count: effectiveSummary.cancelled,
            tone: 'text-(--color-warning)',
          },
        ].filter((chip) => chip.count > 0)
      : [],
  );
</script>

<Modal
  bind:open
  title={t('library.bulkImport.title')}
  description={t('library.bulkImport.description')}
  size="lg"
  onOpenChange={(v) => {
    if (!v) onClose();
  }}
>
  {#snippet children()}
    <!-- Steps: the flow is linear, so the reader always sees which one is next. -->
    <ol
      class="m-0 mb-5 flex list-none items-center gap-2 p-0"
      aria-label={t('library.bulkImport.title')}
    >
      {#each steps as s, i (s.n)}
        {@const isDone = s.n < step}
        {@const isCurrent = s.n === step}
        <li class="flex items-center gap-2" aria-current={isCurrent ? 'step' : undefined}>
          <span
            class="flex size-6 shrink-0 items-center justify-center rounded-full text-xs font-bold tabular-nums {isDone
              ? 'bg-(--color-brand) text-(--color-brand-on)'
              : isCurrent
                ? 'border border-(--color-brand) bg-(--color-brand-soft) text-(--color-brand-text)'
                : 'border border-(--color-edge) text-(--color-text-muted)'}"
          >
            {#if isDone}
              <Check size={13} strokeWidth={3} aria-hidden="true" />
            {:else}
              {s.n}
            {/if}
          </span>
          <span
            class="text-sm {isCurrent
              ? 'font-semibold text-(--color-primary)'
              : 'text-(--color-text-muted)'}">{s.label}</span
          >
          {#if i < steps.length - 1}
            <span class="mx-1 h-px w-8 bg-(--color-edge)" aria-hidden="true"></span>
          {/if}
        </li>
      {/each}
    </ol>

    {#if !folderPath}
      <!-- Step 1: one large target instead of three buttons, two of them dead. -->
      <button
        type="button"
        onclick={onPickFolder}
        disabled={isScanning || isImporting}
        class="group flex w-full cursor-pointer flex-col items-center gap-3 rounded-(--radius-lg) border border-dashed border-(--color-edge-strong) px-6 py-10 text-center transition-colors hover:border-(--color-brand)/60 hover:bg-(--color-brand-soft) focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-(--color-brand)"
      >
        <span
          class="flex size-12 items-center justify-center rounded-full bg-(--color-brand-soft) text-(--color-brand-text)"
        >
          <FolderOpen size={22} strokeWidth={1.8} aria-hidden="true" />
        </span>
        <span class="text-base font-semibold text-(--color-primary)">
          {t('library.bulkImport.selectFolder')}
        </span>
        <span class="text-sm text-(--color-text-muted)">{t('library.bulkImport.noFolder')}</span>
      </button>
    {:else}
      <!-- The chosen folder, with a quiet way to change it. -->
      <div
        class="flex items-center gap-3 rounded-(--radius-lg) border border-(--color-edge) px-4 py-3"
      >
        <span
          class="flex size-9 shrink-0 items-center justify-center rounded-full bg-(--color-brand-soft) text-(--color-brand-text)"
        >
          <FolderOpen size={17} strokeWidth={1.8} aria-hidden="true" />
        </span>
        <p class="m-0 min-w-0 flex-1 truncate text-sm font-medium text-(--color-primary)">
          {folderName
            ? t('library.bulkImport.selectedFolder', { name: folderName })
            : t('library.bulkImport.noFolder')}
        </p>
        <Button
          variant="link"
          size="sm"
          onclick={onPickFolder}
          disabled={isScanning || isImporting}
        >
          {t('library.bulkImport.changeFolder')}
        </Button>
      </div>

      {#if scanResult}
        <div class="mt-3 flex flex-wrap gap-2 text-xs">
          <span
            class="rounded-full bg-(--color-brand-soft) px-2.5 py-1 font-semibold text-(--color-brand-text)"
          >
            {t('library.bulkImport.filesFound', { count: scanResult.files.length })}
          </span>
          {#if scanResult.skippedUnsupportedCount > 0}
            <span
              class="rounded-full bg-(--color-surface-hover) px-2.5 py-1 text-(--color-secondary)"
            >
              {t('library.bulkImport.skippedUnsupported', {
                count: scanResult.skippedUnsupportedCount,
              })}
            </span>
          {/if}
          {#if scanResult.skippedUnreadableCount > 0}
            <span
              class="rounded-full bg-(--color-surface-hover) px-2.5 py-1 text-(--color-secondary)"
            >
              {t('library.bulkImport.skippedUnreadable', {
                count: scanResult.skippedUnreadableCount,
              })}
            </span>
          {/if}
        </div>
      {:else if !effectiveSummary}
        <p class="mt-4 flex items-center gap-2 text-sm text-(--color-text-muted)">
          <FolderSearch size={16} strokeWidth={1.8} aria-hidden="true" />
          {isScanning ? t('library.bulkImport.scanning') : t('library.bulkImport.scanHint')}
        </p>
      {/if}
    {/if}

    {#if scanError}
      <p class="mt-4 flex items-start gap-2 text-sm text-(--color-error)" role="alert">
        <span class="mt-1.5 size-2 shrink-0 rounded-full bg-(--color-error)" aria-hidden="true"
        ></span>
        {scanError}
      </p>
    {/if}

    {#if effectiveSummary}
      <div class="mt-5">
        <div class="mb-2 flex items-center justify-between gap-3 text-sm">
          <span class="font-medium text-(--color-primary)">
            {t('library.bulkImport.progress', { done: doneCount, total: effectiveSummary.total })}
          </span>
          <span class="tabular-nums text-(--color-text-muted)">{progressPct}%</span>
        </div>
        <div
          class="h-2 w-full overflow-hidden rounded-full bg-(--color-edge)"
          role="progressbar"
          aria-valuemin={0}
          aria-valuemax={100}
          aria-valuenow={progressPct}
        >
          <div
            class="h-full rounded-full bg-linear-to-r from-(--color-brand-start) to-(--color-brand) transition-[width] duration-300"
            style="width: {progressPct}%"
          ></div>
        </div>
        {#if currentFileName}
          <p class="mt-2 truncate text-xs text-(--color-text-muted)">
            {t('library.bulkImport.currentFile', { name: currentFileName })}
          </p>
        {/if}
        {#if outcomeChips.length > 0}
          <div class="mt-3 flex flex-wrap gap-x-4 gap-y-1 text-xs font-semibold">
            {#each outcomeChips as chip (chip.key)}
              <span class={chip.tone}>{t(chip.key as MessageKey, { count: chip.count })}</span>
            {/each}
          </div>
        {/if}
      </div>
    {/if}

    {#if rows.length > 0}
      <ul
        class="scrollbar-hidden -mx-2 mt-4 flex max-h-72 flex-col gap-0.5 overflow-y-auto border-t border-(--color-edge) pt-3"
      >
        {#each rows as row, index (`${row.file.fileName}-${index}`)}
          <li class="flex items-center gap-3 rounded-(--radius-md) px-2 py-2">
            <FileText
              size={16}
              strokeWidth={1.8}
              class="shrink-0 text-(--color-text-muted)"
              aria-hidden="true"
            />
            <div class="min-w-0 flex-1">
              <p class="m-0 truncate text-sm font-medium text-(--color-primary)">
                {row.file.fileName}
              </p>
              {#if row.message}
                <p
                  class="m-0 truncate text-xs {row.status === BULK_IMPORT_STATUS.FAILED
                    ? 'text-(--color-error)'
                    : 'text-(--color-text-muted)'}"
                >
                  {row.message}
                </p>
              {/if}
            </div>
            <span
              class="shrink-0 rounded-full border border-(--color-edge) px-2 py-0.5 text-[0.6875rem] font-semibold tracking-wide text-(--color-secondary) uppercase"
            >
              {row.file.format}
            </span>
            <span
              class="w-20 shrink-0 text-right text-xs font-semibold {getBulkImportStatusClass(
                row.status,
              )}"
            >
              {t(getBulkImportStatusKey(row.status) as MessageKey)}
            </span>
          </li>
        {/each}
      </ul>
    {:else if folderPath && scanResult}
      <p class="mt-4 text-sm text-(--color-text-muted)">{t('library.bulkImport.noFiles')}</p>
    {/if}
  {/snippet}

  {#snippet footer()}
    <div class="flex w-full items-center justify-end gap-2">
      <Button variant="secondary" size="md" onclick={onClose}>
        {t('library.bulkImport.close')}
      </Button>
      {#if isImporting}
        <Button variant="danger-outline" size="md" onclick={onCancelImport}>
          {t('library.bulkImport.cancel')}
        </Button>
      {:else if folderPath && (!scanResult || isScanning)}
        <Button variant="accent" size="md" onclick={onScan} disabled={isScanning}>
          {isScanning ? t('library.bulkImport.scanning') : t('library.bulkImport.scan')}
        </Button>
      {:else if scanResult && !effectiveSummary}
        <Button variant="secondary" size="md" onclick={onScan} disabled={isScanning}>
          {t('library.bulkImport.rescan')}
        </Button>
        <Button variant="accent" size="md" onclick={onStartImport} disabled={!canStartImport}>
          {t('library.bulkImport.confirm')}
        </Button>
      {/if}
    </div>
  {/snippet}
</Modal>
