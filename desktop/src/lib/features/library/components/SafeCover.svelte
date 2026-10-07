<script lang="ts">
  import { readFile } from '@tauri-apps/plugin-fs';
  import { onDestroy } from 'svelte';
  import { logger } from '$lib/shared/logger/Logger';

  let {
    path,
    alt = 'Cover',
    className = '',
    fallback,
  }: {
    path: string;
    alt?: string;
    className?: string;
    fallback?: import('svelte').Snippet;
  } = $props();

  let objectUrl = $state<string | null>(null);
  let error = $state(false);

  /** Remote cover references (signed/public URL) bypass Tauri fs and render directly. */
  const isRemote = $derived(/^https?:\/\//i.test(path.trim()));

  const releaseObjectUrl = (): void => {
    if (!objectUrl) {
      return;
    }

    URL.revokeObjectURL(objectUrl);
    objectUrl = null;
  };

  async function loadThumbnail(): Promise<void> {
    if (!path || path.trim().length === 0) {
      releaseObjectUrl();
      error = true;
      return;
    }

    // Remote URLs load through the browser HTTP cache; failures fall back below.
    if (isRemote) {
      releaseObjectUrl();
      error = false;
      return;
    }

    try {
      const bytes = await readFile(path);
      if (!bytes || bytes.length === 0) {
        releaseObjectUrl();
        error = true;
        return;
      }

      const blob = new Blob([bytes], { type: 'image/png' });

      releaseObjectUrl();

      objectUrl = URL.createObjectURL(blob);
      error = false;
    } catch (e) {
      logger.error(
        '[SafeCover] Failed to read cover file:',
        { path, error: e instanceof Error ? e.message : String(e) },
        'library',
      );
      releaseObjectUrl();
      error = true;
    }
  }

  $effect(() => {
    loadThumbnail();
  });

  onDestroy(() => {
    releaseObjectUrl();
  });
</script>

{#if isRemote && !error}
  <img
    src={path}
    {alt}
    class={className}
    loading="lazy"
    onerror={() => {
      error = true;
    }}
  />
{:else if objectUrl && !error}
  <img
    src={objectUrl}
    {alt}
    class={className}
    onerror={() => {
      releaseObjectUrl();
      error = true;
    }}
  />
{:else if fallback}
  {@render fallback()}
{:else}
  <div
    class={`${className} flex items-center justify-center bg-gradient-to-br from-[#f2ede0] to-[#e4d5ba] text-[#5d4a33] border border-(--color-border,#d6d3ce) font-bold`}
    role="img"
    aria-label={alt}
  >
    <span>{alt?.trim().slice(0, 1).toUpperCase() || 'B'}</span>
  </div>
{/if}
