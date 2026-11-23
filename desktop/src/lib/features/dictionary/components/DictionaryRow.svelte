<script lang="ts">
  import type { DictionaryWordDto } from '$lib/shared/types';
  import {
    avatarColorVariable,
    entryInitial,
    formatEntryDate,
    isComplete,
    type Translator,
  } from '../dictionaryEntry';

  type Props = {
    word: DictionaryWordDto;
    /** Position in the rendered list; drives the avatar colour rotation. */
    index: number;
    selected: boolean;
    t: Translator;
    now?: Date;
    onselect: (id: string) => void;
  };

  let { word, index, selected, t, now = new Date(), onselect }: Props = $props();

  const initial = $derived(entryInitial(word.word));
  const bookTitle = $derived(word.sourceBookTitle?.trim() ?? '');
  const dateLabel = $derived(formatEntryDate(word.createdAt, now, t));
  const complete = $derived(isComplete(word));
</script>

<li>
  <button
    type="button"
    aria-current={selected ? 'true' : undefined}
    data-testid="dictionary-row"
    class={`flex w-full cursor-pointer items-center gap-3 rounded-(--radius-md) border p-3 text-left transition-colors focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-(--color-brand) ${
      selected
        ? 'border-(--color-brand)/50 bg-(--color-accent-fill)'
        : 'border-transparent bg-transparent hover:bg-(--color-panel-input)'
    }`}
    onclick={() => onselect(word.id)}
  >
    <span
      class="flex size-10 shrink-0 items-center justify-center rounded-(--radius-md) text-base font-semibold text-(--color-primary)"
      style={`background-color: color-mix(in srgb, ${avatarColorVariable(index)} 20%, transparent); box-shadow: inset 0 0 0 1px color-mix(in srgb, ${avatarColorVariable(index)} 45%, transparent)`}
      data-testid="dictionary-row-initial"
      aria-hidden="true"
    >
      {initial}
    </span>

    <span class="flex min-w-0 flex-1 flex-col gap-0.5">
      <span
        class="truncate text-sm font-semibold text-(--color-primary)"
        data-testid="dictionary-row-term"
      >
        {word.word}
      </span>
      {#if bookTitle}
        <span
          class="truncate text-micro text-(--color-text-muted)"
          data-testid="dictionary-row-book"
        >
          {bookTitle}
        </span>
      {/if}
    </span>

    <span class="flex shrink-0 flex-col items-end gap-1">
      <span class="text-micro text-(--color-text-muted)" data-testid="dictionary-row-date">
        {dateLabel}
      </span>
      {#if !complete}
        <span
          class="inline-flex items-center gap-1.5 text-micro font-medium text-(--color-text-muted)"
          data-testid="dictionary-row-incomplete"
        >
          <span class="size-1.5 rounded-full bg-(--color-warning)" aria-hidden="true"></span>
          {t('dictionary.incomplete')}
        </span>
      {/if}
    </span>
  </button>
</li>
