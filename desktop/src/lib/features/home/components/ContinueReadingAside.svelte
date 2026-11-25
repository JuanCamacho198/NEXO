<script lang="ts">
  import { invoke } from '@tauri-apps/api/core';
  import type { LibraryBookDto } from '$lib/shared/types';
  import type { MessageKey } from '$lib/shared/i18n';
  import type { ViewerPort } from '$lib/shared/ports/ViewerPort';
  import { TauriViewerAdapter } from '$lib/shared/ports';
  import type { ReadingProgressDto } from '$lib/shared/types';
  import { buildContinueStats } from '../continueReadingStats';
  import { resolveLastChapter } from '../resolveLastChapter';
  import { getLastChapter, saveLastChapter } from '$lib/shared/stores/lastChapterStore';

  type Props = {
    book: LibraryBookDto & { filePath?: string };
    t: (key: MessageKey, params?: Record<string, string | number>) => string;
    // The port, not the raw client: shared/api is off limits outside the
    // adapters, and this keeps the rail testable with a fake.
    viewerPort?: ViewerPort;
  };

  let { book, t, viewerPort: viewerPortProp }: Props = $props();
  const viewerPort: ViewerPort = $derived(viewerPortProp ?? new TauriViewerAdapter());

  let chapter = $state<string | null>(null);

  // The reader records the chapter while the book is open; a book not opened
  // since (or read on another device) has none recorded, so the rail works it
  // out from the saved position and the book's outline, once, and records it.
  // Only the book on display is resolved: the hero shows one at a time.
  $effect(() => {
    const id = book.id;
    const stored = getLastChapter(id);
    chapter = stored;
    if (stored) return;
    let cancelled = false;
    void resolveLastChapter(book, {
      getProgress: (bookId: string): Promise<ReadingProgressDto | null> =>
        viewerPort.getProgress(bookId),
      getToc: async (bookId, filePath) =>
        (
          await invoke<{ toc: Array<{ index?: number; label?: string | null }> }>('parse_epub', {
            filePath,
            bookId,
          })
        ).toc ?? [],
    }).then((title) => {
      if (cancelled || !title) return;
      saveLastChapter(id, title);
      chapter = title;
    });
    return () => {
      cancelled = true;
    };
  });

  const stats = $derived(buildContinueStats(book, chapter, new Date(), t));
</script>

<!-- The hero's right rail: what is left and when the book was last opened.
  Open rows under a left hairline, no boxes; hidden on narrow windows, where
  the book itself needs the width. -->
{#if stats.length > 0}
  <dl
    class="m-0 hidden w-56 shrink-0 flex-col justify-end gap-5 border-l border-(--color-edge) pt-14 pb-1 pl-8 lg:flex"
    data-testid="continue-stats"
  >
    {#each stats as stat (stat.key)}
      <div class="flex flex-col gap-1">
        <dt class="text-xs font-semibold tracking-[0.08em] text-(--color-text-muted) uppercase">
          {stat.label}
        </dt>
        <dd
          class="m-0 line-clamp-2 text-xl leading-snug font-semibold tracking-tight wrap-break-word text-(--color-primary)"
        >
          {stat.value}
        </dd>
      </div>
    {/each}
  </dl>
{/if}
