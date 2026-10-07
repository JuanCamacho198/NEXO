/**
 * SupabaseProgressSync — syncs reading progress to Supabase via PostgREST.
 *
 * Handles:
 * - upsertProgress: writes a progress row (ON CONFLICT user_id, book_id DO UPDATE)
 * - fetchProgress: reads all progress rows for the current user
 * - subscribeToProgress: Realtime subscription for cross-device sync
 * - importFromDrive: one-time import of existing Drive progress to Supabase
 */
import { getSessionClient, hasLiveSession } from '$lib/services/supabase';
import { authState } from '$lib/shared/stores/AuthState.svelte';
import type { SupabaseClient, RealtimePostgresChangesPayload } from '@supabase/supabase-js';
import type { RemoteHighlightRow, RemoteReadingSessionRow } from '$lib/shared/types';
import { logger } from '$lib/shared/logger/Logger';

export interface SupabaseProgressRow {
  id?: string;
  userId: string;
  bookId: string;
  cfiLocation: string;
  percentage: number;
  currentPage?: number | null;
  locatorJson?: string | null;
  version?: number;
  /**
   * Mirrors the real `reading_progress.version` column (int4, default 1).
   * Kept as an alias for callers that pass a state version; persisted as `version`.
   * The reading_progress table has NO reading_state / state_version / device_id
   * columns — those ghost fields caused HTTP 400 on every progress push.
   */
  stateVersion?: number;
  updatedAt: string;
}

export interface SupabaseBookmarkRow {
  id?: string;
  userId: string;
  bookId: string;
  cfiLocation: string;
  titleSnippet?: string | null;
  locatorJson?: string | null;
  deletedAt?: string | null;
  updatedAt: string;
}

export interface SupabaseHighlightRow {
  id?: string;
  userId: string;
  bookId: string;
  cfiRange: string;
  textContent: string;
  note?: string | null;
  color: string;
  page?: number | null;
  type?: string | null;
  rectJson?: Record<string, number> | null;
  locatorJson?: string | null;
  deletedAt?: string | null;
  updatedAt: string;
}

export interface SupabaseTagRow {
  id?: string;
  userId: string;
  name: string;
  color?: string | null;
}

/**
 * A row in the `reading_sessions` Supabase table. Field names mirror the
 * remote DDL (Android-deployed) exactly; `device` defaults to 'desktop' on
 * the push path. `updatedAt` is the client LWW clock carried in the outbox
 * payload (NOT `now()`), so pull-back of an own push is a tie → no-op.
 */
export interface SupabaseReadingSessionRow {
  id: string;
  userId: string;
  bookId: string;
  startedAt: string;
  durationMinutes: number;
  date: string;
  device: string;
  updatedAt: string;
  startPercentage?: number | null;
  endPercentage?: number | null;
}

export type ProgressChangeCallback = (row: SupabaseProgressRow) => void;
export type BookmarkChangeCallback = (row: SupabaseBookmarkRow) => void;
export type HighlightChangeCallback = (row: SupabaseHighlightRow) => void;
export type ReadingSessionChangeCallback = (row: RemoteReadingSessionRow) => void;

export interface SupabaseBookState {
  progress: SupabaseProgressRow | null;
  bookmarks: SupabaseBookmarkRow[];
  highlights: SupabaseHighlightRow[];
}

export class SupabaseProgressSync {
  private supabase: SupabaseClient;
  private userId: string;
  private unsubscribeRealtime: (() => void) | null = null;
  private unsubscribeBookmarksRealtime: (() => void) | null = null;
  private unsubscribeHighlightsRealtime: (() => void) | null = null;
  private unsubscribeReadingSessionsRealtime: (() => void) | null = null;
  private progressChannel: import('@supabase/supabase-js').RealtimeChannel | null = null;
  private bookmarksChannel: import('@supabase/supabase-js').RealtimeChannel | null = null;
  private highlightsChannel: import('@supabase/supabase-js').RealtimeChannel | null = null;
  private readingSessionsChannel: import('@supabase/supabase-js').RealtimeChannel | null = null;

  constructor(userId: string) {
    this.supabase = getSessionClient();
    this.userId = userId;
  }

  /**
   * Hot-path auth gate (D1, SR-1): no PostgREST request may fire without a
   * live session whose user matches the current `authState` user. The
   * `this.userId` check also closes the stale-instance hole (a sync instance
   * built for a previous user must never write under another user's session).
   * Gated calls no-op immediately — no request, no throw, no markFailed.
   */
  private isGated(): boolean {
    return !hasLiveSession() || this.userId !== authState.userId;
  }

  /**
   * Upsert a single progress row.
   * ON CONFLICT(user_id, book_id) DO UPDATE with all fields.
   */
  async upsertProgress(progress: SupabaseProgressRow): Promise<void> {
    if (this.isGated()) return;
    const { error } = await this.supabase.from('reading_progress').upsert(
      {
        user_id: progress.userId,
        book_id: progress.bookId,
        cfi_location: progress.cfiLocation,
        percentage: progress.percentage,
        current_page: progress.currentPage ?? null,
        locator_json: progress.locatorJson ?? null,
        // NOTE: reading_progress has no reading_state / state_version / device_id
        // columns — those ghost fields caused HTTP 400 on every progress push.
        // Only `version` (int4, default 1) exists; map the client stateVersion
        // onto it and drop the rest.
        version: progress.stateVersion ?? progress.version ?? 1,
        updated_at: progress.updatedAt,
      },
      {
        onConflict: 'user_id, book_id',
        ignoreDuplicates: false,
      },
    );

    if (error) throw error;
  }

  /**
   * Fetch all reading_progress rows for the current user.
   */
  async fetchProgress(): Promise<SupabaseProgressRow[]> {
    if (this.isGated()) return [];
    const { data, error } = await this.supabase
      .from('reading_progress')
      .select('*')
      .eq('user_id', this.userId);

    if (error) throw error;

    return (data ?? []).map(this.mapRow);
  }

  async fetchBookState(bookId: string): Promise<SupabaseBookState> {
    if (this.isGated()) {
      logger.warn(
        '[SupabaseProgressSync] fetchBookState gated: no live session or user mismatch',
        {
          userId: this.userId,
          bookId: bookId.slice(0, 4),
          hasLiveSession: hasLiveSession(),
          authUserId: authState.userId?.slice(0, 4) ?? null,
        },
        'sync',
      );
      return { progress: null, bookmarks: [], highlights: [] };
    }
    const [progress, bookmarks, highlights] = await Promise.all([
      this.fetchProgressForBook(bookId),
      this.fetchBookmarks(bookId),
      this.fetchHighlights(bookId),
    ]);
    return { progress, bookmarks, highlights };
  }

  async fetchProgressForBook(bookId: string): Promise<SupabaseProgressRow | null> {
    if (this.isGated()) return null;
    const { data, error } = await this.supabase
      .from('reading_progress')
      .select('*')
      .eq('user_id', this.userId)
      .eq('book_id', bookId)
      .maybeSingle();
    if (error) throw error;
    return data ? this.mapRow(data as Record<string, unknown>) : null;
  }

  /**
   * Subscribe to realtime changes on reading_progress for this user.
   * Returns an unsubscribe function. Includes defensive removeChannel()
   * so re-subscribe after subscribe() never throws
   * "cannot add postgres_changes callbacks after subscribe()".
   */
  subscribeToProgress(callback: ProgressChangeCallback): () => void {
    if (
      this.progressChannel &&
      (this.progressChannel as unknown as { state?: string }).state === 'subscribed' &&
      this.unsubscribeRealtime
    ) {
      return this.unsubscribeRealtime;
    }
    const channel = this.supabase.channel(`progress:${this.userId}`);
    this.progressChannel = channel;

    channel.on(
      'postgres_changes',
      {
        event: '*',
        schema: 'public',
        table: 'reading_progress',
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
      if (this.progressChannel === channel) this.progressChannel = null;
      this.unsubscribeRealtime = null;
    };

    return this.unsubscribeRealtime;
  }

  /**
   * Import existing Drive progress data to Supabase.
   * Accepts an array of progress rows (typically parsed from Drive state.json files).
   * Batches upserts in chunks of 100.
   */
  async importFromDrive(driveProgressData: SupabaseProgressRow[]): Promise<void> {
    if (this.isGated() || driveProgressData.length === 0) return;

    const chunkSize = 100;
    for (let i = 0; i < driveProgressData.length; i += chunkSize) {
      const chunk = driveProgressData.slice(i, i + chunkSize);
      const { error } = await this.supabase.from('reading_progress').upsert(
        chunk.map((p) => ({
          user_id: p.userId,
          book_id: p.bookId,
          cfi_location: p.cfiLocation,
          percentage: p.percentage,
          current_page: p.currentPage ?? null,
          locator_json: p.locatorJson ?? null,
          updated_at: p.updatedAt,
        })),
        {
          onConflict: 'user_id, book_id',
          ignoreDuplicates: false,
        },
      );

      if (error) throw error;
    }
  }

  // ─── Bookmarks ────────────────────────────────────────────────

  /**
   * Upsert a single bookmark row.
   * ON CONFLICT(user_id, book_id, COALESCE(cfi_location,'')) DO UPDATE
   * but only for rows WHERE deleted_at IS NULL.
   */
  async upsertBookmark(bookmark: SupabaseBookmarkRow): Promise<void> {
    if (this.isGated()) return;
    const payload: Record<string, unknown> = {
      id: bookmark.id,
      user_id: bookmark.userId,
      book_id: bookmark.bookId,
      cfi_location: bookmark.cfiLocation,
      title_snippet: bookmark.titleSnippet ?? null,
      locator_json: bookmark.locatorJson ?? null,
      deleted_at: bookmark.deletedAt ?? null,
      updated_at: bookmark.updatedAt,
    };

    const { error } = await this.supabase.from('bookmarks').upsert(payload, {
      onConflict: 'user_id, book_id, cfi_location',
      ignoreDuplicates: false,
    });

    if (error) throw error;
  }

  /**
   * Fetch all bookmarks for the current user, optionally filtered by book.
   */
  async fetchBookmarks(bookId?: string): Promise<SupabaseBookmarkRow[]> {
    if (this.isGated()) return [];
    let query = this.supabase.from('bookmarks').select('*').eq('user_id', this.userId);

    if (bookId) {
      query = query.eq('book_id', bookId);
    }

    const { data, error } = await query;
    if (error) throw error;

    return (data ?? []).map(this.mapBookmarkRow);
  }

  /**
   * Subscribe to realtime changes on bookmarks for this user.
   * Returns an unsubscribe function.
   */
  subscribeToBookmarks(callback: BookmarkChangeCallback): () => void {
    if (
      this.bookmarksChannel &&
      (this.bookmarksChannel as unknown as { state?: string }).state === 'subscribed' &&
      this.unsubscribeBookmarksRealtime
    ) {
      return this.unsubscribeBookmarksRealtime;
    }
    const channel = this.supabase.channel(`bookmarks:${this.userId}`);
    this.bookmarksChannel = channel;

    channel.on(
      'postgres_changes',
      {
        event: '*',
        schema: 'public',
        table: 'bookmarks',
        filter: `user_id=eq.${this.userId}`,
      },
      (payload: RealtimePostgresChangesPayload<Record<string, unknown>>) => {
        if (payload.new && typeof payload.new === 'object') {
          callback(this.mapBookmarkRow(payload.new as Record<string, unknown>));
        }
      },
    );

    channel.subscribe();
    this.unsubscribeBookmarksRealtime = () => {
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
      if (this.bookmarksChannel === channel) this.bookmarksChannel = null;
      this.unsubscribeBookmarksRealtime = null;
    };

    return this.unsubscribeBookmarksRealtime;
  }

  // ─── Highlights ───────────────────────────────────────────────

  /**
   * Upsert a single highlight row.
   * ON CONFLICT(id) DO UPDATE with all fields.
   */
  async upsertHighlight(highlight: SupabaseHighlightRow): Promise<void> {
    if (this.isGated()) return;
    const payload: Record<string, unknown> = {
      id: highlight.id,
      user_id: highlight.userId,
      book_id: highlight.bookId,
      cfi_range: highlight.cfiRange,
      text_content: highlight.textContent,
      note: highlight.note ?? null,
      color: highlight.color,
      page: highlight.page ?? null,
      type: highlight.type ?? null,
      rect_json: highlight.rectJson ?? null,
      locator_json: highlight.locatorJson ?? null,
      deleted_at: highlight.deletedAt ?? null,
      updated_at: highlight.updatedAt,
    };

    const { error } = await this.supabase.from('highlights').upsert(payload, {
      onConflict: 'id',
      ignoreDuplicates: false,
    });

    if (error) throw error;
  }

  /**
   * Upsert a single reading session row.
   * ON CONFLICT(id) DO UPDATE — the deterministic session id is the PK on both
   * sides, so re-flushing the same session (or an Android push of the same id)
   * collapses to exactly one remote row (SCEN-push-4). `updated_at` is the
   * client clock from the outbox payload, never `now()` (SCEN-pull-3).
   */
  async upsertReadingSession(session: SupabaseReadingSessionRow): Promise<void> {
    if (this.isGated()) return;
    const payload: Record<string, unknown> = {
      id: session.id,
      user_id: session.userId,
      book_id: session.bookId,
      started_at: session.startedAt,
      duration_minutes: session.durationMinutes,
      date: session.date,
      device: session.device,
      updated_at: session.updatedAt,
      start_percentage: session.startPercentage ?? null,
      end_percentage: session.endPercentage ?? null,
    };

    const { error } = await this.supabase.from('reading_sessions').upsert(payload, {
      onConflict: 'id',
      ignoreDuplicates: false,
    });

    if (error) throw error;
  }

  /**
   * Fetch all highlights for the current user, optionally filtered by book.
   */
  async fetchHighlights(bookId?: string): Promise<SupabaseHighlightRow[]> {
    if (this.isGated()) {
      logger.warn(
        '[SupabaseProgressSync] fetchHighlights gated: no live session or user mismatch',
        {
          userId: this.userId,
          bookId: bookId?.slice(0, 4) ?? 'all',
          hasLiveSession: hasLiveSession(),
          authUserId: authState.userId?.slice(0, 4) ?? null,
        },
        'sync',
      );
      return [];
    }
    let query = this.supabase.from('highlights').select('*').eq('user_id', this.userId);

    if (bookId) {
      query = query.eq('book_id', bookId);
    }

    const { data, error } = await query;
    if (error) throw error;

    return (data ?? []).map(this.mapHighlightRow);
  }

  // ─── Highlights (pull) ─────────────────────────────────────

  /**
   * Fetch all highlights rows for the current user (initial pull for WS2).
   * Mirrors fetchReadingSessions: gated, full-table select filtered by user_id,
   * rows mapped to RemoteHighlightRow for the Rust merge command. The caller
   * chunks the result into 500-row invokes (bounded IPC, DHR-1..2).
   */
  async fetchAllHighlightsForPull(): Promise<RemoteHighlightRow[]> {
    if (this.isGated()) {
      logger.warn(
        '[SupabaseProgressSync] fetchAllHighlightsForPull gated: no live session or user mismatch',
        {
          userId: this.userId,
          hasLiveSession: hasLiveSession(),
          authUserId: authState.userId?.slice(0, 4) ?? null,
        },
        'sync',
      );
      return [];
    }
    const { data, error } = await this.supabase
      .from('highlights')
      .select('*')
      .eq('user_id', this.userId);

    if (error) throw error;

    return (data ?? []).map((row) => this.mapHighlightPullRow(row as Record<string, unknown>));
  }

  /**
   * Convert a raw Supabase `highlights` row (snake_case) to the typed
   * RemoteHighlightRow the Rust upsertRemoteHighlights command expects
   * (serde camelCase). `updated_at`/`deleted_at` ISO strings → epoch millis
   * (mirrors mapReadingSessionRow); cfi nullable, PDF empty-CFI allowed.
   */
  mapHighlightPullRow(raw: Record<string, unknown>): RemoteHighlightRow {
    return {
      id: String(raw.id ?? ''),
      userId: String(raw.user_id ?? ''),
      bookId: String(raw.book_id ?? ''),
      cfiRange: raw.cfi_range != null ? String(raw.cfi_range) : null,
      textContent: String(raw.text_content ?? ''),
      note: raw.note != null ? String(raw.note) : null,
      color: String(raw.color ?? '#FACC15'),
      page: raw.page != null ? Number(raw.page) : null,
      updatedAtEpochMillis: Date.parse(String(raw.updated_at ?? '')),
      deletedAtEpochMillis: raw.deleted_at != null ? Date.parse(String(raw.deleted_at)) : null,
    };
  }

  // ─── Reading sessions (pull) ─────────────────────────────────────

  /**
   * Fetch all reading_sessions rows for the current user (initial pull).
   * Mirrors fetchProgress: gated, full-table select filtered by user_id,
   * rows mapped to RemoteReadingSessionRow for the Rust merge command.
   * The caller chunks the result into 500-row invokes (D11 — bounded IPC).
   */
  async fetchReadingSessions(): Promise<RemoteReadingSessionRow[]> {
    if (this.isGated()) return [];
    const { data, error } = await this.supabase
      .from('reading_sessions')
      .select('*')
      .eq('user_id', this.userId);

    if (error) throw error;

    return (data ?? []).map((row) => this.mapReadingSessionRow(row as Record<string, unknown>));
  }

  /**
   * Subscribe to realtime changes on reading_sessions for this user.
   * Handles Insert/Update only — Delete and Select are no-ops (Android
   * parity: the local table is the merged source of truth; remote deletes
   * never un-merge local rows).
   * Returns an unsubscribe function.
   */
  subscribeToReadingSessions(callback: ReadingSessionChangeCallback): () => void {
    if (
      this.readingSessionsChannel &&
      (this.readingSessionsChannel as unknown as { state?: string }).state === 'subscribed' &&
      this.unsubscribeReadingSessionsRealtime
    ) {
      return this.unsubscribeReadingSessionsRealtime;
    }
    const channel = this.supabase.channel(`sessions:${this.userId}`);
    this.readingSessionsChannel = channel;

    const handleChange = (
      payload: RealtimePostgresChangesPayload<Record<string, unknown>>,
    ): void => {
      if (payload.new && typeof payload.new === 'object') {
        callback(this.mapReadingSessionRow(payload.new as Record<string, unknown>));
      }
    };

    channel.on(
      'postgres_changes',
      {
        event: 'INSERT',
        schema: 'public',
        table: 'reading_sessions',
        filter: `user_id=eq.${this.userId}`,
      },
      handleChange,
    );
    channel.on(
      'postgres_changes',
      {
        event: 'UPDATE',
        schema: 'public',
        table: 'reading_sessions',
        filter: `user_id=eq.${this.userId}`,
      },
      handleChange,
    );

    channel.subscribe();
    this.unsubscribeReadingSessionsRealtime = () => {
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
      if (this.readingSessionsChannel === channel) this.readingSessionsChannel = null;
      this.unsubscribeReadingSessionsRealtime = null;
    };

    return this.unsubscribeReadingSessionsRealtime;
  }

  /**
   * Convert a raw Supabase `reading_sessions` row (snake_case) to the typed
   * RemoteReadingSessionRow the Rust upsertRemoteReadingSessions command
   * expects (serde camelCase). `updated_at` ISO string → epoch millis
   * (mirrors Android applyRemoteSession `remoteTime`); percentages nullable.
   */
  mapReadingSessionRow(raw: Record<string, unknown>): RemoteReadingSessionRow {
    return {
      id: String(raw.id ?? ''),
      userId: String(raw.user_id ?? ''),
      bookId: String(raw.book_id ?? ''),
      startedAt: String(raw.started_at ?? ''),
      durationMinutes: Number(raw.duration_minutes ?? 0),
      date: String(raw.date ?? ''),
      updatedAtEpochMillis: Date.parse(String(raw.updated_at ?? '')),
      startPercentage: raw.start_percentage != null ? Number(raw.start_percentage) : null,
      endPercentage: raw.end_percentage != null ? Number(raw.end_percentage) : null,
    };
  }

  /**
   * Subscribe to realtime changes on highlights for this user.
   * Returns an unsubscribe function.
   */
  subscribeToHighlights(callback: HighlightChangeCallback): () => void {
    if (
      this.highlightsChannel &&
      (this.highlightsChannel as unknown as { state?: string }).state === 'subscribed' &&
      this.unsubscribeHighlightsRealtime
    ) {
      return this.unsubscribeHighlightsRealtime;
    }
    const channel = this.supabase.channel(`highlights:${this.userId}`);
    this.highlightsChannel = channel;

    channel.on(
      'postgres_changes',
      {
        event: '*',
        schema: 'public',
        table: 'highlights',
        filter: `user_id=eq.${this.userId}`,
      },
      (payload: RealtimePostgresChangesPayload<Record<string, unknown>>) => {
        if (payload.new && typeof payload.new === 'object') {
          callback(this.mapHighlightRow(payload.new as Record<string, unknown>));
        }
      },
    );

    channel.subscribe();
    this.unsubscribeHighlightsRealtime = () => {
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
      if (this.highlightsChannel === channel) this.highlightsChannel = null;
      this.unsubscribeHighlightsRealtime = null;
    };

    return this.unsubscribeHighlightsRealtime;
  }

  // ─── Tags ─────────────────────────────────────────────────────

  /**
   * Find-or-create a tag by name for the current user.
   * SELECT existing by (user_id, name), INSERT if not found.
   * Returns the tag row (with id).
   */
  async upsertTag(tag: SupabaseTagRow): Promise<SupabaseTagRow> {
    // Gate (SR-1): no request without a live session; no-op returns the input
    // unpersisted rather than throwing or fabricating an id.
    if (this.isGated()) return { ...tag };
    // Try to find existing tag
    const { data: existing, error: findError } = await this.supabase
      .from('tags')
      .select('*')
      .eq('user_id', tag.userId)
      .eq('name', tag.name)
      .maybeSingle();

    if (findError) throw findError;

    if (existing) {
      // Update color if provided
      if (tag.color != null) {
        const { error: updError } = await this.supabase
          .from('tags')
          .update({ color: tag.color })
          .eq('id', existing.id);

        if (updError) throw updError;
      }
      return this.mapTagRow(existing);
    }

    // Insert new tag
    const payload: Record<string, unknown> = {
      id: tag.id,
      user_id: tag.userId,
      name: tag.name,
      color: tag.color ?? null,
    };

    const { data: inserted, error: insError } = await this.supabase
      .from('tags')
      .upsert(payload, { onConflict: 'user_id, name', ignoreDuplicates: false })
      .select()
      .maybeSingle();

    if (insError) throw insError;

    return this.mapTagRow(inserted ?? payload);
  }

  /**
   * Link a tag to a highlight via highlight_tags junction.
   * ON CONFLICT DO NOTHING (idempotent).
   */
  async linkTagToHighlight(highlightId: string, tagId: string): Promise<void> {
    if (this.isGated()) return;
    const { error } = await this.supabase.from('highlight_tags').upsert(
      {
        highlight_id: highlightId,
        tag_id: tagId,
      },
      { onConflict: 'highlight_id, tag_id', ignoreDuplicates: true },
    );

    if (error) throw error;
  }

  // ─── Import helpers ───────────────────────────────────────────

  /**
   * Import bookmark data from Drive (batched upsert).
   */
  async importBookmarksFromDrive(bookmarks: SupabaseBookmarkRow[]): Promise<void> {
    if (this.isGated() || bookmarks.length === 0) return;

    const chunkSize = 100;
    for (let i = 0; i < bookmarks.length; i += chunkSize) {
      const chunk = bookmarks.slice(i, i + chunkSize);
      const { error } = await this.supabase.from('bookmarks').upsert(
        chunk.map((b) => ({
          id: b.id,
          user_id: b.userId,
          book_id: b.bookId,
          cfi_location: b.cfiLocation,
          title_snippet: b.titleSnippet ?? null,
          locator_json: b.locatorJson ?? null,
          deleted_at: b.deletedAt ?? null,
          updated_at: b.updatedAt,
        })),
        {
          onConflict: 'user_id, book_id, cfi_location',
          ignoreDuplicates: false,
        },
      );
      if (error) throw error;
    }
  }

  /**
   * Import highlight data from Drive (batched upsert).
   */
  async importHighlightsFromDrive(highlights: SupabaseHighlightRow[]): Promise<void> {
    if (this.isGated() || highlights.length === 0) return;

    const chunkSize = 100;
    for (let i = 0; i < highlights.length; i += chunkSize) {
      const chunk = highlights.slice(i, i + chunkSize);
      const { error } = await this.supabase.from('highlights').upsert(
        chunk.map((h) => ({
          id: h.id,
          user_id: h.userId,
          book_id: h.bookId,
          cfi_range: h.cfiRange,
          text_content: h.textContent,
          note: h.note ?? null,
          color: h.color,
          page: h.page ?? null,
          type: h.type ?? null,
          rect_json: h.rectJson ?? null,
          locator_json: h.locatorJson ?? null,
          deleted_at: h.deletedAt ?? null,
          updated_at: h.updatedAt,
        })),
        {
          onConflict: 'id',
          ignoreDuplicates: false,
        },
      );
      if (error) throw error;
    }
  }

  /**
   * Single Realtime supervisor (PR2): owns 4 channels
   * `progress:uid` / `highlights:uid` / `bookmarks:uid` / `sessions:uid`,
   * all gated by hasLiveSession. Call once after login (authState.userId set),
   * teardown via destroy() on logout. Realtime <2s for all 4 domains.
   */
  subscribeAll(callbacks: {
    onProgress: ProgressChangeCallback;
    onBookmark: BookmarkChangeCallback;
    onHighlight: HighlightChangeCallback;
    onSession: ReadingSessionChangeCallback;
  }): () => void {
    if (this.isGated()) return () => {};
    const u1 = this.subscribeToProgress(callbacks.onProgress);
    const u2 = this.subscribeToBookmarks(callbacks.onBookmark);
    const u3 = this.subscribeToHighlights(callbacks.onHighlight);
    const u4 = this.subscribeToReadingSessions(callbacks.onSession);
    return () => {
      u1();
      u2();
      u3();
      u4();
    };
  }

  getRealtimeStatus(): Record<string, 'connected' | 'connecting' | 'closed' | 'error'> {
    const mapState = (ch: unknown): 'connected' | 'connecting' | 'closed' | 'error' => {
      const state = (ch as { state?: string } | null)?.state ?? '';
      if (state === 'subscribed' || state === 'joined') return 'connected';
      if (state === 'joining' || state === 'connecting') return 'connecting';
      if (state === 'closed' || state === 'leaving' || state === 'unsubscribed') return 'closed';
      if (state === 'errored' || state === 'error') return 'error';
      return 'closed';
    };
    return {
      progress: this.progressChannel ? mapState(this.progressChannel) : 'closed',
      bookmarks: this.bookmarksChannel ? mapState(this.bookmarksChannel) : 'closed',
      highlights: this.highlightsChannel ? mapState(this.highlightsChannel) : 'closed',
      sessions: this.readingSessionsChannel ? mapState(this.readingSessionsChannel) : 'closed',
    };
  }

  /**
   * Clean up all realtime subscriptions — single supervisor teardown on logout.
   * Each unsubscribe now also calls `removeChannel` to drop the channel from
   * `client.channels` and avoid orphaned subscriptions.
   */
  destroy(): void {
    try {
      this.unsubscribeRealtime?.();
    } catch {
      /* ignore */
    }
    this.unsubscribeRealtime = null;
    if (this.progressChannel) {
      try {
        this.supabase.removeChannel(this.progressChannel);
      } catch {
        /* ignore */
      }
      this.progressChannel = null;
    }
    try {
      this.unsubscribeBookmarksRealtime?.();
    } catch {
      /* ignore */
    }
    this.unsubscribeBookmarksRealtime = null;
    if (this.bookmarksChannel) {
      try {
        this.supabase.removeChannel(this.bookmarksChannel);
      } catch {
        /* ignore */
      }
      this.bookmarksChannel = null;
    }
    try {
      this.unsubscribeHighlightsRealtime?.();
    } catch {
      /* ignore */
    }
    this.unsubscribeHighlightsRealtime = null;
    if (this.highlightsChannel) {
      try {
        this.supabase.removeChannel(this.highlightsChannel);
      } catch {
        /* ignore */
      }
      this.highlightsChannel = null;
    }
    try {
      this.unsubscribeReadingSessionsRealtime?.();
    } catch {
      /* ignore */
    }
    this.unsubscribeReadingSessionsRealtime = null;
    if (this.readingSessionsChannel) {
      try {
        this.supabase.removeChannel(this.readingSessionsChannel);
      } catch {
        /* ignore */
      }
      this.readingSessionsChannel = null;
    }
  }

  private mapRow(row: Record<string, unknown>): SupabaseProgressRow {
    return {
      id: String(row.id ?? ''),
      userId: String(row.user_id ?? ''),
      bookId: String(row.book_id ?? ''),
      cfiLocation: String(row.cfi_location ?? ''),
      percentage: Number(row.percentage ?? 0),
      currentPage: row.current_page != null ? Number(row.current_page) : null,
      locatorJson: row.locator_json != null ? String(row.locator_json) : null,
      version: Number(row.version ?? 1),
      // reading_progress has no reading_state / state_version / device_id columns;
      // read the LWW version from the real `version` column only.
      stateVersion: Number(row.version ?? 1),
      updatedAt: String(row.updated_at ?? new Date().toISOString()),
    };
  }

  private mapBookmarkRow(row: Record<string, unknown>): SupabaseBookmarkRow {
    return {
      id: String(row.id ?? crypto.randomUUID()),
      userId: String(row.user_id ?? ''),
      bookId: String(row.book_id ?? ''),
      cfiLocation: String(row.cfi_location ?? ''),
      titleSnippet: row.title_snippet != null ? String(row.title_snippet) : null,
      locatorJson: row.locator_json != null ? String(row.locator_json) : null,
      deletedAt: row.deleted_at != null ? String(row.deleted_at) : null,
      updatedAt: String(row.updated_at ?? new Date().toISOString()),
    };
  }

  private mapHighlightRow(row: Record<string, unknown>): SupabaseHighlightRow {
    return {
      id: String(row.id ?? crypto.randomUUID()),
      userId: String(row.user_id ?? ''),
      bookId: String(row.book_id ?? ''),
      cfiRange: String(row.cfi_range ?? ''),
      textContent: String(row.text_content ?? ''),
      note: row.note != null ? String(row.note) : null,
      color: String(row.color ?? 'yellow'),
      page: row.page != null ? Number(row.page) : null,
      type: row.type != null ? String(row.type) : null,
      rectJson: row.rect_json != null ? (row.rect_json as Record<string, number>) : null,
      locatorJson: row.locator_json != null ? String(row.locator_json) : null,
      deletedAt: row.deleted_at != null ? String(row.deleted_at) : null,
      updatedAt: String(row.updated_at ?? new Date().toISOString()),
    };
  }

  private mapTagRow(row: Record<string, unknown>): SupabaseTagRow {
    return {
      id: String(row.id ?? crypto.randomUUID()),
      userId: String(row.user_id ?? ''),
      name: String(row.name ?? ''),
      color: row.color != null ? String(row.color) : null,
    };
  }
}
