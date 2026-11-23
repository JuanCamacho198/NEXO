<script lang="ts">
  import Download from 'lucide-svelte/icons/download';
  import ExternalLink from 'lucide-svelte/icons/external-link';
  import Button from '$lib/shared/ui/forms/Button.svelte';
  import Modal from '$lib/shared/ui/layout/Modal.svelte';
  import type { MessageKey } from '$lib/shared/i18n/messages.en';
  import { resolveAccess, type CatalogBook } from '$lib/shared/services/catalog';
  import type { DiscoverDetailStatus, DiscoverDownloadState } from './DiscoverDomainState.svelte';
  import DiscoverAccessSection from './DiscoverAccessSection.svelte';
  import {
    discoverDescription,
    discoverExternalLink,
    discoverFormatLabels,
  } from './discoverDetailFormat';
  import { providerLabel } from './discoverProviderLabel';

  type Translate = (key: MessageKey, params?: Record<string, string | number>) => string;

  let {
    detail,
    detailStatus,
    t,
    onDismiss,
    downloadState = 'idle',
    downloadError = null,
    progressBytes = 0,
    progressTotal = null,
    onDownload = () => {},
    onCancelDownload = () => {},
    onRetryDownload = () => {},
    onRetryDetail = () => {},
    inLibrary = false,
    onOpenBook = () => {},
    measureOverflow = (element: HTMLElement): boolean =>
      element.scrollHeight > element.clientHeight,
  }: {
    detail: CatalogBook | null;
    detailStatus: DiscoverDetailStatus;
    t: Translate;
    onDismiss: () => void;
    downloadState?: DiscoverDownloadState;
    downloadError?: string | null;
    progressBytes?: number;
    progressTotal?: number | null;
    onDownload?: () => void;
    onCancelDownload?: () => void;
    onRetryDownload?: () => void;
    onRetryDetail?: () => void;
    /** True when the library already holds a book with this catalog id. */
    inLibrary?: boolean;
    /** Opens the library book in the reader (the in-library affordance). */
    onOpenBook?: () => void;
    /**
     * Overflow probe for the description clamp. Injectable because jsdom does
     * not lay out (a real `scrollHeight`/`clientHeight` reading is always 0
     * there), so tests can drive both directions deterministically.
     */
    measureOverflow?: (element: HTMLElement) => boolean;
  } = $props();

  /** Stable id linking the description region to its "show more" toggle. */
  const DESCRIPTION_ID = 'discover-description';

  /** Modal facade (mirrors ShelfDetailModal): `bind:open` + reset-on-close. */
  // svelte-ignore state_referenced_locally
  let open = $state(detailStatus !== 'closed');
  let coverFailed = $state(false);

  // Keep `open` in step with the domain status; a user close (Escape,
  // backdrop, close button) flows back through `onDismiss`.
  $effect(() => {
    open = detailStatus !== 'closed';
  });
  $effect(() => {
    if (!open && detailStatus !== 'closed') onDismiss();
  });
  // Reset-on-close: never show a stale cover fallback on reopen.
  $effect(() => {
    if (!open) coverFailed = false;
  });

  // Reset the cover fallback whenever a different book loads.
  let lastDetailId: string | null = $state(null);
  $effect(() => {
    const id = detail?.id ?? null;
    if (id !== lastDetailId) {
      lastDetailId = id;
      coverFailed = false;
    }
  });

  const showCover = $derived(
    detail !== null && detail.coverUrl !== null && detail.coverUrl !== undefined && !coverFailed,
  );
  const description = $derived(detail ? discoverDescription(detail) : undefined);

  /** Rendered description paragraph; measured to decide whether to clamp. */
  let descriptionEl = $state<HTMLParagraphElement | undefined>();
  let descriptionExpanded = $state(false);
  let descriptionOverflows = $state(false);

  // A different book starts collapsed, and the collapsed paragraph is measured
  // again from scratch.
  $effect(() => {
    void detail?.id;
    descriptionExpanded = false;
    descriptionOverflows = false;
  });

  // Measure only while collapsed: an expanded paragraph can never report
  // overflow, so its last collapsed reading is kept on screen (the "show less"
  // toggle stays available). Recomputed whenever the text or element changes.
  //
  // A single reading at mount is not enough: the dialog is still opening and
  // laying out, so the paragraph measured 0×0 and the toggle never appeared
  // under a visibly cut description. A ResizeObserver reads it again once it
  // has its real size (and whenever the dialog resizes).
  $effect(() => {
    const element = descriptionEl;
    void description;
    if (!element || descriptionExpanded) return;
    descriptionOverflows = measureOverflow(element);
    if (typeof ResizeObserver === 'undefined') return;
    const observer = new ResizeObserver(() => {
      if (!descriptionExpanded) descriptionOverflows = measureOverflow(element);
    });
    observer.observe(element);
    return () => observer.disconnect();
  });

  const formatLabels = $derived(detail?.formats ? discoverFormatLabels(detail.formats) : []);
  const externalLink = $derived(detail ? discoverExternalLink(detail) : null);
  const externalLabel = $derived(
    externalLink?.kind === 'openlibrary'
      ? t('discover.externalOpenLibrary')
      : t('discover.externalGutenberg'),
  );
  /** PD-gated access model: FREE/BUY/SUBSCRIBE options + in-app download flag. */
  const access = $derived(detail ? resolveAccess(detail) : null);
  // The in-app download is offered only for public-domain https downloads, so a
  // non-PD book with a catalog URL never surfaces an in-app transfer CTA.
  const showDownloadCta = $derived(access?.canDownloadInApp === true);
  const progressPct = $derived(
    progressTotal !== null && progressTotal > 0
      ? Math.min(100, Math.round((progressBytes / progressTotal) * 100))
      : null,
  );
</script>

<!-- The header names the context; the book's title is the body's own heading,
  so it is not printed twice one line apart. -->
<Modal bind:open title={t('sidebar.discover')} size="xl">
  {#snippet children()}
    {#if detailStatus === 'loading'}
      <p class="text-sm text-(--color-text-muted)">{t('discover.loading')}</p>
    {:else if detailStatus === 'notFound'}
      <p class="text-sm text-(--color-text-muted)">{t('discover.detailNotFound')}</p>
    {:else if detailStatus === 'offline'}
      <p class="text-sm text-(--color-text-muted)">{t('discover.offline')}</p>
      <Button variant="secondary" size="sm" class="mt-2" onclick={onRetryDetail}>
        {t('discover.retry')}
      </Button>
    {:else if detailStatus === 'error' || detail === null}
      <p class="text-sm text-(--color-text-muted)">{t('discover.errorUpstream')}</p>
    {:else}
      <div class="flex flex-col gap-6 sm:flex-row sm:items-start">
        <div class="flex shrink-0 flex-col items-center gap-3 sm:items-start">
          <div
            class="relative aspect-2/3 w-36 overflow-hidden rounded-(--radius-md) border border-(--color-edge) bg-(--color-surface-subtle) shadow-[0_18px_36px_-16px_rgba(0,0,0,0.7)]"
          >
            {#if showCover && detail !== null}
              <img
                src={detail.coverUrl ?? ''}
                alt={detail.title}
                loading="lazy"
                decoding="async"
                class="h-full w-full object-cover"
                onerror={() => (coverFailed = true)}
              />
            {:else if detail !== null}
              <div
                class="flex h-full w-full items-center justify-center bg-linear-to-br from-(--color-brand-soft) to-transparent"
              >
                <span class="font-serif text-5xl font-semibold text-(--color-brand-text)/70"
                  >{detail.title.trim()[0]?.toUpperCase() || '?'}</span
                >
              </div>
            {/if}
          </div>
          {#if detail !== null}
            <span
              class="inline-flex items-center gap-1.5 rounded-full border border-(--color-edge) px-2.5 py-0.5 text-xs font-medium text-(--color-secondary)"
            >
              <span class="size-1.5 rounded-full bg-(--color-brand)" aria-hidden="true"></span>
              {providerLabel(detail.provider)}
            </span>
          {/if}
        </div>

        <div class="min-w-0 flex-1">
          <h3
            class="line-clamp-2 text-2xl leading-tight font-bold tracking-[-0.02em] text-balance text-(--color-primary)"
          >
            {detail.title}
          </h3>
          <p class="mt-1.5 text-base text-(--color-secondary)">
            {t('discover.byAuthors', { authors: detail.authors.join(', ') })}
          </p>

          <dl class="mt-4 flex flex-col gap-2 text-sm">
            {#if detail.languages.length > 0}
              <div class="flex items-baseline gap-2">
                <dt class="shrink-0 font-medium text-(--color-text-muted)">
                  {t('discover.languages')}
                </dt>
                <dd class="m-0 text-(--color-primary) uppercase">{detail.languages.join(', ')}</dd>
              </div>
            {/if}
            {#if formatLabels.length > 0}
              <div class="flex flex-wrap items-center gap-1.5">
                <dt class="mr-0.5 shrink-0 font-medium text-(--color-text-muted)">
                  {t('discover.formats')}
                </dt>
                {#each formatLabels as label (label)}
                  <dd
                    class="m-0 inline-flex items-center rounded-full border border-(--color-edge) px-2 py-0.5 text-xs font-semibold tracking-wide text-(--color-secondary)"
                  >
                    {label}
                  </dd>
                {/each}
              </div>
            {/if}
          </dl>

          {#if detail.subjects.length > 0}
            <div class="mt-3 flex flex-wrap gap-1.5">
              <span class="sr-only">{t('discover.subjects')}</span>
              {#each detail.subjects.slice(0, 8) as subject (subject)}
                <span
                  class="rounded-full bg-(--color-surface-hover) px-2.5 py-0.5 text-xs text-(--color-secondary)"
                  >{subject}</span
                >
              {/each}
            </div>
          {/if}

          <div class="mt-5 flex flex-wrap items-center gap-x-4 gap-y-3">
            {#if showDownloadCta}
              <div class="min-w-0">
                {#if downloadState === 'idle'}
                  {#if inLibrary}
                    <!--
                      The catalog id is the library id for Discover imports, so an
                      exact match means the transfer is already done. Only the idle
                      state reaches this branch: an in-flight or terminal transfer
                      state keeps its own rendering below.
                    -->
                    <div class="flex flex-wrap items-center gap-3">
                      <p role="status" class="text-sm font-medium text-(--color-primary)">
                        {t('discover.inLibrary')}
                      </p>
                      <Button variant="secondary" size="sm" onclick={onOpenBook}>
                        {t('discover.openBook')}
                      </Button>
                    </div>
                  {:else}
                    <Button variant="accent" size="lg" onclick={onDownload}>
                      {#snippet leadingIcon()}
                        <Download size={16} strokeWidth={2.2} aria-hidden="true" />
                      {/snippet}
                      {t('discover.download')}
                    </Button>
                  {/if}
                {:else if downloadState === 'downloading'}
                  <p role="status" class="text-sm text-(--color-text-muted)">
                    {t('discover.downloading')}
                  </p>
                  {#if progressPct !== null}
                    <div
                      role="progressbar"
                      aria-valuemin={0}
                      aria-valuemax={100}
                      aria-valuenow={progressPct}
                      class="mt-2 h-1.5 w-56 rounded-full bg-(--color-edge)"
                    >
                      <div
                        class="h-1.5 rounded-full bg-linear-to-r from-(--color-brand-start) to-(--color-brand) transition-all"
                        style="width: {progressPct}%"
                      ></div>
                    </div>
                  {/if}
                  <button
                    type="button"
                    class="mt-2 text-sm font-semibold text-(--color-brand-text) underline-offset-4 hover:underline"
                    onclick={onCancelDownload}
                  >
                    {t('discover.downloadCancel')}
                  </button>
                {:else if downloadState === 'importing'}
                  <p role="status" class="text-sm text-(--color-text-muted)">
                    {t('discover.importing')}
                  </p>
                {:else if downloadState === 'imported'}
                  <p role="status" class="text-sm font-medium text-(--color-primary)">
                    {t('discover.imported')}
                  </p>
                {:else if downloadState === 'cancelled'}
                  <p role="status" class="text-sm text-(--color-text-muted)">
                    {t('discover.downloadCancelled')}
                  </p>
                  <Button variant="secondary" size="sm" class="mt-2" onclick={onRetryDownload}>
                    {t('discover.retry')}
                  </Button>
                {:else}
                  <p role="alert" class="text-sm text-(--color-text-muted)">
                    {t('discover.downloadFailed')}{#if downloadError !== null}
                      {` (${downloadError})`}{/if}
                  </p>
                  <Button variant="secondary" size="sm" class="mt-2" onclick={onRetryDownload}>
                    {t('discover.retry')}
                  </Button>
                {/if}
              </div>
            {/if}
            {#if externalLink !== null}
              <a
                href={externalLink.url}
                target="_blank"
                rel="noreferrer"
                class="inline-flex items-center gap-1.5 rounded-full px-3 py-2 text-sm font-semibold text-(--color-brand-text) transition-colors hover:bg-(--color-brand-soft)"
              >
                {externalLabel}
                <ExternalLink size={14} strokeWidth={2} aria-hidden="true" />
              </a>
            {/if}
          </div>
        </div>
      </div>

      {#if description !== undefined}
        <div class="mt-6 border-t border-(--color-edge) pt-5">
          <p
            id={DESCRIPTION_ID}
            bind:this={descriptionEl}
            class="text-sm leading-relaxed text-(--color-secondary) {!descriptionExpanded &&
            descriptionOverflows
              ? 'mask-b-from-45%'
              : ''}"
            class:max-h-20={!descriptionExpanded}
            class:overflow-hidden={!descriptionExpanded}
          >
            {description}
          </p>
          {#if descriptionOverflows}
            <button
              type="button"
              class="mt-2 text-sm font-semibold text-(--color-brand-text) underline-offset-4 hover:underline"
              aria-expanded={descriptionExpanded}
              aria-controls={DESCRIPTION_ID}
              onclick={() => (descriptionExpanded = !descriptionExpanded)}
            >
              {descriptionExpanded
                ? t('discover.descriptionShowLess')
                : t('discover.descriptionShowMore')}
            </button>
          {/if}
        </div>
      {/if}
      {#if access !== null}
        <DiscoverAccessSection {access} {t} />
      {/if}
    {/if}
  {/snippet}
  {#snippet footer()}
    <Button variant="secondary" size="sm" onclick={onDismiss}>
      {t('discover.dismiss')}
    </Button>
  {/snippet}
</Modal>
