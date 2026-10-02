import { describe, expect, it } from 'vitest';
import {
  LAYOUT_VERSION,
  FILENAME_VERSION,
  GUARD_VERSION,
  canRunLayoutMigration,
  createManifest,
  parseManifest,
  serializeManifest,
} from '$lib/shared/sync/driveManifest';

describe('driveManifest (WU6 FR-01 / INV-5)', () => {
  it('a missing or unparseable manifest is version 0, never current', () => {
    expect(parseManifest(null)).toBeNull();
    expect(parseManifest('')).toBeNull();
    expect(parseManifest('{not json')).toBeNull();
    expect(canRunLayoutMigration(parseManifest(null))).toBe(false);
  });

  it('round-trips a created manifest with the current versions', () => {
    const manifest = createManifest([{ name: 'gutendex2701.epub', version: 3, checksum: 'ab' }]);
    const parsed = parseManifest(serializeManifest(manifest));
    expect(parsed).not.toBeNull();
    expect(parsed?.layoutVersion).toBe(LAYOUT_VERSION);
    expect(parsed?.filenameVersion).toBe(FILENAME_VERSION);
    expect(parsed?.guardVersion).toBe(GUARD_VERSION);
    expect(parsed?.legacyParked).toBe(false);
    expect(parsed?.objects).toHaveLength(1);
    expect(parsed?.objects[0]).toEqual({
      name: 'gutendex2701.epub',
      version: 3,
      checksum: 'ab',
    });
  });

  it('omitted version fields parse as 0 so WU6 stays gated', () => {
    const parsed = parseManifest(JSON.stringify({ updatedAt: 'now' }));
    expect(parsed?.filenameVersion).toBe(0);
    expect(canRunLayoutMigration(parsed)).toBe(false);
  });

  it('defaults the cold-backup cutover to dual-write when absent', () => {
    const parsed = parseManifest(JSON.stringify({ filenameVersion: 1 }));
    expect(parsed?.coldBackupCutover).toEqual({ dualWrite: true, newPathPrimary: true });
  });

  it('gates the layout migration on filenameVersion >= 1', () => {
    expect(canRunLayoutMigration(parseManifest(JSON.stringify({ filenameVersion: 0 })))).toBe(false);
    expect(canRunLayoutMigration(parseManifest(JSON.stringify({ filenameVersion: 1 })))).toBe(true);
    expect(canRunLayoutMigration(parseManifest(JSON.stringify({ filenameVersion: 2 })))).toBe(true);
  });
});
