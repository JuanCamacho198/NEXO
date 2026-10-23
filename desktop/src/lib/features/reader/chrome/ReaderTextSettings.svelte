<script lang="ts">
  import Check from 'lucide-svelte/icons/check';
  import RotateCcw from 'lucide-svelte/icons/rotate-ccw';
  import { onDestroy } from 'svelte';
  import type { MessageKey } from '$lib/shared/i18n';
  import type { ReaderSettings, ReaderTextAlign } from '$lib/shared/types';
  import {
    READER_PROGRESS_INDICATOR_BOOK_PAGES,
    READER_PROGRESS_INDICATOR_DEFAULT,
  } from '$lib/shared/types/settings';
  import { createFocusTrap } from '$lib/shared/utils/focusTrap';
  import { fly } from 'svelte/transition';
  import { cubicOut } from 'svelte/easing';
  import ThemeSwatches from './ThemeSwatches.svelte';
  import Button from '$lib/shared/ui/forms/Button.svelte';
  import Dropdown from '$lib/shared/ui/navigation/Dropdown.svelte';
  import {
    READER_BRIGHTNESS_MAX,
    READER_BRIGHTNESS_MIN,
    useReaderTextSettings,
  } from './useReaderTextSettings.svelte';
  import {
    FONT_FAMILY_PRESETS,
    LINE_HEIGHT_PRESETS,
    MARGIN_MAX_REM,
    MARGIN_MIN_REM,
    MARGIN_STEP_REM,
    PARAGRAPH_SPACING_PRESETS,
  } from './readerTextPresets';

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

  // Svelte JS transitions ignore the global `prefers-reduced-motion` CSS block,
  // so the panel reads the query itself and zeroes its own duration.
  let reducedMotion = $state(false);
  $effect(() => {
    if (typeof window === 'undefined' || typeof window.matchMedia !== 'function') return;
    const mq = window.matchMedia('(prefers-reduced-motion: reduce)');
    const apply = (): void => {
      reducedMotion = mq.matches;
    };
    apply();
    mq.addEventListener('change', apply);
    return () => mq.removeEventListener('change', apply);
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

  // U5: '' is the publisher font — the render pipeline already treats a
  // falsy family as "no override". It just never had a visible exit until now.
  function fontPreviewStyle(): string {
    const cur = readerSettings.epub.fontFamily;
    return cur ? `font-family: ${cur}, serif;` : '';
  }

  // U6: destructive actions look destructive and never fire one-tap.
  let resetArmed = $state(false);
  let resetTimer: ReturnType<typeof setTimeout> | undefined;
  onDestroy(() => clearTimeout(resetTimer));

  function handleReset(): void {
    if (!resetArmed) {
      resetArmed = true;
      clearTimeout(resetTimer);
      resetTimer = setTimeout(() => {
        resetArmed = false;
      }, 3000);
      return;
    }
    clearTimeout(resetTimer);
    resetArmed = false;
    textSettings.resetToDefaults();
  }

  /** Horizontal margin is the left/right pair; vertical is top/bottom. */
  function horizontalMargin(): number {
    return readerSettings.margins.left;
  }

  function verticalMargin(): number {
    return readerSettings.margins.top;
  }

  function setHorizontalMargin(value: number): void {
    onSettingsChange({
      ...readerSettings,
      margins: { ...readerSettings.margins, left: value, right: value },
    });
  }

  function setVerticalMargin(value: number): void {
    onSettingsChange({
      ...readerSettings,
      margins: { ...readerSettings.margins, top: value, bottom: value },
    });
  }
</script>

{#snippet sectionTitle(label: string)}
  <h3 class="text-xs font-semibold tracking-wide text-(--color-text-muted) uppercase">{label}</h3>
{/snippet}

{#snippet toggleRow(
  label: string,
  checked: boolean,
  onclick: () => void,
  ariaLabel: string,
  title = '',
)}
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
      title={title || undefined}
    >
      <span
        class="h-4 w-4 rounded-full bg-white shadow transition-transform"
        class:translate-x-[22px]={checked}
        class:translate-x-[2px]={!checked}
      ></span>
    </button>
  </div>
{/snippet}

{#snippet selectRow(
  label: string,
  value: string,
  options: Array<{ value: string; label: string }>,
  onselect: (value: string) => void,
  testid: string,
  style = '',
)}
  <!-- Atom-backed select (bits-ui). The testid sits on a wrapper because the
    atom renders a button trigger, not a native <select>. -->
  <div class="flex items-center justify-between gap-3">
    <span class="text-sm text-(--color-text-muted)">{label}</span>
    <div class="shrink-0" data-testid={testid}>
      <Dropdown
        {options}
        {value}
        class="max-w-36 truncate rounded-xl border-transparent bg-(--color-border) px-3 py-2 text-(--color-accent-blue) hover:bg-(--color-border)"
        onchange={(e) => onselect(e.value)}
      >
        {#snippet trigger()}
          <span class="truncate text-sm" {style}>
            {options.find((o) => o.value === value)?.label ?? label}
          </span>
          <svg class="ml-2 h-4 w-4 shrink-0" fill="none" stroke="currentColor" viewBox="0 0 24 24">
            <path
              stroke-linecap="round"
              stroke-linejoin="round"
              stroke-width="2"
              d="M19 9l-7 7-7-7"
            />
          </svg>
        {/snippet}
      </Dropdown>
    </div>
  </div>
{/snippet}

{#snippet stepperRow(
  label: string,
  value: string,
  testid: string,
  ondecrement: () => void,
  onincrement: () => void,
  decrementDisabled = false,
  incrementDisabled = false,
)}
  <!-- One row shape for every numeric setting: a visible label, then −/value/+.
    The label is what tells the two minus signs apart; the buttons share a fixed
    width so the three rows align as a column instead of zig-zagging. -->
  <div class="flex items-center justify-between gap-3">
    <span class="text-sm text-(--color-text-muted)">{label}</span>
    <div class="flex shrink-0 items-center gap-1" role="group" aria-label={label}>
      <button
        type="button"
        data-testid={`${testid}-dec`}
        class="flex h-10 w-10 cursor-pointer items-center justify-center rounded text-(--color-text-muted) hover:bg-(--color-surface-hover) disabled:cursor-not-allowed disabled:opacity-30"
        onclick={ondecrement}
        aria-label={`${label} −`}
        disabled={decrementDisabled}
      >
        <svg
          xmlns="http://www.w3.org/2000/svg"
          width="16"
          height="16"
          viewBox="0 0 24 24"
          fill="none"
          stroke="currentColor"
          stroke-width="2"
          stroke-linecap="round"
          aria-hidden="true"><line x1="5" y1="12" x2="19" y2="12"></line></svg
        >
      </button>
      <span
        data-testid={`${testid}-value`}
        aria-live="polite"
        class="min-w-14 text-center text-sm font-medium text-(--color-text)">{value}</span
      >
      <button
        type="button"
        data-testid={`${testid}-inc`}
        class="flex h-10 w-10 cursor-pointer items-center justify-center rounded text-(--color-text-muted) hover:bg-(--color-surface-hover) disabled:cursor-not-allowed disabled:opacity-30"
        onclick={onincrement}
        aria-label={`${label} +`}
        disabled={incrementDisabled}
      >
        <svg
          xmlns="http://www.w3.org/2000/svg"
          width="16"
          height="16"
          viewBox="0 0 24 24"
          fill="none"
          stroke="currentColor"
          stroke-width="2"
          stroke-linecap="round"
          aria-hidden="true"
          ><line x1="12" y1="5" x2="12" y2="19"></line><line x1="5" y1="12" x2="19" y2="12"
          ></line></svg
        >
      </button>
    </div>
  </div>
{/snippet}

{#snippet marginSlider(
  label: string,
  value: number,
  testid: string,
  oninput: (value: number) => void,
)}
  <!-- Two axes instead of five named bundles: "Cómodo" never said how much, and
    a reader who wants wide margins but tight leading had no way to say it. -->
  <div class="flex flex-col gap-1.5">
    <div class="flex items-center justify-between">
      <span class="text-sm text-(--color-text-muted)">{label}</span>
      <span class="text-xs text-(--color-accent-blue)">{value.toFixed(1)}rem</span>
    </div>
    <input
      type="range"
      min={MARGIN_MIN_REM}
      max={MARGIN_MAX_REM}
      step={MARGIN_STEP_REM}
      {value}
      data-testid={testid}
      oninput={(e) => oninput(Number((e.currentTarget as HTMLInputElement).value))}
      class="slider-thumb w-full cursor-pointer accent-(--color-accent-blue)"
      aria-label={label}
    />
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
    <div bind:this={sidebarEl} class="absolute right-0 top-0 flex h-full w-65 flex-col overflow-y-auto border-l border-(--color-border-deep) bg-(--color-surface)/70 pt-15 text-(--color-text-muted) backdrop-blur-sm" transition:fly={{ x: reducedMotion ? 0 : 260, opacity: 0, duration: reducedMotion ? 0 : 250, easing: cubicOut }} onkeydown={(e) => e.key === 'Escape' && onClose()} role="dialog" aria-label={t('reader.ajustes_texto')} tabindex="0">
      <header class="relative flex items-center justify-between border-b border-(--color-border)/5 px-4 py-4">
        {#if textSettings.showSavedToast}
          <span class="absolute -top-2 right-4 flex items-center gap-1 rounded-full bg-(--color-accent-blue)/20 px-2.5 py-0.5 text-xs text-(--color-accent-blue)" transition:fly={{ y: -4, duration: 150 }}><Check size={14} strokeWidth={1.8} class="h-3.5 w-3.5 shrink-0" aria-hidden="true" />{t('reader.saved')}</span>
        {/if}
        <h2 class="text-sm font-semibold text-(--color-text-inverse)">{t('reader.ajustes_texto')}</h2>
        <Button
          variant="ghost"
          size="sm"
          onclick={onClose}
          class="shrink-0 px-2 text-(--color-text-muted) hover:text-(--color-text-inverse)"
          aria-label={t('settings.close')}
          title={t('settings.close')}
        >
          <svg xmlns="http://www.w3.org/2000/svg" width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><line x1="18" y1="6" x2="6" y2="18"></line><line x1="6" y1="6" x2="18" y2="18"></line></svg>
        </Button>
      </header>
      <section class="flex flex-col gap-6 p-4">
        <ThemeSwatches {readerSettings} {onSettingsChange} {t} />
        {#if format === 'pdf'}
          <!-- U1: brightness/contrast only render in PDF, the one format whose
            pipeline applies them. In EPUB they persisted + toasted with zero
            visual effect. -->
          <div class="flex flex-col gap-4">
            {@render sectionTitle(t('reader.section_page'))}
            {@render sliderRow(t('settings.reader.brightness'), readerSettings.brightness, textSettings.setBrightness)}
            {@render sliderRow(t('settings.reader.contrast'), readerSettings.contrast, textSettings.setContrast)}
          </div>
        {/if}
        {#if format === 'epub'}
          <div class="flex flex-col gap-4">
            {@render sectionTitle(t('reader.section_typography'))}
            {@render selectRow(
              t('reader.font'),
              readerSettings.epub.fontFamily,
              FONT_FAMILY_PRESETS.map((f) => ({ value: f.value, label: t(f.labelKey as MessageKey) })),
              (v) => onSettingsChange({ ...readerSettings, epub: { ...readerSettings.epub, fontFamily: v } }),
              'font-select',
              fontPreviewStyle(),
            )}
            {@render stepperRow(
              t('reader.font_size'),
              `${readerSettings.epub.fontSize}%`,
              'font-size',
              () => textSettings.changeFontSize(-10),
              () => textSettings.changeFontSize(10),
              readerSettings.epub.fontSize <= 80,
              readerSettings.epub.fontSize >= 200,
            )}
            {@render stepperRow(
              t('reader.line_spacing'),
              `${readerSettings.lineHeight.toFixed(1)}×`,
              'line-height',
              () => textSettings.changeLineHeight(-1),
              () => textSettings.changeLineHeight(1),
              readerSettings.lineHeight <= Math.min(...LINE_HEIGHT_PRESETS),
              readerSettings.lineHeight >= Math.max(...LINE_HEIGHT_PRESETS),
            )}
            {@render stepperRow(
              t('reader.letter_spacing'),
              readerSettings.letterSpacing > 0
                ? `+${readerSettings.letterSpacing}px`
                : `${readerSettings.letterSpacing}px`,
              'letter-spacing',
              () => textSettings.changeLetterSpacing(-1),
              () => textSettings.changeLetterSpacing(1),
              readerSettings.letterSpacing <= -2,
              readerSettings.letterSpacing >= 10,
            )}
          </div>
          <div class="flex flex-col gap-4">
            {@render sectionTitle(t('reader.section_page'))}
            {@render selectRow(
              t('reader.reading_mode'),
              readerSettings.paginated ? 'paginated' : 'scroll',
              [
                { value: 'scroll', label: t('reader.mode_scroll') },
                { value: 'paginated', label: t('reader.mode_paginated') },
              ],
              (v) => onSettingsChange({ ...readerSettings, paginated: v === 'paginated' }),
              'reading-mode-select',
            )}
            {@render selectRow(
              t('reader.alignment'),
              readerSettings.textAlign,
              (['left', 'center', 'right', 'justify'] as const).map((a) => ({
                value: a,
                label: alignLabel(a),
              })),
              (v) => onSettingsChange({ ...readerSettings, textAlign: v as ReaderTextAlign }),
              'alignment-select',
            )}
            {@render marginSlider(
              t('reader.margin_horizontal'),
              horizontalMargin(),
              'margins-horizontal',
              setHorizontalMargin,
            )}
            {@render marginSlider(
              t('reader.margin_vertical'),
              verticalMargin(),
              'margins-vertical',
              setVerticalMargin,
            )}
            {@render selectRow(
              t('reader.paragraph_spacing'),
              String(readerSettings.paragraphSpacing),
              PARAGRAPH_SPACING_PRESETS.map((p) => ({ value: String(p), label: `${p}em` })),
              (v) => onSettingsChange({ ...readerSettings, paragraphSpacing: Number(v) }),
              'spacing-select',
            )}
          </div>
          <div class="flex flex-col gap-4">
            {@render sectionTitle(t('reader.section_writing'))}
            {@render selectRow(
              t('reader.direction'),
              readerSettings.direction,
              [
                { value: 'ltr', label: t('reader.directionLtr') },
                { value: 'rtl', label: t('reader.directionRtl') },
              ],
              (v) => onSettingsChange({ ...readerSettings, direction: v as ReaderSettings['direction'] }),
              'direction-select',
            )}
            {@render toggleRow(t('reader.hyphenation'), readerSettings.hyphenation, () => onSettingsChange({ ...readerSettings, hyphenation: !readerSettings.hyphenation }), t('reader.hyphenation'))}
            <!-- The page field counts the current chapter unless this is on, and
              then it counts the whole book in whatever unit the reading mode
              uses. Same row shape as the switch above it. -->
            {@render toggleRow(
              t('reader.book_page_totals'),
              readerSettings.progressIndicator === READER_PROGRESS_INDICATOR_BOOK_PAGES,
              () => onSettingsChange({
                ...readerSettings,
                progressIndicator: readerSettings.progressIndicator === READER_PROGRESS_INDICATOR_BOOK_PAGES
                  ? READER_PROGRESS_INDICATOR_DEFAULT
                  : READER_PROGRESS_INDICATOR_BOOK_PAGES,
              }),
              t('reader.book_page_totals'),
              t('reader.book_page_totals_tooltip'),
            )}
          </div>
        {/if}
        <!-- U1: the showHeader/showFooter/verticalScrolling switches lived here.
          Removed: they persisted but no viewer reads them, so every toggle
          was fake feedback. Restore them only with a wired reader. -->
        <section aria-label={t('reader.reset_defaults')} class="rounded-xl border border-(--color-error)/40 p-2">
          <button
            type="button"
            data-testid="reset-defaults"
            onclick={handleReset}
            class="flex w-full cursor-pointer items-center gap-2 rounded-lg px-3 py-2 hover:bg-(--color-surface-hover)"
            class:text-(--color-error)={resetArmed}
            class:text-(--color-text-muted)={!resetArmed}
          >
            <RotateCcw size={14} strokeWidth={2} class="h-3.5 w-3.5 shrink-0" aria-hidden="true" />
            <span class="text-sm font-medium"
              >{resetArmed ? t('reader.reset_confirm') : t('reader.reset_defaults')}</span
            >
          </button>
        </section>
      </section>
    </div>
  </div>
{/if}
<!-- prettier-ignore-end -->
