<script lang="ts">
  import type { MessageKey } from '$lib/shared/i18n';
  import type { Translator, UserFieldDraft, UserFieldKey } from '../dictionaryEntry';

  type Props = {
    t: Translator;
    draft: UserFieldDraft;
    onChangeDraft: (next: UserFieldDraft) => void;
    onSave: () => void;
    onCancelEdit: () => void;
  };

  let { t, draft, onChangeDraft, onSave, onCancelEdit }: Props = $props();

  /**
   * The four user-authored fields and nothing else. There is no row for
   * `quote` or the book reference, which is REQ-DRE-007's read-only half made
   * structural: the form has no control that could name an evidence key.
   */
  const fields: { key: UserFieldKey; label: MessageKey; multiline: boolean }[] = [
    { key: 'definition', label: 'dictionary.description', multiline: true },
    { key: 'partOfSpeech', label: 'dictionary.partOfSpeechLabel', multiline: false },
    { key: 'phonetic', label: 'dictionary.phoneticLabel', multiline: false },
    { key: 'example', label: 'dictionary.personalExample', multiline: true },
  ];

  function update(key: UserFieldKey, value: string): void {
    onChangeDraft({ ...draft, [key]: value });
  }

  function handleSubmit(event: SubmitEvent): void {
    event.preventDefault();
    onSave();
  }
</script>

<form class="flex flex-col gap-4" data-testid="dictionary-edit-form" onsubmit={handleSubmit}>
  {#each fields as field (field.key)}
    <label class="flex flex-col gap-2">
      <span class="text-xs font-semibold text-(--color-accent-blue)">{t(field.label)}</span>
      {#if field.multiline}
        <textarea
          rows="2"
          class="resize-y rounded-[10px] border border-(--color-panel-border) bg-(--color-panel-input) px-3 py-2.5 text-sm text-(--color-primary) placeholder:text-(--color-text-tertiary)"
          value={draft[field.key]}
          data-testid={`dictionary-edit-${field.key}`}
          oninput={(event) => update(field.key, event.currentTarget.value)}></textarea>
      {:else}
        <input
          type="text"
          class="rounded-[10px] border border-(--color-panel-border) bg-(--color-panel-input) px-3 py-2.5 text-sm text-(--color-primary) placeholder:text-(--color-text-tertiary)"
          value={draft[field.key]}
          data-testid={`dictionary-edit-${field.key}`}
          oninput={(event) => update(field.key, event.currentTarget.value)}
        />
      {/if}
    </label>
  {/each}

  <div class="flex items-center gap-2">
    <button
      type="button"
      class="cursor-pointer rounded-sm bg-(--color-panel-input) px-1.5 py-2 text-xs font-semibold text-(--color-secondary) transition-colors hover:bg-(--color-accent-fill)"
      data-testid="dictionary-edit-cancel"
      onclick={onCancelEdit}
    >
      {t('dictionary.cancel')}
    </button>
    <button
      type="submit"
      class="cursor-pointer rounded-[10px] bg-(--color-accent-blue) px-4 py-2.5 text-sm font-bold text-(--color-accent-on) transition-opacity hover:opacity-90"
      data-testid="dictionary-edit-save"
    >
      {t('dictionary.save')}
    </button>
  </div>
</form>
