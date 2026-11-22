<script lang="ts">
  import { SafeCover } from '$lib/features/library';
  import { panelClass } from '$lib/shared/ui/emberStyles';
  import type { LibraryBookDto } from '$lib/shared/types';
  import type { MessageKey } from '$lib/shared/i18n';

  type Props = {
    books: LibraryBookDto[];
    onOpen: (book: LibraryBookDto) => void;
    t: (key: MessageKey, params?: Record<string, string | number>) => string;
  };

  let { books, onOpen, t }: Props = $props();

  const top = $derived(Math.max(books[0]?.minutesRead ?? 0, 1));
</script>

<article class="{panelClass} p-5">
  <h2 class="text-lg font-bold tracking-[-0.01em] text-(--color-primary)">
    {t('stats.mostReadBooks')}
  </h2>
  <p class="text-sm text-(--color-text-muted)">{t('stats.mostReadBooksDesc')}</p>

  <ol class="m-0 mt-4 list-none space-y-1 p-0">
    {#each books as book, index (book.id)}
      <li>
        <button
          type="button"
          class="flex w-full cursor-pointer items-center gap-4 rounded-(--radius-md) p-2.5 text-left transition-colors duration-150 hover:bg-(--color-surface-subtle) focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-(--color-brand)"
          onclick={() => onOpen(book)}
        >
          <span
            class="w-5 shrink-0 text-center text-lg font-bold tabular-nums {index === 0
              ? 'text-(--color-brand-text)'
              : 'text-(--color-text-muted)'}">{index + 1}</span
          >
          <div
            class="h-16 w-11 shrink-0 overflow-hidden rounded-(--radius-sm) border border-(--color-edge) bg-(--color-surface-subtle) shadow-[0_8px_18px_-10px_rgba(0,0,0,0.7)]"
          >
            <SafeCover
              path={book.coverPath ?? ''}
              alt={t('stats.bookCover', { title: book.title })}
              className="h-full w-full object-cover"
            >
              {#snippet fallback()}
                <div
                  class="flex h-full w-full items-center justify-center bg-linear-to-b from-(--color-surface-subtle) to-(--color-horizon-fade) text-micro uppercase tracking-[0.12em] text-(--color-text-muted)"
                >
                  {t('stats.bookPlaceholder')}
                </div>
              {/snippet}
            </SafeCover>
          </div>
          <div class="min-w-0 flex-1">
            <div class="flex items-baseline justify-between gap-3">
              <p class="truncate text-sm font-semibold text-(--color-primary)">{book.title}</p>
              <span class="shrink-0 text-sm font-semibold tabular-nums text-(--color-secondary)"
                >{book.minutesRead} min</span
              >
            </div>
            <div class="mt-2 h-1.5 overflow-hidden rounded-full bg-(--color-edge)">
              <div
                class="h-full rounded-full bg-linear-to-r from-(--color-brand-start) to-(--color-brand)"
                style={`width: ${Math.max(6, Math.round((book.minutesRead / top) * 100))}%;`}
              ></div>
            </div>
          </div>
        </button>
      </li>
    {/each}
  </ol>
</article>
