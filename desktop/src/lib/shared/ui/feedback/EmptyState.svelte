<script lang="ts">
  import type { Snippet } from 'svelte';

  type Props = {
    icon?: string;
    title: string;
    description?: string;
    action?: Snippet;
    class?: string;
    /**
     * Heading level for the title. Defaults to `h2`: an empty state is the
     * primary content of its region, so it belongs directly under the page
     * `h1`. Pick a deeper level only when the state really is nested inside a
     * visible subsection heading.
     */
    titleTag?: 'h2' | 'h3' | 'h4';
  };

  let {
    icon,
    title,
    description,
    action,
    class: className = '',
    titleTag = 'h2',
  }: Props = $props();

  const iconPaths: Record<string, string> = {
    book: 'M12 6.253v13m0-13C10.832 5.477 9.246 5 7.5 5S4.168 5.477 3 6.253v13C4.168 18.477 5.754 18 7.5 18s3.332.477 4.5 1.253m0-13C13.168 5.477 14.754 5 16.5 5c1.747 0 3.332.477 4.5 1.253v13C19.832 18.477 18.247 18 16.5 18c-1.746 0-3.332.477-4.5 1.253',
    search: 'M21 21l-6-6m2-5a7 7 0 11-14 0 7 7 0 0114 0z',
    folder: 'M3 7v10a2 2 0 002 2h14a2 2 0 002-2V9a2 2 0 00-2-2h-6l-2-2H5a2 2 0 00-2 2z',
    error: 'M12 8v4m0 4h.01M21 12a9 9 0 11-18 0 9 9 0 0118 0z',
  };
</script>

<div class={`flex flex-col items-center justify-center py-12 text-center ${className}`}>
  {#if icon && iconPaths[icon]}
    <div
      class="mb-5 flex h-14 w-14 items-center justify-center rounded-full bg-gradient-to-br from-(--color-surface) to-(--color-border) ring-1 ring-inset ring-(--color-border)"
    >
      <svg
        class="h-6 w-6 text-(--color-text-muted)"
        fill="none"
        stroke="currentColor"
        viewBox="0 0 24 24"
      >
        <path
          stroke-linecap="round"
          stroke-linejoin="round"
          stroke-width="1.5"
          d={iconPaths[icon]}
        />
      </svg>
    </div>
  {/if}

  <svelte:element
    this={titleTag}
    class="text-lg font-semibold tracking-tight text-(--color-primary)">{title}</svelte:element
  >

  {#if description}
    <p class="mt-2 max-w-xs text-sm leading-relaxed text-(--color-text-muted)">{description}</p>
  {/if}

  {#if action}
    <div class="mt-8">
      {@render action()}
    </div>
  {/if}
</div>
