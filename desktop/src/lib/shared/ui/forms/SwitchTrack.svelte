<script lang="ts">
  // The visual half of a switch: the control that owns `role="switch"` and
  // `aria-checked` renders this inside itself. Same track as the sidebar theme
  // toggle, so every switch in the app reads as one control.
  type Props = { checked: boolean };

  let { checked }: Props = $props();
</script>

<span
  class="relative h-6 w-11 shrink-0 rounded-full border transition-colors duration-(--duration-normal) ease-(--ease-smooth) {checked
    ? 'border-transparent bg-(--color-brand)'
    : 'border-(--color-edge-strong) bg-(--color-surface-hover)'}"
  aria-hidden="true"
>
  <span
    class="absolute top-1/2 left-0.5 size-4.5 -translate-y-1/2 rounded-full bg-white shadow-[0_1px_4px_rgba(0,0,0,0.35)] transition-transform duration-(--duration-normal) ease-(--ease-smooth) {checked
      ? 'translate-x-5'
      : ''}"
  ></span>
</span>
