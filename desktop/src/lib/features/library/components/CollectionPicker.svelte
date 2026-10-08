<script lang="ts">
  import type { CollectionDto } from '$lib/shared/types';
  import type { LibraryPort } from '$lib/shared/ports/LibraryPort';
  import { TauriLibraryAdapter } from '$lib/shared/ports/adapters/tauri/TauriLibraryAdapter';
  import { logger } from '$lib/shared/logger/Logger';

  type Props = {
    bookId: string;
    collectionIds: number[];
    collections: CollectionDto[];
    onUpdate: (newIds: number[]) => void;
    libraryPort?: LibraryPort;
  };

  let {
    bookId,
    collectionIds,
    collections,
    onUpdate,
    libraryPort: libraryPortProp,
  }: Props = $props();

  // svelte-ignore state_referenced_locally
  const libraryPort: LibraryPort = libraryPortProp ?? new TauriLibraryAdapter();

  let loading = $state(false);

  async function handleToggle(collectionId: number, checked: boolean): Promise<void> {
    if (loading) return;
    loading = true;
    try {
      if (checked) {
        await libraryPort.addBookToCollection({ bookId, collectionId });
      } else {
        await libraryPort.removeBookFromCollection({ bookId, collectionId });
      }
      const newIds = checked
        ? [...collectionIds, collectionId]
        : collectionIds.filter((id) => id !== collectionId);
      onUpdate(newIds);
    } catch (e) {
      logger.error(
        'Failed to update collection:',
        { error: e instanceof Error ? e.message : String(e) },
        'library',
      );
    } finally {
      loading = false;
    }
  }
</script>

<fieldset class="space-y-2 border-0 p-0 m-0">
  <legend class="sr-only">Colecciones</legend>
  {#each collections as collection}
    <label class="flex items-center gap-2 cursor-pointer">
      <input
        type="checkbox"
        checked={collectionIds.includes(collection.id)}
        disabled={loading}
        class="h-4 w-4 rounded border-(--color-border) text-(--color-primary) focus:ring-(--color-primary)"
        onchange={(e) => handleToggle(collection.id, e.currentTarget.checked)}
      />
      <span
        class="inline-block h-3 w-3 rounded-full"
        style="background-color: {collection.color ?? '#6366f1'}"
      ></span>
      <span class="text-sm text-(--color-primary)">{collection.name}</span>
    </label>
  {/each}
  {#if collections.length === 0}
    <p class="text-sm text-(--color-text-muted)">No collections available</p>
  {/if}
</fieldset>
