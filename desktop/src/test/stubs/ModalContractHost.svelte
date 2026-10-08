<script lang="ts">
  import ModalContractStub from './ModalContractStub.svelte';

  // Mirrors AppModals: it owns the domain flag and passes it ONE-WAY as
  // `open`, clearing it from `onClose`. A facade close must therefore reach
  // this flag or the modal can never reopen.
  type Props = {
    onclose?: () => void;
  };

  let { onclose }: Props = $props();

  let flag = $state(true);
</script>

<button id="reopen" onclick={() => (flag = true)}>reopen</button>
<ModalContractStub
  open={flag}
  onClose={() => {
    flag = false;
    onclose?.();
  }}
/>
