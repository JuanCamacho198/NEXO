<script lang="ts">
  import type { Snippet } from 'svelte';
  import type { HTMLAttributes } from 'svelte/elements';

  // Global HTML attributes are accepted and forwarded to the root <section>,
  // so a consumer can pass `data-testid`, `aria-*`, `id` and the like without
  // the atom inventing a prop for each.
  type Props = Omit<HTMLAttributes<HTMLElement>, 'title' | 'class'> & {
    title?: string;
    subtitle?: string;
    hint?: string;
    variant?: 'default' | 'surface';
    padding?: 'none' | 'sm' | 'md' | 'lg';
    actions?: Snippet;
    children?: Snippet;
    class?: string;
  };

  let {
    title,
    subtitle,
    hint,
    variant = 'default',
    padding = 'md',
    actions,
    children,
    class: className = '',
    ...rest
  }: Props = $props();

  const paddingClasses = {
    none: '',
    sm: 'p-3',
    md: 'p-4',
    lg: 'p-6',
  };

  const variantClasses = {
    default: 'bg-(--color-surface)',
    surface: 'bg-(--color-surface-dim)',
  };
</script>

<section
  {...rest}
  class={`shrink-0 overflow-hidden rounded-(--radius-xl) border border-(--color-border) ${variantClasses[variant]} shadow-(--shadow-soft) backdrop-blur-xl ${className}`}
>
  {#if title || subtitle || hint || actions}
    <div class="border-b border-(--color-border)/80 px-5 py-4">
      <div class="flex items-center justify-between gap-3">
        <div class="min-w-0">
          {#if title}
            <h2 class="text-xl font-semibold tracking-tight text-(--color-primary)">{title}</h2>
          {/if}
          {#if subtitle}
            <p class="mt-1 text-sm text-(--color-secondary)">{subtitle}</p>
          {/if}
          {#if hint}
            <span
              class="mt-1 inline-block text-xs uppercase tracking-[0.18em] text-(--color-text-muted)"
              >{hint}</span
            >
          {/if}
        </div>
        {#if actions}
          <div class="flex shrink-0 items-center gap-2">
            {@render actions()}
          </div>
        {/if}
      </div>
    </div>
  {/if}

  <div class={paddingClasses[padding]}>
    {#if children}
      {@render children()}
    {/if}
  </div>
</section>
