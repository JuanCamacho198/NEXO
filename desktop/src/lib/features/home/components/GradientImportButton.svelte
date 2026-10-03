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
  Import is a secondary warm action on Home (HOME-04). It keeps the coral
  semantic role as a quiet outline button so the reading/resume CTA owns the
  primary color. The former gradient is gone; style comes from tokens only.
-->
<button
  type="button"
  {onclick}
  {disabled}
  class="inline-flex items-center justify-center gap-2 rounded-lg border border-(--color-import) bg-transparent px-3.5 py-2 text-sm font-medium text-(--color-import) transition-colors hover:bg-(--color-import-bg) focus-visible:ring-2 ring-(--color-import) disabled:opacity-50"
>
  <ItemIcon size={14} strokeWidth={1.8} />
  {label}
</button>
