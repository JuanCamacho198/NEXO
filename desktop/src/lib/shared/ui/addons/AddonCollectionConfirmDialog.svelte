<!--
  AddonCollectionConfirmDialog — task 15.
  Confirmation before installing a whole addon set from a deep link: every addon
  by name and version, the ones that request `stream` marked, which ones update
  an existing install, and how many entries the import refused.
-->
<script lang="ts">
  import Button from '../forms/Button.svelte';
  import Modal from '../layout/Modal.svelte';
  import type { CollectionPreviewEntry } from '$lib/features/addons/useCollectionDeepLink.svelte';
  import {
    ADDON_COLLECTION_ALREADY_INSTALLED,
    ADDON_COLLECTION_CONFIRM_CANCEL_LABEL,
    ADDON_COLLECTION_CONFIRM_DESCRIPTION,
    ADDON_COLLECTION_CONFIRM_INSTALLING_LABEL,
    ADDON_COLLECTION_CONFIRM_INSTALL_LABEL,
    ADDON_COLLECTION_CONFIRM_TITLE,
    ADDON_COLLECTION_STREAM_LABEL,
    ADDON_COLLECTION_STREAM_NOTE,
    addonCollectionSkippedText,
  } from './addonCollectionDialog';
  import { addonInstallConfirmErrorText } from './addonInstallDialog';

  type Props = {
    open: boolean;
    entries: CollectionPreviewEntry[];
    skipped?: number;
    alreadyInstalled?: string[];
    busy?: boolean;
    error?: string | null;
    onconfirm: () => void;
    oncancel: () => void;
  };

  let {
    open = $bindable(false),
    entries,
    skipped = 0,
    alreadyInstalled = [],
    busy = false,
    error = null,
    onconfirm,
    oncancel,
  }: Props = $props();

  const installedUrls = $derived(new Set(alreadyInstalled));
  const anyStream = $derived(entries.some((entry) => entry.requestsStream));
</script>

{#if open && entries.length > 0}
  <Modal
    bind:open
    size="sm"
    title={ADDON_COLLECTION_CONFIRM_TITLE}
    description={ADDON_COLLECTION_CONFIRM_DESCRIPTION}
    onOpenChange={(v) => {
      if (!v) oncancel();
    }}
  >
    {#snippet children()}
      <div class="flex flex-col gap-2 text-sm text-(--color-text-muted)">
        <ul class="flex flex-col gap-1">
          {#each entries as entry (entry.transportUrl)}
            <li class="flex flex-col">
              <span class="font-semibold text-(--color-text-primary)">
                {entry.manifest.name} v{entry.manifest.version}
              </span>
              {#if entry.requestsStream}
                <span class="text-xs text-(--color-accent)">{ADDON_COLLECTION_STREAM_LABEL}</span>
              {/if}
              {#if installedUrls.has(entry.transportUrl)}
                <span class="text-xs text-(--color-text-muted)">
                  {ADDON_COLLECTION_ALREADY_INSTALLED}
                </span>
              {/if}
            </li>
          {/each}
        </ul>
        {#if skipped > 0}
          <p class="text-xs text-(--color-text-muted)">{addonCollectionSkippedText(skipped)}</p>
        {/if}
        {#if anyStream}
          <p class="text-xs">{ADDON_COLLECTION_STREAM_NOTE}</p>
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
        {ADDON_COLLECTION_CONFIRM_CANCEL_LABEL}
      </Button>
      <Button variant="primary" size="sm" disabled={busy} onclick={onconfirm}>
        {busy ? ADDON_COLLECTION_CONFIRM_INSTALLING_LABEL : ADDON_COLLECTION_CONFIRM_INSTALL_LABEL}
      </Button>
    {/snippet}
  </Modal>
{/if}
