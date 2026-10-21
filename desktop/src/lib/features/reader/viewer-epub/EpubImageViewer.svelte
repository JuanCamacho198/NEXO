<script lang="ts">
  /**
   * Full-screen image viewer for the EPUB reader.
   *
   * Lives beside `EpubNativeViewer` rather than inside it: that component is at
   * its size ceiling, and none of this needs the chapter frame — the parent
   * hands over a resolved URL and the component owns zoom, drag and dismissal.
   *
   * Built as a plain fixed overlay rather than `<dialog>.showModal()` so the
   * element stays in normal DOM order and the parent's keyboard handling still
   * runs; the backdrop, Escape and focus return are handled here instead, because
   * the reader's own Escape handling (footnote, then immersive exit) would
   * otherwise consume the key first.
   */
  import type { MessageKey } from '$lib/shared/i18n';

  type Props = {
    /** Resolved image URL, or null when nothing is open. */
    src: string | null;
    /** Accessible name for the dialog and the image. */
    alt: string;
    onClose: () => void;
    t: (key: MessageKey, params?: Record<string, string | number>) => string;
  };

  let { src, alt, onClose, t }: Props = $props();

  /** Zoom steps, in order. 1 is "fit the viewport". */
  const ZOOM_STEPS = [1, 1.5, 2, 3, 4] as const;
  const MIN_ZOOM = 0.5;
  const MAX_ZOOM = 6;

  let zoom = $state(1);
  let offsetX = $state(0);
  let offsetY = $state(0);
  let dragging = $state(false);
  let dialogEl = $state<HTMLDivElement | null>(null);
  let imgEl = $state<HTMLImageElement | null>(null);
  // Pointer id of the active drag. A mouse and a touch must not both steer.
  let dragPointer: number | null = null;
  let dragStart = { x: 0, y: 0, offsetX: 0, offsetY: 0 };
  /** The element focus came from, so closing can put it back. */
  let returnFocusTo: HTMLElement | null = null;

  $effect(() => {
    if (!src) return;
    returnFocusTo = document.activeElement as HTMLElement | null;
    // Reset on every open: a viewer reopened on a new plate must not inherit the
    // previous plate's pan, which would point off-screen.
    zoom = 1;
    offsetX = 0;
    offsetY = 0;
    // Focus the dialog itself, never the image: a focused <img> would take the
    // arrow keys the reader uses to turn pages.
    dialogEl?.focus();
    return () => {
      // Restore focus to where the reader was. The chapter iframe cannot hold
      // parent focus, so the reader's root (a tabindex="-1" container) is
      // usually what comes back — which is exactly what makes arrow paging work
      // again after closing.
      returnFocusTo?.focus?.();
      returnFocusTo = null;
    };
  });

  function clampOffset(): void {
    // Pan is only meaningful when the scaled image overflows the viewport; at or
    // below fit, any offset would just drift the picture off-centre.
    const img = imgEl;
    if (!img) return;
    const rect = img.getBoundingClientRect();
    const maxX = Math.max(0, (rect.width - window.innerWidth) / 2);
    const maxY = Math.max(0, (rect.height - window.innerHeight) / 2);
    offsetX = Math.max(-maxX, Math.min(maxX, offsetX));
    offsetY = Math.max(-maxY, Math.min(maxY, offsetY));
  }

  function setZoom(next: number, origin?: { x: number; y: number }): void {
    const clamped = Math.max(MIN_ZOOM, Math.min(MAX_ZOOM, next));
    const img = imgEl;
    if (img && origin) {
      // Keep the point under the cursor fixed across a wheel zoom. Without this
      // the picture slides away from what the reader was looking at.
      const rect = img.getBoundingClientRect();
      const cx = rect.left + rect.width / 2;
      const cy = rect.top + rect.height / 2;
      const ratio = clamped / zoom;
      offsetX = offsetX + (origin.x - cx) * (1 - ratio);
      offsetY = offsetY + (origin.y - cy) * (1 - ratio);
    }
    zoom = clamped;
    clampOffset();
  }

  function zoomBySteps(direction: 1 | -1): void {
    const index = ZOOM_STEPS.findIndex((step) => step >= zoom - 1e-3);
    const current = index === -1 ? ZOOM_STEPS.length - 1 : index;
    const next = ZOOM_STEPS[Math.max(0, Math.min(ZOOM_STEPS.length - 1, current + direction))];
    setZoom(next);
    offsetX = 0;
    offsetY = 0;
  }

  function handleWheel(event: WheelEvent): void {
    event.preventDefault();
    const step = event.deltaY > 0 ? -0.2 : 0.2;
    setZoom(zoom + step, { x: event.clientX, y: event.clientY });
  }

  function handlePointerDown(event: PointerEvent & { currentTarget: EventTarget | null }): void {
    // Only the picture drags. A press on the backdrop is a dismissal, and a
    // press on a control must not start a pan. Node name, not `instanceof`:
    // this component renders in the parent document, so here the check is
    // same-realm, but the string form cannot silently start failing if the
    // stage is ever moved inside a frame.
    if ((event.target as Element | null)?.nodeName?.toLowerCase() !== 'img') return;
    dragPointer = event.pointerId;
    dragging = true;
    dragStart = { x: event.clientX, y: event.clientY, offsetX, offsetY };
    (event.currentTarget as HTMLElement).setPointerCapture?.(event.pointerId);
  }

  function handlePointerMove(event: PointerEvent): void {
    if (!dragging || dragPointer !== event.pointerId) return;
    offsetX = dragStart.offsetX + (event.clientX - dragStart.x);
    offsetY = dragStart.offsetY + (event.clientY - dragStart.y);
    clampOffset();
  }

  function handlePointerUp(event: PointerEvent): void {
    if (dragPointer !== event.pointerId) return;
    dragging = false;
    dragPointer = null;
  }

  function handleKeydown(event: KeyboardEvent): void {
    if (!src) return;
    // Stop at the viewer: the reader's own handler walks arrows into page turns
    // and Escape into the immersive-chrome exit. One key, one layer.
    if (event.key === 'Escape') {
      event.preventDefault();
      event.stopImmediatePropagation();
      onClose();
      return;
    }
    if (event.key === '+' || event.key === '=') {
      event.preventDefault();
      event.stopImmediatePropagation();
      zoomBySteps(1);
      return;
    }
    if (event.key === '-') {
      event.preventDefault();
      event.stopImmediatePropagation();
      zoomBySteps(-1);
      return;
    }
    if (event.key === '0') {
      event.preventDefault();
      event.stopImmediatePropagation();
      zoom = 1;
      offsetX = 0;
      offsetY = 0;
    }
  }

  function handleBackdropClick(event: MouseEvent & { currentTarget: EventTarget | null }): void {
    // The dialog box covers the viewport, so "the backdrop" is any target that
    // is the dialog itself rather than the figure or a control inside it.
    if (event.target === event.currentTarget) onClose();
  }
</script>

<svelte:window onkeydown={handleKeydown} />

{#if src}
  <div
    bind:this={dialogEl}
    role="dialog"
    aria-modal="true"
    aria-label={alt || t('epub.imageViewer')}
    data-testid="epub-image-viewer"
    tabindex="-1"
    class="fixed inset-0 z-70 flex flex-col bg-black/90 outline-none"
    onpointerdown={handlePointerDown}
    onpointermove={handlePointerMove}
    onpointerup={handlePointerUp}
    onpointercancel={handlePointerUp}
    onwheel={handleWheel}
    onclick={handleBackdropClick}
    onkeydown={(event) => {
      // The dialog is focusable so it can receive Escape and the zoom keys; the
      // backdrop-dismiss semantics are the click handler above, and this exists
      // so the a11y contract on a role="dialog" element is explicit.
      if (event.key === 'Enter' || event.key === ' ') onClose();
    }}
    ondblclick={() => setZoom(zoom > 1 ? 1 : 2)}
  >
    <div class="pointer-events-none absolute inset-x-0 top-0 flex justify-end p-3">
      <div
        class="pointer-events-auto flex items-center gap-1 rounded-full bg-white/10 backdrop-blur-sm"
      >
        <button
          type="button"
          aria-label={t('epub.imageZoomOut')}
          class="cursor-pointer px-3 py-2 text-lg leading-none text-white/90 hover:text-white"
          onclick={() => zoomBySteps(-1)}
        >
          &minus;
        </button>
        <button
          type="button"
          aria-label={t('epub.imageZoomReset')}
          class="min-w-12 cursor-pointer px-2 py-2 text-xs leading-none text-white/90 hover:text-white"
          onclick={() => {
            zoom = 1;
            offsetX = 0;
            offsetY = 0;
          }}
        >
          {Math.round(zoom * 100)}%
        </button>
        <button
          type="button"
          aria-label={t('epub.imageZoomIn')}
          class="cursor-pointer px-3 py-2 text-lg leading-none text-white/90 hover:text-white"
          onclick={() => zoomBySteps(1)}
        >
          +
        </button>
        <button
          type="button"
          aria-label={t('common.close')}
          data-testid="epub-image-viewer-close"
          class="cursor-pointer px-3 py-2 text-lg leading-none text-white/90 hover:text-white"
          onclick={() => onClose()}
        >
          &times;
        </button>
      </div>
    </div>

    <div class="flex h-full w-full items-center justify-center overflow-hidden">
      <img
        bind:this={imgEl}
        {src}
        {alt}
        draggable="false"
        data-testid="epub-image-viewer-img"
        class="max-h-full max-w-full origin-center select-none"
        class:cursor-grab={zoom > 1 && !dragging}
        class:cursor-grabbing={dragging}
        style="transform: translate({offsetX}px, {offsetY}px) scale({zoom}); transition: {dragging
          ? 'none'
          : 'transform 120ms ease-out'};"
      />
    </div>
  </div>
{/if}
