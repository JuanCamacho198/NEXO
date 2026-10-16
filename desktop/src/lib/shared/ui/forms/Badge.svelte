<script lang="ts">
  /**
   * Small status label on a filled surface.
   *
   * The ink is `--color-accent-on`, not literal `white`: the accent plane is
   * dark in the dark theme and light in the light theme, so a hardcoded white
   * label fails on one of the two (measured 1.33:1 on the lightest avatar
   * fill). `--color-accent-on` is the token declared for exactly this pairing.
   *
   * `color` stays available for a caller that needs a fill outside the token
   * set; it is expected to be a value that keeps `--color-accent-on` legible.
   */
  type Props = {
    text?: string;
    color?: string;
    class?: string;
  };

  let { text = '', color, class: className = '' }: Props = $props();

  const bgColor = $derived(color ?? 'var(--color-accent)');
</script>

<span
  class="inline-flex items-center rounded-full px-2.5 py-0.5 text-xs font-medium text-(--color-accent-on) {className}"
  style="background-color: {bgColor};"
>
  {text}
</span>
