<script lang="ts">
  import type { Snippet } from 'svelte';
  import type { HTMLButtonAttributes } from 'svelte/elements';

  // A toggle chip that narrows a list (catalogue filters, genres, sources).
  // `selected` is exposed as `aria-pressed`, so the state is never colour-only.
  type Props = Omit<HTMLButtonAttributes, 'class' | 'children'> & {
    selected?: boolean;
    children?: Snippet;
    class?: string;
  };

  let { selected = false, children, class: className = '', ...rest }: Props = $props();
</script>

<button
  type="button"
  {...rest}
  aria-pressed={selected}
  class="h-8 shrink-0 rounded-full border px-3.5 text-sm font-medium whitespace-nowrap transition-[background-color,border-color,color] duration-(--duration-fast) ease-(--ease-smooth) focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-(--color-brand) focus-visible:ring-offset-2 focus-visible:ring-offset-(--color-background) {selected
    ? 'border-(--color-brand)/60 bg-(--color-brand-soft) text-(--color-primary)'
    : 'border-(--color-edge) bg-transparent text-(--color-text-muted) hover:border-(--color-edge-strong) hover:text-(--color-primary)'} {className}"
>
  {@render children?.()}
</button>
