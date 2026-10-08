<script lang="ts">
  import { Button, Modal } from '$lib/shared/ui';
  import { libraryState } from '$lib/shared/stores/LibraryDomainState.svelte';
  import type { MessageKey } from '$lib/shared/i18n';

  type Props = {
    open: boolean;
    onClose: () => void;
    t: (key: MessageKey, params?: Record<string, string | number>) => string;
  };

  let { open, onClose, t }: Props = $props();

  // 2-step flow: confirm → choose. Reset to step 1 every time the modal opens.
  let step = $state<'confirm' | 'choose'>('confirm');

  $effect(() => {
    if (open) {
      step = 'confirm';
    }
  });

  function handleLocalOnly(): void {
    const book = libraryState.pendingRemoveBook;
    onClose();
    if (book) void libraryState.handleHideBook(book);
  }

  function handleLocalAndDrive(): void {
    const book = libraryState.pendingRemoveBook;
    onClose();
    if (book) void libraryState.handleRemoveBookFromDrive(book);
  }
</script>

{#if open && libraryState.pendingRemoveBook}
  {@const pendingBook = libraryState.pendingRemoveBook}
  <Modal
    bind:open
    title={t('shelf.removeConfirmTitle')}
    description={step === 'confirm'
      ? t('shelf.removeConfirmBody', { title: pendingBook.title })
      : undefined}
    size="sm"
    onOpenChange={(v) => {
      if (!v) onClose();
    }}
  >
    {#snippet children()}
      {#if step === 'choose'}
        <div class="space-y-3">
          <button
            type="button"
            class="w-full rounded-lg border border-(--color-border) bg-(--color-surface) p-3 text-left transition-colors hover:bg-(--color-surface-hover)"
            onclick={handleLocalOnly}
          >
            <span class="block text-sm font-medium text-(--color-primary)"
              >{t('shelf.removeLocalOnly')}</span
            >
            <span class="block text-xs text-(--color-text-muted)"
              >{t('shelf.removeLocalOnlySubtitle')}</span
            >
          </button>
          <button
            type="button"
            class="w-full rounded-lg border border-(--color-error)/40 bg-(--color-surface) p-3 text-left transition-colors hover:border-(--color-error) hover:bg-(--color-error-soft)"
            onclick={handleLocalAndDrive}
          >
            <span class="block text-sm font-medium text-(--color-error)"
              >{t('shelf.removeLocalAndDrive')}</span
            >
            <span class="block text-xs text-(--color-text-muted)"
              >{t('shelf.removeLocalAndDriveSubtitle')}</span
            >
          </button>
        </div>
      {/if}
    {/snippet}

    {#snippet footer()}
      {#if step === 'confirm'}
        <Button variant="secondary" onclick={onClose}>{t('shelf.removeCancel')}</Button>
        <Button onclick={() => (step = 'choose')}>{t('shelf.removeContinue')}</Button>
      {:else}
        <Button variant="secondary" onclick={onClose}>{t('shelf.removeCancel')}</Button>
      {/if}
    {/snippet}
  </Modal>
{/if}
