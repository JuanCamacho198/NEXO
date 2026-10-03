/**
 * FR-08 write-guard scenarios: stale writer rejected with remote bytes
 * untouched, fresh writer bumps binary + marker atomically, unknown base adopts
 * the remote, and the retry path rebases rather than failing permanently.
 */
import { beforeEach, describe, expect, it } from 'vitest';
import { canonicalName } from '$lib/shared/sync/driveFilename';
import {
  __resetBaseCache,
  appPropertiesFor,
  checksumOf,
  guardedUpload,
  guardedUploadWithRetry,
  manifestObjectEntry,
  markerFromAppProperties,
  StaleWriteError,
  type DriveGuardPort,
  type VersionMarker,
} from '$lib/shared/sync/driveWriteGuard';

const BOOK = canonicalName('gutendex:2701');
const NAME = 'gutendex2701.epub';

interface StoredObject {
  bytes: Uint8Array;
  marker: VersionMarker;
}

class FakeDrive implements DriveGuardPort {
  readonly objects = new Map<string, StoredObject>();
  writes = 0;
  writeFailures = 0;

  async readMarker(objectName: string): Promise<VersionMarker | null> {
    return this.objects.get(objectName)?.marker ?? null;
  }

  async writeBinary(objectName: string, bytes: Uint8Array, marker: VersionMarker): Promise<string> {
    if (this.writeFailures > 0) {
      this.writeFailures -= 1;
      throw new Error('transient network drop');
    }
    this.writes += 1;
    this.objects.set(objectName, { bytes, marker });
    return `id-${objectName}`;
  }
}

const bytes = (...values: number[]): Uint8Array => new Uint8Array(values);

describe('DriveWriteGuard — stale vs fresh', () => {
  let drive: FakeDrive;

  beforeEach(() => {
    drive = new FakeDrive();
    __resetBaseCache();
  });

  it('rejects a stale writer and leaves the remote binary unchanged', async () => {
    drive.objects.set(NAME, { bytes: bytes(1, 2, 3), marker: { version: 2, checksum: 'aa' } });

    const error = await guardedUpload(drive, BOOK, 'epub', bytes(9), {
      version: 1,
      checksum: 'aa',
    }).catch((e: unknown) => e);

    expect(error).toBeInstanceOf(StaleWriteError);
    expect((error as StaleWriteError).expectedVersion).toBe(1);
    expect((error as StaleWriteError).actualVersion).toBe(2);
    expect(drive.writes).toBe(0);
    expect([...drive.objects.get(NAME)!.bytes]).toEqual([1, 2, 3]);
  });

  it('lets a fresh writer update the binary and the version marker atomically', async () => {
    drive.objects.set(NAME, { bytes: bytes(1), marker: { version: 1, checksum: 'old' } });

    const result = await guardedUpload(drive, BOOK, 'epub', bytes(4, 5), {
      version: 1,
      checksum: 'old',
    });

    expect(result.marker.version).toBe(2);
    expect(result.marker.checksum).toBe(checksumOf(bytes(4, 5)));
    expect(drive.writes).toBe(1);
    const stored = drive.objects.get(NAME)!;
    expect([...stored.bytes]).toEqual([4, 5]);
    expect(stored.marker).toEqual(result.marker);
  });

  it('starts at version 1 when the remote has no marker', async () => {
    const result = await guardedUpload(drive, BOOK, 'epub', bytes(7), null);
    expect(result.marker.version).toBe(1);
  });

  it('adopts the remote version when no base is known instead of failing closed', async () => {
    drive.objects.set(NAME, { bytes: bytes(1), marker: { version: 5, checksum: 'x' } });

    const result = await guardedUpload(drive, BOOK, 'epub', bytes(2), null);

    expect(result.marker.version).toBe(6);
  });
});

describe('DriveWriteGuard — retry path', () => {
  let drive: FakeDrive;

  beforeEach(() => {
    drive = new FakeDrive();
    __resetBaseCache();
  });

  it('rebases on a stale rejection and succeeds on the next attempt', async () => {
    drive.objects.set(NAME, { bytes: bytes(1), marker: { version: 2, checksum: 'bb' } });

    const result = await guardedUploadWithRetry(drive, BOOK, 'epub', bytes(3), () => ({
      version: 1,
      checksum: 'aa',
    }));

    expect(result.marker.version).toBe(3);
    expect(drive.writes).toBe(1);
  });

  it('retries a transient upload failure instead of turning it permanent', async () => {
    drive.writeFailures = 1;

    const result = await guardedUploadWithRetry(drive, BOOK, 'epub', bytes(8), () => null);

    expect(result.marker.version).toBe(1);
    expect(drive.writes).toBe(1);
  });
});

describe('DriveWriteGuard — marker contract', () => {
  it('round-trips a marker through Drive appProperties', () => {
    const marker: VersionMarker = { version: 4, checksum: 'deadbeef' };
    const props = appPropertiesFor(marker);
    expect(props).toEqual({ nexoVersion: '4', nexoChecksum: 'deadbeef' });
    expect(markerFromAppProperties(props)).toEqual(marker);
  });

  it('treats an absent marker as null and exposes a manifest entry', () => {
    expect(markerFromAppProperties(null)).toBeNull();
    expect(markerFromAppProperties({})).toBeNull();
    expect(manifestObjectEntry(NAME, { version: 2, checksum: 'ab' })).toEqual({
      name: NAME,
      version: 2,
      checksum: 'ab',
    });
  });

  it('hashes content deterministically (empty included)', () => {
    expect(checksumOf(bytes(1, 2, 3))).toBe(checksumOf(bytes(1, 2, 3)));
    expect(checksumOf(bytes())).toHaveLength(16);
  });
});
