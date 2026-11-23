<script lang="ts">
  import type { Snippet } from 'svelte';
  import type { HTMLAttributes } from 'svelte/elements';
  import { sectionClass } from '../emberStyles';

  // Global HTML attributes are accepted and forwarded to the root <section>,
  // so a consumer can pass `data-testid`, `aria-*`, `id` and the like without
  // the atom inventing a prop for each.
  type Props = Omit<HTMLAttributes<HTMLElement>, 'title' | 'class'> & {
    title?: string;
    subtitle?: string;
    hint?: string;
    /** `none` drops the gap under the header; the body never has side padding. */
    padding?: 'none' | 'sm' | 'md' | 'lg';
    /**
     * Settings-row layout: title and subtitle on the left, the children (one
     * or a few controls) on the right, wrapping under the text when narrow.
     */
    inline?: boolean;
    actions?: Snippet;
    children?: Snippet;
    class?: string;
  };

  let {
    title,
    subtitle,
    hint,
    padding = 'md',
    inline = false,
    actions,
    children,
    class: className = '',
    ...rest
  }: Props = $props();

  const hasHeader = $derived(Boolean(title || subtitle || hint || actions));

  // NEXO Ember (DESIGN.md): a panel is an open section under a hairline, not a
  // card. Stacked panels read as one divided list.
  const bodyGap = {
    none: '',
    sm: 'mt-3',
    md: 'mt-4',
    lg: 'mt-5',
  };
</script>

<section {...rest} class={`shrink-0 ${sectionClass} ${className}`}>
  {#if inline}
    <div class="flex flex-wrap items-center justify-between gap-x-8 gap-y-3">
      <div class="min-w-0 flex-1 basis-72">
        {#if title}
          <h2 class="text-base font-semibold text-(--color-primary)">{title}</h2>
        {/if}
        {#if subtitle}
          <p class="mt-1 text-sm leading-relaxed text-(--color-text-muted)">{subtitle}</p>
        {/if}
      </div>
      {#if children}
        <div class="flex shrink-0 flex-wrap items-center gap-2">
          {@render children()}
        </div>
      {/if}
    </div>
  {:else}
    {#if hasHeader}
      <div class="flex items-center justify-between gap-3">
        <div class="min-w-0">
          {#if title}
            <h2 class="text-base font-semibold text-(--color-primary)">{title}</h2>
          {/if}
          {#if subtitle}
            <p class="mt-1 text-sm leading-relaxed text-(--color-text-muted)">{subtitle}</p>
          {/if}
          {#if hint}
            <span class="mt-1 inline-block text-xs text-(--color-text-muted)">{hint}</span>
          {/if}
        </div>
        {#if actions}
          <div class="flex shrink-0 items-center gap-2">
            {@render actions()}
          </div>
        {/if}
      </div>
    {/if}

    <div class={hasHeader ? bodyGap[padding] : ''}>
      {#if children}
        {@render children()}
      {/if}
    </div>
  {/if}
</section>
