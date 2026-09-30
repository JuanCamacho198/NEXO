/** PR4 Desktop recovery import — reinstall zero-delete, import success, mismatch cleanup, idempotent retry, unavailable/auth, remote mapping. */
import { describe, it, expect, vi } from 'vitest';
import {
  classifyRecoveryCatalog,
  importRecoveredBook,
  importSelectedBooks,
  resolveRemoteRef,
  sha256Hex,
} from '$lib/shared/recovery/desktopRecoveryImport';
import { canonicalBookName } from '$lib/shared/protocol/DriveCatalogContract';
import type { SupabaseUserBookRow } from '$lib/shared/sync/SupabaseBookCatalogSync';
const row = (o: Partial<SupabaseUserBookRow> = {}): SupabaseUserBookRow => ({
  id: 'book-1',
  userId: 'user-1',
  title: 'Recovered Book',
  author: 'Author',
  format: 'epub',
  contentHash: null,
  filePath: null,
  coverUrl: null,
  description: null,
  totalPages: null,
  sourceDevice: 'android',
  importedAt: '2025-01-01T00:00:00Z',
  updatedAt: '2025-06-01T00:00:00Z',
  lifecycle: 'available',
  catalogVersion: 2,
  remoteProvider: 'google_drive',
  remoteFileId: 'file-abc123',
  remotePath: 'Nexo/Books/book-1.epub',
  remoteName: 'book-1.epub',
  protocolVersion: 1,
  ...o,
});
const deps = (o: Partial<ReturnType<typeof mkDeps>> = {}) => ({ ...mkDeps(), ...o });
const mkDeps = () => ({
  download: vi.fn().mockResolvedValue(new TextEncoder().encode('book-bytes')),
  persist: vi.fn().mockResolvedValue(undefined),
  markImported: vi.fn().mockResolvedValue(undefined),
  findByHash: vi.fn().mockResolvedValue(null),
});
describe('classifyRecoveryCatalog — reinstall zero-delete', () => {
  it('fresh install: deleted/unavailable excluded, no local ids, zero deletion emitted', () => {
    const remote = [
      row({ id: 'a', lifecycle: 'available' }),
      row({ id: 'b', lifecycle: 'imported' }),
      row({ id: 'c', lifecycle: 'unavailable' }),
      row({ id: 'd', lifecycle: 'deleted', deletedAt: '2025-05-01T00:00:00Z' }),
    ];
    const { downloadable, deleted } = classifyRecoveryCatalog(remote, new Set());
    expect(downloadable.map((r) => r.id)).toEqual(['a', 'b']);
    expect(deleted.map((r) => r.id)).toEqual(['d']);
  });
  it('local absence of a remote row never removes it from downloadable', () => {
    expect(classifyRecoveryCatalog([row()], new Set(['other-book'])).downloadable).toHaveLength(1);
  });
});
describe('resolveRemoteRef — remote mapping', () => {
  it('prefers stable remote file id, falls back to canonical name', () => {
    expect(resolveRemoteRef(row())).toBe('file-abc123');
    expect(resolveRemoteRef(row({ remoteFileId: null }))).toBe(canonicalBookName('book-1', 'epub'));
  });
});
describe('importRecoveredBook — selection/import success', () => {
  it('downloads, verifies hash, persists atomically, marks imported with version bump', async () => {
    const bytes = new TextEncoder().encode('book-bytes');
    const d = deps({ download: vi.fn().mockResolvedValue(bytes) });
    const result = await importRecoveredBook(
      row({ contentHash: `sha256:${await sha256Hex(bytes)}` }),
      d,
    );
    expect(result.outcome).toBe('imported');
    expect(d.persist).toHaveBeenCalledWith(
      'book-1',
      bytes,
      expect.objectContaining({ title: 'Recovered Book', format: 'epub' }),
    );
    expect(d.markImported).toHaveBeenCalledWith('book-1', 3);
  });
  it('imports multiple selected books independently', async () => {
    const d = deps();
    expect(
      (await importSelectedBooks([row({ id: 'a' }), row({ id: 'b' })], d)).map((r) => r.outcome),
    ).toEqual(['imported', 'imported']);
    expect(d.persist).toHaveBeenCalledTimes(2);
  });
});
describe('importRecoveredBook — mismatch cleanup and integrity', () => {
  it('aborts on SHA-256 mismatch: no persistence, typed HASH_MISMATCH', async () => {
    const d = deps({ download: vi.fn().mockResolvedValue(new TextEncoder().encode('tampered')) });
    const result = await importRecoveredBook(row({ contentHash: 'sha256:' + '0'.repeat(64) }), d);
    expect(result.outcome).toBe('failed');
    expect(result.error?.code).toBe('HASH_MISMATCH');
    expect(result.error?.retryable).toBe(true);
    expect(d.persist).not.toHaveBeenCalled();
    expect(d.markImported).not.toHaveBeenCalled();
  });
  it('duplicate hash already imported → already_imported without download', async () => {
    const d = deps({ findByHash: vi.fn().mockResolvedValue(row({ id: 'existing' })) });
    const result = await importRecoveredBook(row({ contentHash: 'sha256:abc' }), d);
    expect(result.outcome).toBe('already_imported');
    expect(d.download).not.toHaveBeenCalled();
  });
  it('retry after transient persist failure is idempotent (no partial book)', async () => {
    const d = deps();
    d.persist.mockRejectedValueOnce(new Error('disk full')).mockResolvedValueOnce(undefined);
    const bytes = new TextEncoder().encode('book-bytes');
    const r = row({ contentHash: `sha256:${await sha256Hex(bytes)}` });
    expect((await importRecoveredBook(r, d)).outcome).toBe('failed');
    expect(d.markImported).not.toHaveBeenCalled();
    expect((await importRecoveredBook(r, d)).outcome).toBe('imported');
    expect(d.persist).toHaveBeenCalledTimes(2);
  });
});
describe('importRecoveredBook — unavailable and auth errors', () => {
  it('deleted/unavailable rows are never imported', async () => {
    const d = deps();
    expect((await importRecoveredBook(row({ lifecycle: 'deleted' }), d)).outcome).toBe(
      'unavailable',
    );
    expect((await importRecoveredBook(row({ lifecycle: 'unavailable' }), d)).outcome).toBe(
      'unavailable',
    );
    expect(d.download).not.toHaveBeenCalled();
    expect(d.persist).not.toHaveBeenCalled();
  });
  it('maps Drive auth failure to AUTH_REQUIRED without persistence', async () => {
    const d = deps({
      download: vi
        .fn()
        .mockRejectedValue(
          new Error('Google Drive authentication required. Please sign in with Google again.'),
        ),
    });
    const result = await importRecoveredBook(row(), d);
    expect(result.outcome).toBe('failed');
    expect(result.error?.code).toBe('AUTH_REQUIRED');
    expect(d.persist).not.toHaveBeenCalled();
  });
  it('maps missing remote file to REMOTE_NOT_FOUND', async () => {
    const d = deps({ download: vi.fn().mockRejectedValue(new Error('File not found on GDrive')) });
    const result = await importRecoveredBook(row(), d);
    expect(result.outcome).toBe('failed');
    expect(result.error?.code).toBe('REMOTE_NOT_FOUND');
  });
});
