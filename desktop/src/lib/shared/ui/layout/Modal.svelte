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
    /**
     * Optional supporting text announced after the title. Rendered as
     * bits-ui's `Dialog.Description` and linked through `aria-describedby`, so
     * a dialog that must explain a consequence states it to assistive tech as
     * well as on screen. Kept inside the body scroll region.
     */
    description?: string;
    children?: Snippet;
    footer?: Snippet;
    size?: 'sm' | 'md' | 'lg' | 'xl';
    /**
     * Dialog layer. `app` (default) paints above the menu/popover layer so no
     * menu can stay interactable over an open dialog; `reader` lifts a dialog
     * above the reader chrome it overlays. See the layer tokens in tokens.css
     * for the documented stacking order.
     */
    layer?: 'app' | 'reader';
    noCloseButton?: boolean;
    /**
     * Accessible name for the header close button. Defaults to the shared
     * `modal.closeAria` message; a migrated caller whose close control has
     * historically carried a different label passes it here to keep its
     * existing contract (e.g. NoteEditorModal uses its `highlight.cancel`).
     */
    closeLabel?: string;
    class?: string;
    /**
     * Take the dialog's initial focus. Forwarded to bits-ui's content, whose
     * default is the first focusable element — the close button here. Call
     * `event.preventDefault()` and focus what you want instead.
     */
    onOpenAutoFocus?: (event: Event) => void;
    /**
     * Reports every close of the dialog to the caller, exactly once per close.
     *
     * Callers that only forward a one-way `open` prop (a domain flag rendered
     * as `open={flag !== null}`) need this to clear that flag: a facade-close
     * (X, Escape, backdrop, or the X handler) writes the bound `open` locally,
     * which never reaches the parent flag, so without this the dialog could
     * never reopen. `false` is the only value the facade emits on its own.
     */
    onOpenChange?: (open: boolean) => void;
  };

  let {
    open = $bindable(false),
    title,
    description,
    children,
    footer,
    size = 'md',
    layer = 'app',
    noCloseButton = false,
    closeLabel,
    class: className = '',
    onOpenAutoFocus,
    onOpenChange,
  }: Props = $props();

  // Per-instance ids. bits-ui would generate its own, but the facade used to
  // hardcode `modal-title`, so two simultaneous dialogs exposed the same id and
  // the inner dialog's `aria-labelledby` resolved to the outer dialog's title.
  // A runes id keeps title and description unique per mounted dialog.
  const uid = $props.id();
  const titleId = `${uid}-title`;
  const descriptionId = `${uid}-description`;

  // Svelte JS transitions ignore the global `prefers-reduced-motion` CSS block
  // (that block only shortens CSS transitions/animations), so the facade reads
  // the media query itself and zeroes the transition durations.
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

  // Track open dialogs so an unmount-while-open releases the body lock
  // synchronously instead of waiting for bits-ui's delayed cleanup.
  $effect(() => {
    if (!open) return;
    // A menu/popover can outlive the pointer interaction that opened a dialog
    // (a programmatic open), leaving it painted over the dialog. Announce the
    // open so those surfaces can dismiss themselves; ShelfActionMenu listens.
    if (typeof window !== 'undefined') window.dispatchEvent(new CustomEvent('np:dialog-open'));
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

  const layerClass = $derived(
    layer === 'reader' ? 'z-(--layer-dialog-reader)' : 'z-(--layer-dialog)',
  );

  /**
   * Single funnel for every close path. bits-ui drives this from its own
   * `onOpenChange` (Escape / backdrop); the X button calls it directly.
   * bits-ui's internal `handleClose` is a no-op while already closed, so one
   * interaction yields exactly one notification.
   */
  function handleOpenChange(value: boolean): void {
    open = value;
    onOpenChange?.(value);
  }
</script>

<Dialog.Root bind:open onOpenChange={handleOpenChange}>
  <Dialog.Portal>
    <Dialog.Overlay
      class="fixed inset-0 {layerClass} flex items-center justify-center bg-black/50 p-4"
    >
      <!--
        Svelte 5 rejects `transition:` on a component ("This type of directive is
        not valid on components"), so the overlay/content transitions are carried
        by the elements the `child` snippets render. Each element receives
        bits-ui's own props (ref, role, aria, data-state), which is why the
        classes still land on the same element as before.
      -->
      {#snippet child({ props })}
        <div {...props} transition:fade={{ duration: reducedMotion ? 0 : 200 }}>
          <Dialog.Content
            {onOpenAutoFocus}
            class="flex max-h-[calc(100vh-2rem)] w-full {sizeClass} flex-col overflow-hidden rounded-xl border border-(--color-border) bg-(--color-elevated) {className}"
          >
            {#snippet child({ props })}
              <div
                {...props}
                transition:fly={{ duration: reducedMotion ? 0 : 200, opacity: 0, y: -20 }}
              >
                <div
                  class="flex shrink-0 items-center justify-between border-b border-(--color-border) px-6 py-4"
                >
                  <Dialog.Title
                    id={titleId}
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
                      onclick={() => handleOpenChange(false)}
                      aria-label={closeLabel ?? tFn('modal.closeAria')}
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
                  region reachable by keyboard. `modal-scroll-region` swaps the global
                  focus ring to an inset offset so the rounded `overflow-hidden` panel
                  cannot clip it.
                -->
                <!-- svelte-ignore a11y_no_noninteractive_tabindex -->
                <div
                  class="modal-scroll-region min-h-0 flex-1 overflow-y-auto px-6 py-4"
                  tabindex="0"
                  role="region"
                  aria-label={title}
                >
                  {#if description}
                    <Dialog.Description id={descriptionId}>
                      {#snippet child({ props })}
                        <p {...props} class="mb-3 text-sm text-(--color-text-muted)">
                          {description}
                        </p>
                      {/snippet}
                    </Dialog.Description>
                  {/if}
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
