<script lang="ts">
  import Modal from '$lib/shared/ui/layout/Modal.svelte';

  // Mirrors a caller modal (EditMetadataModal et al.): it receives `open` and
  // `onClose` from its parent, does `bind:open` on the facade, and forwards a
  // facade close back to `onClose` through `onOpenChange`.
  type Props = {
    open: boolean;
    onClose: () => void;
  };

  let { open, onClose }: Props = $props();
</script>

<Modal
  bind:open
  title="Contract dialog"
  onOpenChange={(v) => {
    if (!v) onClose();
  }}
>
  <p>contract body</p>
</Modal>
