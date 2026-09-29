<script lang="ts">
  import { onDestroy, onMount } from 'svelte';
  import type { MessageKey } from '$lib/shared/i18n';
  import { authState } from '$lib/shared/stores/AuthState.svelte';
  import { SyncService } from '$lib/shared/services/SyncService';
  import { syncHealthState } from '$lib/shared/stores/SyncHealthState.svelte';
  import { storageState } from '$lib/shared/stores/StorageState.svelte';
  import { dictionaryState } from '$lib/shared/stores/DictionaryState.svelte';
  import { createDevicesState } from '$lib/shared/stores/DevicesState.svelte';
  import type { SyncScope } from '$lib/shared/types/book';

  type Props = { t: (key: MessageKey, params?: Record<string, string | number>) => string };
  let { t }: Props = $props();

  let isSyncing = $state(false);
  let lastSyncMsg = $state<string | null>(null);
  const devicesState = $state(createDevicesState());
  let editingDeviceId = $state<string | null>(null);
  let editingName = $state('');
  let renameError = $state<string | null>(null);
  let removeError = $state<string | null>(null);

  // Derived health
  let health = $derived(syncHealthState.health);
  let scopes = $derived(syncHealthState.scopes);
  let conflicts = $derived(syncHealthState.conflicts);

  const scopeList: { key: SyncScope; label: string }[] = [
    { key: 'progress', label: 'Progress' },
    { key: 'bookmarks', label: 'Bookmarks' },
    { key: 'highlights', label: 'Highlights' },
    { key: 'sessions', label: 'Sessions' },
    { key: 'catalog', label: 'Catalog' },
    { key: 'dictionary', label: 'Dictionary' },
  ];

  function formatRelative(iso: string | null): string {
    if (!iso) return '—';
    const diff = Date.now() - new Date(iso).getTime();
    if (diff < 60000) return 'just now';
    const mins = Math.floor(diff / 60000);
    if (mins < 60) return `${mins}m ago`;
    const hours = Math.floor(mins / 60);
    if (hours < 24) return `${hours}h ago`;
    const days = Math.floor(hours / 24);
    return `${days}d ago`;
  }

  function realtimeDot(status: string | null | undefined): string {
    if (status === 'connected') return 'bg-emerald-500';
    if (status === 'connecting') return 'bg-amber-400';
    if (status === 'error') return 'bg-red-500';
    return 'bg-zinc-400';
  }

  $effect(() => {
    if (authState.isAuthenticated && authState.userId) {
      devicesState.loadDevices(authState.userId);
    }
  });

  onMount(() => {
    void syncHealthState.refresh();
    syncHealthState.startPoll();
    // Touch storage/dictionary to satisfy prompt wiring (no-op if already loaded)
    void storageState.loadStats().catch(() => {});
  });

  onDestroy(() => {
    syncHealthState.stopPoll();
    devicesState.destroy();
  });

  async function handleSyncNow(): Promise<void> {
    isSyncing = true;
    try {
      await SyncService.syncMetadata();
      lastSyncMsg = new Date().toLocaleString();
      await syncHealthState.refresh();
    } catch (e) {
      lastSyncMsg = e instanceof Error ? e.message : t('errors.commandFailure');
    } finally {
      isSyncing = false;
    }
  }

  function toggleScope(scope: SyncScope): void {
    const enabled = scopes[scope] !== false;
    syncHealthState.setScopeEnabled(scope, !enabled);
  }

  async function handleResolve(
    conflictId: string,
    keep: 'keep_local' | 'keep_remote',
  ): Promise<void> {
    try {
      await syncHealthState.resolveConflict(conflictId, keep);
    } catch (e) {
      console.error('resolve conflict failed', e);
    }
  }

  async function handleRename(deviceId: string): Promise<void> {
    renameError = null;
    const name = editingName.trim();
    if (!name) {
      renameError = 'Name required';
      return;
    }
    try {
      await devicesState.rename(deviceId, name);
      editingDeviceId = null;
      editingName = '';
    } catch (e) {
      renameError = e instanceof Error ? e.message : String(e);
    }
  }

  async function handleRemoveStale(deviceId: string): Promise<void> {
    removeError = null;
    if (!authState.userId) return;
    if (!confirm('Remove this device?')) return;
    try {
      await devicesState.removeStale(deviceId, authState.userId);
    } catch (e) {
      const msg = e instanceof Error ? e.message : String(e);
      if (msg === 'device.not_stale') {
        removeError = 'Device not stale (<30d) — cannot remove';
      } else {
        removeError = msg;
      }
    }
  }

  async function handleRemove(deviceId: string): Promise<void> {
    if (!authState.userId) return;
    if (!confirm('Remove device?')) return;
    try {
      await devicesState.remove(deviceId, authState.userId);
    } catch (e) {
      removeError = e instanceof Error ? e.message : String(e);
    }
  }
</script>

<section class="space-y-5 w-full max-w-none">
  <header class="flex flex-col gap-1">
    <h1 class="text-3xl font-semibold tracking-tight text-(--color-primary)">{t('sync.title')}</h1>
    <p class="text-sm text-(--color-text-muted)">{t('sync.subtitle')}</p>
  </header>

  <!-- Sync now + indicators -->
  <div class="rounded-xl border border-(--color-border) bg-(--color-surface) p-5 space-y-3">
    <div class="flex items-center justify-between gap-4">
      <div class="space-y-1">
        <h3 class="text-sm font-semibold text-(--color-primary) flex items-center gap-2">
          {t('settings.sync.syncNow')}
          {#if health}
            <span
              class="inline-flex items-center gap-1.5 rounded-full border border-(--color-border) bg-white px-2 py-0.5 text-xs"
            >
              <span class="h-2 w-2 rounded-full {realtimeDot(health.realtimeStatus)}"></span>
              {health.realtimeStatus}
            </span>
            {#if health.pendingCount > 0}
              <span class="rounded-full bg-amber-100 px-2 py-0.5 text-xs font-medium text-amber-800"
                >pending {health.pendingCount}</span
              >
            {:else}
              <span class="rounded-full bg-emerald-50 px-2 py-0.5 text-xs text-emerald-700"
                >pending 0</span
              >
            {/if}
          {/if}
        </h3>
        <p class="text-xs text-(--color-text-muted)">
          {#if authState.isAuthenticated}
            {t('settings.sync.signedIn')} · {authState.userId?.slice(0, 8) ?? ''}
          {:else}
            {t('settings.sync.signedOut')}
          {/if}
          {#if health?.lastSyncAt}
            · last {formatRelative(health.lastSyncAt)}
          {:else if lastSyncMsg}
            · {lastSyncMsg}
          {/if}
          {#if health?.lastError}
            · <span class="text-red-600">{health.lastError.slice(0, 60)}</span>
          {/if}
        </p>
        <p class="text-xs text-(--color-text-muted)">
          Dictionary {dictionaryState.words.length} words · Storage {storageState.stats
            ? `${Math.round((storageState.stats.totalBytes / 1024 / 1024) * 10) / 10} MB`
            : '—'}
        </p>
      </div>
      <button
        type="button"
        class="inline-flex items-center gap-2 rounded-xl border border-(--color-primary) bg-(--color-primary) px-4 py-2 text-sm font-medium text-(--color-background) hover:opacity-90 disabled:opacity-60 cursor-pointer shrink-0"
        disabled={isSyncing || !authState.isAuthenticated}
        onclick={() => void handleSyncNow()}
      >
        {#if isSyncing}
          <span
            class="h-4 w-4 animate-spin rounded-full border-2 border-(--color-background) border-t-transparent"
          ></span>
        {/if}
        {isSyncing ? t('settings.notifications.syncingNow') : t('settings.sync.syncNow')}
      </button>
    </div>
    {#if !authState.isAuthenticated}
      <p class="text-xs text-amber-600">{t('settings.authDescription')}</p>
    {/if}
  </div>

  <!-- Health card -->
  <div class="rounded-xl border border-(--color-border) bg-(--color-surface) p-4 space-y-2">
    <h3 class="text-sm font-semibold text-(--color-primary)">Sync Health</h3>
    {#if health}
      <div class="grid grid-cols-2 gap-3 text-xs">
        <div class="rounded-lg bg-zinc-50 p-3">
          <div class="text-(--color-text-muted)">Last sync</div>
          <div class="font-medium text-(--color-primary)">
            {health.lastSyncAt ? formatRelative(health.lastSyncAt) : 'never'}
          </div>
          <div class="text-(--color-text-muted) truncate">{health.lastSyncAt ?? '—'}</div>
        </div>
        <div class="rounded-lg bg-zinc-50 p-3">
          <div class="text-(--color-text-muted)">Pending</div>
          <div class="font-medium text-(--color-primary)">{health.pendingCount}</div>
          <div class="text-(--color-text-muted)">
            outbox depth {health.outboxDepth ?? health.pendingCount}
          </div>
        </div>
        <div class="rounded-lg bg-zinc-50 p-3">
          <div class="text-(--color-text-muted)">Realtime</div>
          <div class="flex items-center gap-1.5 font-medium">
            <span class="h-2 w-2 rounded-full {realtimeDot(health.realtimeStatus)}"></span>
            {health.realtimeStatus}
          </div>
          <div class="text-(--color-text-muted) text-[11px]">
            {health.nextRetryAt ? `next retry ${formatRelative(health.nextRetryAt)}` : 'no retry'}
          </div>
        </div>
        <div class="rounded-lg bg-zinc-50 p-3">
          <div class="text-(--color-text-muted)">Last error</div>
          <div
            class="font-medium truncate {health.lastError
              ? 'text-red-600'
              : 'text-(--color-primary)'}"
          >
            {health.lastError ?? '—'}
          </div>
          <div class="text-(--color-text-muted)">{health.lastError ? 'check logs' : 'healthy'}</div>
        </div>
      </div>
      {#if health.realtime && Object.keys(health.realtime).length > 0}
        <div class="flex flex-wrap gap-1.5 pt-1">
          {#each Object.entries(health.realtime) as [topic, st] (topic)}
            <span
              class="inline-flex items-center gap-1 rounded-full border px-2 py-0.5 text-[11px] {st ===
              'connected'
                ? 'border-emerald-200 bg-emerald-50 text-emerald-700'
                : st === 'connecting'
                  ? 'border-amber-200 bg-amber-50 text-amber-700'
                  : st === 'error'
                    ? 'border-red-200 bg-red-50 text-red-700'
                    : 'border-zinc-200 bg-zinc-50 text-zinc-600'}"
            >
              <span class="h-1.5 w-1.5 rounded-full {realtimeDot(st)}"></span>
              {topic.slice(0, 24)} · {st}
            </span>
          {/each}
        </div>
      {/if}
    {:else}
      <p class="text-xs text-(--color-text-muted)">Loading health…</p>
    {/if}
  </div>

  <!-- Toggles per scope -->
  <div class="rounded-xl border border-(--color-border) bg-(--color-surface) p-4">
    <h3 class="text-sm font-semibold text-(--color-primary) mb-3">Sync scopes</h3>
    <div class="grid grid-cols-1 gap-2 sm:grid-cols-2">
      {#each scopeList as s (s.key)}
        <label
          class="flex items-center justify-between gap-3 rounded-lg border border-(--color-border) px-3 py-2 cursor-pointer hover:bg-zinc-50"
        >
          <span class="text-sm text-(--color-primary)">{s.label}</span>
          <input
            type="checkbox"
            checked={scopes[s.key] !== false}
            onchange={() => toggleScope(s.key)}
            class="h-4 w-4 accent-(--color-primary)"
          />
        </label>
      {/each}
    </div>
    <p class="mt-2 text-xs text-(--color-text-muted)">
      Disabled scopes stay queued until re-enabled.
    </p>
  </div>

  <!-- Conflicts -->
  {#if conflicts.length > 0}
    <div class="rounded-xl border border-amber-300 bg-amber-50 p-4 space-y-3">
      <h3 class="text-sm font-semibold text-amber-900">Conflicts ({conflicts.length}) — LWW</h3>
      {#each conflicts as c (c.id)}
        <div
          class="flex items-center justify-between gap-3 rounded-lg border border-amber-200 bg-white px-3 py-2"
        >
          <div class="min-w-0">
            <div class="truncate text-sm font-medium text-(--color-primary)">
              {c.word ?? c.localWord ?? c.id.slice(0, 8)}
            </div>
            <div class="text-xs text-(--color-text-muted)">
              local {formatRelative(c.localUpdatedAt)} · remote {formatRelative(c.remoteUpdatedAt)}
            </div>
          </div>
          <div class="flex shrink-0 gap-1.5">
            <button
              type="button"
              class="rounded-lg border border-(--color-border) bg-white px-2.5 py-1 text-xs hover:bg-zinc-50 cursor-pointer"
              onclick={() => void handleResolve(c.id, 'keep_local')}>Keep local</button
            >
            <button
              type="button"
              class="rounded-lg border border-(--color-border) bg-white px-2.5 py-1 text-xs hover:bg-zinc-50 cursor-pointer"
              onclick={() => void handleResolve(c.id, 'keep_remote')}>Keep remote</button
            >
          </div>
        </div>
      {/each}
    </div>
  {/if}

  <!-- Devices -->
  <div class="rounded-xl border border-(--color-border) bg-(--color-surface) p-4 space-y-3">
    <h3 class="text-sm font-semibold text-(--color-primary)">Devices</h3>
    {#if devicesState.isLoading}
      <p class="text-xs text-(--color-text-muted)">Loading devices…</p>
    {:else if devicesState.devices.length === 0}
      <p class="text-xs text-(--color-text-muted)">No devices</p>
    {:else}
      <div class="space-y-2">
        {#each devicesState.devices as d (d.id)}
          <div class="flex items-center gap-2 rounded-lg border border-(--color-border) px-3 py-2">
            <div class="min-w-0 flex-1">
              {#if editingDeviceId === d.id}
                <input
                  class="w-full rounded border px-2 py-1 text-sm"
                  bind:value={editingName}
                  placeholder="Device name"
                  onkeydown={(e) => {
                    if (e.key === 'Enter') void handleRename(d.id);
                    if (e.key === 'Escape') {
                      editingDeviceId = null;
                      editingName = '';
                    }
                  }}
                />
              {:else}
                <div class="truncate text-sm font-medium text-(--color-primary)">
                  {d.name}
                  {#if d.isCurrent}<span
                      class="ml-1 rounded bg-(--color-accent-soft) px-1.5 py-0.5 text-[11px] text-(--color-accent-start)"
                      >this device</span
                    >{/if}
                </div>
                <div class="truncate text-xs text-(--color-text-muted)">
                  {d.os} · {d.lastActive.unit === 'now'
                    ? 'now'
                    : `${d.lastActive.value} ${d.lastActive.unit} ago`}
                </div>
              {/if}
            </div>
            <div class="flex shrink-0 gap-1">
              {#if editingDeviceId === d.id}
                <button
                  type="button"
                  class="rounded border px-2 py-1 text-xs hover:bg-zinc-50 cursor-pointer"
                  onclick={() => void handleRename(d.id)}>Save</button
                >
                <button
                  type="button"
                  class="rounded border px-2 py-1 text-xs hover:bg-zinc-50 cursor-pointer"
                  onclick={() => {
                    editingDeviceId = null;
                    editingName = '';
                    renameError = null;
                  }}>Cancel</button
                >
              {:else}
                <button
                  type="button"
                  class="rounded border px-2 py-1 text-xs hover:bg-zinc-50 cursor-pointer"
                  onclick={() => {
                    editingDeviceId = d.id;
                    editingName = d.name;
                  }}>Rename</button
                >
                {#if !d.isCurrent}
                  <button
                    type="button"
                    class="rounded border border-red-200 px-2 py-1 text-xs text-red-600 hover:bg-red-50 cursor-pointer"
                    onclick={() => void handleRemoveStale(d.id)}>Remove stale</button
                  >
                  <button
                    type="button"
                    class="rounded border px-2 py-1 text-xs hover:bg-zinc-50 cursor-pointer"
                    onclick={() => void handleRemove(d.id)}>Remove</button
                  >
                {/if}
              {/if}
            </div>
          </div>
        {/each}
      </div>
      {#if renameError}<p class="text-xs text-red-600">{renameError}</p>{/if}
      {#if removeError}<p class="text-xs text-red-600">{removeError}</p>{/if}
      {#if devicesState.error}<p class="text-xs text-amber-700">{devicesState.error}</p>{/if}
    {/if}
  </div>
</section>
