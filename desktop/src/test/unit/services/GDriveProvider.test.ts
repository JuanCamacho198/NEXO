/**
 * Unit tests for GDriveProvider — token source is the independent Drive
 * grant (login-drive-separation, work unit 3).
 * Tests behavior: token from DriveConnectService (Drive store + silent
 * refresh chain), optional `name` on upload.
 */
import { describe, it, expect, vi, beforeEach } from 'vitest';

// Mock DriveConnectService BEFORE importing GDriveProvider
vi.mock('$lib/shared/services/DriveConnectService', () => ({
  getDriveAccessToken: vi.fn(),
  refreshDriveAccessToken: vi.fn(),
}));

import {
  GDriveProvider,
  __resetGDriveFolderCache,
  type DriveError,
} from '$lib/shared/services/storage/GDriveProvider';
import {
  getDriveAccessToken,
  refreshDriveAccessToken,
} from '$lib/shared/services/DriveConnectService';

function mockDriveApiResponses(
  responses: Array<{
    ok: boolean;
    status?: number;
    json: () => unknown;
    text?: () => Promise<string>;
  }>,
) {
  let callIndex = 0;
  vi.mocked(globalThis.fetch).mockImplementation(async () => {
    const resp = responses[callIndex++] ?? responses[responses.length - 1];
    return {
      ok: resp.ok,
      status: resp.status ?? (resp.ok ? 200 : 401),
      statusText: resp.ok ? 'OK' : 'Unauthorized',
      json: resp.json,
      arrayBuffer: async () => new ArrayBuffer(0),
      text: resp.text ?? (async () => 'error'),
    } as Response;
  });
}

function mockAuth(token: string) {
  vi.mocked(getDriveAccessToken).mockResolvedValue(token);
}

function mockAuthError(message: string) {
  vi.mocked(getDriveAccessToken).mockRejectedValue(new Error(message));
}

describe('GDriveProvider — token source swap', () => {
  let provider: GDriveProvider;

  beforeEach(() => {
    vi.clearAllMocks();
    globalThis.fetch = vi.fn();
    __resetGDriveFolderCache();
    provider = new GDriveProvider();
  });

  it('calls getDriveAccessToken (Drive store) for authentication', async () => {
    mockAuth('ya29.test-token');
    mockDriveApiResponses([
      { ok: true, json: () => Promise.resolve({ files: [{ id: 'folder-1', name: 'Books' }] }) },
      { ok: true, json: () => Promise.resolve({ files: [] }) },
    ]);

    await provider.list('');

    expect(getDriveAccessToken).toHaveBeenCalled();
  });

  it('propagates typed AUTH_REQUIRED when the Drive grant is missing (Drive connect, not login)', async () => {
    mockAuthError('Google Drive is not connected. Connect Google Drive in Settings.');

    await expect(provider.list('')).rejects.toThrow(/not connected/i);
    expect(getDriveAccessToken).toHaveBeenCalled();
  });

  it('upload with custom name uses it in Drive API metadata', async () => {
    mockAuth('token-123');
    // Root folder, Books folder, find-by-name (no match), then upload
    mockDriveApiResponses([
      { ok: true, json: () => Promise.resolve({ files: [{ id: 'root-abc' }] }) },
      { ok: true, json: () => Promise.resolve({ files: [{ id: 'folder-abc' }] }) },
      { ok: true, json: () => Promise.resolve({ files: [] }) },
      { ok: true, json: () => Promise.resolve({ id: 'file-xyz' }) },
    ]);

    const data = new Uint8Array([1, 2, 3]);
    const result = await provider.upload('book-001', data, 'book-001_state.json');

    expect(result).toBe('file-xyz');
    // Root and Books folder lookups precede the upload.
    const uploadCall = vi.mocked(globalThis.fetch).mock.calls[3];
    expect(uploadCall[0]).toContain('upload/drive/v3/files');
    // Verify the FormData contains the custom name
    const formData = uploadCall[1]?.body as FormData;
    const metadataBlob = formData?.get('metadata') as Blob;
    const metadataText = await metadataBlob?.text();
    const metadata = JSON.parse(metadataText);
    expect(metadata.name).toBe('book-001_state.json');
    expect(metadata.parents).toEqual(['folder-abc']);
  });

  it('upload without name defaults to id as filename', async () => {
    mockAuth('token-456');
    mockDriveApiResponses([
      { ok: true, json: () => Promise.resolve({ files: [{ id: 'root-def' }] }) },
      { ok: true, json: () => Promise.resolve({ files: [{ id: 'folder-def' }] }) },
      { ok: true, json: () => Promise.resolve({ files: [] }) },
      { ok: true, json: () => Promise.resolve({ id: 'file-ghi' }) },
    ]);

    const data = new Uint8Array([4, 5, 6]);
    const result = await provider.upload('epub-001', data);

    expect(result).toBe('file-ghi');
    // Verify metadata name defaults to id
    const uploadCall = vi.mocked(globalThis.fetch).mock.calls[3];
    const formData = uploadCall[1]?.body as FormData;
    const metadataBlob = formData?.get('metadata') as Blob;
    const metadataText = await metadataBlob?.text();
    const metadata = JSON.parse(metadataText);
    expect(metadata.name).toBe('epub-001');
  });
});

describe('GDriveProvider — idempotent upload (DRP-3)', () => {
  let provider: GDriveProvider;

  beforeEach(() => {
    vi.clearAllMocks();
    globalThis.fetch = vi.fn();
    __resetGDriveFolderCache();
    provider = new GDriveProvider();
    vi.mocked(refreshDriveAccessToken).mockResolvedValue('ya29.refreshed-token');
  });

  it('RED: finds existing file by canonical name and PATCH-updates it (no duplicate create)', async () => {
    mockAuth('token-1');
    mockDriveApiResponses([
      { ok: true, json: () => Promise.resolve({ files: [{ id: 'root-1' }] }) },
      { ok: true, json: () => Promise.resolve({ files: [{ id: 'folder-1' }] }) },
      { ok: true, json: () => Promise.resolve({ files: [{ id: 'file-existing' }] }) },
      { ok: true, json: () => Promise.resolve({ id: 'file-existing' }) },
    ]);

    const fileId = await provider.upload('book-1', new Uint8Array([1]), 'book-1.epub');

    expect(fileId).toBe('file-existing');
    const uploadCall = vi.mocked(globalThis.fetch).mock.calls[3];
    expect(uploadCall[0]).toContain('files/file-existing?uploadType=multipart');
    expect(uploadCall[1]?.method).toBe('PATCH');
    // DRIVE_PARENTS_PATCH: `parents` must NOT be sent on update (PATCH) —
    // Google rejects it with 403 fieldNotWritable. Only `name` may be sent.
    const patchFormData = uploadCall[1]?.body as FormData;
    const patchMetadata = JSON.parse(await (patchFormData.get('metadata') as Blob).text());
    expect(patchMetadata.name).toBe('book-1.epub');
    expect(patchMetadata.parents).toBeUndefined();
  });

  it('RED: creates a new file when no file with the canonical name exists', async () => {
    mockAuth('token-2');
    mockDriveApiResponses([
      { ok: true, json: () => Promise.resolve({ files: [{ id: 'root-2' }] }) },
      { ok: true, json: () => Promise.resolve({ files: [{ id: 'folder-2' }] }) },
      { ok: true, json: () => Promise.resolve({ files: [] }) },
      { ok: true, json: () => Promise.resolve({ id: 'file-created' }) },
    ]);

    const fileId = await provider.upload('book-2', new Uint8Array([1]), 'book-2.epub');

    expect(fileId).toBe('file-created');
    const uploadCall = vi.mocked(globalThis.fetch).mock.calls[3];
    expect(uploadCall[0]).toContain('upload/drive/v3/files?uploadType=multipart');
    expect(uploadCall[1]?.method).toBe('POST');
    // On create (POST) `parents` IS valid and must be sent to place the file
    // in the Nexo/Books folder.
    const postFormData = uploadCall[1]?.body as FormData;
    const postMetadata = JSON.parse(await (postFormData.get('metadata') as Blob).text());
    expect(postMetadata.name).toBe('book-2.epub');
    expect(postMetadata.parents).toEqual(['folder-2']);
  });

  it('RED: picks the first non-trashed match when duplicate names exist', async () => {
    mockAuth('token-3');
    mockDriveApiResponses([
      { ok: true, json: () => Promise.resolve({ files: [{ id: 'root-3' }] }) },
      { ok: true, json: () => Promise.resolve({ files: [{ id: 'folder-3' }] }) },
      {
        ok: true,
        json: () =>
          Promise.resolve({ files: [{ id: 'trashed-1', trashed: true }, { id: 'keep-1' }] }),
      },
      { ok: true, json: () => Promise.resolve({ id: 'keep-1' }) },
    ]);

    const fileId = await provider.upload('book-3', new Uint8Array([1]), 'book-3.epub');

    expect(fileId).toBe('keep-1');
    const uploadCall = vi.mocked(globalThis.fetch).mock.calls[3];
    expect(uploadCall[0]).toContain('files/keep-1?uploadType=multipart');
    expect(uploadCall[1]?.method).toBe('PATCH');
  });

  it('RED: retry after lost response updates the existing file (DRP-3 scenario)', async () => {
    mockAuth('token-4');
    mockDriveApiResponses([
      { ok: true, json: () => Promise.resolve({ files: [{ id: 'root-4' }] }) },
      { ok: true, json: () => Promise.resolve({ files: [{ id: 'folder-4' }] }) },
      { ok: true, json: () => Promise.resolve({ files: [{ id: 'file-uploaded' }] }) },
      { ok: true, json: () => Promise.resolve({ id: 'file-uploaded' }) },
    ]);

    // Simulate the retry of a book whose upload response was lost
    const fileId = await provider.upload('book-4', new Uint8Array([1]), 'book-4.epub');

    expect(fileId).toBe('file-uploaded');
    // Only one upload request happened (PATCH), no POST create
    const uploadCalls = vi
      .mocked(globalThis.fetch)
      .mock.calls.filter((c) => String(c[0]).includes('uploadType=multipart'));
    expect(uploadCalls).toHaveLength(1);
    expect(uploadCalls[0][1]?.method).toBe('PATCH');
  });
});

describe('GDriveProvider — token refresh layers (DTL-1/DTL-2/DTL-3)', () => {
  let provider: GDriveProvider;

  beforeEach(() => {
    vi.clearAllMocks();
    globalThis.fetch = vi.fn();
    __resetGDriveFolderCache();
    provider = new GDriveProvider();
    vi.mocked(refreshDriveAccessToken).mockResolvedValue('ya29.refreshed-token');
  });

  it('uses the Drive store token directly — no session fallback (single refresh chain in the Drive module)', async () => {
    mockAuth('token-r0');
    mockDriveApiResponses([
      { ok: true, json: () => Promise.resolve({ files: [{ id: 'root-r0' }] }) },
      { ok: true, json: () => Promise.resolve({ files: [{ id: 'folder-r0' }] }) },
      { ok: true, json: () => Promise.resolve({ files: [] }) },
      { ok: true, json: () => Promise.resolve({ id: 'file-r0' }) },
    ]);

    const fileId = await provider.upload('book-r0', new Uint8Array([1]), 'book-r0.epub');

    expect(getDriveAccessToken).toHaveBeenCalledTimes(1);
    expect(refreshDriveAccessToken).not.toHaveBeenCalled();
    expect(fileId).toBe('file-r0');
  });

  it('propagates AUTH_REQUIRED from the Drive module when refresh is impossible (invalid grant)', async () => {
    mockAuth('token-r0b');
    vi.mocked(refreshDriveAccessToken).mockRejectedValue(
      Object.assign(new Error('Google Drive is not connected. Connect Google Drive in Settings.'), {
        code: 'AUTH_REQUIRED',
        retryable: false,
      }),
    );
    mockDriveApiResponses([
      { ok: true, json: () => Promise.resolve({ files: [{ id: 'root-r0b' }] }) },
      { ok: true, json: () => Promise.resolve({ files: [{ id: 'folder-r0b' }] }) },
      { ok: false, status: 401, json: () => Promise.resolve({}) },
    ]);

    await expect(provider.upload('book-r0b', new Uint8Array([1]), 'book-r0b.epub')).rejects.toThrow(
      /not connected/i,
    );

    expect(refreshDriveAccessToken).toHaveBeenCalledTimes(1);
  });

  it('RED: 401 mid-request → refresh once → retry once → succeeds (no duplicate work)', async () => {
    mockAuth('token-r2');
    mockDriveApiResponses([
      { ok: true, json: () => Promise.resolve({ files: [{ id: 'root-r2' }] }) },
      { ok: true, json: () => Promise.resolve({ files: [{ id: 'folder-r2' }] }) },
      { ok: false, status: 401, json: () => Promise.resolve({}) },
      { ok: true, json: () => Promise.resolve({ files: [{ id: 'file-r2' }] }) },
      { ok: true, json: () => Promise.resolve({ id: 'file-r2' }) },
    ]);

    const fileId = await provider.upload('book-r2', new Uint8Array([1]), 'book-r2.epub');

    expect(refreshDriveAccessToken).toHaveBeenCalledTimes(1);
    expect(fileId).toBe('file-r2');
  });

  it('RED: still 401 after refresh → typed AUTH_EXPIRED, refresh bounded to ONE attempt (no hot loop)', async () => {
    mockAuth('token-r3');
    mockDriveApiResponses([
      { ok: true, json: () => Promise.resolve({ files: [{ id: 'root-r3' }] }) },
      { ok: true, json: () => Promise.resolve({ files: [{ id: 'folder-r3' }] }) },
      { ok: false, status: 401, json: () => Promise.resolve({}) },
      { ok: false, status: 401, json: () => Promise.resolve({}) },
    ]);

    await expect(provider.upload('book-r3', new Uint8Array([1]), 'book-r3.epub')).rejects.toThrow(
      /connect google drive/i,
    );

    expect(refreshDriveAccessToken).toHaveBeenCalledTimes(1);
  });

  it('RED: 403 after refresh → typed PERMISSION_DENIED, refresh bounded to ONE attempt', async () => {
    mockAuth('token-r4');
    mockDriveApiResponses([
      { ok: true, json: () => Promise.resolve({ files: [{ id: 'root-r4' }] }) },
      { ok: true, json: () => Promise.resolve({ files: [{ id: 'folder-r4' }] }) },
      { ok: false, status: 403, json: () => Promise.resolve({}) },
      { ok: false, status: 403, json: () => Promise.resolve({}) },
    ]);

    await expect(provider.upload('book-r4', new Uint8Array([1]), 'book-r4.epub')).rejects.toThrow(
      /permission denied/i,
    );

    expect(refreshDriveAccessToken).toHaveBeenCalledTimes(1);
  });

  it('RED: second upload reuses the resolved folder (no duplicate Nexo/Books)', async () => {
    mockAuth('token-conc');
    // First upload: find Nexo (none) → create → find Books (none) → create → find file (none) → upload
    // Second upload (cache hit): find file (none) → upload — NO folder creations.
    mockDriveApiResponses([
      { ok: true, json: () => Promise.resolve({ files: [] }) }, // find Nexo → none
      { ok: true, json: () => Promise.resolve({ id: 'root-new' }) }, // create Nexo
      { ok: true, json: () => Promise.resolve({ files: [] }) }, // find Books → none
      { ok: true, json: () => Promise.resolve({ id: 'books-new' }) }, // create Books
      { ok: true, json: () => Promise.resolve({ files: [] }) }, // find file-a → none
      { ok: true, json: () => Promise.resolve({ id: 'file-a' }) }, // upload A
      { ok: true, json: () => Promise.resolve({ files: [] }) }, // find file-b → none
      { ok: true, json: () => Promise.resolve({ id: 'file-b' }) }, // upload B
    ]);

    await provider.upload('book-a', new Uint8Array([1]), 'book-a.epub');
    const b = await provider.upload('book-b', new Uint8Array([2]), 'book-b.epub');

    expect(b).toBe('file-b');
    // Exactly TWO folder-create calls total (one Nexo + one Books), never four.
    const createCalls = vi
      .mocked(globalThis.fetch)
      .mock.calls.filter((c) => String(c[1]?.body).includes('google-apps.folder'));
    expect(createCalls).toHaveLength(2);
  });

  it('RED: upload failure message redacts tokens (DTL-3 — no raw bearer in errors)', async () => {
    mockAuth('token-r5');
    mockDriveApiResponses([
      { ok: true, json: () => Promise.resolve({ files: [{ id: 'root-r5' }] }) },
      { ok: true, json: () => Promise.resolve({ files: [{ id: 'folder-r5' }] }) },
      { ok: true, json: () => Promise.resolve({ files: [] }) },
      {
        ok: false,
        status: 500,
        json: () => Promise.resolve({}),
        text: async () =>
          'authorization=ya29.SECRET-TOKEN and eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjMifQ.sig',
      },
    ]);

    const err = (await provider
      .upload('book-r5', new Uint8Array([1]), 'book-r5.epub')
      .catch((e: Error) => e)) as Error;
    // The raw token value must never appear in the surfaced error (DTL-3)
    expect(err.message).not.toContain('SECRET-TOKEN');
    // The redacted form replaces it
    expect(err.message).toContain('[REDACTED]');
  });
});

describe('GDriveProvider — delete (trash, REQ-11)', () => {
  let provider: GDriveProvider;

  beforeEach(() => {
    vi.clearAllMocks();
    globalThis.fetch = vi.fn();
    __resetGDriveFolderCache();
    provider = new GDriveProvider();
    vi.mocked(refreshDriveAccessToken).mockResolvedValue('ya29.refreshed-token');
  });

  it('trashes a file directly by ID (PATCH to the file ID)', async () => {
    mockAuth('token-del-1');
    mockDriveApiResponses([{ ok: true, json: () => Promise.resolve({}) }]);

    await provider.delete('1AbCdefGhIjKlMnOpQrStUvWxYz');

    expect(globalThis.fetch).toHaveBeenCalledTimes(1);
    const [url, init] = vi.mocked(globalThis.fetch).mock.calls[0];
    expect(String(url)).toContain('/files/1AbCdefGhIjKlMnOpQrStUvWxYz');
    expect(init?.method).toBe('PATCH');
    // fetchWithToken only forwards Authorization — content-type comes from the Blob
    expect(init?.headers).not.toHaveProperty('Content-Type');
    const body = init?.body as Blob;
    expect(body.type).toBe('application/json');
    expect(JSON.parse(await body.text())).toEqual({ trashed: true });
  });

  it('trashes by name: searches Nexo/Books then PATCHes the found ID', async () => {
    mockAuth('token-del-2');
    mockDriveApiResponses([
      { ok: true, json: () => Promise.resolve({ files: [{ id: 'root-del-2' }] }) },
      { ok: true, json: () => Promise.resolve({ files: [{ id: 'folder-del-2' }] }) },
      { ok: true, json: () => Promise.resolve({ files: [{ id: 'file-del-2' }] }) },
      { ok: true, json: () => Promise.resolve({}) },
    ]);

    await provider.delete('book-1.epub');

    const calls = vi.mocked(globalThis.fetch).mock.calls;
    // Search request scoped to Nexo/Books with trashed = false
    const searchUrl = decodeURIComponent(String(calls[2][0]));
    expect(searchUrl).toContain(
      "name = 'book-1.epub' and 'folder-del-2' in parents and trashed = false",
    );
    // PATCH goes to the resolved file ID
    const [url, init] = calls[3];
    expect(String(url)).toContain('/files/file-del-2');
    expect(init?.method).toBe('PATCH');
    const body = init?.body as Blob;
    expect(body.type).toBe('application/json');
    expect(JSON.parse(await body.text())).toEqual({ trashed: true });
  });

  it('no-ops (idempotent) when no non-trashed file matches the name', async () => {
    mockAuth('token-del-3');
    mockDriveApiResponses([
      { ok: true, json: () => Promise.resolve({ files: [{ id: 'root-del-3' }] }) },
      { ok: true, json: () => Promise.resolve({ files: [{ id: 'folder-del-3' }] }) },
      { ok: true, json: () => Promise.resolve({ files: [] }) },
    ]);

    await expect(provider.delete('missing.epub')).resolves.toBeUndefined();

    const patchCalls = vi
      .mocked(globalThis.fetch)
      .mock.calls.filter(([, init]) => init?.method === 'PATCH');
    expect(patchCalls).toHaveLength(0);
  });

  it('maps 401 to typed AUTH_EXPIRED (parity with upload/download)', async () => {
    mockAuth('token-del-4');
    mockDriveApiResponses([{ ok: false, status: 401, json: () => Promise.resolve({}) }]);

    const err = (await provider
      .delete('1AbCdefGhIjKlMnOpQrStUvWxYz')
      .catch((e) => e)) as DriveError;

    expect(err.code).toBe('AUTH_EXPIRED');
    expect(err.retryable).toBe(false);
  });

  it('maps 403 to typed PERMISSION_DENIED (parity with upload/download)', async () => {
    mockAuth('token-del-5');
    mockDriveApiResponses([{ ok: false, status: 403, json: () => Promise.resolve({}) }]);

    const err = (await provider
      .delete('1AbCdefGhIjKlMnOpQrStUvWxYz')
      .catch((e) => e)) as DriveError;

    expect(err.code).toBe('PERMISSION_DENIED');
    expect(err.retryable).toBe(false);
  });
});

describe('GDriveProvider — getUsage (read-only Drive byte measurement)', () => {
  let provider: GDriveProvider;

  beforeEach(() => {
    vi.clearAllMocks();
    globalThis.fetch = vi.fn();
    __resetGDriveFolderCache();
    provider = new GDriveProvider();
    vi.mocked(refreshDriveAccessToken).mockResolvedValue('ya29.refreshed-token');
  });

  it('sums every page of Nexo/Books without creating folders', async () => {
    mockAuth('token-usage-1');
    mockDriveApiResponses([
      { ok: true, json: () => Promise.resolve({ files: [{ id: 'root-u' }] }) },
      { ok: true, json: () => Promise.resolve({ files: [{ id: 'folder-u' }] }) },
      {
        ok: true,
        json: () =>
          Promise.resolve({ files: [{ size: '100' }, { size: '250' }], nextPageToken: 'tok' }),
      },
      { ok: true, json: () => Promise.resolve({ files: [{ size: '50' }] }) },
    ]);

    const usage = await provider.getUsage();

    expect(usage).toEqual({ bytes: 400, fileCount: 3 });
    // No folder creation (POST) happened on a read-only measurement.
    const createCalls = vi
      .mocked(globalThis.fetch)
      .mock.calls.filter((c) => c[1]?.method === 'POST');
    expect(createCalls).toHaveLength(0);
    // The second page request forwards the page token.
    const pageCall = String(vi.mocked(globalThis.fetch).mock.calls[3][0]);
    expect(pageCall).toContain('pageToken=tok');
    expect(pageCall).toContain('fields=');
  });

  it('returns zero without creating Nexo/Books when the folders do not exist', async () => {
    mockAuth('token-usage-2');
    mockDriveApiResponses([{ ok: true, json: () => Promise.resolve({ files: [] }) }]);

    const usage = await provider.getUsage();

    expect(usage).toEqual({ bytes: 0, fileCount: 0 });
    expect(globalThis.fetch).toHaveBeenCalledTimes(1);
  });

  it('treats files without a size (folders) as zero bytes', async () => {
    mockAuth('token-usage-3');
    mockDriveApiResponses([
      { ok: true, json: () => Promise.resolve({ files: [{ id: 'root-u3' }] }) },
      { ok: true, json: () => Promise.resolve({ files: [{ id: 'folder-u3' }] }) },
      {
        ok: true,
        json: () => Promise.resolve({ files: [{}, { size: '10' }, { id: 'nested-folder' }] }),
      },
    ]);

    const usage = await provider.getUsage();

    expect(usage).toEqual({ bytes: 10, fileCount: 3 });
  });

  it('surfaces a typed AUTH_EXPIRED instead of reporting 0 bytes on 401', async () => {
    mockAuth('token-usage-4');
    mockDriveApiResponses([
      { ok: true, json: () => Promise.resolve({ files: [{ id: 'root-u4' }] }) },
      { ok: true, json: () => Promise.resolve({ files: [{ id: 'folder-u4' }] }) },
      { ok: false, status: 401, json: () => Promise.resolve({}) },
      { ok: false, status: 401, json: () => Promise.resolve({}) },
    ]);

    const err = (await provider.getUsage().catch((e) => e)) as DriveError;

    expect(err.code).toBe('AUTH_EXPIRED');
    expect(err.retryable).toBe(false);
  });
});

describe('GDriveProvider — FR-08 marker + guarded upload', () => {
  let provider: GDriveProvider;

  beforeEach(() => {
    vi.clearAllMocks();
    globalThis.fetch = vi.fn();
    __resetGDriveFolderCache();
    provider = new GDriveProvider();
    vi.mocked(refreshDriveAccessToken).mockResolvedValue('ya29.refreshed-token');
  });

  it('readMarker parses appProperties, and null when the marker is absent', async () => {
    mockAuth('token-marker');
    mockDriveApiResponses([
      { ok: true, json: () => Promise.resolve({ files: [{ id: 'file-m', name: 'x.epub' }] }) },
      {
        ok: true,
        json: () => Promise.resolve({ appProperties: { nexoVersion: '3', nexoChecksum: 'ab' } }),
      },
    ]);

    expect(await provider.readMarker('x.epub')).toEqual({ version: 3, checksum: 'ab' });
  });

  it('readMarker returns null when the file has no marker', async () => {
    mockAuth('token-marker-none');
    mockDriveApiResponses([
      { ok: true, json: () => Promise.resolve({ files: [{ id: 'file-n', name: 'y.epub' }] }) },
      { ok: true, json: () => Promise.resolve({}) },
    ]);

    expect(await provider.readMarker('y.epub')).toBeNull();
  });

  it('uploadGuarded sends the marker in the Drive metadata (create)', async () => {
    mockAuth('token-guard');
    mockDriveApiResponses([
      { ok: true, json: () => Promise.resolve({ files: [{ id: 'root-g' }] }) },
      { ok: true, json: () => Promise.resolve({ files: [{ id: 'folder-g' }] }) },
      { ok: true, json: () => Promise.resolve({ files: [] }) },
      { ok: true, json: () => Promise.resolve({ id: 'file-g' }) },
    ]);

    const fileId = await provider.uploadGuarded('a.epub', new Uint8Array([1]), {
      version: 2,
      checksum: 'cd',
    });

    expect(fileId).toBe('file-g');
    const uploadCall = vi.mocked(globalThis.fetch).mock.calls[3];
    expect(uploadCall[1]?.method).toBe('POST');
    const metadata = JSON.parse(
      await ((uploadCall[1]?.body as FormData).get('metadata') as Blob).text(),
    );
    expect(metadata).toEqual({
      name: 'a.epub',
      appProperties: { nexoVersion: '2', nexoChecksum: 'cd' },
      parents: ['folder-g'],
    });
  });
});

describe('GDriveProvider — WU6 gated books-folder migration (Nexo/books)', () => {
  let provider: GDriveProvider;

  beforeEach(() => {
    vi.clearAllMocks();
    globalThis.fetch = vi.fn();
    __resetGDriveFolderCache();
    provider = new GDriveProvider();
    vi.mocked(refreshDriveAccessToken).mockResolvedValue('ya29.refreshed-token');
  });

  it('keeps the legacy Books folder when no manifest proves filenameVersion >= 1 (blocked)', async () => {
    mockAuth('token-legacy-blocked');
    mockDriveApiResponses([
      { ok: true, json: () => Promise.resolve({ files: [{ id: 'root-l' }] }) }, // find Nexo
      { ok: true, json: () => Promise.resolve({ files: [{ id: 'legacy-folder', name: 'Books' }] }) }, // combined
      { ok: true, json: () => Promise.resolve({ files: [{ id: 'root-l' }] }) }, // findFolderStrict Nexo
      { ok: true, json: () => Promise.resolve({ files: [] }) }, // manifest absent
      { ok: true, json: () => Promise.resolve({ files: [] }) }, // find file
      { ok: true, json: () => Promise.resolve({ id: 'file-l' }) }, // create
    ]);

    const fileId = await provider.upload('b', new Uint8Array([1]), 'b.epub');

    expect(fileId).toBe('file-l');
    const patchCalls = vi
      .mocked(globalThis.fetch)
      .mock.calls.filter(([, init]) => init?.method === 'PATCH');
    expect(patchCalls).toHaveLength(0); // never renames before WU4 is proven
    const metadata = JSON.parse(
      await ((vi.mocked(globalThis.fetch).mock.calls[5][1]?.body as FormData).get('metadata') as Blob).text(),
    );
    expect(metadata.parents).toEqual(['legacy-folder']); // still writes into legacy
  });

  it('renames the legacy Books folder in place once the manifest reports filenameVersion 1', async () => {
    mockAuth('token-legacy-rename');
    mockDriveApiResponses([
      { ok: true, json: () => Promise.resolve({ files: [{ id: 'root-l2' }] }) }, // find Nexo
      { ok: true, json: () => Promise.resolve({ files: [{ id: 'legacy-folder', name: 'Books' }] }) }, // combined
      { ok: true, json: () => Promise.resolve({ files: [{ id: 'root-l2' }] }) }, // findFolderStrict Nexo
      { ok: true, json: () => Promise.resolve({ files: [{ id: 'manifest-f' }] }) }, // manifest search
      { ok: true, json: () => Promise.resolve({}), text: async () => '{"filenameVersion":1}' }, // media
      { ok: true, json: () => Promise.resolve({}) }, // rename PATCH
      { ok: true, json: () => Promise.resolve({ files: [] }) }, // find file
      { ok: true, json: () => Promise.resolve({ id: 'file-l2' }) }, // create
    ]);

    await provider.upload('b', new Uint8Array([1]), 'b.epub');

    const renameCall = vi
      .mocked(globalThis.fetch)
      .mock.calls.find(
        ([url, init]) =>
          String(url).includes('/files/legacy-folder') && init?.method === 'PATCH',
      );
    expect(renameCall).toBeDefined();
    const body = renameCall?.[1]?.body as Blob;
    expect(JSON.parse(await body.text())).toEqual({ name: 'books' });
    // Same folder id keeps every child: the create still lands in legacy-folder.
    const metadata = JSON.parse(
      await ((vi.mocked(globalThis.fetch).mock.calls[7][1]?.body as FormData).get('metadata') as Blob).text(),
    );
    expect(metadata.parents).toEqual(['legacy-folder']);
  });
});
