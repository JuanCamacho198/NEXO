<script lang="ts">
  import Check from 'lucide-svelte/icons/check';
  import type { MessageKey } from '$lib/shared/i18n';
  import type { ReaderSettings, ReaderTextAlign } from '$lib/shared/types';
  import { createFocusTrap } from '$lib/shared/utils/focusTrap';
  import { fly } from 'svelte/transition';
  import ThemeSwatches from './ThemeSwatches.svelte';
  import {
    READER_BRIGHTNESS_MAX,
    READER_BRIGHTNESS_MIN,
    useReaderTextSettings,
  } from './useReaderTextSettings.svelte';
  import { FONT_FAMILY_PRESETS, cyclePreset } from './readerTextPresets';

  type Props = {
    open: boolean;
    format: 'pdf' | 'epub';
    readerSettings: ReaderSettings;
    onSettingsChange: (settings: ReaderSettings) => void;
    t: (key: MessageKey, params?: Record<string, string | number>) => string;
    onClose: () => void;
  };

  let { open, format, readerSettings, onSettingsChange, t, onClose }: Props = $props();

  let sidebarEl: HTMLElement | undefined = $state();
  const textSettings = useReaderTextSettings({
    getSettings: () => readerSettings,
    onSettingsChange: (s) => onSettingsChange(s),
  });

  function handleBackdropClick(e: MouseEvent): void {
    if (e.target === e.currentTarget) onClose();
  }

  $effect(() => {
    if (open && sidebarEl) {
      const trap = createFocusTrap(sidebarEl);
      trap.activate();
      return () => trap.deactivate();
    }
  });

  function alignLabel(al: ReaderTextAlign): string {
    const labels: Record<ReaderTextAlign, string> = {
      left: t('reader.alignLeft'),
      center: t('reader.alignCenter'),
      right: t('reader.alignRight'),
      justify: t('reader.alignJustify'),
    };
    return labels[al] ?? t('reader.alignLeft');
  }

  function cycleFontFamily(): void {
    const cur = readerSettings.epub.fontFamily;
    const next = cyclePreset(cur, FONT_FAMILY_PRESETS);
    onSettingsChange({ ...readerSettings, epub: { ...readerSettings.epub, fontFamily: next } });
  }
</script>

{#snippet settingsRow(label: string, value: string, onclick: () => void)}
  <div
    class="flex cursor-pointer items-center justify-between"
    {onclick}
    role="button"
    tabindex="0"
    onkeydown={(e) => e.key === 'Enter' && onclick()}
  >
    <span class="text-sm text-(--color-text-muted)">{label}: {value}</span>
    <svg
      xmlns="http://www.w3.org/2000/svg"
      width="11"
      height="11"
      viewBox="0 0 24 24"
      fill="none"
      stroke="var(--color-accent-blue)"
      stroke-width="3"
      stroke-linecap="round"
      stroke-linejoin="round"><polyline points="6 9 12 15 18 9"></polyline></svg
    >
  </div>
{/snippet}

{#snippet toggleRow(label: string, checked: boolean, onclick: () => void, ariaLabel: string)}
  <div class="flex items-center justify-between">
    <span class="text-sm text-(--color-text-muted)">{label}</span>
    <button
      type="button"
      class="relative flex h-5 w-10 cursor-pointer items-center rounded-full transition-colors"
      style="background-color: {checked ? 'var(--color-accent-blue)' : 'rgba(148, 173, 206, 0.2)'};"
      {onclick}
      role="switch"
      aria-checked={checked}
      aria-label={ariaLabel}
    >
      <span
        class="h-4 w-4 rounded-full bg-white shadow transition-transform"
        class:translate-x-[22px]={checked}
        class:translate-x-[2px]={!checked}
      ></span>
    </button>
  </div>
{/snippet}

{#snippet sliderRow(label: string, value: number, oninput: (value: number) => void)}
  <div class="flex flex-col gap-1.5">
    <div class="flex items-center justify-between">
      <span class="text-sm text-(--color-text-muted)">{label}</span>
      <span class="text-xs text-(--color-accent-blue)">{value}%</span>
    </div>
    <input
      type="range"
      min={READER_BRIGHTNESS_MIN}
      max={READER_BRIGHTNESS_MAX}
      step="1"
      {value}
      oninput={(e) => oninput(Number((e.currentTarget as HTMLInputElement).value))}
      class="slider-thumb w-full cursor-pointer accent-(--color-accent-blue)"
      aria-label={label}
    />
  </div>
{/snippet}

<!-- prettier-ignore-start -->
{#if open}
  <div class="fixed inset-0 z-40" onclick={handleBackdropClick} onkeydown={(e) => e.key === 'Escape' && onClose()} role="presentation">
    <div class="absolute inset-0 bg-(--color-surface)/70"></div>
    <div bind:this={sidebarEl} class="absolute right-0 top-0 flex h-full w-65 flex-col overflow-y-auto border-l border-(--color-border-deep) bg-(--color-surface)/70 pt-15 text-(--color-text-muted) backdrop-blur-sm" onkeydown={(e) => e.key === 'Escape' && onClose()} role="dialog" aria-label={t('reader.ajustes_texto')} tabindex="0">
      <header class="relative flex items-center justify-between border-b border-(--color-border)/5 px-4 py-4">
        {#if textSettings.showSavedToast}
          <span class="absolute -top-2 right-4 flex items-center gap-1 rounded-full bg-(--color-accent-blue)/20 px-2.5 py-0.5 text-xs text-(--color-accent-blue)" transition:fly={{ y: -4, duration: 150 }}><Check size={14} strokeWidth={1.8} class="h-3.5 w-3.5 shrink-0" aria-hidden="true" />{t('reader.saved')}</span>
        {/if}
        <button type="button" onclick={onClose} class="flex cursor-pointer items-center gap-1 text-(--color-text-muted) hover:text-(--color-text-inverse)" aria-label={t('settings.close')}>
          <svg xmlns="http://www.w3.org/2000/svg" width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><line x1="18" y1="6" x2="6" y2="18"></line><line x1="6" y1="6" x2="18" y2="18"></line></svg>
          <span class="text-xs font-medium">{t('settings.close')}</span>
        </button>
        {#if format === 'epub'}
          <button type="button" class="cursor-pointer rounded p-2 hover:bg-(--color-surface-hover)" onclick={textSettings.cycleAlignment} aria-label={t('reader.alignment')} title={alignLabel(readerSettings.textAlign)}><svg xmlns="http://www.w3.org/2000/svg" width="15" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" class="text-(--color-text-muted)"><line x1="17" y1="10" x2="3" y2="10"></line><line x1="21" y1="6" x2="3" y2="6"></line><line x1="21" y1="14" x2="3" y2="14"></line><line x1="17" y1="18" x2="3" y2="18"></line></svg></button>
          <button type="button" class="cursor-pointer rounded bg-(--color-accent-blue) p-2" onclick={() => textSettings.changeFontSize(10)} aria-label={t('reader.font_increase')}><svg xmlns="http://www.w3.org/2000/svg" width="17" height="14" viewBox="0 0 24 24" fill="none" stroke="white" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><polyline points="4 7 4 4 20 4 20 7"></polyline><line x1="9" y1="20" x2="15" y2="20"></line><line x1="12" y1="4" x2="12" y2="20"></line></svg></button>
          <button type="button" class="cursor-pointer rounded p-2 hover:bg-(--color-surface-hover)" onclick={textSettings.toggleDirection} aria-label={t('reader.direction')} title={readerSettings.direction === 'ltr' ? t('reader.directionLtr') : t('reader.directionRtl')}><svg xmlns="http://www.w3.org/2000/svg" width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" class="text-(--color-text-muted)"><rect x="3" y="3" width="7" height="7"></rect><rect x="14" y="3" width="7" height="7"></rect><rect x="14" y="14" width="7" height="7"></rect><rect x="3" y="14" width="7" height="7"></rect></svg></button>
        {/if}
      </header>
      <section class="flex flex-col gap-6 p-4">
        <ThemeSwatches {readerSettings} {onSettingsChange} {t} />
        <div class="h-px w-full bg-(--color-border)"></div>
        <div class="flex flex-col gap-4">
          {@render sliderRow(t('settings.reader.brightness'), readerSettings.brightness, textSettings.setBrightness)}
          {@render sliderRow(t('settings.reader.contrast'), readerSettings.contrast, textSettings.setContrast)}
        </div>
        <div class="h-px w-full bg-(--color-border)"></div>
        {#if format === 'epub'}
          <button type="button" class="flex w-full items-center justify-between rounded-xl bg-(--color-border) px-3 py-2" onclick={cycleFontFamily}><span class="text-sm font-normal text-(--color-accent-blue)">{readerSettings.epub.fontFamily || t('reader.fontDefault')}</span><svg xmlns="http://www.w3.org/2000/svg" width="9" height="6" viewBox="0 0 24 24" fill="none" stroke="var(--color-accent-blue)" stroke-width="3" stroke-linecap="round" stroke-linejoin="round"><polyline points="6 9 12 15 18 9"></polyline></svg></button>
          <div class="flex justify-between">
            <button type="button" class="flex h-12 w-26.25 cursor-pointer items-center justify-center rounded p-2 hover:bg-(--color-surface-hover)" onclick={() => textSettings.changeFontSize(-10)} aria-label={t('reader.font_decrease')}><svg xmlns="http://www.w3.org/2000/svg" width="17" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" class="text-(--color-text-muted)"><polyline points="4 7 4 4 20 4 20 7"></polyline><line x1="9" y1="20" x2="15" y2="20"></line><line x1="12" y1="4" x2="12" y2="20"></line></svg></button>
            <button type="button" class="flex h-12 w-26.25 cursor-pointer items-center justify-center rounded p-2 hover:bg-(--color-surface-hover)" onclick={textSettings.cycleLineHeight} aria-label={t('reader.line_spacing')} title={`Line height: ${readerSettings.lineHeight}`}><svg xmlns="http://www.w3.org/2000/svg" width="27" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" class="text-(--color-primary)"><line x1="5" y1="3" x2="19" y2="3"></line><line x1="5" y1="21" x2="19" y2="21"></line><polyline points="12 7 9 10 15 10"></polyline><polyline points="9 14 12 17 15 14"></polyline><line x1="12" y1="7" x2="12" y2="17"></line></svg></button>
          </div>
          <div class="flex justify-between">
            <button type="button" class="flex h-10 w-26.25 cursor-pointer items-center justify-center rounded p-2 hover:bg-(--color-surface-hover)" onclick={() => textSettings.changeLetterSpacing(-1)} aria-label={t('reader.spacing_decrease')}><svg xmlns="http://www.w3.org/2000/svg" width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" class="text-(--color-text-muted)"><line x1="5" y1="12" x2="19" y2="12"></line></svg></button>
            <button type="button" class="flex h-10 w-26.25 cursor-pointer items-center justify-center rounded p-2 hover:bg-(--color-surface-hover)" onclick={() => textSettings.changeLetterSpacing(1)} aria-label={t('reader.spacing_increase')}><svg xmlns="http://www.w3.org/2000/svg" width="20" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" class="text-(--color-text-muted)"><line x1="12" y1="5" x2="12" y2="19"></line><line x1="5" y1="12" x2="19" y2="12"></line></svg></button>
          </div>
          <div class="h-px w-full bg-(--color-border)"></div>
          <div class="flex flex-col gap-4">
            {@render settingsRow(`${t('reader.direction')}`, readerSettings.direction === 'ltr' ? t('reader.directionLtr') : t('reader.directionRtl'), textSettings.toggleDirection)}
            {@render settingsRow(`${t('reader.alignment')}`, alignLabel(readerSettings.textAlign), textSettings.cycleAlignment)}
            {@render settingsRow(`${t('reader.margins')}`, `${readerSettings.margins.left.toFixed(1)}rem`, textSettings.cycleMargins)}
            {@render settingsRow(`${t('reader.paragraph_spacing')}`, `${readerSettings.paragraphSpacing}em`, textSettings.cycleParagraphSpacing)}
            {@render settingsRow(`${t('reader.hyphenation')}`, readerSettings.hyphenation ? t('reader.hyphenationOn') : t('reader.hyphenationOff'), () => onSettingsChange({ ...readerSettings, hyphenation: !readerSettings.hyphenation }))}
          </div>
          <div class="h-px w-full bg-(--color-border)"></div>
        {/if}
        <section class="flex flex-col gap-4">
          {@render toggleRow(t('settings.reading.showHeader'), readerSettings.showHeader, () => onSettingsChange({ ...readerSettings, showHeader: !readerSettings.showHeader }), t('settings.reading.showHeader'))}
          {@render toggleRow(t('settings.reading.showFooter'), readerSettings.showFooter, () => onSettingsChange({ ...readerSettings, showFooter: !readerSettings.showFooter }), t('settings.reading.showFooter'))}
          {#if format === 'epub'}{@render toggleRow(t('reader.vertical_scroll'), readerSettings.verticalScrolling, () => onSettingsChange({ ...readerSettings, verticalScrolling: !readerSettings.verticalScrolling }), t('reader.vertical_scroll'))}{/if}
        </section>
        <button type="button" class="flex w-full cursor-pointer items-center justify-between rounded-xl bg-(--color-border) px-3 py-2 hover:bg-(--color-surface-hover)" onclick={textSettings.resetToDefaults}><span class="text-sm text-(--color-accent-blue)">{t('reader.saved_settings')}</span><svg xmlns="http://www.w3.org/2000/svg" width="9" height="6" viewBox="0 0 24 24" fill="none" stroke="var(--color-accent-blue)" stroke-width="3" stroke-linecap="round" stroke-linejoin="round"><polyline points="6 9 12 15 18 9"></polyline></svg></button>
      </section>
    </div>
  </div>
{/if}
<!-- prettier-ignore-end -->
