<script lang="ts">
  import ChevronDown from 'lucide-svelte/icons/chevron-down';
  import Check from 'lucide-svelte/icons/check';
  import { clampZoomPercent, ZOOM_OPTIONS } from '$lib/features/reader/viewer-pdf/pdfNavigation';
  import { chromeIconButton } from './chromeControl';
  import type { MessageKey } from '$lib/shared/i18n';

  type Props = {
    value: number;
    onSelect: (value: number) => void;
    /** Required so no call site can silently announce the wrong unit: the PDF
     * path shows viewer scale, the EPUB path shows font size. */
    labelKind: 'zoom' | 'fontSize';
    t: (key: MessageKey, params?: Record<string, string | number>) => string;
  };

  let { value, onSelect, labelKind, t }: Props = $props();

  let open = $state(false);
  let rootEl: HTMLDivElement | null = $state(null);

  const clampedValue = $derived(clampZoomPercent(value));
  const accessibleName = $derived(
    labelKind === 'fontSize'
      ? t('reader.font_size_level', { level: clampedValue })
      : t('reader.zoomLevel', { level: clampedValue }),
  );
  const options = ZOOM_OPTIONS;

  const triggerClass = $derived(
    `${chromeIconButton('action')} gap-1.5 min-w-18 px-3 text-sm font-semibold tabular-nums ${open ? 'bg-(--color-surface-hover)' : ''}`,
  );

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
    class={triggerClass}
    aria-haspopup="listbox"
    aria-expanded={open}
    aria-label={accessibleName}
    data-testid="zoom-dropdown-trigger"
  >
    <span>{clampedValue}%</span>
    <ChevronDown
      size={12}
      strokeWidth={2}
      class="h-3 w-3 text-(--color-text-muted) transition-transform {open ? 'rotate-180' : ''}"
      aria-hidden="true"
    />
  </button>

  {#if open}
    <ul
      class="scrollbar-hidden absolute bottom-full right-0 z-50 mb-3 flex max-h-[min(18rem,60vh)] min-w-32 flex-col gap-0.5 overflow-y-auto overscroll-contain rounded-(--radius-lg) border border-(--color-edge) bg-(--color-reader-chrome) p-1.5 shadow-[0_18px_40px_-16px_rgba(0,0,0,0.55)]"
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
            class="flex w-full cursor-pointer items-center justify-between rounded-full px-3 py-1.5 text-sm tabular-nums transition-colors {selected
              ? 'bg-(--color-brand-soft) font-semibold text-(--color-brand-text)'
              : 'text-(--color-secondary) hover:bg-(--color-surface-hover) hover:text-(--color-primary)'}"
            data-testid="zoom-option-{opt}"
          >
            <span class="flex items-center gap-2">
              {#if selected}
                <Check
                  size={13}
                  strokeWidth={2.5}
                  class="h-3.5 w-3.5 shrink-0"
                  aria-hidden="true"
                />
              {:else}
                <span class="inline-block w-3.5"></span>
              {/if}
              {opt}%
              {#if isDefault && selected}
                <span class="sr-only">(default)</span>
              {/if}
            </span>
          </button>
        </li>
      {/each}
    </ul>
  {/if}
</div>
