/**
 * WU6 Drive `manifest.json` version marker (FR-01, INV-5) and the cold-backup
 * cutover flags (design: dual-write old+new path, new-path-first read).
 *
 * The manifest is the version gate for the whole change:
 *
 * - `layoutVersion`  — WU6 target tree (`books/`, `backups/`, `legacy/`).
 * - `filenameVersion`— WU4 canonical algorithm. WU6 MUST NOT run until a remote
 *   manifest reports `filenameVersion >= 1`, otherwise the migration could move
 *   files before filename reconciliation has stopped new divergence.
 * - `guardVersion`   — WU5 write guard active.
 * - `legacyParked`   — Phase B parking done (post-cutover only, always false
 *   for this change).
 * - `coldBackupCutover` — old path still written for pre-change clients.
 *
 * A missing manifest means version 0 for every gate (never "current").
 */

export const MANIFEST_FILE = 'manifest.json';
export const LAYOUT_VERSION = 1;
export const FILENAME_VERSION = 1;
export const GUARD_VERSION = 1;

export interface ColdBackupCutover {
  /** Writers also write the legacy `Nexo/Books/nexo_cold_backup.json` path. */
  dualWrite: boolean;
  /** Readers try `Nexo/backups/...` first, falling back to the legacy path. */
  newPathPrimary: boolean;
}

export interface DriveManifest {
  layoutVersion: number;
  filenameVersion: number;
  guardVersion: number;
  legacyParked: boolean;
  coldBackupCutover: ColdBackupCutover;
  updatedAt: string;
  /** Per-object `{name, version, checksum}` entries recorded by the guard. */
  objects: Array<{ name: string; version: number; checksum: string }>;
}

export function defaultCutover(): ColdBackupCutover {
  return { dualWrite: true, newPathPrimary: true };
}

export function createManifest(
  objects: DriveManifest['objects'] = [],
  now: () => string = () => new Date().toISOString(),
): DriveManifest {
  return {
    layoutVersion: LAYOUT_VERSION,
    filenameVersion: FILENAME_VERSION,
    guardVersion: GUARD_VERSION,
    legacyParked: false,
    coldBackupCutover: defaultCutover(),
    updatedAt: now(),
    objects,
  };
}

/**
 * Parse a remote manifest. Returns `null` for a missing/unparseable body so a
 * caller treats it as version 0 instead of inventing a current version.
 */
export function parseManifest(raw: string | null | undefined): DriveManifest | null {
  if (!raw) return null;
  let value: unknown;
  try {
    value = JSON.parse(raw);
  } catch {
    return null;
  }
  if (typeof value !== 'object' || value === null) return null;
  const record = value as Record<string, unknown>;
  const numberOrZero = (key: string): number => {
    const n = Number(record[key]);
    return Number.isFinite(n) && n >= 0 ? n : 0;
  };
  const cutover = (record.coldBackupCutover ?? {}) as Record<string, unknown>;
  return {
    layoutVersion: numberOrZero('layoutVersion'),
    filenameVersion: numberOrZero('filenameVersion'),
    guardVersion: numberOrZero('guardVersion'),
    legacyParked: record.legacyParked === true,
    coldBackupCutover: {
      dualWrite: cutover.dualWrite !== false,
      newPathPrimary: cutover.newPathPrimary !== false,
    },
    updatedAt: typeof record.updatedAt === 'string' ? record.updatedAt : '',
    objects: Array.isArray(record.objects)
      ? (record.objects as DriveManifest['objects']).filter(
          (entry) =>
            entry != null &&
            typeof entry.name === 'string' &&
            Number.isFinite(entry.version) &&
            typeof entry.checksum === 'string',
        )
      : [],
  };
}

export function serializeManifest(manifest: DriveManifest): string {
  return JSON.stringify(manifest);
}

/**
 * WU6 is allowed to run only once the remote manifest proves the WU4 canonical
 * filename algorithm has shipped (`filenameVersion >= 1`). A missing manifest or
 * a lower version aborts the layout migration.
 */
export function canRunLayoutMigration(manifest: DriveManifest | null): boolean {
  return manifest !== null && manifest.filenameVersion >= FILENAME_VERSION;
}
