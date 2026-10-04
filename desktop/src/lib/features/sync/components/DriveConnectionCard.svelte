<script lang="ts">
  import Panel from '$lib/shared/ui/layout/Panel.svelte';
  import { driveState } from '$lib/shared/stores/driveState.svelte';
  import { beginDriveConnect, disconnectDrive } from '$lib/shared/services/DriveConnectService';
  import { pushToast } from '$lib/shared/stores/ToastQueue.svelte';
  import type { MessageKey } from '$lib/shared/i18n';

  type Props = {
    t: (key: MessageKey, params?: Record<string, string | number>) => string;
  };

  let { t }: Props = $props();

  const isAuthorized = $derived(driveState.isAuthorized);
  const isConnecting = $derived(driveState.isConnecting);

  async function handleConnect(): Promise<void> {
    const result = await beginDriveConnect();
    // beginDriveConnect feeds driveState itself; surface failures only —
    // cancel stays silent (Android parity), success flips the card.
    if (result.kind === 'failure') {
      pushToast('error', result.message || t('settings.sync.drive.connectFailed'));
    } else if (result.kind === 'success') {
      pushToast('success', t('settings.sync.drive.connected'));
    }
  }

  async function handleDisconnect(): Promise<void> {
    await disconnectDrive();
    pushToast('success', t('settings.sync.drive.disconnected'));
  }
</script>

<Panel title={t('settings.sync.drive.title')} subtitle={t('settings.sync.drive.description')}>
  <div class="flex items-center gap-3">
    <span
      class="h-2.5 w-2.5 shrink-0 rounded-full {isAuthorized
        ? 'bg-(--color-success)'
        : 'bg-(--color-text-muted)'}"
      aria-hidden="true"
    ></span>
    <span class="flex-1 text-xs text-(--color-primary)">
      {isAuthorized ? t('settings.sync.drive.connected') : t('settings.sync.drive.notConnected')}
    </span>
    {#if isAuthorized}
      <button
        type="button"
        class="flex items-center justify-center gap-2 px-4 py-2 rounded-lg border border-(--color-border) bg-(--color-background) cursor-pointer transition-all duration-200 text-xs font-medium text-(--color-primary) hover:bg-(--color-surface) disabled:opacity-60 disabled:cursor-not-allowed"
        onclick={() => void handleDisconnect()}
        disabled={isConnecting}
      >
        <span>{t('settings.sync.drive.disconnect')}</span>
      </button>
    {:else}
      <button
        type="button"
        class="flex items-center justify-center gap-2 px-4 py-2 rounded-lg border border-(--color-primary) bg-(--color-primary) cursor-pointer transition-all duration-200 text-xs font-medium text-(--color-background) hover:opacity-90 disabled:opacity-60 disabled:cursor-not-allowed"
        onclick={() => void handleConnect()}
        disabled={isConnecting}
      >
        <span
          >{isConnecting
            ? t('settings.sync.drive.connecting')
            : t('settings.sync.drive.connect')}</span
        >
      </button>
    {/if}
  </div>
  {#if driveState.lastError}
    <p class="mt-2 text-xs text-(--color-error)">{driveState.lastError}</p>
  {/if}
</Panel>
