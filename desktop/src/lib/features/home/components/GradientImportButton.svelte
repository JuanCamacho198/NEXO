<script lang="ts">
  import Plus from 'lucide-svelte/icons/plus';
  import type { Icon as LucideIcon } from 'lucide-svelte';

  type Props = {
    label: string;
    onclick: () => void;
    icon?: typeof LucideIcon;
    disabled?: boolean;
  };

  let { label, onclick, icon: ItemIcon = Plus, disabled = false }: Props = $props();
</script>

<!--
  Import is Home's secondary action: an ember label on a quiet pill, so the
  resume CTA keeps the only filled ember surface on the screen.
-->
<button
  type="button"
  {onclick}
  {disabled}
  class="inline-flex h-11 items-center justify-center gap-2 rounded-full border border-(--color-edge) bg-(--color-home-card) px-5 text-sm font-semibold text-(--color-brand-text) transition-colors duration-(--duration-fast) ease-(--ease-smooth) hover:border-(--color-brand)/50 hover:bg-(--color-brand-soft) focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-(--color-brand) active:scale-96 disabled:opacity-50"
>
  <ItemIcon size={18} strokeWidth={2.2} aria-hidden="true" />
  {label}
</button>
