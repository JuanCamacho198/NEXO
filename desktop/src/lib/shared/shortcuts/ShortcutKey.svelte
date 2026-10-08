<script lang="ts">
  type Props = {
    label: string;
  };

  let { label }: Props = $props();

  // A single glyph (an arrow, a letter, a symbol) fills far less of the cap
  // than a word does at the same size, so it reads as a smaller key even
  // though every cap is the same box. Give the lone glyph a step up so all
  // caps carry the same optical weight.
  const glyphSize = $derived(label.length <= 1 ? 'text-lg' : 'text-xs');
</script>

<!--
  Honest keycap: a light-catching top face, a visible bottom edge with a small
  offset and a soft blur, and a faint top edge line. Depth is declared once as
  the box-shadow (no border) so it is not a ghost card. The `active:` state
  compresses the bottom edge while the cap is pressed, so the metaphor holds
  whenever a cap is used on an interactive row.
-->
<kbd
  class="inline-flex h-8 min-w-8 select-none items-center justify-center rounded-md bg-(--color-elevated) px-2 font-mono {glyphSize} font-semibold leading-none text-(--color-primary) shadow-[0_2px_2px_-1px_var(--color-keycap-edge),inset_0_1px_0_0_var(--color-keycap-highlight)] transition-[transform,box-shadow] duration-150 active:translate-y-px active:shadow-[0_1px_1px_-1px_var(--color-keycap-edge),inset_0_1px_0_0_var(--color-keycap-highlight)]"
  >{label}</kbd
>
