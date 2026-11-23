<script lang="ts">
  import { SafeCover } from '$lib/features/library';

  type Props = { coverPath: string | null };

  let { coverPath }: Props = $props();
</script>

<!-- The book's own colour behind the hero: its cover, enlarged, blurred and
  faded out toward the text so every book tints the card differently without
  ever sitting under a word. A book without a cover gets the ember glow. -->
<div
  class="pointer-events-none absolute inset-y-0 right-0 -z-10 w-1/2 overflow-hidden [mask-image:linear-gradient(to_left,black,transparent)]"
  aria-hidden="true"
>
  <SafeCover
    path={coverPath ?? ''}
    alt=""
    className="size-full scale-125 object-cover opacity-(--hero-cover-tint) blur-3xl"
  >
    {#snippet fallback()}
      <div
        class="size-full bg-radial-[at_100%_0%] from-(--color-brand-soft) to-transparent to-70%"
      ></div>
    {/snippet}
  </SafeCover>
</div>
