<script lang="ts">
  import Toast from './Toast.svelte';
  import { toastQueue, dismiss, type ToastItem } from '$lib/shared/stores/ToastQueue.svelte';
  import { openNotification } from '$lib/shared/services/notificationNavigation';

  /**
   * Follows a toast's notification deep link: marks the entry read,
   * navigates when its target resolves, and dismisses the toast either way.
   * Unknown targets degrade safely (read + dismissed, no navigation).
   */
  function handleAction(toast: ToastItem): void {
    const action = toast.action;
    if (!action) return;
    openNotification({ id: action.notificationId, target: action.target });
    dismiss(toast.id);
  }
</script>

{#each toastQueue.items as toast (toast.id)}
  {#if toast.action}
    <Toast
      type={toast.type}
      message={toast.message}
      visible={true}
      onDismiss={() => dismiss(toast.id)}
    >
      {#snippet action()}
        <button
          type="button"
          class="rounded-lg border border-(--color-border) bg-(--color-surface) px-3 py-1.5 text-sm font-medium text-(--color-primary) transition-colors hover:bg-(--color-panel-accent)"
          onclick={() => handleAction(toast)}
        >
          {toast.action?.label}
        </button>
      {/snippet}
    </Toast>
  {:else}
    <Toast
      type={toast.type}
      message={toast.message}
      visible={true}
      onDismiss={() => dismiss(toast.id)}
    ></Toast>
  {/if}
{/each}
