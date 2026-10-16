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

  const triggerClass = `${chromeIconButton('action')} gap-1 min-w-18`;

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
    <ChevronDown size={12} strokeWidth={2} class="h-3 w-3" aria-hidden="true" />
  </button>

  {#if open}
    <ul
      class="absolute bottom-full right-0 z-50 mb-2 max-h-[min(16rem,60vh)] min-w-28 overflow-y-auto overscroll-contain rounded-xl border border-(--color-border) bg-(--color-surface) py-1 shadow-lg"
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
            class="flex w-full items-center justify-between px-3 py-1.5 text-xs hover:bg-(--color-surface-hover) cursor-pointer {selected
              ? 'font-semibold text-(--color-primary)'
              : 'text-(--color-text-muted)'}"
            data-testid="zoom-option-{opt}"
          >
            <span class="flex items-center gap-1.5">
              {#if selected}
                <Check size={12} strokeWidth={2.5} class="h-3 w-3 shrink-0" aria-hidden="true" />
              {:else}
                <span class="w-3 inline-block"></span>
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
