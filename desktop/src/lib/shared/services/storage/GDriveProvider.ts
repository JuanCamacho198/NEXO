import {
  getDriveAccessToken,
  refreshDriveAccessToken,
} from '$lib/shared/services/DriveConnectService';
import { DRIVE_BOOKS_PATH } from '$lib/shared/protocol/DriveCatalogContract';
import { redactLogLine, type SyncErrorCode } from '$lib/shared/protocol/DriveCatalogContract';
import {
  appPropertiesFor,
  markerFromAppProperties,
  type DriveGuardPort,
  type VersionMarker,
} from '$lib/shared/sync/driveWriteGuard';
import type { StorageProvider } from './StorageProvider';

/**
 * Typed Drive error: carries a stable SyncErrorCode (`AUTH_EXPIRED`,
 * `AUTH_REQUIRED`, `PERMISSION_DENIED`) and `retryable=false` so callers can
 * surface a Drive-connect prompt instead of silently retrying. Messages are
 * always redacted (DTL-3).
 */
export type DriveError = Error & { code?: SyncErrorCode; retryable?: boolean };

/**
 * Module-level folder-resolution cache shared across ALL GDriveProvider
 * instances (SyncService.gdrive and GoogleDriveStateSync.gdrive are separate
 * instances). Without it, concurrent sync paths (syncBooks + syncState in
 * Promise.all) race to create duplicate Nexo/Books trees on first run.
 */
let folderIds: string | null = null;
let folderIdsPromise: Promise<string> | null = null;

/** Reset the module-level folder cache (used by tests between cases). */
export function __resetGDriveFolderCache(): void {
  folderIds = null;
  folderIdsPromise = null;
}

export class GDriveProvider implements StorageProvider, DriveGuardPort {
  private static readonly GDRIVE_API_BASE = 'https://www.googleapis.com/drive/v3';
  private static readonly GDRIVE_UPLOAD_BASE = 'https://www.googleapis.com/upload/drive/v3';
  private static readonly FOLDER_NAME = DRIVE_BOOKS_PATH;

  /**
   * Resolve a usable Drive access token once per operation from the
   * independent Drive grant (`drive.json`, login-drive-separation). The Drive
   * module owns the store → silent-refresh chain and the single-flight refresh
   * mutex; refresh failure throws a typed `AUTH_REQUIRED` pointing at Drive
   * connect (DTL-2).
   */
  private async getAccessToken(): Promise<string> {
    return getDriveAccessToken();
  }

  private authError(code: SyncErrorCode, message: string): DriveError {
    const err = new Error(redactLogLine(message)) as DriveError;
    err.code = code;
    err.retryable = false;
    return err;
  }

  /** Map a Drive API HTTP failure to a typed, redacted error (DTL-3). */
  private async driveError(prefix: string, response: Response): Promise<never> {
    const body = await response.text().catch(() => '');
    if (response.status === 401) {
      throw this.authError(
        'AUTH_EXPIRED',
        `${prefix}: Google Drive access expired. Connect Google Drive in Settings to use Drive features.`,
      );
    }
    if (response.status === 403) {
      throw this.authError('PERMISSION_DENIED', `${prefix}: Google Drive permission denied.`);
    }
    throw new Error(redactLogLine(`${prefix}: ${body || response.statusText}`));
  }

  /**
   * Perform a Drive API request with a resolved token. On HTTP 401/403 the
   * token is refreshed ONCE and the request retried ONCE (DTL-2, Android
   * DriveCoordinator parity). No hot loop: at most one refresh per request,
   * then a typed AUTH_EXPIRED/PERMISSION_DENIED error surfaces.
   */
  private async fetchWithToken(
    url: string,
    init: { method?: string; body?: BodyInit },
    token: string,
  ): Promise<Response> {
    const authorized = (t: string): RequestInit => ({
      ...init,
      headers: { Authorization: `Bearer ${t}` },
    });
    let response = await fetch(url, authorized(token));
    if (response.status === 401 || response.status === 403) {
      token = await refreshDriveAccessToken(); // throws typed AUTH_REQUIRED when refresh is impossible
      response = await fetch(url, authorized(token));
    }
    return response;
  }

  private async getOrCreateFolder(accessToken: string): Promise<string> {
    // Module-level memoization: the first caller resolves the folder tree,
    // concurrent callers await the SAME promise instead of racing to create
    // duplicate Nexo/Books folders (DRIVE_DUP_FOLDERS).
    if (folderIds !== null) return folderIds;
    if (folderIdsPromise === null) {
      folderIdsPromise = this.resolveFolderIds(accessToken);
    }
    return folderIdsPromise;
  }

  private async resolveFolderIds(accessToken: string): Promise<string> {
    const root =
      (await this.findFolder(accessToken, 'Nexo')) ??
      (await this.createFolder(accessToken, 'Nexo'));
    const books = await this.findFolder(accessToken, 'Books', root);
    if (books) {
      folderIds = books;
      return books;
    }
    const createResponse = await fetch(`${GDriveProvider.GDRIVE_API_BASE}/files`, {
      method: 'POST',
      headers: {
        Authorization: `Bearer ${accessToken}`,
        'Content-Type': 'application/json',
      },
      body: JSON.stringify({
        name: 'Books',
        mimeType: 'application/vnd.google-apps.folder',
        parents: [root],
      }),
    });
    if (!createResponse.ok)
      throw await this.driveError('GDrive folder creation failed', createResponse);
    const createData = await createResponse.json();
    folderIds = createData.id;
    return createData.id;
  }

  private async findFolder(
    accessToken: string,
    name: string,
    parentId?: string,
  ): Promise<string | null> {
    const parent = parentId ? ` and '${parentId}' in parents` : '';
    const query = encodeURIComponent(
      `name = '${name}' and mimeType = 'application/vnd.google-apps.folder' and trashed = false${parent}`,
    );
    const response = await fetch(`${GDriveProvider.GDRIVE_API_BASE}/files?q=${query}`, {
      headers: { Authorization: `Bearer ${accessToken}` },
    });
    const data = await response.json();
    return data.files?.[0]?.id ?? null;
  }

  /**
   * `findFolder` with honest failure semantics: a non-OK response throws a
   * typed, redacted Drive error instead of collapsing an auth/permission
   * failure into "folder absent" (which would misreport usage as 0 bytes).
   */
  private async findFolderStrict(
    accessToken: string,
    name: string,
    parentId?: string,
  ): Promise<string | null> {
    const parent = parentId ? ` and '${parentId}' in parents` : '';
    const query = encodeURIComponent(
      `name = '${name}' and mimeType = 'application/vnd.google-apps.folder' and trashed = false${parent}`,
    );
    const response = await this.fetchWithToken(
      `${GDriveProvider.GDRIVE_API_BASE}/files?q=${query}&fields=files(id)`,
      { method: 'GET' },
      accessToken,
    );
    if (!response.ok) throw await this.driveError('GDrive search failed', response);
    const data = await response.json();
    return data.files?.[0]?.id ?? null;
  }

  private async createFolder(accessToken: string, name: string): Promise<string> {
    const response = await fetch(`${GDriveProvider.GDRIVE_API_BASE}/files`, {
      method: 'POST',
      headers: { Authorization: `Bearer ${accessToken}`, 'Content-Type': 'application/json' },
      body: JSON.stringify({ name, mimeType: 'application/vnd.google-apps.folder' }),
    });
    if (!response.ok) throw await this.driveError('GDrive folder creation failed', response);
    return (await response.json()).id;
  }

  /**
   * Upload a file to `Nexo/Books`, idempotently (DRP-3): find an existing
   * non-trashed file by canonical name and PATCH-update it (Android
   * findFileByName → files().update parity); otherwise POST-create. Never
   * creates a second Drive file for the same canonical name. Returns the real
   * Drive file ID (create or update).
   */
  async upload(id: string, file: Uint8Array, name?: string): Promise<string> {
    const accessToken = await this.getAccessToken();
    const folderId = await this.getOrCreateFolder(accessToken);
    const fileName = name || id;

    const query = encodeURIComponent(
      `name = '${fileName}' and '${folderId}' in parents and trashed = false`,
    );
    const searchResponse = await this.fetchWithToken(
      `${GDriveProvider.GDRIVE_API_BASE}/files?q=${query}`,
      { method: 'GET' },
      accessToken,
    );
    if (!searchResponse.ok) throw await this.driveError('GDrive search failed', searchResponse);
    const searchData = await searchResponse.json();
    const existing =
      (searchData.files ?? []).find((f: { trashed?: boolean }) => !f.trashed) ?? null;

    // Google Drive rejects `parents` in update (PATCH) requests with
    // 403 fieldNotWritable: "The parents field is not directly writable in
    // update requests. Use the addParents and removeParents parameters instead."
    // So `parents` is only sent on create (POST); updates send only `name`.
    const method = existing ? 'PATCH' : 'POST';
    const metadata = existing ? { name: fileName } : { name: fileName, parents: [folderId] };

    const formData = new FormData();
    formData.append('metadata', new Blob([JSON.stringify(metadata)], { type: 'application/json' }));
    formData.append('file', new Blob([file.buffer as ArrayBuffer]));

    const url = existing
      ? `${GDriveProvider.GDRIVE_UPLOAD_BASE}/files/${existing.id}?uploadType=multipart`
      : `${GDriveProvider.GDRIVE_UPLOAD_BASE}/files?uploadType=multipart`;

    const response = await this.fetchWithToken(url, { method, body: formData }, accessToken);

    if (!response.ok) {
      throw await this.driveError('GDrive Upload Failed', response);
    }

    const data = await response.json();
    return data.id; // Returns the GDrive file ID (create or update)
  }

  /**
   * FR-08 guard port: read the `{ nexoVersion, nexoChecksum }` marker recorded
   * in the object's Drive `appProperties`. Returns null when the object or the
   * marker is absent; the guard treats that as version 0.
   */
  async readMarker(objectName: string): Promise<VersionMarker | null> {
    const accessToken = await this.getAccessToken();
    const query = encodeURIComponent(`name = '${objectName}' and trashed = false`);
    const search = await this.fetchWithToken(
      `${GDriveProvider.GDRIVE_API_BASE}/files?q=${query}`,
      { method: 'GET' },
      accessToken,
    );
    if (!search.ok) throw await this.driveError('GDrive marker search failed', search);
    const listing = await search.json();
    const existing =
      (listing.files ?? []).find((f: { trashed?: boolean }) => !f.trashed) ?? null;
    if (!existing?.id) return null;

    const meta = await this.fetchWithToken(
      `${GDriveProvider.GDRIVE_API_BASE}/files/${existing.id}?fields=appProperties`,
      { method: 'GET' },
      accessToken,
    );
    if (!meta.ok) throw await this.driveError('GDrive marker read failed', meta);
    const body = await meta.json();
    return markerFromAppProperties(body.appProperties ?? null);
  }

  /** FR-08 guard port: write bytes + marker in one `files.update`/create. */
  async writeBinary(
    objectName: string,
    bytes: Uint8Array,
    marker: VersionMarker,
  ): Promise<string> {
    return this.uploadGuarded(objectName, bytes, marker);
  }

  /**
   * Guarded upload: same idempotent find-by-name + PATCH/POST flow as
   * {@link upload}, but the metadata carries the `{ nexoVersion, nexoChecksum }`
   * marker so the binary and its version marker move together.
   */
  async uploadGuarded(
    objectName: string,
    bytes: Uint8Array,
    marker: VersionMarker,
  ): Promise<string> {
    const accessToken = await this.getAccessToken();
    const folderId = await this.getOrCreateFolder(accessToken);
    const query = encodeURIComponent(
      `name = '${objectName}' and '${folderId}' in parents and trashed = false`,
    );
    const searchResponse = await this.fetchWithToken(
      `${GDriveProvider.GDRIVE_API_BASE}/files?q=${query}`,
      { method: 'GET' },
      accessToken,
    );
    if (!searchResponse.ok) throw await this.driveError('GDrive search failed', searchResponse);
    const searchData = await searchResponse.json();
    const existing =
      (searchData.files ?? []).find((f: { trashed?: boolean }) => !f.trashed) ?? null;

    const appProperties = appPropertiesFor(marker);
    const method = existing ? 'PATCH' : 'POST';
    const metadata = existing
      ? { name: objectName, appProperties }
      : { name: objectName, appProperties, parents: [folderId] };

    const formData = new FormData();
    formData.append('metadata', new Blob([JSON.stringify(metadata)], { type: 'application/json' }));
    formData.append('file', new Blob([bytes.buffer as ArrayBuffer]));

    const url = existing
      ? `${GDriveProvider.GDRIVE_UPLOAD_BASE}/files/${existing.id}?uploadType=multipart`
      : `${GDriveProvider.GDRIVE_UPLOAD_BASE}/files?uploadType=multipart`;

    const response = await this.fetchWithToken(url, { method, body: formData }, accessToken);
    if (!response.ok) throw await this.driveError('GDrive guarded upload failed', response);
    return (await response.json()).id;
  }

  async download(remotePath: string): Promise<Uint8Array> {
    const accessToken = await this.getAccessToken();

    // remotePath here is expected to be the GDrive file ID or we need to find it by name
    // Given the interface, if we use file names as IDs:
    let fileId = remotePath;
    if (!remotePath.match(/^[a-zA-Z0-9_-]{25,}$/)) {
      // Heuristic to check if it's an ID or name
      // It's probably a name, find the ID
      const query = encodeURIComponent(`name = '${remotePath}' and trashed = false`);
      const searchResponse = await this.fetchWithToken(
        `${GDriveProvider.GDRIVE_API_BASE}/files?q=${query}`,
        { method: 'GET' },
        accessToken,
      );
      if (!searchResponse.ok) throw await this.driveError('GDrive search failed', searchResponse);
      const searchData = await searchResponse.json();
      if (!searchData.files || searchData.files.length === 0) {
        throw new Error(`File not found on GDrive: ${remotePath}`);
      }
      fileId = searchData.files[0].id;
    }

    const response = await this.fetchWithToken(
      `${GDriveProvider.GDRIVE_API_BASE}/files/${fileId}?alt=media`,
      { method: 'GET' },
      accessToken,
    );

    if (!response.ok) {
      throw await this.driveError('GDrive Download Failed', response);
    }

    const buffer = await response.arrayBuffer();
    return new Uint8Array(buffer);
  }

  /**
   * Trash a Drive book (NOT permanent delete, REQ-11): resolve the file ID
   * when `remotePath` is not ID-shaped (search by name inside `Nexo/Books`,
   * same heuristic as `download`), then `PATCH /files/{fileId}` with
   * `{ trashed: true }` (files.update). Idempotent: a missing file is a no-op.
   * Trashed files stop appearing in `list()` (its query filters
   * `trashed = false`), so the shelf section self-cleans.
   */
  async delete(remotePath: string): Promise<void> {
    const accessToken = await this.getAccessToken();

    let fileId = remotePath;
    if (!remotePath.match(/^[a-zA-Z0-9_-]{25,}$/)) {
      // Name-shaped path: resolve the ID inside Nexo/Books (upload parity).
      const folderId = await this.getOrCreateFolder(accessToken);
      const query = encodeURIComponent(
        `name = '${remotePath}' and '${folderId}' in parents and trashed = false`,
      );
      const searchResponse = await this.fetchWithToken(
        `${GDriveProvider.GDRIVE_API_BASE}/files?q=${query}`,
        { method: 'GET' },
        accessToken,
      );
      if (!searchResponse.ok) throw await this.driveError('GDrive search failed', searchResponse);
      const searchData = await searchResponse.json();
      const file = (searchData.files ?? []).find((f: { trashed?: boolean }) => !f.trashed);
      if (!file) return; // nothing to trash — idempotent no-op
      fileId = file.id;
    }

    // fetchWithToken overwrites headers (Authorization only) — the JSON
    // Content-Type must come from the Blob, never from init.headers (upload
    // parity). Reusing the Blob on the refresh-retry is safe (Blobs re-read).
    const body = new Blob([JSON.stringify({ trashed: true })], {
      type: 'application/json',
    });
    const response = await this.fetchWithToken(
      `${GDriveProvider.GDRIVE_API_BASE}/files/${fileId}`,
      { method: 'PATCH', body },
      accessToken,
    );
    if (!response.ok) throw await this.driveError('GDrive trash failed', response);
  }

  async list(_prefix: string): Promise<string[]> {
    const accessToken = await this.getAccessToken();
    const folderId = await this.getOrCreateFolder(accessToken);

    const query = encodeURIComponent(`'${folderId}' in parents and trashed = false`);
    const response = await this.fetchWithToken(
      `${GDriveProvider.GDRIVE_API_BASE}/files?q=${query}`,
      { method: 'GET' },
      accessToken,
    );

    const data = await response.json();
    return (data.files || []).map((f: { name: string }) => f.name);
  }

  /**
   * Sum the bytes of every non-trashed file the app owns under `Nexo/Books`.
   * Read-only: it reuses an already-resolved folder or resolves existing
   * folders via `findFolderStrict`, never creating them — opening the storage
   * panel must not mutate Drive. Paginates the full listing, so a single page
   * is never treated as complete.
   */
  async getUsage(): Promise<{ bytes: number; fileCount: number }> {
    const accessToken = await this.getAccessToken();

    let folderId = folderIds;
    if (folderId === null) {
      const root = await this.findFolderStrict(accessToken, 'Nexo');
      if (!root) return { bytes: 0, fileCount: 0 };
      folderId = await this.findFolderStrict(accessToken, 'Books', root);
      if (!folderId) return { bytes: 0, fileCount: 0 };
    }

    let bytes = 0;
    let fileCount = 0;
    let pageToken: string | undefined;
    do {
      const query = encodeURIComponent(`'${folderId}' in parents and trashed = false`);
      const fields = encodeURIComponent('nextPageToken,files(size)');
      let url = `${GDriveProvider.GDRIVE_API_BASE}/files?q=${query}&fields=${fields}&pageSize=1000`;
      if (pageToken) url += `&pageToken=${encodeURIComponent(pageToken)}`;

      const response = await this.fetchWithToken(url, { method: 'GET' }, accessToken);
      if (!response.ok) throw await this.driveError('GDrive usage failed', response);

      const data = await response.json();
      const files: Array<{ size?: string }> = data.files ?? [];
      for (const file of files) {
        bytes += Number(file.size ?? 0) || 0;
      }
      fileCount += files.length;
      pageToken = data.nextPageToken;
    } while (pageToken);

    return { bytes, fileCount };
  }
}
