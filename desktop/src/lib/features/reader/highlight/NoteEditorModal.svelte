<script lang="ts">
  import Modal from '$lib/shared/ui/layout/Modal.svelte';
  import type { MessageKey } from '$lib/shared/i18n';

  type Props = {
    open: boolean;
    note: string | null;
    /**
     * Text of the highlighted passage. When provided, it is shown above the
     * textarea as a reference block so the user can see what they're
     * annotating without scrolling back to the book.
     */
    highlightText?: string | null;
    onSave: (note: string | null) => void;
    onClose: () => void;
    t: (key: MessageKey, params?: Record<string, string | number>) => string;
  };

  let { open, note, highlightText, onSave, onClose, t }: Props = $props();

  let textareaEl = $state<HTMLTextAreaElement | null>(null);
  let draft = $state('');

  function handleSave(): void {
    const trimmed = draft.trim();
    onSave(trimmed || null);
    onClose();
  }

  // Escape, backdrop close and focus containment are owned by the shared
  // Modal facade's bits-ui dismissible layer and focus scope: Escape and an
  // outside pointerdown drive `open` false, which reaches `onClose` through
  // `onOpenChange` below. There is deliberately no local keydown handler and
  // no `focusTrap` import (the old hand-rolled pair was the defect the
  // swap removed). The component never writes `open` itself, so the
  // `onOpenChange` forward cannot double-fire.

  $effect(() => {
    if (open) {
      draft = note ?? '';
    }
  });

  /**
   * The pre-swap dialog moved focus into the textarea on open. bits-ui
   * would otherwise auto-focus the content element itself; preventing its
   * default here and focusing the textarea in the same handler keeps the
   * behaviour deterministic instead of racing two focus writers.
   */
  function handleOpenAutoFocus(event: Event): void {
    event.preventDefault();
    textareaEl?.focus();
  }
</script>

<!--
  The shell is the shared Modal facade. `layer="reader"` lifts it above the
  reader chrome it is opened over (see the layer tokens in tokens.css);
  `closeLabel` keeps the header X's accessible name on this surface's
  `highlight.cancel` string, matching the footer button.
-->
<Modal
  {open}
  layer="reader"
  size="lg"
  title={t('highlight.noteModalTitle')}
  closeLabel={t('highlight.cancel')}
  onOpenAutoFocus={handleOpenAutoFocus}
  onOpenChange={(o) => {
    if (!o) onClose();
  }}
>
  {#snippet children()}
    <div class="flex flex-col gap-4">
      <!-- Reference: highlighted text shown so the user can see what they're annotating -->
      {#if highlightText}
        <div
          class="rounded-r-lg border-l-4 border-(--color-accent-blue) bg-(--color-bg-deep)/40 p-3"
        >
          <div class="text-micro font-bold tracking-[0.6px] text-(--color-text-muted) uppercase">
            {t('highlight.noteReference')}
          </div>
          <div class="mt-1 text-sm text-(--color-secondary)">
            “{highlightText}”
          </div>
        </div>
      {/if}

      <!-- Note textarea -->
      <textarea
        bind:this={textareaEl}
        bind:value={draft}
        rows="5"
        maxlength="1000"
        class="w-full resize-none rounded-2xl border border-(--color-border) bg-(--color-bg-deep) p-4 text-sm text-(--color-text-inverse) placeholder-(--color-text-auxiliary) focus:outline-none focus:ring-1 focus:ring-(--color-accent-sky)"
        placeholder={t('highlight.notePlaceholder')}></textarea>
    </div>
  {/snippet}

  {#snippet footer()}
    <button
      type="button"
      class="cursor-pointer rounded-full px-5 py-2 text-sm font-medium text-(--color-text-muted) transition-colors hover:text-(--color-text-inverse)"
      onclick={onClose}
    >
      {t('highlight.cancel')}
    </button>
    <button
      type="button"
      class="cursor-pointer rounded-full bg-(--color-accent-blue) px-6 py-2 text-sm font-bold text-(--color-bg-deep) shadow-(--shadow-glow) transition-all hover:shadow-(--shadow-glow-hover)"
      onclick={handleSave}
    >
      {t('highlight.save')}
    </button>
  {/snippet}
</Modal>
