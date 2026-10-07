<script lang="ts">
  import { onDestroy, onMount } from 'svelte';
  import type { MessageKey } from '$lib/shared/i18n';
  import type { SyncScope } from '$lib/shared/types/book';
  import { authState } from '$lib/shared/stores/AuthState.svelte';
  import { SyncService } from '$lib/shared/services/SyncService';
  import { syncHealthState } from '$lib/shared/stores/SyncHealthState.svelte';
  import Button from '$lib/shared/ui/forms/Button.svelte';
  import DriveSection from './DriveSection.svelte';

  type Props = { t: (key: MessageKey, params?: Record<string, string | number>) => string };
  let { t }: Props = $props();

  let isSyncing = $state(false);
  let manualSyncAt = $state<string | null>(null);
  let syncError = $state<string | null>(null);

  const health = $derived(syncHealthState.health);
  const scopes = $derived(syncHealthState.scopes);

  type ScopeEntry =
    | { kind: 'row'; key: SyncScope; label: MessageKey }
    | { kind: 'group'; label: MessageKey; scopes: { key: SyncScope; label: MessageKey }[] };
  const scopeEntries: ScopeEntry[] = [
    { kind: 'row', key: 'progress', label: 'sync.scope.progress' },
    {
      kind: 'group',
      label: 'sync.scope.group.annotations',
      scopes: [
        { key: 'bookmarks', label: 'sync.scope.bookmarks' },
        { key: 'highlights', label: 'sync.scope.highlights' },
        { key: 'sessions', label: 'sync.scope.sessions' },
      ],
    },
    { kind: 'row', key: 'catalog', label: 'sync.scope.catalog' },
    { kind: 'row', key: 'dictionary', label: 'sync.scope.dictionary' },
  ];

  function formatRelative(iso: string): string {
    const diff = Date.now() - new Date(iso).getTime();
    if (diff < 60000) return t('sync.relative.now');
    const mins = Math.floor(diff / 60000);
    if (mins < 60) return t('sync.relative.minutes', { count: mins });
    const hours = Math.floor(mins / 60);
    if (hours < 24) return t('sync.relative.hours', { count: hours });
    const days = Math.floor(hours / 24);
    return t('sync.relative.days', { count: days });
  }

  // The freshest success timestamp, whether it came from the polled health
  // snapshot or from a manual run the user just triggered.
  const lastSyncAt = $derived.by(() => {
    const fromHealth = health?.lastSyncAt ?? null;
    if (!fromHealth) return manualSyncAt;
    if (!manualSyncAt) return fromHealth;
    return new Date(fromHealth).getTime() >= new Date(manualSyncAt).getTime()
      ? fromHealth
      : manualSyncAt;
  });

  const rawError = $derived(syncError ?? health?.lastError ?? null);
  const hasError = $derived(rawError !== null);

  const statusKind = $derived(
    isSyncing
      ? 'syncing'
      : !authState.isAuthenticated
        ? 'signedOut'
        : hasError
          ? 'error'
          : (health?.pendingCount ?? 0) > 0
            ? 'pending'
            : 'upToDate',
  );

  const statusText = $derived.by(() => {
    if (statusKind === 'syncing') return t('sync.status.syncing');
    if (statusKind === 'signedOut') return t('settings.sync.signedOut');
    if (statusKind === 'error') return t('sync.status.failed');
    if (statusKind === 'pending') return t('sync.status.pending');
    return lastSyncAt
      ? t('sync.status.upToDateAt', { when: formatRelative(lastSyncAt) })
      : t('sync.status.upToDate');
  });

  const statusClass = $derived(
    statusKind === 'error' ? 'text-(--color-error)' : 'text-(--color-primary)',
  );

  onMount(() => {
    void syncHealthState.refresh();
    syncHealthState.startPoll();
  });

  onDestroy(() => {
    syncHealthState.stopPoll();
  });

  async function handleSyncNow(): Promise<void> {
    if (isSyncing) return;
    isSyncing = true;
    syncError = null;
    try {
      await SyncService.syncMetadata();
      manualSyncAt = new Date().toISOString();
      await syncHealthState.refresh();
    } catch (e) {
      syncError = e instanceof Error ? e.message : t('errors.commandFailure');
    } finally {
      isSyncing = false;
    }
  }

  function toggleScope(scope: SyncScope): void {
    const enabled = scopes[scope] !== false;
    syncHealthState.setScopeEnabled(scope, !enabled);
  }
</script>

<section class="space-y-5 w-full max-w-none">
  <header class="flex flex-col gap-1">
    <h1 class="text-3xl font-semibold tracking-tight text-(--color-primary)">{t('sync.title')}</h1>
    <p class="text-sm text-(--color-text-muted)">{t('sync.subtitle')}</p>
  </header>

  <!-- One derived status line; the text carries the state, not a colour dot. -->
  <div class="rounded-xl border border-(--color-border) bg-(--color-surface) p-5 space-y-3">
    <div class="flex flex-wrap items-center justify-between gap-3">
      <p
        class="text-sm font-medium {statusClass}"
        role="status"
        aria-live="polite"
        data-testid="sync-status"
      >
        {statusText}
      </p>
      <div class="flex shrink-0 items-center gap-2">
        {#if statusKind === 'error'}
          <Button variant="ghost" size="sm" onclick={() => void handleSyncNow()}>
            {t('error.retry')}
          </Button>
        {/if}
        <Button
          variant="secondary"
          size="sm"
          disabled={isSyncing || !authState.isAuthenticated}
          onclick={() => void handleSyncNow()}
        >
          {#if isSyncing}
            <span
              class="mr-2 h-3.5 w-3.5 animate-spin rounded-full border-2 border-current border-t-transparent"
              aria-hidden="true"
            ></span>
          {/if}
          {isSyncing ? t('settings.notifications.syncingNow') : t('settings.sync.syncNow')}
        </Button>
      </div>
    </div>
    {#if !authState.isAuthenticated}
      <p class="text-xs text-(--color-warning)">{t('settings.authDescription')}</p>
    {/if}
  </div>

  <DriveSection {t} />

  <!-- Advanced: scope controls and raw support values, collapsed by default. -->
  <details class="rounded-xl border border-(--color-border) bg-(--color-background)">
    <summary
      class="cursor-pointer select-none px-4 py-3 text-sm font-semibold text-(--color-primary)"
    >
      {t('sync.advanced.title')}
    </summary>
    <div class="space-y-6 border-t border-(--color-border) p-4">
      <div>
        <h3 class="text-sm font-semibold text-(--color-primary)">{t('sync.scope.title')}</h3>
        <div class="mt-3 divide-y divide-(--color-border)">
          {#each scopeEntries as entry, index (index)}
            {#if entry.kind === 'row'}
              <label class="flex cursor-pointer items-center justify-between gap-3 py-2">
                <span class="text-sm text-(--color-primary)">{t(entry.label)}</span>
                <input
                  type="checkbox"
                  checked={scopes[entry.key] !== false}
                  onchange={() => toggleScope(entry.key)}
                  class="h-4 w-4 accent-(--color-primary)"
                />
              </label>
            {:else}
              <div class="py-2">
                <p class="text-xs uppercase tracking-wider text-(--color-text-muted)">
                  {t(entry.label)}
                </p>
                <div class="mt-1 divide-y divide-(--color-border)">
                  {#each entry.scopes as scope (scope.key)}
                    <label class="flex cursor-pointer items-center justify-between gap-3 py-2">
                      <span class="text-sm text-(--color-primary)">{t(scope.label)}</span>
                      <input
                        type="checkbox"
                        checked={scopes[scope.key] !== false}
                        onchange={() => toggleScope(scope.key)}
                        class="h-4 w-4 accent-(--color-primary)"
                      />
                    </label>
                  {/each}
                </div>
              </div>
            {/if}
          {/each}
        </div>
        <p class="mt-3 text-xs text-(--color-text-muted)">{t('sync.scope.hint')}</p>
      </div>

      <div>
        <h3 class="text-sm font-semibold text-(--color-primary)">{t('sync.raw.title')}</h3>
        <dl class="mt-3 grid grid-cols-1 gap-x-6 gap-y-2 text-xs sm:grid-cols-2">
          <div class="flex items-baseline justify-between gap-3">
            <dt class="text-(--color-text-muted)">{t('sync.raw.lastSync')}</dt>
            <dd class="truncate text-(--color-primary)">{lastSyncAt ?? t('sync.raw.never')}</dd>
          </div>
          <div class="flex items-baseline justify-between gap-3">
            <dt class="text-(--color-text-muted)">{t('sync.raw.pending')}</dt>
            <dd class="tabular-nums text-(--color-primary)">{health?.pendingCount ?? 0}</dd>
          </div>
          <div class="flex items-baseline justify-between gap-3">
            <dt class="text-(--color-text-muted)">{t('sync.raw.realtime')}</dt>
            <dd class="truncate text-(--color-primary)">{health?.realtimeStatus ?? '—'}</dd>
          </div>
          <div class="flex items-baseline justify-between gap-3">
            <dt class="text-(--color-text-muted)">{t('sync.raw.lastError')}</dt>
            <dd class="truncate {rawError ? 'text-(--color-error)' : 'text-(--color-primary)'}">
              {rawError ?? t('sync.raw.none')}
            </dd>
          </div>
        </dl>
      </div>
    </div>
  </details>
</section>
