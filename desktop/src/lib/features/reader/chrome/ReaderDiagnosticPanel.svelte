<script lang="ts">
  import type { ReaderDiagnostics } from './readerDiagnostics';
  import { READER_DIAGNOSTICS_LABEL } from './readerDiagnostics';

  type Props = {
    /** Read-only snapshot; the panel never writes back to the reader. */
    diagnostics: ReaderDiagnostics & { enabled: boolean };
  };

  let { diagnostics }: Props = $props();

  const rows = $derived<Array<{ label: string; value: string }>>([
    { label: 'Mode', value: diagnostics.mode },
    { label: 'Page', value: `${diagnostics.page}/${diagnostics.totalPages}` },
    { label: 'scrollLeft', value: String(Math.round(diagnostics.scrollLeft)) },
    { label: 'Layout', value: diagnostics.layoutSettled ? 'SETTLED' : 'SETTLING' },
    { label: 'Restoring CFI', value: diagnostics.restoring ? 'YES' : 'NO' },
  ]);
</script>

<!--
  Opt-in diagnostic readout. Invisible unless the reader was started with the
  diagnostic setting on, so it can never become a product surface. Read-only by
  construction: it renders the numbers it is handed and writes nothing back.
-->
{#if diagnostics.enabled}
  <div
    data-testid="reader-diagnostics"
    role="status"
    aria-live="off"
    class="pointer-events-none fixed left-4 bottom-16 z-9999 rounded-lg border border-(--color-border) bg-(--color-surface)/95 px-3 py-2 font-mono text-xs text-(--color-text-muted) shadow-lg backdrop-blur-sm"
  >
    <h2 class="mb-1 text-micro uppercase tracking-wider">{READER_DIAGNOSTICS_LABEL}</h2>
    <dl class="grid grid-cols-[auto_auto] gap-x-3 gap-y-0.5">
      {#each rows as row (row.label)}
        <dt>{row.label}</dt>
        <dd
          data-testid={`reader-diag-${row.label.toLowerCase().replace(/ /g, '-')}`}
          class="text-(--color-primary)"
        >
          {row.value}
        </dd>
      {/each}
    </dl>
  </div>
{/if}
