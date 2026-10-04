<script module lang="ts">
  /**
   * Dialogs currently open through this facade. Used to release bits-ui's body
   * scroll lock as soon as the last one disappears.
   *
   * bits-ui keeps `pointer-events: none` on `<body>` while a dialog is open and
   * only restores it ~24ms after the lock is destroyed (`body-scroll-lock`
   * `scheduleCleanupIfNoNewLocks`). When a dialog is unmounted while still open
   * — a route change tearing down the screen that owns it, for instance — the
   * whole page stays unclickable for that window. Releasing the lock once no
   * dialog is left makes that unmount safe.
   */
  let openDialogCount = 0;

  function releaseBodyLockIfUnused(): void {
    if (typeof document === 'undefined') return;
    // Another bits-ui dialog (including ones not built on this facade) may
    // still own the lock.
    if (document.querySelector('[data-dialog-content][data-state="open"]')) return;
    if (document.body.style.pointerEvents === 'none') document.body.style.pointerEvents = '';
    if (document.body.style.overflow === 'hidden') document.body.style.overflow = '';
  }
</script>

<script lang="ts">
  import type { Snippet } from 'svelte';
  import type { MessageKey } from '$lib/shared/i18n';
  import { i18n } from '$lib/shared/i18n';

  import { Dialog } from 'bits-ui';
  import { fly, fade } from 'svelte/transition';

  let locale = $state(i18n?.DEFAULT_LOCALE ?? 'es');
  $effect(() => {
    if (!i18n?.locale) return;
    const unsub = i18n.locale.subscribe((l) => {
      locale = l;
    });
    return () => unsub();
  });
  const tFn = (key: MessageKey): string => i18n?.t?.(locale, key) ?? key;

  type Props = {
    open: boolean;
    title: string;
    children?: Snippet;
    footer?: Snippet;
    size?: 'sm' | 'md' | 'lg' | 'xl';
    noCloseButton?: boolean;
    class?: string;
    /**
     * Take the dialog's initial focus. Forwarded to bits-ui's content, whose
     * default is the first focusable element — the close button here. Call
     * `event.preventDefault()` and focus what you want instead.
     */
    onOpenAutoFocus?: (event: Event) => void;
  };

  let {
    open = $bindable(false),
    title,
    children,
    footer,
    size = 'md',
    noCloseButton = false,
    class: className = '',
    onOpenAutoFocus,
  }: Props = $props();

  // Track open dialogs so an unmount-while-open releases the body lock
  // synchronously instead of waiting for bits-ui's delayed cleanup.
  $effect(() => {
    if (!open) return;
    openDialogCount += 1;
    return () => {
      openDialogCount -= 1;
      if (openDialogCount === 0) queueMicrotask(releaseBodyLockIfUnused);
    };
  });

  const sizeClass = $derived(
    size === 'sm'
      ? 'max-w-sm'
      : size === 'lg'
        ? 'max-w-2xl'
        : size === 'xl'
          ? 'max-w-4xl'
          : 'max-w-lg',
  );
</script>

<Dialog.Root bind:open>
  <Dialog.Portal>
    <Dialog.Overlay class="fixed inset-0 z-50 flex items-center justify-center bg-black/50 p-4">
      <!--
        Svelte 5 rejects `transition:` on a component ("This type of directive is
        not valid on components"), so the overlay/content transitions are carried
        by the elements the `child` snippets render. Each element receives
        bits-ui's own props (ref, role, aria, data-state), which is why the
        classes still land on the same element as before.
      -->
      {#snippet child({ props })}
        <div {...props} transition:fade={{ duration: 200 }}>
          <Dialog.Content
            {onOpenAutoFocus}
            class="flex max-h-[calc(100vh-2rem)] w-full {sizeClass} flex-col overflow-hidden rounded-xl border border-(--color-border) bg-(--color-elevated) shadow-xl {className}"
          >
            {#snippet child({ props })}
              <div {...props} transition:fly={{ duration: 200, opacity: 0, y: -20 }}>
                <div
                  class="flex shrink-0 items-center justify-between border-b border-(--color-border) px-6 py-4"
                >
                  <Dialog.Title
                    id="modal-title"
                    level={2}
                    class="text-lg font-semibold text-(--color-primary)"
                  >
                    {#snippet child({ props })}
                      <h2 {...props}>{title}</h2>
                    {/snippet}
                  </Dialog.Title>
                  {#if !noCloseButton}
                    <button
                      class="flex items-center justify-center min-w-7 min-h-7 text-(--color-text-muted) transition-colors hover:text-(--color-primary)"
                      onclick={() => (open = false)}
                      aria-label={tFn('modal.closeAria')}
                    >
                      <svg class="h-5 w-5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                        <path
                          stroke-linecap="round"
                          stroke-linejoin="round"
                          stroke-width="2"
                          d="M6 18L18 6M6 6l12 12"
                        />
                      </svg>
                    </button>
                  {/if}
                </div>

                <!--
                  The body is the single scroll region: the dialog is height-bounded and
                  flex-column, so an over-tall body scrolls here while the header and
                  footer stay pinned. `tabindex="0"` + `role="region"` keep the scroll
                  region reachable by keyboard.
                -->
                <!-- svelte-ignore a11y_no_noninteractive_tabindex, a11y_no_noninteractive_element_interactions -->
                <div
                  class="min-h-0 flex-1 overflow-y-auto px-6 py-4"
                  tabindex="0"
                  role="region"
                  aria-label={title}
                >
                  {#if children}
                    {@render children()}
                  {/if}
                </div>

                {#if footer}
                  <div
                    class="flex shrink-0 items-center justify-end gap-3 border-t border-(--color-border) px-6 py-4"
                  >
                    {@render footer()}
                  </div>
                {/if}
              </div>
            {/snippet}
          </Dialog.Content>
        </div>
      {/snippet}
    </Dialog.Overlay>
  </Dialog.Portal>
</Dialog.Root>
