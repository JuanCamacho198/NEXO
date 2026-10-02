/**
 * SupabaseBookCatalogSync — syncs book metadata to Supabase via PostgREST.
 *
 * Handles:
 * - upsertBook: writes a book row (ON CONFLICT user_id, id DO UPDATE)
 * - fetchCatalog: reads all book rows for the current user
 * - deleteBook: removes a book row for the current user
 * - subscribeToCatalog: Realtime subscription for cross-device book sync
 */
import { getSessionClient } from '$lib/services/supabase';
import {
  DRIVE_PROVIDER,
  PROTOCOL_VERSION,
  canonicalBookName,
  canonicalBookPath,
  coverError,
  redactLogLine,
} from '$lib/shared/protocol/DriveCatalogContract';
import { createErrorEvent } from '$lib/shared/events/ErrorEvent';
import { logger } from '$lib/shared/logger/Logger';
import type { SupabaseClient, RealtimePostgresChangesPayload } from '@supabase/supabase-js';

export interface SupabaseUserBookRow {
  id: string;
  userId: string;
  title: string;
  author: string | null;
  format: string;
  /** Optional so merge-upsert callers can omit it and preserve the existing hash (DRP-2). */
  contentHash?: string | null;
  filePath: string | null;
  coverUrl: string | null;
  description: string | null;
  totalPages: number | null;
  sourceDevice: string | null;
  importedAt: string;
  updatedAt: string;
  /** Recovery catalog fields (PR4): lifecycle, monotonic version, remote mapping, cover refs. */
  lifecycle?: 'available' | 'imported' | 'unavailable' | 'deleted';
  catalogVersion?: number;
  remoteProvider?: string | null;
  remoteFileId?: string | null;
  remotePath?: string | null;
  remoteName?: string | null;
  protocolVersion?: number | null;
  recoveryProtocol?: string | null;
  deletedAt?: string | null;
  deletedByDevice?: string | null;
  coverBucket?: string | null;
  coverObjectPath?: string | null;
  coverHash?: string | null;
  coverMediaType?: string | null;
}
export type CatalogChangeCallback = (row: SupabaseUserBookRow) => void;

export type CatalogChangeDecision =
  'apply' | 'ignore-stale' | 'ignore-equal' | 'ignore-missing-local' | 'ignore-local-tombstone';

/**
 * PR5 Realtime convergence: decide whether an incoming catalog/tombstone
 * change applies to the current local row, using monotonic version ordering.
 * - Missing local state never becomes deletion: a tombstone with no local row
 *   is ignored (reinstall emits no delete).
 * - A local tombstone is never resurrected by a stale/equal/older event.
 * - Equal-version events are idempotent; stale events are ignored.
 * - Only a strictly newer row applies (metadata or explicit tombstone).
 */
export function decideCatalogChange(
  local: Pick<SupabaseUserBookRow, 'catalogVersion' | 'lifecycle'> | null | undefined,
  incoming: Pick<SupabaseUserBookRow, 'catalogVersion' | 'lifecycle'>,
): CatalogChangeDecision {
  if (!local) {
    return incoming.lifecycle === 'deleted' ? 'ignore-missing-local' : 'apply';
  }
  if (local.lifecycle === 'deleted') return 'ignore-local-tombstone';
  const localVersion = local.catalogVersion ?? 0;
  const incomingVersion = incoming.catalogVersion ?? 0;
  if (incomingVersion > localVersion) return 'apply';
  if (incomingVersion === localVersion) return 'ignore-equal';
  return 'ignore-stale';
}

/**
 * Build the remote-reference fields persisted on `user_books` after a
 * successful Drive upload (DRP-1). `recovery_protocol_v1` is set iff a remote
 * ref is written (DRP-5 gate). Protocol version is persisted as text '1'
 * (D5: Android persists Int 1 → text '1'; byte parity).
 */
export function buildRemoteRefs(
  bookId: string,
  format: string,
  fileId: string,
): Pick<
  SupabaseUserBookRow,
  | 'remoteProvider'
  | 'remoteFileId'
  | 'remotePath'
  | 'remoteName'
  | 'protocolVersion'
  | 'recoveryProtocol'
> {
  return {
    remoteProvider: DRIVE_PROVIDER,
    remoteFileId: fileId,
    remotePath: canonicalBookPath(bookId, format),
    remoteName: canonicalBookName(bookId, format),
    protocolVersion: PROTOCOL_VERSION,
    recoveryProtocol: 'recovery_protocol_v1',
  };
}

/**
 * Report a non-blocking cover failure through the structured error-event
 * surface with the stable COVER_FAILED code (REQ-07). Cover persistence must
 * never block a book upsert, so this never throws; the message always travels
 * through `redactLogLine` (DTL-3) so tokens, JWTs, and hashes never reach a log
 * sink. Shared by every cover upload/read path (SyncService cold + hot).
 */
export function reportCoverFailure(bookId: string, err: unknown): void {
  const failed = coverError(crypto.randomUUID(), bookId);
  const reason = err instanceof Error ? err.message : String(err);
  logger.warn(
    createErrorEvent({
      severity: 'low',
      category: 'runtime',
      code: failed.code,
      message: redactLogLine(`Cover upload failed: ${reason}`),
      context: {
        bookId,
        correlationId: failed.correlationId,
        retryable: failed.retryable,
      },
      source: 'sync',
      recoverable: true,
    }),
  );
}

export class SupabaseBookCatalogSync {
  private supabase: SupabaseClient;
  private userId: string;
  private unsubscribeRealtime: (() => void) | null = null;
  private catalogChannel: import('@supabase/supabase-js').RealtimeChannel | null = null;

  constructor(userId: string) {
    this.supabase = getSessionClient();
    this.userId = userId;
  }

  /**
   * Upsert a single book row.
   * ON CONFLICT(user_id, id) DO UPDATE with all fields.
   *
   * Merge semantics (DRP-2/DRP-5): read the current row before upsert and
   * preserve existing remote fields, `content_hash`, and `catalog_version` —
   * remote fields and `content_hash` are written only when the caller provides
   * a non-null value; `catalog_version = max(current ?? 1, incoming ?? 1)`
   * (never lowered); `recovery_protocol` is included in the column set and
   * never downgraded from `recovery_protocol_v1`.
   */
  async upsertBook(book: SupabaseUserBookRow): Promise<void> {
    // Read-before-upsert: mirror tombstoneBook — single extra read.
    const { data: current, error: fetchError } = await this.supabase
      .from('user_books')
      .select(
        'remote_provider, remote_file_id, remote_path, remote_name, protocol_version, catalog_version, content_hash, recovery_protocol',
      )
      .eq('user_id', this.userId)
      .eq('id', book.id)
      .maybeSingle();
    if (fetchError) throw fetchError;

    const remoteProvider = book.remoteProvider ?? current?.remote_provider ?? null;
    const remoteFileId = book.remoteFileId ?? current?.remote_file_id ?? null;
    const remotePath = book.remotePath ?? current?.remote_path ?? null;
    const remoteName = book.remoteName ?? current?.remote_name ?? null;
    const protocolVersion =
      book.protocolVersion ??
      (current?.protocol_version != null ? Number(current.protocol_version) : null);
    const contentHash = book.contentHash ?? current?.content_hash ?? null;
    const catalogVersion = Math.max(
      current?.catalog_version != null ? Number(current.catalog_version) : 1,
      book.catalogVersion ?? 1,
    );
    const currentRecovery = String(current?.recovery_protocol ?? 'legacy');
    // Never downgrade an existing v1 row (DRP-5).
    const recoveryProtocol =
      currentRecovery === 'recovery_protocol_v1'
        ? 'recovery_protocol_v1'
        : (book.recoveryProtocol ?? currentRecovery);

    const { error } = await this.supabase.from('user_books').upsert(
      {
        id: book.id,
        user_id: book.userId,
        title: book.title,
        author: book.author ?? null,
        format: book.format,
        content_hash: contentHash,
        file_path: book.filePath ?? null,
        cover_url: book.coverUrl ?? null,
        description: book.description ?? null,
        total_pages: book.totalPages ?? null,
        source_device: book.sourceDevice ?? null,
        imported_at: book.importedAt,
        updated_at: book.updatedAt,
        lifecycle: book.lifecycle ?? 'imported',
        catalog_version: catalogVersion,
        remote_provider: remoteProvider,
        remote_file_id: remoteFileId,
        remote_path: remotePath,
        remote_name: remoteName,
        protocol_version: protocolVersion != null ? String(protocolVersion) : null,
        recovery_protocol: recoveryProtocol,
        deleted_at: book.deletedAt ?? null,
        deleted_by_device: book.deletedByDevice ?? null,
        cover_bucket: book.coverBucket ?? null,
        cover_object_path: book.coverObjectPath ?? null,
        cover_hash: book.coverHash ?? null,
        cover_media_type: book.coverMediaType ?? null,
      },
      {
        onConflict: 'user_id, id',
        ignoreDuplicates: false,
      },
    );

    if (error) throw error;
  }

  /**
   * Fetch all user_books rows for the current user.
   */
  async fetchCatalog(): Promise<SupabaseUserBookRow[]> {
    const { data, error } = await this.supabase
      .from('user_books')
      .select('*')
      .eq('user_id', this.userId)
      .order('updated_at', { ascending: false });

    if (error) throw error;

    return (data ?? []).map(this.mapRow);
  }

  /**
   * Delete a book row for the current user (legacy hard-delete; explicit
   * user deletion uses tombstoneBook() for versioned tombstones).
   */
  async deleteBook(bookId: string): Promise<void> {
    const { error } = await this.supabase
      .from('user_books')
      .delete()
      .eq('user_id', this.userId)
      .eq('id', bookId);

    if (error) throw error;
  }

  async tombstoneBook(bookId: string): Promise<void> {
    const now = new Date().toISOString();
    const { data: current, error: fetchError } = await this.supabase
      .from('user_books')
      .select('title, format, catalog_version')
      .eq('user_id', this.userId)
      .eq('id', bookId)
      .maybeSingle();
    if (fetchError) throw fetchError;
    const { error } = await this.supabase.from('user_books').upsert(
      {
        id: bookId,
        user_id: this.userId,
        title: current ? String(current.title ?? bookId) : bookId,
        format: current ? String(current.format ?? 'epub') : 'epub',
        lifecycle: 'deleted',
        catalog_version:
          (current?.catalog_version != null ? Number(current.catalog_version) : 1) + 1,
        deleted_at: now,
        deleted_by_device: 'desktop',
        updated_at: now,
      },
      { onConflict: 'user_id, id', ignoreDuplicates: false },
    );
    if (error) throw error;
  }

  /**
   * Upload a cover image to Supabase Storage and return the public URL.
   * Path: covers/{userId}/{bookId}.jpg
   * Non-blocking: failure is mapped to the stable COVER_FAILED error code,
   * logged for observability, and returns null so book import never blocks.
   */
  async uploadCover(
    userId: string,
    bookId: string,
    coverBytes: ArrayBuffer,
  ): Promise<string | null> {
    try {
      const path = `covers/${userId}/${bookId}.jpg`;
      const { error: uploadError } = await this.supabase.storage
        .from('book-covers')
        .upload(path, coverBytes, { upsert: true });

      if (uploadError) {
        reportCoverFailure(bookId, uploadError);
        return null;
      }

      const { data: urlData } = this.supabase.storage.from('book-covers').getPublicUrl(path);

      return urlData.publicUrl;
    } catch (e) {
      reportCoverFailure(bookId, e);
      return null;
    }
  }

  /**
   * Find a book row by content hash for the current user.
   * Returns the first match or null if not found.
   * Used by content-hash dedup (PR 5) to check if a book with the
   * same SHA-256 hash already exists in the catalog.
   */
  async findByHash(contentHash: string): Promise<SupabaseUserBookRow | null> {
    const { data, error } = await this.supabase
      .from('user_books')
      .select('*')
      .eq('user_id', this.userId)
      .eq('content_hash', contentHash)
      .maybeSingle();

    if (error) throw error;
    return data ? this.mapRow(data as Record<string, unknown>) : null;
  }

  /**
   * Subscribe to realtime changes on user_books for this user.
   * Returns an unsubscribe function.
   */
  subscribeToCatalog(callback: CatalogChangeCallback): () => void {
    if (
      this.catalogChannel &&
      (this.catalogChannel as unknown as { state?: string }).state === 'subscribed' &&
      this.unsubscribeRealtime
    ) {
      return this.unsubscribeRealtime;
    }
    const channel = this.supabase.channel(`catalog:${this.userId}`);
    this.catalogChannel = channel;

    channel.on(
      'postgres_changes',
      {
        event: '*',
        schema: 'public',
        table: 'user_books',
        filter: `user_id=eq.${this.userId}`,
      },
      (payload: RealtimePostgresChangesPayload<Record<string, unknown>>) => {
        if (payload.new && typeof payload.new === 'object') {
          callback(this.mapRow(payload.new as Record<string, unknown>));
        }
      },
    );

    channel.subscribe();
    this.unsubscribeRealtime = () => {
      try {
        channel.unsubscribe();
      } catch {
        /* ignore */
      }
      try {
        this.supabase.removeChannel(channel);
      } catch {
        /* ignore */
      }
      if (this.catalogChannel === channel) this.catalogChannel = null;
      this.unsubscribeRealtime = null;
    };

    return this.unsubscribeRealtime;
  }

  getRealtimeStatus(): 'connected' | 'connecting' | 'closed' | 'error' {
    if (!this.catalogChannel) return 'closed';
    const state = (this.catalogChannel as unknown as { state?: string }).state ?? '';
    if (state === 'subscribed' || state === 'joined') return 'connected';
    if (state === 'joining' || state === 'connecting') return 'connecting';
    if (state === 'closed' || state === 'leaving' || state === 'unsubscribed') return 'closed';
    if (state === 'errored' || state === 'error') return 'error';
    return 'closed';
  }

  /**
   * Clean up realtime subscription. Removes the channel from the client as well.
   */
  destroy(): void {
    try {
      this.unsubscribeRealtime?.();
    } catch {
      /* ignore */
    }
    this.unsubscribeRealtime = null;
    if (this.catalogChannel) {
      try {
        this.supabase.removeChannel(this.catalogChannel);
      } catch {
        /* ignore */
      }
      this.catalogChannel = null;
    }
  }

  private mapRow(row: Record<string, unknown>): SupabaseUserBookRow {
    return {
      id: String(row.id ?? ''),
      userId: String(row.user_id ?? ''),
      title: String(row.title ?? ''),
      author: row.author != null ? String(row.author) : null,
      format: String(row.format ?? ''),
      contentHash: row.content_hash != null ? String(row.content_hash) : null,
      filePath: row.file_path != null ? String(row.file_path) : null,
      coverUrl: row.cover_url != null ? String(row.cover_url) : null,
      description: row.description != null ? String(row.description) : null,
      totalPages: row.total_pages != null ? Number(row.total_pages) : null,
      sourceDevice: row.source_device != null ? String(row.source_device) : null,
      importedAt: String(row.imported_at ?? new Date().toISOString()),
      updatedAt: String(row.updated_at ?? new Date().toISOString()),
      lifecycle: (row.lifecycle as SupabaseUserBookRow['lifecycle']) ?? 'imported',
      catalogVersion: row.catalog_version != null ? Number(row.catalog_version) : 1,
      remoteProvider: row.remote_provider != null ? String(row.remote_provider) : null,
      remoteFileId: row.remote_file_id != null ? String(row.remote_file_id) : null,
      remotePath: row.remote_path != null ? String(row.remote_path) : null,
      remoteName: row.remote_name != null ? String(row.remote_name) : null,
      protocolVersion: row.protocol_version != null ? Number(row.protocol_version) : null,
      recoveryProtocol: row.recovery_protocol != null ? String(row.recovery_protocol) : null,
      deletedAt: row.deleted_at != null ? String(row.deleted_at) : null,
      deletedByDevice: row.deleted_by_device != null ? String(row.deleted_by_device) : null,
      coverBucket: row.cover_bucket != null ? String(row.cover_bucket) : null,
      coverObjectPath: row.cover_object_path != null ? String(row.cover_object_path) : null,
      coverHash: row.cover_hash != null ? String(row.cover_hash) : null,
      coverMediaType: row.cover_media_type != null ? String(row.cover_media_type) : null,
    };
  }
}
