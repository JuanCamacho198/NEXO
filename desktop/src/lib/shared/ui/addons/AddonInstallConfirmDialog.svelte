<!--
  AddonInstallConfirmDialog — sdd/addon-deeplink-v1 Work Unit A.
  Confirmation before installing an addon from a deep-link manifest preview:
  name, version, id, already-installed note, busy state, error text.
  Modal + Button atoms; primary confirm (install is not destructive).
-->
<script lang="ts">
  import Button from '../forms/Button.svelte';
  import Modal from '../layout/Modal.svelte';
  import type { AddonManifest } from '@nexo/manifest-validator';
  import {
    ADDON_INSTALL_CONFIRM_TITLE,
    ADDON_INSTALL_CONFIRM_INSTALL_LABEL,
    ADDON_INSTALL_CONFIRM_INSTALLING_LABEL,
    ADDON_INSTALL_CONFIRM_CANCEL_LABEL,
    ADDON_INSTALL_CONFIRM_VERSION_LABEL,
    ADDON_INSTALL_CONFIRM_PUBLISHER_LABEL,
    ADDON_INSTALL_CONFIRM_ALREADY_INSTALLED,
    addonInstallConfirmErrorText,
  } from './addonInstallDialog';

  type Props = {
    open: boolean;
    manifest: AddonManifest | null;
    busy?: boolean;
    error?: string | null;
    alreadyInstalled?: boolean;
    onconfirm: () => void;
    oncancel: () => void;
  };

  let {
    open = $bindable(false),
    manifest,
    busy = false,
    error = null,
    alreadyInstalled = false,
    onconfirm,
    oncancel,
  }: Props = $props();
</script>

{#if open && manifest}
  <Modal bind:open size="sm" title={ADDON_INSTALL_CONFIRM_TITLE}>
    {#snippet children()}
      <div class="flex flex-col gap-2 text-sm text-(--color-text-muted)">
        <p class="text-base font-bold text-(--color-text-primary)">{manifest.name}</p>
        <div class="flex flex-col gap-1">
          <div class="flex justify-between gap-4">
            <span>{ADDON_INSTALL_CONFIRM_VERSION_LABEL}</span>
            <span class="font-semibold text-(--color-text-primary)">{manifest.version}</span>
          </div>
          <div class="flex justify-between gap-4">
            <span>{ADDON_INSTALL_CONFIRM_PUBLISHER_LABEL}</span>
            <span class="font-semibold text-(--color-text-primary)">{manifest.id}</span>
          </div>
        </div>
        {#if alreadyInstalled}
          <p class="text-xs text-(--color-accent)">{ADDON_INSTALL_CONFIRM_ALREADY_INSTALLED}</p>
        {/if}
        {#if error}
          <p class="text-xs text-(--color-error)" role="alert">
            {addonInstallConfirmErrorText(error)}
          </p>
        {/if}
      </div>
    {/snippet}
    {#snippet footer()}
      <Button variant="secondary" size="sm" disabled={busy} onclick={oncancel}>
        {ADDON_INSTALL_CONFIRM_CANCEL_LABEL}
      </Button>
      <Button variant="primary" size="sm" disabled={busy} onclick={onconfirm}>
        {#if busy}
          <svg
            class="h-3.5 w-3.5 animate-spin"
            viewBox="0 0 24 24"
            fill="none"
            stroke="currentColor"
            stroke-width="2"
            aria-hidden="true"
          >
            <path d="M21 12a9 9 0 1 1-6.2-8.56" />
          </svg>
          {ADDON_INSTALL_CONFIRM_INSTALLING_LABEL}
        {:else}
          {ADDON_INSTALL_CONFIRM_INSTALL_LABEL}
        {/if}
      </Button>
    {/snippet}
  </Modal>
{/if}
