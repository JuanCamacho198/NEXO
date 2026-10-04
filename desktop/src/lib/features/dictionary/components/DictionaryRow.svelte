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
    class={`flex w-full cursor-pointer items-center gap-3 rounded-[10px] p-3 text-left transition-colors ${
      selected
        ? 'bg-(--color-accent-fill)'
        : 'bg-(--color-panel-input) hover:bg-(--color-accent-fill)'
    }`}
    onclick={() => onselect(word.id)}
  >
    <span
      class="flex h-9 w-9 shrink-0 items-center justify-center rounded-sm text-sm font-semibold text-(--color-avatar-ink)"
      style={`background-color: ${avatarColorVariable(index)}`}
      data-testid="dictionary-row-initial"
      aria-hidden="true"
    >
      {initial}
    </span>

    <span class="flex min-w-0 flex-1 flex-col gap-0.5">
      <span class="truncate text-2sm text-(--color-primary)" data-testid="dictionary-row-term">
        {word.word}
      </span>
      {#if bookTitle}
        <span
          class="truncate text-2xs text-(--color-text-tertiary)"
          data-testid="dictionary-row-book"
        >
          {bookTitle}
        </span>
      {/if}
    </span>

    <span class="flex shrink-0 flex-col items-end gap-0.5">
      <span class="text-micro text-(--color-text-tertiary)" data-testid="dictionary-row-date">
        {dateLabel}
      </span>
      {#if !complete}
        <span
          class="rounded-full border border-(--color-panel-border) bg-(--color-panel) px-2 py-0.5 text-micro font-semibold text-(--color-text-tertiary)"
          data-testid="dictionary-row-incomplete"
        >
          {t('dictionary.incomplete')}
        </span>
      {/if}
    </span>
  </button>
</li>
