/**
 * FR-08 Drive binary write guard.
 *
 * Before overwriting a Drive binary the guard compares the writer's base marker
 * with the marker recorded on the object itself: version + content checksum live
 * in Drive `appProperties` (`{@link MARKER_VERSION_PROP}` /
 * `{@link MARKER_CHECKSUM_PROP}`), so the marker rides the guarded object and no
 * sidecar file pollutes the listing. A mismatch is a typed
 * {@link StaleWriteError} and the remote bytes stay untouched; a match uploads
 * the bytes and the bumped marker together in one `files.update`, so a crash
 * cannot desync binary from marker.
 *
 * This is a CHECK, not a LOCK. Drive v3 `files.update` has no generation or
 * `If-Match` precondition, so two writers that pass the check concurrently can
 * still both write (last writer wins). The exposed window is the
 * read-check-write gap; it is documented here instead of hidden behind a lock
 * that the API cannot provide.
 *
 * A base captured by an earlier sync makes a stale client fail closed. When no
 * base is known (`null`) the guard adopts the current remote version rather than
 * inventing a conflict, so a fresh or offline client still uploads and a
 * missing marker never becomes a permanent failure. The retry helper
 * (re-)fetches the base, rebases on the remote the guard just observed, and
 * retries a bounded number of times for both stale and transient failures.
 */
import { canonicalObjectName, type CanonicalName } from './driveFilename';

/** Remote version + content checksum recorded alongside a Drive binary. */
export interface VersionMarker {
  version: number;
  checksum: string;
}

export const MARKER_VERSION_PROP = 'nexoVersion';
export const MARKER_CHECKSUM_PROP = 'nexoChecksum';
export const DEFAULT_GUARD_ATTEMPTS = 3;

/** Typed stale-write rejection carrying the base and the remote that beat it. */
export class StaleWriteError extends Error {
  readonly objectName: string;
  readonly expectedVersion: number;
  readonly actualVersion: number;

  constructor(objectName: string, expectedVersion: number, actualVersion: number) {
    super(
      `Stale write rejected for ${objectName}: base version ${expectedVersion} but remote is ${actualVersion}`,
    );
    this.name = 'StaleWriteError';
    this.objectName = objectName;
    this.expectedVersion = expectedVersion;
    this.actualVersion = actualVersion;
  }
}

/** Minimal Drive surface the guard needs; the provider implements it for real. */
export interface DriveGuardPort {
  readMarker(objectName: string): Promise<VersionMarker | null>;
  writeBinary(objectName: string, bytes: Uint8Array, marker: VersionMarker): Promise<string>;
}

export interface GuardedWriteResult {
  objectName: string;
  marker: VersionMarker;
  fileId: string;
}

export type BaseLoader = () => VersionMarker | null | Promise<VersionMarker | null>;

const FNV_OFFSET_BASIS = 0xcbf29ce484222325n;
const FNV_PRIME = 0x100000001b3n;
const FNV_MASK = 0xffffffffffffffffn;

/**
 * FNV-1a 64-bit content checksum, hex-encoded. Deterministic and synchronous so
 * it works in jsdom and matches the Kotlin guard byte-for-byte.
 */
export function checksumOf(bytes: Uint8Array): string {
  let hash = FNV_OFFSET_BASIS;
  for (const byte of bytes) {
    hash ^= BigInt(byte);
    hash = (hash * FNV_PRIME) & FNV_MASK;
  }
  return hash.toString(16).padStart(16, '0');
}

export function appPropertiesFor(marker: VersionMarker): Record<string, string> {
  return {
    [MARKER_VERSION_PROP]: String(marker.version),
    [MARKER_CHECKSUM_PROP]: marker.checksum,
  };
}

export function markerFromAppProperties(
  props: Record<string, string> | null | undefined,
): VersionMarker | null {
  const raw = props?.[MARKER_VERSION_PROP];
  if (raw == null) return null;
  const version = Number.parseInt(raw, 10);
  if (!Number.isFinite(version) || version < 0) return null;
  return { version, checksum: props?.[MARKER_CHECKSUM_PROP] ?? '' };
}

/** Per-object entry a later reconciler or layout migration can record in manifest.json. */
export function manifestObjectEntry(
  objectName: string,
  marker: VersionMarker,
): { name: string; version: number; checksum: string } {
  return { name: objectName, version: marker.version, checksum: marker.checksum };
}

const baseCache = new Map<string, VersionMarker>();

/** Seed the in-session base from a marker observed on a pull/list or a write. */
export function rememberBase(objectName: string, marker: VersionMarker | null): void {
  if (marker !== null) baseCache.set(objectName, marker);
}

/** The last base this process saw for an object, or null when unknown. */
export function recallBase(objectName: string): VersionMarker | null {
  return baseCache.get(objectName) ?? null;
}

export function __resetBaseCache(): void {
  baseCache.clear();
}

/**
 * Check-then-write one Drive binary. `base` is the version the caller's content
 * was based on (`null` = unknown, adopt remote). Throws {@link StaleWriteError}
 * without touching the remote when a known base fell behind.
 */
export async function guardedUpload(
  port: DriveGuardPort,
  book: CanonicalName,
  extension: string | null,
  bytes: Uint8Array,
  base: VersionMarker | null,
): Promise<GuardedWriteResult> {
  const objectName = canonicalObjectName(book, extension);
  const remote = await port.readMarker(objectName);
  const remoteVersion = remote?.version ?? 0;
  if (base !== null && remoteVersion !== base.version) {
    throw new StaleWriteError(objectName, base.version, remoteVersion);
  }
  const marker: VersionMarker = { version: remoteVersion + 1, checksum: checksumOf(bytes) };
  const fileId = await port.writeBinary(objectName, bytes, marker);
  rememberBase(objectName, marker);
  return { objectName, marker, fileId };
}

/**
 * Bounded retry around {@link guardedUpload}. A stale rejection re-fetches the
 * remote marker and rebases before the next attempt; a transient I/O failure is
 * retried unchanged. Either way a transient failure is never promoted to a
 * permanent one — after the final attempt the last error is rethrown.
 */
export async function guardedUploadWithRetry(
  port: DriveGuardPort,
  book: CanonicalName,
  extension: string | null,
  bytes: Uint8Array,
  loadBase: BaseLoader = () => null,
  maxAttempts: number = DEFAULT_GUARD_ATTEMPTS,
): Promise<GuardedWriteResult> {
  let lastError: unknown = null;
  let base = await loadBase();
  for (let attempt = 0; attempt < maxAttempts; attempt += 1) {
    try {
      return await guardedUpload(port, book, extension, bytes, base);
    } catch (error) {
      lastError = error;
      if (error instanceof StaleWriteError) {
        base = await port.readMarker(error.objectName).catch(() => null);
      }
    }
  }
  throw lastError instanceof Error ? lastError : new Error('guarded upload failed');
}
