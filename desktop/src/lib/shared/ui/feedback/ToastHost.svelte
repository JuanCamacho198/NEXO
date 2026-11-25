<script lang="ts">
  import Toast from './Toast.svelte';
  import { toastQueue, dismiss, type ToastItem } from '$lib/shared/stores/ToastQueue.svelte';
  import { openNotification } from '$lib/shared/services/notificationNavigation';

  /**
   * Runs a toast's callback action, or follows its notification deep link: marks the entry read,
   * navigates when its target resolves, and dismisses the toast either way.
   * Unknown targets degrade safely (read + dismissed, no navigation).
   */
  function handleAction(toast: ToastItem): void {
    const action = toast.action;
    if (!action) return;
    if (action.run) action.run();
    else openNotification({ id: action.notificationId, target: action.target });
    dismiss(toast.id);
  }
</script>

<!-- Every toast in one column, bottom centre, newest at the bottom: they
     stack instead of landing on top of each other in a corner, and they sit
     above the reader's bottom bar instead of under a menu. -->
<div
  class="pointer-events-none fixed inset-x-0 bottom-20 z-[200] flex flex-col items-center gap-2 px-4"
  aria-live="polite"
>
  {#each toastQueue.items as toast (toast.id)}
    <Toast
      type={toast.type}
      message={toast.message}
      visible={true}
      stacked
      onDismiss={() => dismiss(toast.id)}
    >
      {#snippet action()}
        {#if toast.action}
          <button
            type="button"
            class="cursor-pointer rounded-full px-3 py-1.5 text-sm font-semibold text-(--color-brand-text) transition-colors hover:bg-(--color-brand-soft) focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-(--color-brand)"
            onclick={() => handleAction(toast)}
          >
            {toast.action.label}
          </button>
        {/if}
      {/snippet}
    </Toast>
  {/each}
</div>
