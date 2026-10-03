<script lang="ts">
  import { onMount } from 'svelte';
  import { debugState } from './debugState.svelte';
  import { TauriViewerAdapter } from '$lib/shared/ports/adapters/tauri/TauriViewerAdapter';
  import { epubCache } from '$lib/features/reader/viewer-epub/epubCache';
  import { documentCache } from '$lib/features/reader/viewer-pdf/pdfStreaming';
  import { metricsStore } from '$lib/shared/logger/MetricsStore';
  import { createDebugIpcStats } from './useDebugIpcStats.svelte';
  import { openDevTools } from './devtoolsClient';
  import { type as osType } from '@tauri-apps/plugin-os';
  import type { DiagnoseResult } from '$lib/shared/types';
  const viewerPort = new TauriViewerAdapter();
  let logsLoading = $state(false);
  let diagnoseResult = $state<DiagnoseResult | null>(null);
  let diagnoseLoading = $state(false);
  const ipc = createDebugIpcStats();
  const CHART_W = 340;
  const handleOpenDevTools = async (): Promise<void> => {
    await openDevTools();
  };
  // DevTools entry point: F12 and Ctrl+Shift+I (Cmd+Option+I on macOS) are
  // handled unconditionally so the inspector is reachable in release builds.
  onMount(() => {
    let isMac = false;
    try {
      isMac = osType() === 'macos';
    } catch {
      isMac = false;
    }
    const handleDevToolsShortcut = (event: KeyboardEvent): void => {
      const key = event.key.toLowerCase();
      const isInspect =
        event.key === 'F12' ||
        (isMac
          ? event.metaKey && event.altKey && key === 'i'
          : event.ctrlKey && event.shiftKey && key === 'i');
      if (!isInspect) return;
      event.preventDefault();
      void handleOpenDevTools();
    };
    window.addEventListener('keydown', handleDevToolsShortcut);
    return () => window.removeEventListener('keydown', handleDevToolsShortcut);
  });
  const handleExportLogs = async (): Promise<void> => {
    logsLoading = true;
    try {
      const lines = await viewerPort.getLogs();
      const blob = new Blob([lines.join('\n')], { type: 'application/jsonl' });
      const url = URL.createObjectURL(blob);
      const a = document.createElement('a');
      a.href = url;
      a.download = `nexo-logs-${new Date().toISOString().slice(0, 10)}.jsonl`;
      a.click();
      URL.revokeObjectURL(url);
    } catch (e) {
      console.error('Failed to export logs:', e);
    } finally {
      logsLoading = false;
    }
  };
  const handleDiagnose = async (): Promise<void> => {
    diagnoseLoading = true;
    try {
      diagnoseResult = await viewerPort.diagnose();
    } catch (e) {
      console.error('Diagnose failed:', e);
    } finally {
      diagnoseLoading = false;
    }
  };
</script>

{#if debugState.enabled}
  <div
    class="fixed top-4 right-4 z-9999 w-96 max-h-[85vh] overflow-y-auto rounded-lg border bg-(--color-surface) text-xs font-mono text-(--color-primary) shadow-xl"
    role="region"
    aria-label="Debug information"
  >
    <div class="border-b border-(--color-border) p-3">
      <div class="flex items-start justify-between gap-2">
        <div class="min-w-0 flex-1">
          <h4 class="mb-1 text-micro uppercase tracking-wider text-(--color-text-muted)">Route</h4>
          <p class="text-sm">{debugState.currentRoute || 'Unknown Route'}</p>
        </div>
        <button
          type="button"
          class="shrink-0 rounded px-2 py-0.5 text-micro font-medium bg-(--color-primary) text-white hover:opacity-80"
          onclick={() => void debugState.copySnapshot()}
          title="Copy debug state to clipboard">Copy State</button
        >
      </div>
      <div class="mt-2 flex items-baseline gap-3">
        <p class="text-micro text-(--color-text-muted)">
          <span class="font-semibold text-(--color-primary)">{debugState.viewportWidth}</span> ×
          <span class="font-semibold text-(--color-primary)">{debugState.viewportHeight}</span>
        </p>
        <span
          class="rounded bg-(--color-border) px-1.5 py-0.5 text-[9px] font-mono font-semibold text-(--color-text-muted)"
          >{debugState.breakpoint}</span
        >
      </div>
    </div>
    <div class="border-b border-(--color-border) p-3">
      <div class="flex items-center justify-between">
        <h4 class="text-micro uppercase tracking-wider text-(--color-text-muted)">DevTools</h4>
        <button
          type="button"
          class="shrink-0 rounded px-2 py-0.5 text-micro font-medium bg-(--color-primary) text-white hover:opacity-80"
          onclick={() => void handleOpenDevTools()}
          title="Open the webview inspector">Open DevTools</button
        >
      </div>
      <p class="mt-1 text-[9px] text-(--color-text-muted)">
        Shortcuts: F12 · Ctrl+Shift+I (Cmd+Option+I on macOS)
      </p>
    </div>
    <div class="border-b border-(--color-border) p-3">
      <div class="flex items-center justify-between">
        <h4 class="text-micro uppercase tracking-wider text-(--color-text-muted)">
          IPC Performance
        </h4>
        <span class="text-[9px] text-(--color-text-muted)">refreshing…</span>
      </div>
      {#if ipc.ipcCalls.length === 0}<p class="mt-1 text-(--color-text-muted)">No IPC calls yet</p>
      {:else}
        <div class="mt-2 grid grid-cols-4 gap-1.5">
          <div class="rounded bg-[rgba(255,255,255,0.04)] p-1.5 text-center">
            <p class="text-[9px] text-(--color-text-muted)">Calls</p>
            <p class="text-sm font-bold">{ipc.ipcSummary.totalCalls}</p>
          </div>
          <div class="rounded bg-[rgba(255,255,255,0.04)] p-1.5 text-center">
            <p class="text-[9px] text-(--color-text-muted)">Avg</p>
            <p class="text-sm font-bold">
              {ipc.ipcSummary.avgDuration}<span class="text-[9px] font-normal">ms</span>
            </p>
          </div>
          <div class="rounded bg-[rgba(255,255,255,0.04)] p-1.5 text-center">
            <p class="text-[9px] text-(--color-text-muted)">Max</p>
            <p class="text-sm font-bold">
              {ipc.ipcSummary.maxDuration}<span class="text-[9px] font-normal">ms</span>
            </p>
          </div>
          <div class="rounded bg-[rgba(255,255,255,0.04)] p-1.5 text-center">
            <p class="text-[9px] text-(--color-text-muted)">Ok</p>
            <p
              class="text-sm font-bold"
              class:text-green-500={ipc.ipcSummary.successRate >= 95}
              class:text-yellow-500={ipc.ipcSummary.successRate < 95}
            >
              {ipc.ipcSummary.successRate}%
            </p>
          </div>
        </div>
        {#if ipc.recentCalls.length > 1}<div class="mt-2">
            <p class="mb-1 text-[9px] text-(--color-text-muted)">
              Recent calls (last {ipc.recentCalls.length})
            </p>
            <div class="rounded bg-(--color-surface-subtle) p-2">
              <svg viewBox={`0 0 ${CHART_W} 68`} class="h-17 w-full"
                >{#each ipc.recentCalls as call, i}{@const barW =
                    (CHART_W - (ipc.recentCalls.length - 1) * 2) /
                    ipc.recentCalls.length}{@const barH =
                    call.durationMs != null
                      ? Math.max(2, (call.durationMs / ipc.ipcSummary.maxDuration) * 56)
                      : 2}{@const x = i * (barW + 2)}{@const y = 64 - barH}<rect
                    {x}
                    {y}
                    width={Math.max(3, barW)}
                    height={barH}
                    rx="1.5"
                    fill={call.success ? '#3388ff' : '#ef4444'}
                    opacity={call.success ? 0.7 : 0.9}
                    ><title
                      >{call.feature}: {call.durationMs}ms {call.success ? 'ok' : 'fail'}</title
                    ></rect
                  >{/each}</svg
              >
            </div>
          </div>{/if}
        <div class="mt-2">
          <p class="mb-1 text-[9px] text-(--color-text-muted)">By command</p>
          <div class="space-y-1">
            {#each ipc.ipcCommands as cmd}{@const maxCount = Math.max(
                ...ipc.ipcCommands.map((c) => c.count),
                1,
              )}
              <div class="rounded bg-(--color-surface-subtle) p-1.5">
                <div class="flex items-center justify-between gap-1">
                  <span class="truncate text-micro" title={cmd.feature}>{cmd.feature}</span><span
                    class="shrink-0 text-micro font-semibold">{cmd.count}x</span
                  >
                </div>
                <div class="mt-0.5 flex items-center gap-2">
                  <div
                    class="h-1.5 flex-1 overflow-hidden rounded-full bg-(--color-surface-subtle)"
                  >
                    <div
                      class="h-full rounded-full"
                      class:bg-(--color-success)={cmd.successRate >= 95}
                      class:bg-(--color-warning)={cmd.successRate >= 80 && cmd.successRate < 95}
                      class:bg-[#ef4444]={cmd.successRate < 80}
                      style={`width: ${(cmd.count / Math.max(maxCount, 1)) * 100}%`}
                    ></div>
                  </div>
                  <span class="shrink-0 text-[9px] text-(--color-text-muted)"
                    >{cmd.avgDuration}ms</span
                  >
                </div>
                <div class="mt-0.5 flex gap-2 text-[9px] text-(--color-text-muted)">
                  <span>min {cmd.minDuration}ms</span><span>p50 {cmd.p50Duration}ms</span><span
                    >max {cmd.maxDuration}ms</span
                  ><span
                    class:text-green-500={cmd.successRate >= 95}
                    class:text-yellow-500={cmd.successRate < 95}>{cmd.successRate}% ok</span
                  >
                </div>
              </div>{/each}
          </div>
        </div>
      {/if}
    </div>
    <div class="border-b border-(--color-border) p-3">
      <h4 class="mb-1 text-micro uppercase tracking-wider text-(--color-text-muted)">Session</h4>
      <p class="truncate" title={metricsStore.getSessionId()}>
        ID: <span class="font-semibold">{metricsStore.getSessionId().slice(0, 8)}…</span>
      </p>
      <p>Metrics: <span class="font-semibold">{metricsStore.getAll().length}</span></p>
    </div>
    <div class="border-b border-(--color-border) p-3">
      <h4 class="mb-1 text-micro uppercase tracking-wider text-(--color-text-muted)">
        Cache State
      </h4>
      <p>EPUB cache: <span class="font-semibold">{epubCache.size} books</span></p>
      <p>PDF cache: <span class="font-semibold">{documentCache.size} docs</span></p>
    </div>
    <div class="border-b border-(--color-border) p-3">
      <h4 class="mb-1 text-micro uppercase tracking-wider text-(--color-text-muted)">
        Health Diagnose
      </h4>
      {#if diagnoseResult}<div class="space-y-0.5">
          <p>
            DB: <span
              class="font-semibold"
              class:text-green-500={diagnoseResult.database === 'healthy'}
              class:text-yellow-500={diagnoseResult.database !== 'healthy'}
              >{diagnoseResult.database}</span
            >
          </p>
          <p>Queue: <span class="font-semibold">{diagnoseResult.queue}</span></p>
          <p>FS: <span class="font-semibold">{diagnoseResult.filesystem}</span></p>
          <p>Log: <span class="font-semibold">{diagnoseResult.logFile}</span></p>
          {#if Object.keys(diagnoseResult.details).length > 0}<details class="mt-1">
              <summary class="cursor-pointer text-(--color-text-muted)">Details</summary>
              <pre class="mt-1 whitespace-pre-wrap break-all text-micro">{JSON.stringify(
                  diagnoseResult.details,
                  null,
                  2,
                )}</pre>
            </details>{/if}
        </div>{/if}<button
        class="mt-1 rounded px-2 py-0.5 text-micro font-medium bg-(--color-primary) text-white hover:opacity-80 disabled:opacity-50"
        onclick={handleDiagnose}
        disabled={diagnoseLoading}>{diagnoseLoading ? 'Running…' : 'Run Diagnose'}</button
      >
    </div>
    <div class="border-b border-(--color-border) p-3">
      <h4 class="mb-1 text-micro uppercase tracking-wider text-(--color-text-muted)">
        Reader Info
      </h4>
      {#if debugState.readerInfo}<div class="space-y-0.5">
          <p>Format: <span class="font-semibold">{debugState.readerInfo.format ?? '—'}</span></p>
          <p>TOC: <span class="font-semibold">{String(debugState.readerInfo.isTocOpen)}</span></p>
          <p>
            Search: <span class="font-semibold">{String(debugState.readerInfo.isSearchOpen)}</span>
          </p>
          <p>
            Fullscreen: <span class="font-semibold"
              >{String(debugState.readerInfo.isFullscreen)}</span
            >
          </p>
          <p>Page: <span class="font-semibold">{debugState.readerInfo.pageInfo}</span></p>
          <p>Scale: <span class="font-semibold">{debugState.readerInfo.scale}</span></p>
        </div>{:else}<p class="text-(--color-text-muted)">No active reader</p>{/if}
    </div>
    <div class="border-b border-(--color-border) p-3">
      <h4 class="mb-1 text-micro uppercase tracking-wider text-(--color-text-muted)">
        Selection Inspector
      </h4>
      {#if debugState.selection}<div class="space-y-0.5">
          <p>Source: <span class="font-semibold">{debugState.selection.source}</span></p>
          <p>Rects: <span class="font-semibold">{debugState.selection.rectCount}</span></p>
          <p class="truncate" title={debugState.selection.text}>
            Text: <span class="font-semibold"
              >{debugState.selection.text.slice(0, 120)}{debugState.selection.text.length > 120
                ? '…'
                : ''}</span
            >
          </p>
        </div>{:else}<p class="text-(--color-text-muted)">No selection</p>{/if}
    </div>
    <div class="border-b border-(--color-border) p-3">
      <h4 class="mb-1 text-micro uppercase tracking-wider text-(--color-text-muted)">
        EPUB Selection Debug
      </h4>
      <div class="space-y-0.5">
        <p>
          Iframe: <span class="font-semibold"
            >{debugState.epub.iframeRect
              ? `${Math.round(debugState.epub.iframeRect.left)},${Math.round(debugState.epub.iframeRect.top)} (${Math.round(debugState.epub.iframeRect.width)}×${Math.round(debugState.epub.iframeRect.height)})`
              : '—'}</span
          >
        </p>
        <p>
          PostMessage RX: <span class="font-semibold">{debugState.epub.postMessageCount}</span>
          (empty-text: <span class="font-semibold">{debugState.epub.emptyTextMessageCount}</span>)
        </p>
        <p>
          Guard: <span
            class="font-semibold"
            class:text-green-500={debugState.epub.guardResult === 'pass'}
            class:text-yellow-500={debugState.epub.guardResult === 'none'}
            class:text-red-500={debugState.epub.guardResult.startsWith('drop-')}
            >{debugState.epub.guardResult}</span
          >
        </p>
        <p>
          onselection: <span class="font-semibold">{debugState.epub.onselectionCalled}</span> |
          onselectionclear:
          <span class="font-semibold">{debugState.epub.onselectionclearCalled}</span>
        </p>
        <p>
          rectCount: <span class="font-semibold">{debugState.epub.rectCount}</span> |
          currentChapter:
          <span class="font-semibold">{debugState.epub.currentChapterIndex ?? '—'}</span>
        </p>
        {#if debugState.epub.lastRawMessage}<details class="mt-1">
            <summary class="cursor-pointer text-(--color-text-muted)"
              >Last msg ({debugState.epub.lastRawMessage.type})</summary
            >
            <pre class="mt-1 whitespace-pre-wrap break-all text-micro">{JSON.stringify(
                debugState.epub.lastRawMessage,
                null,
                2,
              )}</pre>
          </details>{/if}
        <hr class="my-1 border-(--color-border)" />
        <p>
          showToolbar: <span
            class="font-semibold"
            class:text-green-500={debugState.epub.parentState.showToolbar}
            class:text-red-500={!debugState.epub.parentState.showToolbar}
            >{String(debugState.epub.parentState.showToolbar)}</span
          >
        </p>
        <p>
          selectedText: <span class="font-semibold"
            >{debugState.epub.parentState.selectedText || '—'}</span
          >
        </p>
        <p>
          bounds: <span class="font-semibold"
            >{debugState.epub.parentState.selectionBounds
              ? `${Math.round(debugState.epub.parentState.selectionBounds.left)},${Math.round(debugState.epub.parentState.selectionBounds.top)} → ${Math.round(debugState.epub.parentState.selectionBounds.right)},${Math.round(debugState.epub.parentState.selectionBounds.bottom)}`
              : '—'}</span
          >
        </p>
        <p>
          container: <span class="font-semibold"
            >{debugState.epub.parentState.selectionContainer
              ? `${Math.round(debugState.epub.parentState.selectionContainer.left)},${Math.round(debugState.epub.parentState.selectionContainer.top)}`
              : '—'}</span
          >
        </p>
        <p>
          toolbarX/Y: <span class="font-semibold text-cyan-600"
            >{debugState.epub.computedToolbarX !== null && debugState.epub.computedToolbarY !== null
              ? `${Math.round(debugState.epub.computedToolbarX)}, ${Math.round(debugState.epub.computedToolbarY)}`
              : '—'}</span
          >
        </p>
        <hr class="my-1 border-(--color-border)" />
        <p class="text-(--color-text-muted)">
          dismiss: <span class="font-semibold">{debugState.epub.dismissToolbarCallCount}</span>
          ({debugState.epub.lastDismissTrigger})
        </p>
        <p class="text-(--color-text-muted)">
          color pick: <span class="font-semibold">{debugState.epub.colorPickCount}</span>
          ({debugState.epub.lastPickedColor})
        </p>
        <p class="text-(--color-text-muted)">
          saveHighlight: <span class="font-semibold">{debugState.epub.saveHighlightCallCount}</span
          >{debugState.epub.saveHighlightLastError
            ? ` err="${debugState.epub.saveHighlightLastError.slice(0, 30)}"`
            : ''}
        </p>
        <p class="text-(--color-text-muted)">
          persistedHighlights: <span class="font-semibold"
            >{debugState.epub.persistedHighlightsCount}</span
          >
        </p>
      </div>
    </div>
    <div class="p-3">
      <h4 class="mb-1 text-micro uppercase tracking-wider text-(--color-text-muted)">Logs</h4>
      <button
        class="rounded px-2 py-0.5 text-micro font-medium bg-(--color-primary) text-white hover:opacity-80 disabled:opacity-50"
        onclick={handleExportLogs}
        disabled={logsLoading}>{logsLoading ? 'Exporting…' : 'Export Logs'}</button
      >
    </div>
  </div>
{/if}
