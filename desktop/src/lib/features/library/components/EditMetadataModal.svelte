<script lang="ts">
  import { untrack } from 'svelte';
  import { Button, Modal } from '$lib/shared/ui';
  import { pickImage } from '$lib/shared/services/FilePicker';
  import { pushToast } from '$lib/shared/stores/ToastQueue.svelte';
  import MetadataCoverField from './MetadataCoverField.svelte';
  import MetadataFields from './MetadataFields.svelte';
  import {
    buildUpdatedBook,
    draftChanged,
    draftFromBook,
    readCoverUpload,
    validateDraft,
    type CoverEdit,
    type MetadataDraft,
  } from './editMetadataForm';
  import type { LibraryBookDto } from '$lib/shared/types';
  import type { MessageKey } from '$lib/shared/i18n';

  type Props = {
    book: LibraryBookDto | null;
    open: boolean;
    onClose: () => void;
    /** @throws when the save fails; the message is shown inline. */
    onSave: (updatedBook: LibraryBookDto) => void | Promise<void>;
    /** Without both cover callbacks the cover is shown read-only. */
    onReplaceCover?: (
      bookId: string,
      cover: { data: number[]; mimeType: string; previewPath: string },
    ) => Promise<void>;
    onRemoveCover?: (book: LibraryBookDto) => Promise<void>;
    t: (key: MessageKey, params?: Record<string, string | number>) => string;
  };

  let { book, open, onClose, onSave, onReplaceCover, onRemoveCover, t }: Props = $props();

  const EMPTY: MetadataDraft = {
    title: '',
    author: '',
    genreChoice: null,
    customGenre: '',
    language: '',
    publicationDate: '',
  };

  let draft = $state<MetadataDraft>({ ...EMPTY });
  let initial = $state<MetadataDraft>({ ...EMPTY });
  let coverEdit = $state<CoverEdit>(null);
  let savedCoverPath = $state<string | null>(null);
  let isSaving = $state(false);
  let error = $state<string | null>(null);
  let titleEl = $state<HTMLInputElement | null>(null);
  let loadedFor: string | null = null;

  // Re-seed only when a different book opens: a store refresh of the same book
  // while the editor is open must not wipe what the user typed.
  $effect(() => {
    const id = open && book ? book.id : null;
    if (id === loadedFor) return;
    loadedFor = id;
    if (!book) return;
    const target = book;
    untrack(() => {
      initial = draftFromBook(target);
      draft = { ...initial };
      coverEdit = null;
      savedCoverPath = target.coverPath;
      error = null;
    });
  });

  const canEditCover = $derived(Boolean(onReplaceCover && onRemoveCover));
  const errors = $derived(validateDraft(draft, initial));
  const isValid = $derived(Object.keys(errors).length === 0);
  const fieldsChanged = $derived(draftChanged(draft, initial));
  const hasChanges = $derived(fieldsChanged || coverEdit !== null);
  const canSave = $derived(hasChanges && isValid && !isSaving);
  const previewPath = $derived(
    coverEdit?.kind === 'replace'
      ? coverEdit.file.path
      : coverEdit?.kind === 'remove'
        ? null
        : savedCoverPath,
  );
  const disabledReason = $derived(
    isSaving
      ? null
      : !isValid
        ? t('library.editMetadata.fixErrors')
        : !hasChanges
          ? t('library.editMetadata.noChanges')
          : null,
  );
  async function handlePickCover(): Promise<void> {
    const picked = await pickImage();
    if (picked) coverEdit = { kind: 'replace', file: picked };
  }

  async function applyCoverEdit(target: LibraryBookDto): Promise<void> {
    if (coverEdit?.kind === 'replace') {
      const upload = await readCoverUpload(coverEdit.file);
      await onReplaceCover?.(target.id, upload);
      savedCoverPath = upload.previewPath;
    } else if (coverEdit?.kind === 'remove') {
      await onRemoveCover?.(target);
      savedCoverPath = null;
    }
    coverEdit = null;
  }

  async function handleSave(): Promise<void> {
    if (!book || !canSave) return;
    const target = book;
    isSaving = true;
    error = null;
    try {
      await applyCoverEdit(target);
      if (fieldsChanged) await onSave(buildUpdatedBook(target, draft, initial));
      pushToast('success', t('library.editMetadata.saved'));
      onClose();
    } catch (e) {
      error = e instanceof Error && e.message ? e.message : t('library.editMetadata.saveError');
    } finally {
      isSaving = false;
    }
  }

  // Enter in a text field saves; on a select trigger it keeps opening the list.
  function handleKeydown(event: KeyboardEvent): void {
    if (event.key !== 'Enter' || event.isComposing) return;
    if (!(event.target instanceof HTMLInputElement)) return;
    event.preventDefault();
    void handleSave();
  }

  function handleOpenAutoFocus(event: Event): void {
    event.preventDefault();
    titleEl?.focus();
    titleEl?.select();
  }
</script>

{#if open && book}
  <Modal
    {open}
    size="lg"
    title={t('library.editMetadata.title')}
    description={t('library.editMetadata.description')}
    closeLabel={t('library.editMetadata.cancel')}
    onOpenAutoFocus={handleOpenAutoFocus}
    onOpenChange={(v) => {
      if (!v) onClose();
    }}
  >
    {#snippet children()}
      <!-- svelte-ignore a11y_no_noninteractive_element_interactions -->
      <form
        class="search-field flex flex-col gap-6 sm:flex-row"
        onkeydown={handleKeydown}
        onsubmit={(e) => {
          e.preventDefault();
          void handleSave();
        }}
      >
        <MetadataCoverField
          {previewPath}
          title={draft.title || book.title}
          pending={coverEdit !== null}
          disabled={isSaving}
          canEdit={canEditCover}
          onPick={() => void handlePickCover()}
          onRemove={() => (coverEdit = { kind: 'remove' })}
          onRevert={() => (coverEdit = null)}
          {t}
        />

        <div class="flex min-w-0 flex-1 flex-col gap-4">
          <MetadataFields
            bind:draft
            bind:titleEl
            {errors}
            initialLanguage={initial.language}
            disabled={isSaving}
            {t}
          />
          {#if error}
            <p
              class="flex items-start gap-2 rounded-xl border border-(--color-error)/30 bg-(--color-error)/10 px-3 py-2 text-sm text-(--color-error)"
              role="alert"
            >
              {error}
            </p>
          {/if}
        </div>
      </form>
    {/snippet}

    {#snippet footer()}
      <div class="flex w-full flex-wrap items-center gap-2">
        {#if disabledReason}
          <span class="text-xs text-(--color-text-muted)" aria-live="polite">{disabledReason}</span>
        {/if}
        <div class="ml-auto flex items-center gap-2">
          <Button variant="secondary" onclick={onClose} disabled={isSaving}>
            {t('library.editMetadata.cancel')}
          </Button>
          <Button
            variant="accent"
            onclick={() => void handleSave()}
            disabled={!canSave}
            loading={isSaving}
            loadingLabel={t('library.editMetadata.saving')}
          >
            {t('library.editMetadata.save')}
          </Button>
        </div>
      </div>
    {/snippet}
  </Modal>
{/if}
