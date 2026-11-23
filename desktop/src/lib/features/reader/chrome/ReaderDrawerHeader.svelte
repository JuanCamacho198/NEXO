<script lang="ts">
  import type { Snippet } from 'svelte';
  import X from 'lucide-svelte/icons/x';
  import Button from '$lib/shared/ui/forms/Button.svelte';
  import { headingMarker } from '$lib/shared/ui/emberStyles';

  type Props = {
    title: string;
    closeLabel: string;
    onClose: () => void;
    /** Controls placed before the close button (e.g. "add bookmark"). */
    actions?: Snippet;
    /** Transient overlays anchored to the header (e.g. the "saved" toast). */
    children?: Snippet;
  };

  let { title, closeLabel, onClose, actions, children }: Props = $props();
</script>

<!-- The close control lives here, in the panel, next to the title: the top
  bar's X sits outside the panel and the reader has to look away to find it. -->
<header
  class="sticky top-0 z-10 flex shrink-0 items-center gap-3 border-b border-(--color-edge) bg-(--color-reader-chrome) py-3.5 pr-3 pl-5"
>
  {@render children?.()}
  <span class="h-5 {headingMarker}" aria-hidden="true"></span>
  <h2 class="min-w-0 flex-1 truncate text-base font-semibold text-(--color-primary)">{title}</h2>
  {@render actions?.()}
  <Button
    variant="ghost"
    size="sm"
    onclick={onClose}
    class="size-9 shrink-0 p-0 text-(--color-text-muted) hover:text-(--color-primary)"
    aria-label={closeLabel}
    title={closeLabel}
  >
    <X size={16} strokeWidth={2} aria-hidden="true" />
  </Button>
</header>
