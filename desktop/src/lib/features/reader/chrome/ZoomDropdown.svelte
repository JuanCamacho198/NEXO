<script lang="ts">
  import { clampZoomPercent, ZOOM_OPTIONS } from '$lib/features/reader/viewer-pdf/pdfNavigation';

  type Props = {
    value: number;
    onSelect: (value: number) => void;
  };

  let { value, onSelect }: Props = $props();

  let open = $state(false);
  let rootEl: HTMLDivElement | null = $state(null);

  const clampedValue = $derived(clampZoomPercent(value));
  const options = ZOOM_OPTIONS;

  function selectOption(v: number): void {
    const clamped = clampZoomPercent(v);
    open = false;
    onSelect(clamped);
  }

  function handleOutside(e: MouseEvent): void {
    if (!rootEl) return;
    if (!rootEl.contains(e.target as Node)) open = false;
  }

  $effect(() => {
    if (open) {
      window.addEventListener('click', handleOutside);
      return () => window.removeEventListener('click', handleOutside);
    }
  });
</script>

<div class="relative inline-block" bind:this={rootEl}>
  <button
    type="button"
    onclick={() => (open = !open)}
    class="inline-flex items-center gap-1 rounded-full border border-(--color-border) bg-(--color-surface) px-3 py-1.5 text-xs font-medium text-(--color-primary) hover:bg-[color-mix(in_srgb,var(--color-primary)_8%,var(--color-surface))] cursor-pointer min-w-18 min-h-11 justify-center"
    aria-haspopup="listbox"
    aria-expanded={open}
    aria-label="Zoom {clampedValue}%"
    data-testid="zoom-dropdown-trigger"
  >
    <span>{clampedValue}%</span>
    <svg
      width="12"
      height="12"
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      stroke-width="2"
      aria-hidden="true"><path d="M6 9l6 6 6-6" /></svg
    >
  </button>

  {#if open}
    <ul
      class="absolute right-0 z-50 mt-2 min-w-28 rounded-xl border border-(--color-border) bg-(--color-surface) py-1 shadow-lg"
      role="listbox"
      data-testid="zoom-dropdown-list"
    >
      {#each options as opt (opt)}
        {@const selected = opt === clampedValue}
        {@const isDefault = opt === 100}
        <li role="presentation">
          <button
            type="button"
            role="option"
            aria-selected={selected}
            onclick={() => selectOption(opt)}
            class="flex w-full items-center justify-between px-3 py-1.5 text-xs hover:bg-(--color-surface-strong) cursor-pointer {selected
              ? 'font-semibold text-(--color-primary)'
              : 'text-(--color-text-muted)'}"
            data-testid="zoom-option-{opt}"
          >
            <span class="flex items-center gap-1.5">
              {#if selected}
                <svg
                  width="12"
                  height="12"
                  viewBox="0 0 24 24"
                  fill="none"
                  stroke="currentColor"
                  stroke-width="2.5"
                  aria-hidden="true"><path d="M5 13l4 4L19 7" /></svg
                >
              {:else}
                <span class="w-3 inline-block"></span>
              {/if}
              {opt}%
              {#if isDefault && selected}
                <span class="sr-only">(default)</span>
              {/if}
            </span>
            {#if selected}
              <span class="text-micro opacity-60">✓</span>
            {/if}
          </button>
        </li>
      {/each}
    </ul>
  {/if}
</div>
