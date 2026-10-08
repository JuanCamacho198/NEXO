<script lang="ts">
  import Button from '$lib/shared/ui/forms/Button.svelte';
  import Panel from '$lib/shared/ui/layout/Panel.svelte';
  import { driveState } from '$lib/shared/stores/driveState.svelte';
  import {
    beginDriveConnect,
    disconnectDrive,
    isDriveAuthorized,
  } from '$lib/shared/services/DriveConnectService';
  import { pushToast } from '$lib/shared/stores/ToastQueue.svelte';
  import type { MessageKey } from '$lib/shared/i18n';

  type Props = {
    t: (key: MessageKey, params?: Record<string, string | number>) => string;
  };

  let { t }: Props = $props();

  const isAuthorized = $derived(driveState.isAuthorized);
  const isConnecting = $derived(driveState.isConnecting);
  const lastError = $derived(driveState.lastError);
  const showError = $derived(!isAuthorized && !isConnecting && lastError !== null);

  $effect(() => {
    void isDriveAuthorized();
  });

  async function handleConnect(): Promise<void> {
    const result = await beginDriveConnect();
    // beginDriveConnect feeds driveState itself; surface failures only —
    // cancel stays silent (Android parity), success flips the section.
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
  {#if isConnecting}
    <div class="flex items-center gap-3">
      <span
        class="h-2.5 w-2.5 shrink-0 animate-pulse rounded-full bg-(--color-text-muted)"
        aria-hidden="true"
      ></span>
      <span class="flex-1 text-xs text-(--color-primary)"
        >{t('settings.sync.drive.connecting')}</span
      >
    </div>
  {:else if showError}
    <div class="flex items-center gap-3">
      <span class="h-2.5 w-2.5 shrink-0 rounded-full bg-(--color-error)" aria-hidden="true"></span>
      <span class="flex-1 text-xs text-(--color-error)">{lastError}</span>
      <Button variant="primary" data-testid="drive-retry" onclick={() => void handleConnect()}>
        <span>{t('error.retry')}</span>
      </Button>
    </div>
  {:else}
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
        <Button
          variant="secondary"
          data-testid="drive-disconnect"
          onclick={() => void handleDisconnect()}
        >
          <span>{t('settings.sync.drive.disconnect')}</span>
        </Button>
      {:else}
        <Button variant="primary" data-testid="drive-connect" onclick={() => void handleConnect()}>
          <span>{t('settings.sync.drive.connect')}</span>
        </Button>
      {/if}
    </div>
  {/if}
</Panel>
