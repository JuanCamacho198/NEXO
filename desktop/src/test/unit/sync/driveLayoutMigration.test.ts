import { describe, expect, it } from 'vitest';
import {
  BOOKS_FOLDER,
  LEGACY_BOOKS_FOLDER,
  pickBooksFolder,
  planBooksFolderMigration,
} from '$lib/shared/sync/driveLayoutMigration';
import { createManifest, FILENAME_VERSION } from '$lib/shared/sync/driveManifest';

const canonical = { id: 'canonical-id', name: BOOKS_FOLDER };
const legacy = { id: 'legacy-id', name: LEGACY_BOOKS_FOLDER };

describe('driveLayoutMigration (WU6 FR-01 / filenameVersion gate)', () => {
  it('a fresh install creates the canonical folder and never consults the gate (FR-01)', () => {
    // Nothing to migrate: no legacy tree, so even a version-0 manifest cannot
    // block the fresh install.
    expect(planBooksFolderMigration(null, null, null)).toEqual({ kind: 'create' });
    expect(
      planBooksFolderMigration(null, null, createManifest()),
    ).toEqual({ kind: 'create' });
  });

  it('a legacy tree with filenameVersion < 1 is BLOCKED (layout must not migrate first)', () => {
    expect(planBooksFolderMigration(null, legacy, null)).toEqual({
      kind: 'blocked',
      folderId: 'legacy-id',
    });
    const v0 = createManifest();
    v0.filenameVersion = 0;
    expect(planBooksFolderMigration(null, legacy, v0)).toEqual({
      kind: 'blocked',
      folderId: 'legacy-id',
    });
  });

  it('a legacy tree with filenameVersion >= 1 renames in place', () => {
    const manifest = createManifest();
    manifest.filenameVersion = FILENAME_VERSION;
    expect(planBooksFolderMigration(null, legacy, manifest)).toEqual({
      kind: 'rename',
      folderId: 'legacy-id',
    });
  });

  it('adopts the canonical folder; a case-sensitive Books twin is left untouched', () => {
    // Both names present (Drive allows case-different siblings): canonical wins,
    // the legacy twin is never merged or deleted (Phase B parks it).
    expect(planBooksFolderMigration(canonical, legacy, null)).toEqual({
      kind: 'adopt',
      folderId: 'canonical-id',
    });
  });

  it('pickBooksFolder prefers canonical and falls back to legacy without mutating', () => {
    expect(pickBooksFolder(canonical, legacy)).toEqual(canonical);
    expect(pickBooksFolder(null, legacy)).toEqual(legacy);
    expect(pickBooksFolder(null, null)).toBeNull();
  });
});
