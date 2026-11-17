<script lang="ts">
  import { onMount } from 'svelte';
  import { createDebugIpcStats } from '$lib/shared/debug/useDebugIpcStats.svelte';

  // Lives beside the test, not in src/test/fixtures/, because that whole
  // directory is gitignored (binary book/pdf fixtures) and an untracked
  // harness breaks svelte-check on CI with no local symptom.
  let { shouldRun, onReady }: { shouldRun: boolean; onReady: (stats: unknown) => void } = $props();

  // The gate is read through a closure, never as a captured value: the whole
  // point of the hook's `enabled` callback is that it is evaluated per effect
  // run, exactly like the debug flag it wraps in the app.
  const enabled = (): boolean => shouldRun;

  onMount(() => {
    onReady(createDebugIpcStats({ enabled }));
  });
</script>
