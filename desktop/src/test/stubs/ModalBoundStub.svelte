<script lang="ts">
  import type { Snippet } from 'svelte';
  import Modal from '$lib/shared/ui/layout/Modal.svelte';

  type Props = {
    initialOpen?: boolean;
    title?: string;
    description?: string;
    size?: 'sm' | 'md' | 'lg' | 'xl';
    noCloseButton?: boolean;
    class?: string;
    withFooter?: boolean;
    footerText?: string;
    onopen?: (open: boolean) => void;
    onOpenChange?: (open: boolean) => void;
    children?: Snippet;
  };

  let {
    initialOpen = false,
    title = 'Test Modal',
    description,
    size,
    noCloseButton = false,
    class: className,
    withFooter = false,
    footerText = 'Footer action',
    onopen,
    onOpenChange,
    children,
  }: Props = $props();

  // `initialOpen` is a seed, not a two-way prop: only the first value counts.
  // svelte-ignore state_referenced_locally
  let open = $state(initialOpen);

  $effect(() => {
    onopen?.(open);
  });
</script>

{#snippet footerContent()}
  <span>{footerText}</span>
{/snippet}

<!-- Mirrors the Modal call sites: `bind:open` plus the optional `footer`
     snippet (present at ShelfDetailModal, absent at ReadingStatisticsView and
     BulkImportModal). Every write of the bound prop is reported back so a test
     can count it. -->
<Modal
  bind:open
  {title}
  {description}
  {size}
  {noCloseButton}
  class={className}
  footer={withFooter ? footerContent : undefined}
  {onOpenChange}
>
  <p>body content</p>
  {#if children}
    {@render children()}
  {/if}
</Modal>
