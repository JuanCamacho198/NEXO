<script lang="ts">
  import { slide } from 'svelte/transition';
  import BookOpenText from 'lucide-svelte/icons/book-open-text';
  import Languages from 'lucide-svelte/icons/languages';
  import LoaderCircle from 'lucide-svelte/icons/loader-circle';
  import X from 'lucide-svelte/icons/x';
  import type { MessageKey } from '$lib/shared/i18n';
  import type {
    NoTranslationReason,
    SourceOrigin,
  } from '$lib/shared/services/translation/translationLanguageResolution';
  import TranslationLanguageBar from './TranslationLanguageBar.svelte';
  import type { Lookup, LookupTab } from './useLookup.svelte';

  type Props = {
    lookup: Lookup;
    t: (key: MessageKey, params?: Record<string, string | number>) => string;
    /** The panel's width: the menu it belongs to decides it. */
    width: number;
    /** The room the menu leaves in the window; the body scrolls past it. */
    maxHeight: number;
  };

  let { lookup, t, width, maxHeight }: Props = $props();

  /**
   * One message per refusal reason, and where a reason has more than one
   * provenance, one per provenance — because the FIX differs. A source the
   * reader chose (`preference`, `bookPreference`) never reached the detector,
   * so its failure is reported as their own setting, not as a detection miss.
   */
  const TRANSLATION_REFUSAL_KEYS: Record<NoTranslationReason, MessageKey> = {
    'same-language': 'reader.translationSameLanguage',
    'unknown-source': 'reader.translationUnknownSource',
    'invalid-source': 'reader.translationInvalidSource',
    'invalid-target': 'reader.translationInvalidTarget',
  };

  function translationRefusalKey(
    reason: NoTranslationReason,
    sourceOrigin: SourceOrigin | null,
  ): MessageKey {
    const originIsAChoice = sourceOrigin === 'preference' || sourceOrigin === 'bookPreference';
    if (originIsAChoice) {
      if (reason === 'same-language') return 'reader.translationSameLanguageChosen';
      if (reason === 'invalid-source') return 'reader.translationInvalidSourceChosen';
    }
    return TRANSLATION_REFUSAL_KEYS[reason];
  }

  const tabs: Array<{ id: LookupTab; label: MessageKey }> = [
    { id: 'translate', label: 'reader.translate' },
    { id: 'define', label: 'reader.define' },
  ];

  const dismissLabel = $derived(
    lookup.tab === 'define' ? t('reader.dismissDefinition') : t('reader.dismissTranslation'),
  );

  const TAB_BASE =
    'inline-flex flex-1 cursor-pointer items-center justify-center gap-1.5 rounded-full px-3 py-1.5 text-xs font-semibold transition-colors focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-(--color-brand) disabled:cursor-not-allowed disabled:opacity-40';
</script>

<!-- One panel, two tabs: the reader moves between the translation and the
     definition of the same text without closing anything. `role="status"`
     stays on the panel root so its answers are announced as they land. -->
<div
  class="mt-2 overflow-y-auto rounded-2xl border border-(--color-highlight-menu-border) bg-(--color-highlight-menu-bg) text-(--color-text-inverse) shadow-[0_16px_36px_-14px_rgba(0,0,0,0.6)]"
  style="width: {width}px; max-height: {maxHeight}px;"
  role="status"
  aria-live="polite"
  data-testid="lookup-panel"
  in:slide={{ duration: 160 }}
>
  <div
    class="sticky top-0 z-10 flex items-center gap-2 border-b border-white/10 bg-(--color-highlight-menu-bg) px-2 py-2"
  >
    <div class="flex flex-1 gap-1 rounded-full bg-white/5 p-0.5" role="tablist">
      {#each tabs as tab (tab.id)}
        {@const isActive = lookup.tab === tab.id}
        {@const unavailable = tab.id === 'define' && !lookup.canDefine()}
        <button
          type="button"
          role="tab"
          aria-selected={isActive}
          disabled={unavailable}
          title={unavailable ? t('reader.defineOnlyWords') : undefined}
          class="{TAB_BASE} {isActive
            ? 'bg-(--color-brand-soft) text-(--color-brand-text)'
            : 'text-white/65 hover:bg-white/5 hover:text-white'}"
          onclick={() => lookup.select(tab.id)}
          data-testid="lookup-tab-{tab.id}"
        >
          {#if tab.id === 'translate'}
            <Languages size={14} strokeWidth={2} aria-hidden="true" />
          {:else}
            <BookOpenText size={14} strokeWidth={2} aria-hidden="true" />
          {/if}
          {t(tab.label)}
        </button>
      {/each}
    </div>
    <button
      type="button"
      class="flex size-7 shrink-0 cursor-pointer items-center justify-center rounded-full text-white/60 transition-colors hover:bg-white/10 hover:text-white focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-(--color-brand)"
      onclick={() => lookup.close()}
      aria-label={dismissLabel}
      title={dismissLabel}
    >
      <X size={14} strokeWidth={2.2} aria-hidden="true" />
    </button>
  </div>

  <div class="px-4 py-3">
    {#if lookup.tab === 'translate'}
      {#if lookup.translating}
        <p class="flex items-center gap-2 text-xs text-(--color-text-auxiliary)">
          <LoaderCircle size={14} strokeWidth={2} class="animate-spin" aria-hidden="true" />
          {t('reader.translating')}
        </p>
      {:else if lookup.translation?.status === 'ok'}
        <p class="text-[0.9375rem] leading-relaxed wrap-break-word">
          {lookup.translation.text}
        </p>
      {:else if lookup.translation?.status === 'no-result'}
        <p class="text-xs text-(--color-text-auxiliary)">{t('reader.translationNoResult')}</p>
      {:else if lookup.translation?.status === 'unavailable'}
        <p class="text-xs text-(--color-text-auxiliary)">{t('reader.translationUnavailable')}</p>
      {:else if lookup.translation?.status === 'refused'}
        <!-- Suppressed while the picked pair is ahead of the answer: the
             refusal describes the RESOLVED pair, and printing it under a bar
             that shows a different one reads as a contradiction. -->
        {#if !lookup.overlayIsAhead}
          <p class="text-xs leading-relaxed text-(--color-text-auxiliary)">
            {t(translationRefusalKey(lookup.translation.reason, lookup.translation.sourceOrigin))}
          </p>
        {/if}
      {/if}
      {#if !lookup.translating && lookup.shownLanguages}
        <div class="mt-3 border-t border-white/10 pt-2.5">
          <TranslationLanguageBar
            source={lookup.shownLanguages.source}
            target={lookup.shownLanguages.target}
            resolvedSource={lookup.resolvedLanguages?.source ?? null}
            resolvedTarget={lookup.resolvedLanguages?.target ?? null}
            sourceOrigin={lookup.shownSourceOrigin}
            {t}
            onChange={(next) => lookup.changeLanguages(next)}
          />
        </div>
      {/if}
    {:else if lookup.tab === 'define'}
      <!-- The attribution renders on EVERY result branch, the empty one too:
           the CC BY-SA licence requires it to travel with the reused text. -->
      {#if lookup.defining}
        <p class="flex items-center gap-2 text-xs text-(--color-text-auxiliary)">
          <LoaderCircle size={14} strokeWidth={2} class="animate-spin" aria-hidden="true" />
          {t('reader.defining')}
        </p>
      {:else if lookup.definitionFailed}
        <p class="text-xs text-(--color-text-auxiliary)">{t('reader.definitionUnavailable')}</p>
      {:else if lookup.definition}
        {#if lookup.definition.definitions.length === 0}
          <p class="text-xs text-(--color-text-auxiliary)">{t('reader.definitionNoResult')}</p>
        {:else}
          {#if lookup.definition.source === 'translated'}
            <p
              class="mb-2 inline-flex rounded-full bg-white/8 px-2 py-0.5 text-[0.6875rem] text-(--color-text-auxiliary)"
            >
              {t('reader.definitionTranslated')}
            </p>
          {/if}
          <ol class="flex flex-col gap-2">
            {#each lookup.definition.definitions as item, index (index)}
              <li class="flex gap-2 text-sm leading-relaxed wrap-break-word">
                <span
                  class="mt-0.5 shrink-0 text-xs font-semibold text-(--color-brand-text) tabular-nums"
                  aria-hidden="true">{index + 1}.</span
                >
                <span class="min-w-0">
                  {#if item.partOfSpeech}
                    <span
                      data-definition-pos
                      class="mr-1 rounded bg-white/8 px-1.5 py-px text-[0.6875rem] italic text-(--color-text-auxiliary)"
                    >
                      {item.partOfSpeech}
                    </span>
                  {/if}
                  {item.text}
                </span>
              </li>
            {/each}
          </ol>
        {/if}
        <p class="mt-3 text-[0.65rem] text-(--color-text-auxiliary)">
          {lookup.definition.attribution}
        </p>
      {/if}
    {/if}
  </div>
</div>
