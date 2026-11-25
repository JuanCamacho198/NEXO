<script lang="ts">
  import { tick } from 'svelte';
  import { scale } from 'svelte/transition';
  import { cubicOut } from 'svelte/easing';
  import Check from 'lucide-svelte/icons/check';
  import Plus from 'lucide-svelte/icons/plus';
  import Search from 'lucide-svelte/icons/search';
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

  const POPOVER_WIDTH = 256;

  let newTagName = $state('');
  let inputEl = $state<HTMLInputElement | null>(null);

  const normalizedAssigned = $derived(new Set(assignedTagIds));
  const isAssigned = (tagId: string): boolean => normalizedAssigned.has(tagId);

  function normalizeName(name: string): string {
    return name.trim().toLowerCase();
  }

  // One field does both jobs: typing filters the list, and when nothing is
  // called exactly that, the first row offers to create it.
  const query = $derived(normalizeName(newTagName));
  const visibleTags = $derived(
    query ? allTags.filter((tag) => normalizeName(tag.name).includes(query)) : allTags,
  );
  const exactMatch = $derived(
    query ? allTags.find((tag) => normalizeName(tag.name) === query) : undefined,
  );
  const offerCreate = $derived(Boolean(query) && !exactMatch);

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
        inputEl?.focus();
      });
    }
  });

  const position = $derived.by(() => {
    if (!anchor) return { left: 0, top: 0 };
    const rect = anchor.getBoundingClientRect();
    const centred = (rect.left + rect.right) / 2 - POPOVER_WIDTH / 2;
    return {
      left: Math.max(8, Math.min(centred, window.innerWidth - POPOVER_WIDTH - 8)),
      top: rect.bottom + 10,
    };
  });
</script>

{#if open && anchor}
  <div
    class="fixed z-[110] flex flex-col overflow-hidden rounded-2xl border border-(--color-highlight-menu-border) bg-(--color-highlight-menu-bg) text-(--color-text-inverse) shadow-[0_18px_40px_-16px_rgba(0,0,0,0.6)]"
    data-floating-menu="true"
    style="left: {position.left}px; top: {position.top}px; width: {POPOVER_WIDTH}px;"
    role="dialog"
    aria-label={t('highlight.tagPopoverAriaLabel')}
    tabindex="-1"
    onclick={(e) => e.stopPropagation()}
    onwheel={(e) => e.stopPropagation()}
    onkeydown={handleKeydown}
    in:scale={{ duration: 140, start: 0.95, easing: cubicOut }}
  >
    <div class="border-b border-white/10 p-2">
      <!-- A div, not a <label>: the global `label` rule makes labels blocks with
           margins. `search-field` drops the global focus outline so the field
           shows one ring (its border), not two. -->
      <div
        class="search-field flex items-center gap-2 rounded-full border border-white/10 bg-white/5 px-3 transition-colors focus-within:border-(--color-brand)"
      >
        <Search size={14} strokeWidth={2} class="shrink-0 text-white/50" aria-hidden="true" />
        <input
          type="text"
          bind:this={inputEl}
          bind:value={newTagName}
          maxlength="50"
          placeholder={t('highlight.newTagPlaceholder')}
          aria-label={t('highlight.newTagPlaceholder')}
          class="min-w-0 flex-1 rounded-none! border-0! bg-transparent! px-0 py-2 text-sm text-(--color-text-inverse) placeholder-white/45 focus:outline-none"
        />
      </div>
    </div>

    <div class="max-h-56 overflow-y-auto p-1.5" role="group" aria-label={t('highlight.tags')}>
      {#if offerCreate}
        <button
          type="button"
          class="flex w-full cursor-pointer items-center gap-2.5 rounded-xl px-2.5 py-2 text-left text-sm font-medium text-(--color-brand-text) transition-colors hover:bg-(--color-brand-soft)"
          onclick={handleCreate}
        >
          <span
            class="flex size-5 shrink-0 items-center justify-center rounded-full bg-(--color-brand-soft)"
            aria-hidden="true"
          >
            <Plus size={13} strokeWidth={2.5} />
          </span>
          <span class="truncate">{t('highlight.createTagNamed', { name: newTagName.trim() })}</span>
        </button>
      {/if}

      {#if allTags.length === 0 && !offerCreate}
        <p class="px-2.5 py-3 text-xs leading-relaxed text-(--color-text-auxiliary)">
          {t('highlight.noTagsYet')}
        </p>
      {:else}
        {#each visibleTags as tag (tag.id)}
          {@const assigned = isAssigned(tag.id)}
          <button
            type="button"
            class="flex w-full cursor-pointer items-center gap-2.5 rounded-xl px-2.5 py-2 text-left text-sm transition-colors hover:bg-white/8 {assigned
              ? 'text-(--color-text-inverse)'
              : 'text-white/75'}"
            onclick={() => onToggle(tag.id)}
            role="menuitemcheckbox"
            aria-checked={assigned}
          >
            <span
              class="flex size-5 shrink-0 items-center justify-center rounded-md border transition-colors {assigned
                ? 'border-(--color-brand) bg-(--color-brand) text-(--color-brand-on)'
                : 'border-white/25'}"
              aria-hidden="true"
            >
              {#if assigned}
                <Check size={13} strokeWidth={3} />
              {/if}
            </span>
            <span
              class="size-2 shrink-0 rounded-full"
              style="background-color: {tag.color ?? 'rgba(255,255,255,0.35)'};"
              aria-hidden="true"
            ></span>
            <span class="min-w-0 flex-1 truncate">{tag.name}</span>
          </button>
        {/each}
      {/if}
    </div>
  </div>
{/if}
