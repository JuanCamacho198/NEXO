<script lang="ts">
  import Modal from '$lib/shared/ui/layout/Modal.svelte';
  import Calendar from 'lucide-svelte/icons/calendar';
  import Clock from 'lucide-svelte/icons/clock';
  import Info from 'lucide-svelte/icons/info';
  import SquarePen from 'lucide-svelte/icons/square-pen';
  import Button from '$lib/shared/ui/forms/Button.svelte';
  import Dropdown from '$lib/shared/ui/navigation/Dropdown.svelte';
  import SafeCover from '$lib/features/library/components/SafeCover.svelte';
  import { getSafeProgressPercentage } from '$lib/shared/stores/HomeState';
  import { FAVORITES_COLLECTION_ID } from '$lib/features/library/utils';
  import { useShelfEdit } from './useShelfEdit.svelte.js';
  import {
    getCurrentStatus,
    formatMinutes,
    getCollectionNames,
    formatRelativeDate,
    getLanguageName,
    formatPublicationDate,
  } from './shelfDetailFormat';
  import type { LibraryBookDto, CollectionDto } from '$lib/shared/types';
  import type { MessageKey } from '$lib/shared/i18n';
  type Props = {
    open: boolean;
    book: LibraryBookDto | null;
    collections: CollectionDto[];
    t: (key: MessageKey, params?: Record<string, string | number>) => string;
    onClose: () => void;
    onStartReading: (book: LibraryBookDto) => void | Promise<void>;
    onDeleteCover: (book: LibraryBookDto) => void | Promise<void>;
    onStatusChange: (book: LibraryBookDto, status: string) => void | Promise<void>;
    onToggleFavorite: (book: LibraryBookDto) => void | Promise<void>;
    onSaveEdit: (dto: Partial<LibraryBookDto>) => Promise<void>;
    onCoverUpdated?: (bookId: string, path: string) => void;
  };
  let {
    open = $bindable(),
    book,
    collections,
    t,
    onClose,
    onStartReading,
    onDeleteCover,
    onStatusChange,
    onToggleFavorite,
    onSaveEdit,
    onCoverUpdated,
  }: Props = $props();
  const shelfEdit = useShelfEdit({
    getSelectedBook: () => book,
    onSaveEdit: (dto) => onSaveEdit(dto),
    t: (k, p) => t(k, p),
    onCoverUpdated: (id, path) => onCoverUpdated?.(id, path),
  });
  const progressPct = $derived(book ? Math.round(getSafeProgressPercentage(book)) : 0);
  const currentStatus = $derived(getCurrentStatus(book));
  const STATUS_OPTIONS = $derived([
    { value: 'reading', label: t('shelf.statusReading' as MessageKey) },
    { value: 'to_read', label: t('shelf.statusToRead' as MessageKey) },
    { value: 'completed', label: t('shelf.statusCompleted' as MessageKey) },
  ]);
  $effect(() => {
    if (!open) shelfEdit.resetOnClose(false);
  });
  // facade thin: Modal + progressPct/currentStatus/STATUS_OPTIONS + $effect resetOnClose
  // delegates 6 helpers to shelfDetailFormat: getCurrentStatus, formatMinutes, getCollectionNames, formatRelativeDate, getLanguageName, formatPublicationDate
  // shelfEdit wiring: useShelfEdit + resetOnClose + handleCoverImport + isEditing
</script>

{#if book}
  {@const shelfDetail = book}
  <Modal
    bind:open
    title={shelfEdit.isEditing ? t('shelf.editMetadata' as MessageKey) : shelfDetail.title}
    size="xl"
  >
    {#snippet children()}
      <div class="flex flex-col gap-6 sm:flex-row">
        <div class="shrink-0 mx-auto sm:mx-0">
          {#if shelfDetail.coverPath}
            <div class="relative w-48">
              <SafeCover
                path={shelfDetail.coverPath}
                alt={shelfDetail.title}
                className="w-48 h-64 object-cover rounded-lg shadow-md border border-(--color-border)"
              >
                {#snippet fallback()}<div
                    class="w-48 h-64 rounded-lg bg-gradient-to-br from-(--color-primary)/8 to-(--color-primary)/3 flex items-center justify-center border border-(--color-border) shadow-md"
                  >
                    <span class="text-4xl font-bold text-(--color-primary)/30"
                      >{shelfDetail.title.trim()[0]?.toUpperCase() || '?'}</span
                    >
                  </div>{/snippet}
              </SafeCover>
              <button
                type="button"
                class="absolute bottom-2 right-2 flex h-7 w-7 items-center justify-center rounded-full bg-black/50 text-white hover:bg-black/70 transition-colors"
                onclick={() => void onDeleteCover(shelfDetail)}
                aria-label={t('shelf.deleteCoverAria' as MessageKey)}
                ><svg
                  class="h-3.5 w-3.5"
                  fill="none"
                  stroke="currentColor"
                  viewBox="0 0 24 24"
                  stroke-width="2"
                  ><path
                    stroke-linecap="round"
                    stroke-linejoin="round"
                    d="M19 7l-.867 12.142A2 2 0 0116.138 21H7.862a2 2 0 01-1.995-1.858L5 7m5 4v6m4-6v6m1-10V4a1 1 0 00-1-1h-4a1 1 0 00-1 1v3M4 7h16"
                  /></svg
                ></button
              >
            </div>
          {:else}
            <div class="relative w-48">
              <div
                class="w-48 h-64 rounded-lg bg-gradient-to-br from-(--color-primary)/8 to-(--color-primary)/3 flex items-center justify-center border border-(--color-border) shadow-md"
              >
                <span class="text-4xl font-bold text-(--color-primary)/30"
                  >{shelfDetail.title.trim()[0]?.toUpperCase() || '?'}</span
                >
              </div>
              <button
                type="button"
                class="absolute bottom-2 right-2 flex h-7 w-7 items-center justify-center rounded-full bg-black/50 text-white hover:bg-black/70 transition-colors"
                onclick={() => void shelfEdit.handleCoverImport(shelfDetail)}
                aria-label={t('shelf.importCoverAria' as MessageKey)}
                ><svg
                  class="h-3.5 w-3.5"
                  fill="none"
                  stroke="currentColor"
                  viewBox="0 0 24 24"
                  stroke-width="2"
                  ><path
                    stroke-linecap="round"
                    stroke-linejoin="round"
                    d="M4 16v2a2 2 0 002 2h12a2 2 0 002-2v-2M7 10l5 5 5-5M12 15V3"
                  /></svg
                ></button
              >
            </div>
          {/if}
        </div>
        <div class="flex-1 min-w-0 divide-y divide-(--color-border)">
          {#if shelfEdit.isEditing}
            <section class="space-y-3 pb-4">
              <div>
                <label
                  for="edit-title"
                  class="mb-1 block text-xs font-medium text-(--color-primary)"
                  >{t('library.editMetadata.titleLabel' as MessageKey)}</label
                ><input
                  id="edit-title"
                  type="text"
                  bind:value={shelfEdit.editTitle}
                  class="w-full rounded-md border border-(--color-border) bg-(--color-background) px-3 py-2 text-sm text-(--color-primary) focus:border-(--color-primary) focus:outline-none"
                />
              </div>
              <div>
                <label
                  for="edit-author"
                  class="mb-1 block text-xs font-medium text-(--color-primary)"
                  >{t('library.editMetadata.authorLabel' as MessageKey)}</label
                ><input
                  id="edit-author"
                  type="text"
                  bind:value={shelfEdit.editAuthor}
                  class="w-full rounded-md border border-(--color-border) bg-(--color-background) px-3 py-2 text-sm text-(--color-primary) focus:border-(--color-primary) focus:outline-none"
                />
              </div>
              <div>
                <label
                  for="edit-genre"
                  class="mb-1 block text-xs font-medium text-(--color-primary)"
                  >{t('shelf.genreLabel' as MessageKey)}</label
                ><Dropdown
                  options={shelfEdit.genreOptions}
                  bind:value={shelfEdit.selectedGenre}
                  placeholder={t('shelf.selectGenre' as MessageKey)}
                  class="w-full"
                />{#if shelfEdit.selectedGenre === '__other__'}<input
                    id="edit-genre"
                    type="text"
                    bind:value={shelfEdit.customGenre}
                    maxlength={shelfEdit.maxGenreLength}
                    placeholder={t('shelf.customGenre' as MessageKey)}
                    class="mt-2 w-full rounded-md border border-(--color-border) bg-(--color-background) px-3 py-2 text-sm text-(--color-primary) focus:border-(--color-primary) focus:outline-none"
                  />{/if}
              </div>
              {#if shelfEdit.editError}<p class="text-sm text-(--color-error)">
                  {shelfEdit.editError}
                </p>{/if}
            </section>
          {:else}
            <section class="pb-4">
              <div class="grid grid-cols-2 gap-x-6 gap-y-2">
                <p class="text-xs text-(--color-text-muted)">
                  <span class="font-medium text-(--color-primary)"
                    >{t('shelf.authorLabel' as MessageKey)}</span
                  >{#if shelfDetail.author}
                    {shelfDetail.author}{:else}
                    <span class="italic opacity-60">{t('shelf.noAuthor' as MessageKey)}</span>{/if}
                </p>
                <p class="flex items-center gap-2 text-xs text-(--color-text-muted)">
                  <span class="font-medium text-(--color-primary) shrink-0"
                    >{t('shelf.formatLabel' as MessageKey)}</span
                  ><span
                    class="inline-flex items-center rounded-full px-2 py-0.5 text-xs font-medium border uppercase {shelfDetail.format ===
                    'epub'
                      ? 'bg-(--color-primary)/8 text-(--color-primary) border-(--color-primary)/25'
                      : 'bg-(--color-warning)/8 text-(--color-warning) border-(--color-warning)/25'}"
                    >{shelfDetail.format}</span
                  >
                </p>
                <p class="text-xs text-(--color-text-muted)">
                  <span class="font-medium text-(--color-primary)"
                    >{t('shelf.genreLabel' as MessageKey)}</span
                  >{#if shelfDetail.genre}
                    {shelfDetail.genre}{:else}
                    <span class="italic opacity-60">{t('shelf.noGenre' as MessageKey)}</span>{/if}
                </p>
                <p class="text-xs text-(--color-text-muted)">
                  <span class="font-medium text-(--color-primary)"
                    >{t('shelf.languageLabel' as MessageKey)}</span
                  >{#if shelfDetail.language}
                    {getLanguageName(shelfDetail.language, t)}{:else}
                    <span class="italic opacity-60">{t('shelf.noLanguage' as MessageKey)}</span
                    >{/if}
                </p>
              </div>
              <div class="flex flex-wrap items-center gap-2 pt-3">
                <div class="flex items-center gap-1.5">
                  <span class="text-xs text-(--color-text-muted)"
                    >{t('shelf.statusLabel' as MessageKey)}</span
                  ><Dropdown
                    options={STATUS_OPTIONS}
                    value={currentStatus}
                    onchange={({ value }) => {
                      onStatusChange(shelfDetail, value);
                    }}
                  />
                </div>
                {#if shelfDetail.collectionIds}{@const fav =
                    shelfDetail.collectionIds.includes(FAVORITES_COLLECTION_ID)}<button
                    type="button"
                    class="inline-flex items-center gap-1 rounded-full px-2.5 py-0.5 text-xs font-medium border transition-colors {fav
                      ? 'bg-(--color-warning)/15 text-(--color-warning) border-(--color-warning)/25'
                      : 'bg-(--color-surface-subtle) text-(--color-text-muted) border-(--color-border) hover:border-(--color-warning)/25'}"
                    onclick={() => void onToggleFavorite(shelfDetail)}
                    aria-label={fav
                      ? t('shelf.removeFavorite' as MessageKey)
                      : t('shelf.markFavorite' as MessageKey)}
                    ><svg class="h-3.5 w-3.5" viewBox="0 0 24 24" stroke-width="1.5"
                      >{#if fav}<path
                          fill="currentColor"
                          stroke="currentColor"
                          stroke-linecap="round"
                          stroke-linejoin="round"
                          d="M11.048 2.927c.3-.921 1.603-.921 1.902 0l1.519 4.674a1 1 0 00.95.69h4.915c.969 0 1.371 1.24.588 1.81l-3.976 2.888a1 1 0 00-.363 1.118l1.518 4.674c.3.922-.755 1.688-1.538 1.118l-3.976-2.888a1 1 0 00-1.176 0l-3.976 2.888c-.783.57-1.838-.197-1.538-1.118l1.518-4.674a1 1 0 00-.363-1.118l-3.976-2.888c-.784-.57-.38-1.81.588-1.81h4.914a1 1 0 00.951-.69l1.519-4.674z"
                        />{:else}<path
                          fill="none"
                          stroke="currentColor"
                          stroke-linecap="round"
                          stroke-linejoin="round"
                          d="M11.048 2.927c.3-.921 1.603-.921 1.902 0l1.519 4.674a1 1 0 00.95.69h4.915c.969 0 1.371 1.24.588 1.81l-3.976 2.888a1 1 0 00-.363 1.118l1.518 4.674c.3.922-.755 1.688-1.538 1.118l-3.976-2.888a1 1 0 00-1.176 0l-3.976 2.888c-.783.57-1.838-.197-1.538-1.118l1.518-4.674a1 1 0 00-.363-1.118l-3.976-2.888c-.784-.57-.38-1.81.588-1.81h4.914a1 1 0 00.951-.69l1.519-4.674z"
                        />{/if}</svg
                    >{t('shelf.favorite' as MessageKey)}</button
                  >{/if}{#if shelfDetail.readingStatus === 'completed'}<span
                    class="inline-flex items-center gap-1 rounded-full px-2.5 py-0.5 text-xs font-medium border bg-(--color-success)/10 text-(--color-success) border-(--color-success)/25"
                    ><svg
                      class="h-3 w-3"
                      fill="none"
                      stroke="currentColor"
                      viewBox="0 0 24 24"
                      stroke-width="2.5"
                      ><path
                        stroke-linecap="round"
                        stroke-linejoin="round"
                        d="M5 13l4 4L19 7"
                      /></svg
                    >{t('shelf.completed' as MessageKey)}</span
                  >{/if}
              </div>
            </section>
          {/if}
          <section class="space-y-3 py-4">
            <h3 class="text-xs font-semibold uppercase tracking-wider text-(--color-text-muted)">
              <Clock size={14} strokeWidth={1.8} class="inline -mt-0.5 mr-1" />{t(
                'shelf.readingLabel' as MessageKey,
              )}
            </h3>
            {#if progressPct > 0}<div class="space-y-1">
                <div class="flex items-center gap-1 text-2xs text-(--color-text-muted)">
                  <span>{t('shelf.progress' as MessageKey)} {progressPct}%</span>
                </div>
                <div class="h-1.5 w-full rounded-full bg-(--color-border)">
                  <div
                    class="h-1.5 rounded-full bg-(--color-primary) transition-all"
                    style="width: {progressPct}%"
                  ></div>
                </div>
                {#if shelfDetail.totalPages > 0}<p class="text-2xs text-(--color-text-muted)">
                    {t('shelf.pageOf' as MessageKey, {
                      current: shelfDetail.currentPage,
                      total: shelfDetail.totalPages,
                    })}
                  </p>{/if}
              </div>{/if}
            <dl class="flex flex-wrap gap-x-4 gap-y-1 text-xs text-(--color-text-muted)">
              {#if shelfDetail.minutesRead > 0}<dt class="sr-only">
                  {t('shelf.readingLabel' as MessageKey)}
                </dt>
                <dd class="font-medium text-(--color-primary)">
                  <Clock size={14} strokeWidth={1.8} class="inline -mt-0.5 mr-0.5" />{t(
                    'shelf.minutesRead' as MessageKey,
                    { minutes: formatMinutes(shelfDetail.minutesRead, t) },
                  )}
                </dd>{/if}{#if shelfDetail.updatedAt}<dt class="sr-only">
                  {t('shelf.details' as MessageKey)}
                </dt>
                <dd>{formatRelativeDate(shelfDetail.updatedAt, new Date(), t)}</dd>{/if}
            </dl>
            {#if shelfDetail.totalPages === 0 && shelfDetail.minutesRead === 0 && !shelfDetail.updatedAt}<p
                class="text-xs italic text-(--color-text-muted) opacity-60"
              >
                {t('shelf.noReadingData' as MessageKey)}
              </p>{/if}
          </section>
          <section class="space-y-3 pt-4">
            <h3 class="text-xs font-semibold uppercase tracking-wider text-(--color-text-muted)">
              <Info size={14} strokeWidth={1.8} class="inline -mt-0.5 mr-1" />{t(
                'shelf.details' as MessageKey,
              )}
            </h3>
            {#if shelfDetail.publicationDate}<p
                class="flex items-center gap-1.5 text-xs text-(--color-text-muted)"
              >
                <Calendar size={14} strokeWidth={1.8} class="shrink-0" />{t(
                  'shelf.published' as MessageKey,
                )}
                {formatPublicationDate(shelfDetail.publicationDate)}
              </p>{/if}{#if shelfDetail.createdAt}<p
                class="flex items-center gap-1.5 text-xs text-(--color-text-muted)"
              >
                <Calendar size={14} strokeWidth={1.8} class="shrink-0" />{t(
                  'shelf.added' as MessageKey,
                )}
                {formatRelativeDate(shelfDetail.createdAt, new Date(), t)}
              </p>{/if}{#if shelfDetail.collectionIds && shelfDetail.collectionIds.length > 0}{@const collNames =
                getCollectionNames(
                  shelfDetail.collectionIds,
                  collections,
                )}{#if collNames.length > 0}<div class="flex flex-wrap items-center gap-1.5">
                  <span class="text-xs text-(--color-text-muted)"
                    >{t('shelf.collections' as MessageKey)}</span
                  >{#each collNames as name}<span
                      class="inline-flex items-center rounded-md px-2 py-0.5 text-xs font-medium bg-(--color-primary)/8 text-(--color-primary) border border-(--color-primary)/15"
                      >{name}</span
                    >{/each}
                </div>{/if}{/if}
          </section>
        </div>
      </div>
    {/snippet}
    {#snippet footer()}
      {#if shelfEdit.isEditing}<Button
          size="sm"
          variant="ghost"
          onclick={shelfEdit.cancelEditing}
          disabled={shelfEdit.isSaving}>{t('highlight.cancel' as MessageKey)}</Button
        ><Button size="sm" onclick={shelfEdit.saveEditing} disabled={shelfEdit.isSaving}
          >{shelfEdit.isSaving
            ? t('shelf.saving' as MessageKey)
            : t('highlight.save' as MessageKey)}</Button
        >{:else}<Button
          size="sm"
          variant="ghost"
          onclick={() => shelfEdit.startEditing(shelfDetail)}
          ><SquarePen size={14} strokeWidth={1.8} /> {t('shelf.editMetadata' as MessageKey)}</Button
        ><Button size="sm" variant="ghost" onclick={onClose}
          >{t('common.close' as MessageKey)}</Button
        ><Button
          size="sm"
          onclick={() => {
            open = false;
            void onStartReading(shelfDetail);
          }}>{t('app.read' as MessageKey)}</Button
        >{/if}
    {/snippet}
  </Modal>
{/if}
