<script lang="ts">
  import Dropdown from '$lib/shared/ui/navigation/Dropdown.svelte';
  import {
    MAX_GENRE_LENGTH,
    OTHER_GENRE,
    genreOptions,
    languageOptions,
    type DraftErrors,
    type MetadataDraft,
  } from './editMetadataForm';
  import type { MessageKey } from '$lib/shared/i18n';

  type Props = {
    draft: MetadataDraft;
    errors: DraftErrors;
    /** The language the book opened with: an unknown stored code stays selectable. */
    initialLanguage: string;
    disabled: boolean;
    titleEl?: HTMLInputElement | null;
    t: (key: MessageKey, params?: Record<string, string | number>) => string;
  };

  let {
    draft = $bindable(),
    errors,
    initialLanguage,
    disabled,
    titleEl = $bindable(null),
    t,
  }: Props = $props();

  const genres = $derived(genreOptions(t));
  const languages = $derived(languageOptions(t, initialLanguage));

  // `!` beats the unlayered input[type='text'] chrome in styles.css.
  const labelClass = 'text-xs font-semibold tracking-[0.06em] text-(--color-text-muted) uppercase';
  const inputClass =
    'w-full! rounded-xl! border! bg-(--color-home-card)! px-3.5 py-2.5 text-sm text-(--color-primary) placeholder-(--color-text-muted) transition-colors focus:border-(--color-brand)! focus:outline-none';
  const borderFor = (invalid: boolean): string =>
    invalid ? 'border-(--color-error)!' : 'border-(--color-edge)!';
  const selectClass =
    'w-full rounded-xl! border-(--color-edge)! bg-(--color-home-card)! px-3.5! py-2.5! focus-visible:border-(--color-brand)!';
</script>

{#snippet fieldLabel(id: string, key: MessageKey)}
  <label for={id} class="mt-0! mb-1.5! {labelClass}">{t(key)}</label>
{/snippet}

<!-- The select atom takes no id, so its caption is a plain heading, not a <label for>. -->
{#snippet selectLabel(key: MessageKey)}
  <span class="mb-1.5 block {labelClass}">{t(key)}</span>
{/snippet}

{#snippet fieldError(key: MessageKey | undefined, id: string)}
  {#if key}<p {id} class="mt-1.5 text-xs text-(--color-error)">{t(key)}</p>{/if}
{/snippet}

<div class="flex min-w-0 flex-1 flex-col gap-4">
  <div>
    {@render fieldLabel('edit-title', 'library.editMetadata.titleLabel')}
    <input
      id="edit-title"
      type="text"
      bind:this={titleEl}
      bind:value={draft.title}
      {disabled}
      aria-invalid={errors.title ? 'true' : undefined}
      aria-describedby={errors.title ? 'edit-title-error' : undefined}
      class="{inputClass} {borderFor(Boolean(errors.title))}"
    />
    {@render fieldError(errors.title, 'edit-title-error')}
  </div>

  <div>
    {@render fieldLabel('edit-author', 'library.editMetadata.authorLabel')}
    <input
      id="edit-author"
      type="text"
      bind:value={draft.author}
      {disabled}
      placeholder={t('library.editMetadata.authorPlaceholder')}
      class="{inputClass} {borderFor(false)}"
    />
  </div>

  <div class="grid grid-cols-1 gap-4 sm:grid-cols-2">
    <div class="min-w-0 [&>div]:w-full">
      {@render selectLabel('library.editMetadata.genreLabel')}
      <Dropdown
        options={genres}
        bind:value={draft.genreChoice}
        placeholder={t('shelf.selectGenre')}
        {disabled}
        class={selectClass}
      />
    </div>
    <div class="min-w-0 [&>div]:w-full">
      {@render selectLabel('library.editMetadata.languageLabel')}
      <Dropdown options={languages} bind:value={draft.language} {disabled} class={selectClass} />
    </div>
  </div>

  {#if draft.genreChoice === OTHER_GENRE}
    <div>
      {@render fieldLabel('edit-genre', 'library.editMetadata.customGenreLabel')}
      <input
        id="edit-genre"
        type="text"
        bind:value={draft.customGenre}
        maxlength={MAX_GENRE_LENGTH}
        {disabled}
        placeholder={t('shelf.customGenre')}
        aria-invalid={errors.genre ? 'true' : undefined}
        class="{inputClass} {borderFor(Boolean(errors.genre))}"
      />
      {@render fieldError(errors.genre, 'edit-genre-error')}
    </div>
  {/if}

  <div>
    {@render fieldLabel('edit-publication-date', 'library.editMetadata.publicationDateLabel')}
    <input
      id="edit-publication-date"
      type="text"
      bind:value={draft.publicationDate}
      {disabled}
      placeholder={t('library.editMetadata.publicationDatePlaceholder')}
      aria-invalid={errors.publicationDate ? 'true' : undefined}
      aria-describedby="edit-publication-date-hint"
      class="{inputClass} {borderFor(Boolean(errors.publicationDate))}"
    />
    {#if errors.publicationDate}
      {@render fieldError(errors.publicationDate, 'edit-publication-date-hint')}
    {:else}
      <p id="edit-publication-date-hint" class="mt-1.5 text-xs text-(--color-text-muted)">
        {t('library.editMetadata.publicationDateHint')}
      </p>
    {/if}
  </div>
</div>
