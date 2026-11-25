<script lang="ts">
  import Modal from '$lib/shared/ui/layout/Modal.svelte';
  import Button from '$lib/shared/ui/forms/Button.svelte';
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
    /** The highlight's colour: the reference block wears it, as in the book. */
    highlightColor?: string | null;
    onSave: (note: string | null) => void;
    onClose: () => void;
    t: (key: MessageKey, params?: Record<string, string | number>) => string;
  };

  let { open, note, highlightText, highlightColor = null, onSave, onClose, t }: Props = $props();

  const MAX_LENGTH = 1000;

  let textareaEl = $state<HTMLTextAreaElement | null>(null);
  let draft = $state('');

  const hadNote = $derived(Boolean(note && note.trim()));
  const quoteColor = $derived(highlightColor ?? 'var(--color-brand)');

  function handleSave(): void {
    const trimmed = draft.trim();
    onSave(trimmed || null);
    onClose();
  }

  function handleDelete(): void {
    onSave(null);
    onClose();
  }

  // Ctrl/⌘ + Enter saves without reaching for the mouse; a plain Enter is a
  // new line, as in any text box.
  function handleKeydown(event: KeyboardEvent): void {
    if (event.key === 'Enter' && (event.ctrlKey || event.metaKey)) {
      event.preventDefault();
      handleSave();
    }
  }

  // Escape, backdrop close and focus containment are owned by the shared
  // Modal facade's bits-ui dismissible layer and focus scope: Escape and an
  // outside pointerdown drive `open` false, which reaches `onClose` through
  // `onOpenChange` below. There is deliberately no `focusTrap` import (the old
  // hand-rolled pair was the defect the swap removed). The component never
  // writes `open` itself, so the `onOpenChange` forward cannot double-fire.

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
      <!-- The passage being annotated, in the highlight's own colour. -->
      {#if highlightText}
        <figure
          class="m-0 rounded-r-xl border-l-4 py-3 pr-4 pl-4"
          style="border-color: {quoteColor}; background-color: color-mix(in srgb, {quoteColor} 12%, transparent);"
        >
          <figcaption
            class="text-micro font-semibold tracking-[0.08em] text-(--color-text-muted) uppercase"
          >
            {t('highlight.noteReference')}
          </figcaption>
          <blockquote
            class="m-0 mt-1.5 line-clamp-4 font-serif text-[0.9375rem] leading-relaxed text-(--color-primary)"
          >
            “{highlightText}”
          </blockquote>
        </figure>
      {/if}

      <!-- `search-field`: the border carries the focus, not the global outline. -->
      <div class="search-field flex flex-col gap-1.5">
        <textarea
          bind:this={textareaEl}
          bind:value={draft}
          rows="6"
          maxlength={MAX_LENGTH}
          onkeydown={handleKeydown}
          aria-label={t('highlight.noteInputAriaLabel')}
          class="w-full resize-none rounded-xl border border-(--color-edge) bg-(--color-background) p-4 text-sm leading-relaxed text-(--color-primary) placeholder-(--color-text-muted) transition-colors focus:border-(--color-brand) focus:outline-none"
          placeholder={t('highlight.notePlaceholder')}></textarea>
        <div class="flex items-center justify-between px-1 text-xs text-(--color-text-muted)">
          <span>{t('highlight.noteShortcutHint')}</span>
          <span class="tabular-nums">{draft.length}/{MAX_LENGTH}</span>
        </div>
      </div>
    </div>
  {/snippet}

  {#snippet footer()}
    <div class="flex w-full items-center gap-2">
      {#if hadNote}
        <Button variant="danger-outline" size="sm" onclick={handleDelete}>
          {t('highlight.deleteNote')}
        </Button>
      {/if}
      <div class="ml-auto flex items-center gap-2">
        <Button variant="secondary" size="md" onclick={onClose}>
          {t('highlight.cancel')}
        </Button>
        <Button variant="accent" size="md" onclick={handleSave}>
          {t('highlight.save')}
        </Button>
      </div>
    </div>
  {/snippet}
</Modal>
