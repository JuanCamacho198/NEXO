<script lang="ts">
  import type { MessageKey } from '$lib/shared/i18n';
  import type { DriveUsage } from '$lib/shared/services/storage/DriveUsageService';
  import Button from '$lib/shared/ui/forms/Button.svelte';

  type Props = {
    t: (key: MessageKey, params?: Record<string, string | number>) => string;
    formatBytes: (bytes: number) => string;
    totalBytes: number;
    dbBytes: number;
    coversBytes: number;
    tempBytes: number;
    driveUsage: DriveUsage | null;
    isLoadingDriveUsage: boolean;
    onRefreshDrive: () => void;
  };

  let {
    t,
    formatBytes,
    totalBytes,
    dbBytes,
    coversBytes,
    tempBytes,
    driveUsage,
    isLoadingDriveUsage,
    onRefreshDrive,
  }: Props = $props();

  const localBytes = $derived(dbBytes + coversBytes);

  function pct(part: number, total: number): number {
    if (total === 0) return 0;
    return Math.min(100, Math.round((part / total) * 100));
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

  const driveTopRight = $derived.by((): string => {
    if (driveUsage === null || isLoadingDriveUsage) return t('storage.drive.measuring');
    if (driveUsage.state === 'not_connected') return t('storage.drive.notConnected');
    if (driveUsage.state === 'failed') return t('storage.drive.failed');
    return formatBytes(driveUsage.bytes);
  });

  const driveBarPct = $derived(
    driveUsage?.state === 'measured' ? pct(driveUsage.bytes, totalBytes) : 0,
  );

  const driveFailureMessage = $derived(driveUsage?.state === 'failed' ? driveUsage.message : null);

  const driveFooter = $derived.by((): string | null => {
    if (driveUsage?.state !== 'measured') return null;
    const age = formatAge(Date.now() - driveUsage.measuredAt);
    return `${driveUsage.fileCount} files · ${t('storage.drive.measuredAgo', { age })}`;
  });
</script>

<div class="grid grid-cols-1 md:grid-cols-3 gap-3">
  <!-- Hot - Supabase -->
  <div class="rounded-lg border border-(--color-border) bg-(--color-background) p-3">
    <div class="flex items-center gap-2 mb-1">
      <span class="size-2 rounded-full bg-emerald-500"></span>
      <span class="text-xs font-semibold text-(--color-primary)">Hot · Supabase</span>
      <span class="ml-auto text-2xs text-(--color-text-muted)"
        >{t('storage.supabase.notMeasured')}</span
      >
    </div>
    <p class="text-2xs text-(--color-text-muted)">progress / highlights / bookmarks</p>
    <div class="mt-2 h-1.5 rounded bg-(--color-border) overflow-hidden">
      <div class="h-full bg-emerald-500" style="width: 0%"></div>
    </div>
  </div>
  <!-- Cold - Drive -->
  <div class="rounded-lg border border-(--color-border) bg-(--color-background) p-3">
    <div class="flex items-center gap-2 mb-1">
      <span class="size-2 rounded-full bg-sky-500"></span>
      <span class="text-xs font-semibold text-(--color-primary)">Cold · Drive</span>
      <span class="ml-auto text-2xs text-(--color-text-muted)">{driveTopRight}</span>
    </div>
    <p class="text-2xs text-(--color-text-muted)">book-covers + cold_backup.json</p>
    <div class="mt-2 h-1.5 rounded bg-(--color-border) overflow-hidden">
      <div class="h-full bg-sky-500" style="width: {driveBarPct}%"></div>
    </div>
    {#if driveFailureMessage}
      <p class="text-2xs text-amber-600 mt-1">{driveFailureMessage}</p>
    {:else if driveFooter}
      <p class="text-2xs text-(--color-text-muted) mt-1">{driveFooter}</p>
    {/if}
    <Button size="sm" variant="ghost" disabled={isLoadingDriveUsage} onclick={onRefreshDrive}>
      {t('storage.drive.refresh')}
    </Button>
  </div>
  <!-- Local - SQLite + covers -->
  <div class="rounded-lg border border-(--color-border) bg-(--color-background) p-3">
    <div class="flex items-center gap-2 mb-1">
      <span class="size-2 rounded-full bg-orange-500"></span>
      <span class="text-xs font-semibold text-(--color-primary)">Local</span>
      <span class="ml-auto text-2xs text-(--color-text-muted)">{formatBytes(localBytes)}</span>
    </div>
    <p class="text-2xs text-(--color-text-muted)">
      SQLite {formatBytes(dbBytes)} + covers {formatBytes(coversBytes)}
    </p>
    <div class="mt-2 h-1.5 rounded bg-(--color-border) overflow-hidden">
      <div class="h-full bg-orange-500" style="width: {pct(localBytes, totalBytes)}%"></div>
    </div>
    <p class="text-2xs text-(--color-text-muted) mt-1">
      Temp {formatBytes(tempBytes)} ({pct(tempBytes, totalBytes)}%)
    </p>
  </div>
</div>
