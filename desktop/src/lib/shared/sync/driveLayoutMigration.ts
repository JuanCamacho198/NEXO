/**
 * WU6 Drive layout migration — move the shared book tree from the legacy
 * capitalized `Nexo/Books/` folder to the target `Nexo/books/`.
 *
 * The plan is gated on the remote `manifest.json` (`filenameVersion >= 1`, via
 * {@link canRunLayoutMigration}) so the layout cannot migrate while the retired
 * filename sanitizers can still cause new Drive collisions. The gate only has
 * meaning when there is something to migrate: a fresh install has no legacy
 * folder, so it is never blocked (FR-01).
 *
 * Discipline (mirrors `driveReconciler`): never delete, never assume the legacy
 * folder is empty, and leave a pre-WU4 client's books reachable.
 *
 *   - canonical `books/` present               -> adopt it. If Drive's
 *     case-sensitive name lookup surfaces BOTH `books/` and `Books/` (the
 *     duplicate case), canonical wins and the legacy twin is left untouched
 *     (never merged, never deleted; Phase B parks it later);
 *   - legacy `Books/` present, WU4 not shipped  -> BLOCKED: keep the legacy tree
 *     live (`filenameVersion < 1`);
 *   - legacy `Books/` present, WU4 shipped       -> rename IN PLACE
 *     (`files.update` on the SAME folder id), so no bytes move and no child is
 *     lost — a true rename, never a duplicate create;
 *   - nothing present                            -> create the canonical folder
 *     (fresh install: the gate is not consulted because there is nothing to
 *     migrate).
 */
import { canRunLayoutMigration, type DriveManifest } from './driveManifest';

/** Target folder name (lowercase) and the legacy capitalized one. */
export const BOOKS_FOLDER = 'books';
export const LEGACY_BOOKS_FOLDER = 'Books';

export interface DriveFolderRef {
  id: string;
  name: string;
}

export type BooksFolderPlan =
  | { kind: 'adopt'; folderId: string }
  | { kind: 'rename'; folderId: string }
  | { kind: 'blocked'; folderId: string }
  | { kind: 'create' };

/**
 * Pure decision for the book folder. `manifest` is the parsed remote manifest
 * (`null` when absent/unparseable, i.e. version 0).
 */
export function planBooksFolderMigration(
  canonical: DriveFolderRef | null,
  legacy: DriveFolderRef | null,
  manifest: DriveManifest | null,
): BooksFolderPlan {
  if (canonical) return { kind: 'adopt', folderId: canonical.id };
  if (!legacy) return { kind: 'create' };
  if (!canRunLayoutMigration(manifest)) return { kind: 'blocked', folderId: legacy.id };
  return { kind: 'rename', folderId: legacy.id };
}

/** Folder a read-only caller should use; never mutates Drive, never creates. */
export function pickBooksFolder(
  canonical: DriveFolderRef | null,
  legacy: DriveFolderRef | null,
): DriveFolderRef | null {
  return canonical ?? legacy;
}
