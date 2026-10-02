/**
 * FR-07 adopt-or-rename reconciliation (Phase A only). For every library book
 * the reconciler computes the canonical Drive name plus every legacy form a
 * pre-WU4 client could have written, scans the remote listing, and emits a plan:
 *
 * - canonical object present, no legacy twin -> adopt it (record it, no write);
 * - canonical object present + legacy twin    -> keep canonical live; the twin
 *   is left untouched (never deleted). If both carry a checksum and they differ,
 *   the outcome is flagged `flag-mismatch` for observability;
 * - legacy-only object                        -> copy bytes to the canonical
 *   name, verify, and LEAVE THE SOURCE IN PLACE (Phase A never deletes; Phase B
 *   parking is deferred behind the `legacyParked` cutover flag in the design).
 *
 * The plan never contains a delete. Reconciliation cannot orphan or duplicate a
 * book: exactly one canonical target is resolvable per book.
 */
import { canonicalDriveObjectName, legacyForms } from './driveFilename';

export interface DriveBookRef {
  bookId: string;
  extension: string;
}

export interface DriveObjectInfo {
  name: string;
  size?: number | null;
  checksum?: string | null;
}

export type ReconcileAction =
  | { kind: 'adopt'; bookId: string; canonicalName: string }
  | { kind: 'keep-canonical'; bookId: string; canonicalName: string; legacyTwin: string }
  | { kind: 'flag-mismatch'; bookId: string; canonicalName: string; legacyTwin: string }
  | { kind: 'copy-to-canonical'; bookId: string; sourceName: string; canonicalName: string };

const STATE_SUFFIX = '_state.json';

function stemOf(objectName: string): string | null {
  if (objectName.length === 0 || objectName.endsWith(STATE_SUFFIX)) return null;
  const dot = objectName.lastIndexOf('.');
  if (dot <= 0 || dot === objectName.length - 1) return null;
  return objectName.slice(0, dot);
}

/** Pure plan: no I/O, no deletes. */
export function planReconciliation(
  books: DriveBookRef[],
  objects: DriveObjectInfo[],
): ReconcileAction[] {
  const byName = new Map(objects.map((object) => [object.name, object]));
  const actions: ReconcileAction[] = [];

  for (const book of books) {
    const canonicalName = canonicalDriveObjectName(book.bookId, book.extension);
    const legacyStems = legacyForms(book.bookId);
    const legacyTwin = objects.find((object) => {
      if (object.name === canonicalName) return false;
      const stem = stemOf(object.name);
      return stem != null && legacyStems.has(stem);
    });

    if (byName.has(canonicalName)) {
      if (!legacyTwin) {
        actions.push({ kind: 'adopt', bookId: book.bookId, canonicalName });
        continue;
      }
      const canonical = byName.get(canonicalName);
      const mismatch =
        canonical?.checksum != null &&
        legacyTwin.checksum != null &&
        canonical.checksum !== legacyTwin.checksum;
      actions.push({
        kind: mismatch ? 'flag-mismatch' : 'keep-canonical',
        bookId: book.bookId,
        canonicalName,
        legacyTwin: legacyTwin.name,
      });
      continue;
    }

    if (legacyTwin) {
      actions.push({
        kind: 'copy-to-canonical',
        bookId: book.bookId,
        sourceName: legacyTwin.name,
        canonicalName,
      });
    }
  }

  return actions;
}

/** Minimal Drive surface the executor needs; the write guard (WU5) is not involved. */
export interface DriveReconcilePort {
  list(): Promise<string[]>;
  download(name: string): Promise<Uint8Array>;
  upload(name: string, bytes: Uint8Array): Promise<unknown>;
  getFileSize?(name: string): Promise<number | null>;
}

export interface ReconcileOutcome {
  actions: ReconcileAction[];
  copied: string[];
  failed: Array<{ source: string; reason: string }>;
}

function bytesEqual(a: Uint8Array, b: Uint8Array): boolean {
  if (a.length !== b.length) return false;
  for (let i = 0; i < a.length; i += 1) {
    if (a[i] !== b[i]) return false;
  }
  return true;
}

/**
 * Execute the plan. Copy-to-canonical downloads the source, uploads the
 * canonical target, then re-downloads it and verifies the bytes. A failed
 * verification is reported and the source is preserved; nothing is ever deleted.
 */
export async function reconcileDriveBookNames(
  port: DriveReconcilePort,
  books: DriveBookRef[],
): Promise<ReconcileOutcome> {
  const remoteNames = await port.list();
  const actions = planReconciliation(
    books,
    remoteNames.map((name) => ({ name })),
  );
  const copied: string[] = [];
  const failed: Array<{ source: string; reason: string }> = [];

  for (const action of actions) {
    if (action.kind !== 'copy-to-canonical') continue;
    try {
      const sourceBytes = await port.download(action.sourceName);
      await port.upload(action.canonicalName, sourceBytes);
      const verifyBytes = await port.download(action.canonicalName);
      if (!bytesEqual(sourceBytes, verifyBytes)) {
        failed.push({ source: action.sourceName, reason: 'canonical verification mismatch' });
        continue;
      }
      copied.push(action.canonicalName);
    } catch (error) {
      failed.push({
        source: action.sourceName,
        reason: error instanceof Error ? error.message : String(error),
      });
    }
  }

  return { actions, copied, failed };
}
