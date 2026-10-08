/**
 * Discover download transfer port (WU2).
 *
 * Remote book transfers run in the native backend (`downloadRemoteBook` in
 * `desktop/src-tauri/src/commands/download.rs`). The webview never fetches the
 * URL — the CSP forbids it — it only subscribes to the backend's
 * `discover-download-progress` events. The backend already guarantees exactly
 * one terminal event per transfer, so this port forwards progress phases only
 * and settles one promise per download call.
 */
import { listen, type UnlistenFn } from '@tauri-apps/api/event';
import {
  cancelRemoteDownload,
  downloadRemoteBook,
  type DownloadProgressPayload,
} from '$lib/shared/api/downloadApi';

/** Event name emitted by the Rust download command. */
const DOWNLOAD_PROGRESS_EVENT = 'discover-download-progress';

/** Byte progress callback; `totalBytes` is `null` when the host omits the length. */
export type DiscoverProgressFn = (doneBytes: number, totalBytes: number | null) => void;

/** Backend progress payload; the Rust `DownloadProgressPayload` shape. */
export type DownloadProgressEvent = DownloadProgressPayload;

/** Phases the backend emits for a transfer. */
export type DownloadPhase = DownloadProgressEvent['phase'];

export interface DownloadTransferRequest {
  transferId: string;
  url: string;
  format: string;
}

export interface DownloadTransferResult {
  filePath: string;
  bytes: number;
}

export interface DownloadTransferPort {
  /** Resolves once the backend finalized the file; rejects on failure or cancel. */
  download(
    req: DownloadTransferRequest,
    onProgress: DiscoverProgressFn,
  ): Promise<DownloadTransferResult>;
  cancel(transferId: string): Promise<void>;
}

/** Raised when the backend reported `BOOK_DOWNLOAD_CANCELLED`. */
export class DownloadCancelledError extends Error {
  readonly transferId: string;

  constructor(transferId: string) {
    super('BOOK_DOWNLOAD_CANCELLED');
    this.name = 'DownloadCancelledError';
    this.transferId = transferId;
  }
}

const CANCELLED_RE = /BOOK_DOWNLOAD_CANCELLED/;
/** Stable `BOOK_DOWNLOAD_*` code prefix; upstream bodies are never surfaced. */
const CODE_ONLY_RE = /^BOOK_DOWNLOAD_[A-Z0-9_]+/;

function messageOf(err: unknown): string {
  if (typeof err === 'string') return err;
  if (err instanceof Error) return err.message;
  return String(err);
}

/** True when the backend reported the transfer as cancelled. */
export function isDownloadCancelled(err: unknown): boolean {
  return err instanceof DownloadCancelledError || CANCELLED_RE.test(messageOf(err));
}

/** Reduce a backend error to its deterministic code so no upstream body leaks. */
export function downloadErrorCode(err: unknown): string {
  const raw = messageOf(err).trim();
  return CODE_ONLY_RE.exec(raw)?.[0] ?? raw;
}

/**
 * Production port: invokes `downloadRemoteBook` and relays
 * `discover-download-progress` events that belong to this transfer id. The
 * listener is removed as soon as the transfer settles, and terminal phases are
 * never forwarded as byte progress (the promise carries the outcome).
 */
export function createTauriDownloadTransfer(): DownloadTransferPort {
  return {
    async download(req, onProgress) {
      let unlisten: UnlistenFn | null = null;
      let settled = false;
      try {
        unlisten = await listen<DownloadProgressPayload>(DOWNLOAD_PROGRESS_EVENT, (event) => {
          if (settled) return;
          const payload = event.payload;
          if (payload.transferId !== req.transferId) return;
          if (payload.phase !== 'progress') return;
          onProgress(payload.downloaded, payload.total ?? null);
        });
        const result = await downloadRemoteBook({
          transferId: req.transferId,
          url: req.url,
          format: req.format,
        });
        return { filePath: result.filePath, bytes: result.bytes };
      } catch (err) {
        if (isDownloadCancelled(err)) throw new DownloadCancelledError(req.transferId);
        throw new Error(downloadErrorCode(err));
      } finally {
        settled = true;
        unlisten?.();
      }
    },
    async cancel(transferId) {
      await cancelRemoteDownload(transferId);
    },
  };
}
