<script lang="ts">
  import { Button, Modal } from '$lib/shared/ui';
  import type { LibraryBookDto } from '$lib/shared/types';
  import type { MessageKey } from '$lib/shared/i18n';

  type Props = {
    book: LibraryBookDto | null;
    open: boolean;
    onClose: () => void;
    onSave: (updatedBook: LibraryBookDto) => void;
    t: (key: MessageKey, params?: Record<string, string | number>) => string;
  };

  let { book, open, onClose, onSave, t }: Props = $props();

  let title = $state('');
  let author = $state('');
  let genre = $state('');
  let isSaving = $state(false);
  let error = $state<string | null>(null);

  const MAX_GENRE_LENGTH = 80;
  // C0 control characters (0x00-0x1F) and DEL (0x7F). Anything else
  // (printable whitespace, accented letters, etc.) is allowed; this
  // matches the spec — we reject \n, \t, and other control codes, but
  // accept regular spaces.
  const CONTROL_CHAR_REGEX = /[\u0000-\u001f\u007f]/;

  $effect(() => {
    if (open && book) {
      title = book.title;
      author = book.author || '';
      genre = book.genre ?? '';
      error = null;
    }
  });

  const hasChanges = $derived(
    book !== null &&
      (title !== book.title || author !== (book.author || '') || genre !== (book.genre ?? '')),
  );

  const handleSave = async (): Promise<void> => {
    if (!book || !title.trim()) {
      error = t('library.editMetadata.titleRequired');
      return;
    }

    const trimmedGenre = genre.trim();
    if (trimmedGenre.length > MAX_GENRE_LENGTH) {
      error = t('library.editMetadata.genreTooLong');
      return;
    }
    if (CONTROL_CHAR_REGEX.test(trimmedGenre)) {
      error = t('library.editMetadata.genreInvalidChars');
      return;
    }

    isSaving = true;
    error = null;

    try {
      await onSave({
        ...book,
        title: title.trim(),
        author: author.trim(),
        genre: trimmedGenre.length > 0 ? trimmedGenre : null,
      });
    } catch (e) {
      error = e instanceof Error ? e.message : t('errors.commandFailure');
    } finally {
      isSaving = false;
    }
  };
</script>

{#if open && book}
  <Modal
    bind:open
    title={t('library.editMetadata.title')}
    onOpenChange={(v) => {
      if (!v) onClose();
    }}
  >
    {#snippet children()}
      <div class="space-y-4">
        <div>
          <label for="edit-title" class="mb-1 block text-sm font-medium text-(--color-primary)">
            {t('library.editMetadata.titleLabel')}
          </label>
          <input
            id="edit-title"
            type="text"
            bind:value={title}
            class="w-full rounded-md border border-(--color-border) bg-(--color-background) px-3 py-2 text-sm text-(--color-primary) focus:border-(--color-primary) focus:outline-none"
          />
        </div>

        <div>
          <label for="edit-author" class="mb-1 block text-sm font-medium text-(--color-primary)">
            {t('library.editMetadata.authorLabel')}
          </label>
          <input
            id="edit-author"
            type="text"
            bind:value={author}
            class="w-full rounded-md border border-(--color-border) bg-(--color-background) px-3 py-2 text-sm text-(--color-primary) focus:border-(--color-primary) focus:outline-none"
          />
        </div>

        <div>
          <label for="edit-genre" class="mb-1 block text-sm font-medium text-(--color-primary)">
            {t('library.editMetadata.genreLabel')}
          </label>
          <input
            id="edit-genre"
            type="text"
            bind:value={genre}
            maxlength={MAX_GENRE_LENGTH}
            class="w-full rounded-md border border-(--color-border) bg-(--color-background) px-3 py-2 text-sm text-(--color-primary) focus:border-(--color-primary) focus:outline-none"
          />
        </div>

        {#if error}
          <p class="text-sm text-(--color-error)">{error}</p>
        {/if}
      </div>
    {/snippet}

    {#snippet footer()}
      <Button variant="secondary" onclick={onClose} disabled={isSaving}>
        {t('library.editMetadata.cancel')}
      </Button>
      <Button onclick={handleSave} disabled={!hasChanges || isSaving}>
        {isSaving ? t('library.editMetadata.saving') : t('library.editMetadata.save')}
      </Button>
    {/snippet}
  </Modal>
{/if}
