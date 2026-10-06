<script lang="ts">
  import { appState } from '$lib/shared/stores/AppState.svelte';
  import { libraryState } from '$lib/shared/stores/LibraryDomainState.svelte';
  import { bulkImportState } from '$lib/shared/stores/BulkImportDomainState.svelte';
  import { EditMetadataModal, BulkImportModal, RemoveBookModal } from '$lib/features/library';
  import ErrorToast from '$lib/shared/ui/feedback/ErrorToast.svelte';
  import ErrorFallback from '$lib/shared/ui/feedback/ErrorFallback.svelte';
  import ToastHost from '$lib/shared/ui/feedback/ToastHost.svelte';
  import FeedbackDialog from '$lib/shared/ui/feedback/FeedbackDialog.svelte';
  import AddonInstallConfirmDialog from '$lib/shared/ui/addons/AddonInstallConfirmDialog.svelte';
  import { getAddonRegistry } from '$lib/shared/services/addons/AddonRegistry';
  import { createInstallDeepLink } from '$lib/features/settings/useInstallDeepLink.svelte';
  import { setInstallDeepLinkHandler } from '$lib/features/addons/installDeepLink';
  import {
    isDismissed,
    readLastEventId,
    startAutoFlush,
    flushFeedbackQueue,
    type FlushTransport,
  } from '$lib/shared/feedback/feedbackStore';
  import * as Sentry from '@sentry/browser';

  // Spec D3 — triggers: ErrorFallback (desktop) opens the feedback dialog;
  // on next-launch-after-crash the persisted last-event-id is read on mount
  // and the dialog opens automatically. The dialog itself owns the dismiss
  // marker so a dismissed eventId never re-prompts.

  let feedbackOpen = $state(false);
  let feedbackEventId = $state<string | null>(null);

  // Public for ErrorFallback — see the listen-for-event pattern below.
  export function openFeedback(eventId: string | null = null): void {
    feedbackEventId = eventId ?? readLastEventId();
    feedbackOpen = true;
  }

  // Auto-prompt on next launch after a crash (post-crash next-launch trigger).
  // We only fire when a non-dismissed eventId is persisted in localStorage.
  // The dismissed-set is checked here so a once-dismissed crash never
  // re-nags even if the user reopens the app weeks later. The dialog itself
  // handles the online/offline gate by queueing on `navigator.onLine === false`.
  $effect(() => {
    if (typeof window === 'undefined') return;
    const lastId = readLastEventId();
    if (lastId && !isDismissed(lastId)) {
      feedbackEventId = lastId;
      feedbackOpen = true;
    }
  });

  // Spec D3 — ErrorFallback trigger. ErrorFallback dispatches
  // `np:open-feedback` (no eventId payload — by the time the fallback
  // renders, Sentry.lastEventId() has already been persisted to
  // `np.feedback.lastEventId` so we just call openFeedback(null) and
  // fall back to readLastEventId() inside the function).
  $effect(() => {
    if (typeof window === 'undefined') return;
    const onOpen = (): void => {
      openFeedback(null);
    };
    window.addEventListener('np:open-feedback', onOpen);
    return () => window.removeEventListener('np:open-feedback', onOpen);
  });

  // Wire the auto-flush listener: every time `online` fires we try to
  // drain the offline queue through Sentry.captureFeedback. The transport
  // mirrors the dialog's own send path so failures fall back to re-queue.
  $effect(() => {
    if (typeof window === 'undefined') return;
    const transport: FlushTransport = {
      send: async (item) => {
        try {
          Sentry.withScope((scope) => {
            scope.setContext('book', item.contexts.book as unknown as Record<string, unknown>);
            scope.setTag('feedback.source', 'desktop-modal-flush');
            Sentry.captureFeedback({
              message: item.message,
              // eventId is `string | null`; captureFeedback wants `string | undefined`.
              associatedEventId:
                item.eventId == null || item.eventId === 'no-event' ? undefined : item.eventId,
            });
          });
          return true;
        } catch {
          return false;
        }
      },
    };
    const handle = startAutoFlush(transport);
    // Best-effort initial flush on mount — covers the case where the
    // browser was already online when the app started.
    void flushFeedbackQueue(transport);
    return () => handle();
  });

  // sdd/addon-deeplink-v1: single shared registry + deep-link install flow.
  // setInstallDeepLinkHandler routes parsed install URLs from main.ts's
  // handleDeepLinkUrls (cold onOpenUrl + warm single-instance event) here.
  const installDeepLink = createInstallDeepLink({ registry: getAddonRegistry() });
  setInstallDeepLinkHandler((url) => void installDeepLink.handleInstallUrl(url));
</script>

<EditMetadataModal
  book={libraryState.editingBook as import('$lib/shared/types').LibraryBookDto | null}
  open={libraryState.editingBook !== null}
  onClose={() => {
    libraryState.editingBook = null;
  }}
  onSave={libraryState.handleSaveEditedBook}
  t={appState.t}
/>

<BulkImportModal
  open={bulkImportState.isBulkImportOpen}
  folderName={bulkImportState.bulkImportFolderName}
  folderPath={bulkImportState.bulkImportFolderPath}
  scanResult={bulkImportState.bulkScanResult}
  isScanning={bulkImportState.isBulkScanning}
  scanError={bulkImportState.bulkScanError}
  isImporting={bulkImportState.isBulkImporting}
  importProgress={bulkImportState.bulkImportProgress}
  importSummary={bulkImportState.bulkImportSummary}
  onClose={() => bulkImportState.closeBulkImportModal()}
  onPickFolder={() =>
    bulkImportState.handlePickBulkImportFolder(appState.t('library.bulkImport.selectFolderTitle'))}
  onScan={() => bulkImportState.handleScanBulkImportFolder()}
  onStartImport={() => bulkImportState.handleStartBulkImport()}
  onCancelImport={() => bulkImportState.handleCancelBulkImport()}
  t={appState.t}
/>

<ErrorToast />
<ErrorFallback />

<FeedbackDialog
  bind:open={feedbackOpen}
  eventId={feedbackEventId}
  onDismiss={(id) => {
    if (id) {
      // markDismissed is called inside the dialog; here we just close.
    }
    feedbackEventId = null;
  }}
/>

<RemoveBookModal
  open={libraryState.pendingRemoveBook !== null}
  onClose={() => {
    libraryState.pendingRemoveBook = null;
  }}
  t={appState.t}
/>

<!-- sdd/addon-deeplink-v1: confirm-install flow (cold + warm deep link). -->
<AddonInstallConfirmDialog
  open={installDeepLink.dialogOpen}
  manifest={installDeepLink.manifest}
  busy={installDeepLink.state === 'installing'}
  error={installDeepLink.errorCode}
  alreadyInstalled={installDeepLink.alreadyInstalled}
  onconfirm={() => void installDeepLink.confirm()}
  oncancel={() => installDeepLink.cancel()}
/>

<ToastHost />
