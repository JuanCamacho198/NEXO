<script lang="ts">
  import type { LibraryBookDto } from '$lib/shared/types';
  import type { MessageKey } from '$lib/shared/i18n';
  import { buildContinueStats } from '../continueReadingStats';
  import { getLastChapter } from '$lib/shared/stores/lastChapterStore';

  type Props = {
    book: LibraryBookDto;
    t: (key: MessageKey, params?: Record<string, string | number>) => string;
  };

  let { book, t }: Props = $props();

  const stats = $derived(buildContinueStats(book, getLastChapter(book.id), new Date(), t));
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
