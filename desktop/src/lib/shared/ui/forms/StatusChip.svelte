<script lang="ts">
  /**
   * Compact status chip for a navigation entry or list row.
   *
   * Distinct from `Badge`: that atom is a filled, high-contrast label for
   * standalone emphasis, while this one is a quiet marker attached to another
   * element's label, sized to sit in a dense row without shifting its baseline.
   * It deliberately uses the soft/accent pair rather than a filled plane so it
   * reads as metadata, not as a call to action.
   *
   * The `dot` variant is the same signal when no room exists for the word: a
   * small filled circle meant to be positioned against an icon. Callers own the
   * positioning class, since the anchor differs per host.
   */
  export type StatusChipTone = 'neutral' | 'accent' | 'danger';

  type Props = {
    text?: string;
    tone?: StatusChipTone;
    /** Renders a dot instead of the text label. */
    dot?: boolean;
    class?: string;
  };

  let { text = '', tone = 'accent', dot = false, class: className = '' }: Props = $props();

  const toneClass: Record<StatusChipTone, string> = {
    neutral: 'bg-(--color-surface-hover) text-(--color-text-muted)',
    accent: 'bg-(--color-accent-soft) text-(--color-accent-blue)',
    danger: 'bg-(--color-error-soft) text-(--color-error)',
  };
</script>

{#if dot}
  <span
    class="size-1.5 rounded-full {tone === 'danger'
      ? 'bg-(--color-error)'
      : tone === 'neutral'
        ? 'bg-(--color-text-muted)'
        : 'bg-(--color-accent-blue)'} {className}"
    aria-hidden="true"
  ></span>
{:else}
  <span
    class="inline-flex shrink-0 items-center rounded-full px-1.5 py-0.5 text-micro font-medium whitespace-nowrap {toneClass[
      tone
    ]} {className}"
  >
    {text}
  </span>
{/if}
