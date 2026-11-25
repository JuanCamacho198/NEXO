<script lang="ts">
  import ImagePlus from 'lucide-svelte/icons/image-plus';
  import Trash2 from 'lucide-svelte/icons/trash-2';
  import Undo2 from 'lucide-svelte/icons/undo-2';
  import Button from '$lib/shared/ui/forms/Button.svelte';
  import SafeCover from './SafeCover.svelte';
  import type { MessageKey } from '$lib/shared/i18n';

  type Props = {
    previewPath: string | null;
    title: string;
    pending: boolean;
    disabled: boolean;
    canEdit: boolean;
    onPick: () => void;
    onRemove: () => void;
    onRevert: () => void;
    t: (key: MessageKey, params?: Record<string, string | number>) => string;
  };

  let { previewPath, title, pending, disabled, canEdit, onPick, onRemove, onRevert, t }: Props =
    $props();

  const initials = $derived(
    title
      .split(/\s+/)
      .filter(Boolean)
      .slice(0, 2)
      .map((w) => w[0]?.toUpperCase() ?? '')
      .join(''),
  );
</script>

{#snippet placeholder()}
  <div
    class="flex h-full w-full flex-col items-center justify-center gap-2 bg-linear-to-br from-(--color-home-card) to-(--color-elevated) text-(--color-text-muted)"
  >
    <span class="font-serif text-3xl font-semibold text-(--color-primary)/70">{initials}</span>
    <span class="text-xs">{t('library.editMetadata.noCover')}</span>
  </div>
{/snippet}

<div class="flex flex-col items-center gap-3 sm:items-stretch">
  <div
    class="relative h-60 w-40 overflow-hidden rounded-xl border border-(--color-edge) shadow-[0_16px_32px_-18px_rgba(0,0,0,0.7)]"
  >
    {#if previewPath}
      <SafeCover
        path={previewPath}
        alt={title}
        className="h-full w-full object-cover"
        fallback={placeholder}
      />
    {:else}
      {@render placeholder()}
    {/if}
    {#if pending}
      <span
        class="absolute top-2 left-2 rounded-full bg-(--color-brand-soft) px-2 py-0.5 text-micro font-semibold text-(--color-brand-text) backdrop-blur-sm"
      >
        {t('library.editMetadata.coverPending')}
      </span>
    {/if}
  </div>

  {#if canEdit}
    <div class="flex w-40 flex-col gap-1.5">
      <Button variant="secondary" size="sm" fullWidth {disabled} onclick={onPick}>
        {#snippet leadingIcon()}<ImagePlus class="h-3.5 w-3.5" aria-hidden="true" />{/snippet}
        {t('library.editMetadata.changeCover')}
      </Button>
      {#if pending}
        <Button variant="ghost" size="sm" fullWidth {disabled} onclick={onRevert}>
          {#snippet leadingIcon()}<Undo2 class="h-3.5 w-3.5" aria-hidden="true" />{/snippet}
          {t('library.editMetadata.revertCover')}
        </Button>
      {:else if previewPath}
        <Button variant="ghost" size="sm" fullWidth {disabled} onclick={onRemove}>
          {#snippet leadingIcon()}<Trash2 class="h-3.5 w-3.5" aria-hidden="true" />{/snippet}
          {t('library.editMetadata.removeCover')}
        </Button>
      {/if}
    </div>
  {/if}
</div>
