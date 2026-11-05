<script lang="ts">
  import { tick } from 'svelte';
  import type { MessageKey } from '$lib/shared/i18n';
  import type { TagDto } from '$lib/shared/types/book';

  type Props = {
    open: boolean;
    anchor: HTMLElement | null;
    assignedTagIds: string[];
    allTags: TagDto[];
    onCreate: (name: string, color?: string) => void;
    onToggle: (tagId: string) => void;
    onClose: () => void;
    t: (key: MessageKey, params?: Record<string, string | number>) => string;
  };

  let { open, anchor, assignedTagIds, allTags, onCreate, onToggle, onClose, t }: Props = $props();

  let newTagName = $state('');
  let popoverEl = $state<HTMLDivElement | null>(null);

  const normalizedAssigned = $derived(new Set(assignedTagIds));
  const isAssigned = (tagId: string): boolean => normalizedAssigned.has(tagId);

  function normalizeName(name: string): string {
    return name.trim().toLowerCase();
  }

  function handleCreate(): void {
    const name = newTagName.trim();
    if (!name) return;

    const normalized = normalizeName(name);
    const existing = allTags.find((tag) => normalizeName(tag.name) === normalized);
    if (existing) {
      if (!isAssigned(existing.id)) {
        onToggle(existing.id);
      }
    } else {
      onCreate(name);
    }
    newTagName = '';
  }

  function handleKeydown(event: KeyboardEvent): void {
    if (event.key === 'Escape') {
      event.preventDefault();
      onClose();
    } else if (event.key === 'Enter' && newTagName.trim()) {
      event.preventDefault();
      handleCreate();
    }
  }

  $effect(() => {
    if (open) {
      newTagName = '';
      void tick().then(() => {
        popoverEl?.focus();
      });
    }
  });
</script>

{#if open && anchor}
  <div
    bind:this={popoverEl}
    class="fixed z-[110] w-56 rounded-xl border border-(--color-highlight-menu-border) bg-(--color-highlight-menu-bg) p-2 shadow-xl"
    data-floating-menu="true"
    style="left: {Math.max(
      8,
      Math.min(anchor.getBoundingClientRect().left, window.innerWidth - 240),
    )}px; top: {anchor.getBoundingClientRect().bottom + 8}px;"
    role="dialog"
    aria-label={t('highlight.tagPopoverAriaLabel')}
    tabindex="-1"
    onclick={(e) => e.stopPropagation()}
    onkeydown={handleKeydown}
  >
    <div class="flex items-center gap-1 border-b border-(--color-highlight-menu-border) pb-2">
      <input
        type="text"
        bind:value={newTagName}
        maxlength="50"
        placeholder={t('highlight.newTagPlaceholder')}
        class="flex-1 rounded-md border border-(--color-highlight-menu-border) bg-(--color-bg-deep) px-2 py-1 text-sm text-(--color-text-inverse) placeholder-(--color-text-auxiliary) focus:outline-none focus:ring-1 focus:ring-(--color-accent-sky)"
      />
      <button
        type="button"
        class="rounded-md bg-(--color-accent-sky) px-2 py-1 text-xs text-(--color-bg-deep) hover:bg-(--color-accent-blue) disabled:opacity-50"
        disabled={!newTagName.trim()}
        onclick={handleCreate}
      >
        {t('highlight.createTag')}
      </button>
    </div>

    <div class="mt-2 max-h-40 overflow-y-auto" role="group" aria-label={t('highlight.tags')}>
      {#if allTags.length === 0}
        <p class="px-1 py-2 text-xs text-(--color-text-auxiliary)">
          {t('highlight.noTagsYet')}
        </p>
      {:else}
        {#each allTags as tag (tag.id)}
          <button
            type="button"
            class="flex w-full cursor-pointer items-center gap-2 rounded-lg px-2 py-1.5 text-left text-sm text-(--color-text-inverse) hover:bg-white/10"
            onclick={() => onToggle(tag.id)}
            role="menuitemcheckbox"
            aria-checked={isAssigned(tag.id)}
          >
            <span
              class="h-3 w-3 rounded-full border border-(--color-highlight-menu-border)"
              class:bg-(--color-accent-sky)={isAssigned(tag.id)}
              style={tag.color ? `background-color: ${tag.color};` : undefined}
            ></span>
            {tag.name}
          </button>
        {/each}
      {/if}
    </div>
  </div>
{/if}
