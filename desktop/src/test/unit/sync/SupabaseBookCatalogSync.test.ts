/**
 * Unit tests for SupabaseBookCatalogSync — PR 2
 * Tests: upsert flow, fetch catalog, delete, Realtime subscription,
 * and reconciliation gap detection.
 */
import { describe, it, expect, vi, beforeEach, beforeAll } from 'vitest';
import { decideCatalogChange } from '$lib/shared/sync/SupabaseBookCatalogSync';
import type { SupabaseUserBookRow } from '$lib/shared/sync/SupabaseBookCatalogSync';

// ---- Mock control variables ----
let mockFrom = vi.fn();
let mockChannel = vi.fn();
let mockStorage: { from: ReturnType<typeof vi.fn> } = { from: vi.fn() };
let mockChainable: Record<string, ReturnType<typeof vi.fn>>;

// ---- Mock supabase client factory ----
vi.mock('$lib/services/supabase', () => ({
  getSessionClient: () => ({
    from: mockFrom,
    channel: mockChannel,
    storage: mockStorage,
  }),
}));

// ---- Test data ----
function makeUserBookRow(overrides: Partial<SupabaseUserBookRow> = {}) {
  return {
    id: 'book-1',
    userId: 'user-1',
    title: 'Test Book',
    author: 'Test Author',
    format: 'epub',
    contentHash: null,
    filePath: null,
    coverUrl: null,
    description: null,
    totalPages: 250,
    sourceDevice: 'desktop',
    importedAt: '2025-01-01T00:00:00Z',
    updatedAt: '2025-06-01T00:00:00Z',
    ...overrides,
  };
}

function makeRawRow(overrides: Record<string, unknown> = {}): Record<string, unknown> {
  return {
    id: 'book-1',
    user_id: 'user-1',
    title: 'Test Book',
    author: 'Test Author',
    format: 'epub',
    content_hash: null,
    file_path: null,
    cover_url: null,
    description: null,
    total_pages: 250,
    source_device: 'desktop',
    imported_at: '2025-01-01T00:00:00Z',
    updated_at: '2025-06-01T00:00:00Z',
    ...overrides,
  };
}

let SupabaseBookCatalogSync: typeof import('$lib/shared/sync/SupabaseBookCatalogSync').SupabaseBookCatalogSync;

beforeAll(async () => {
  const mod = await import('$lib/shared/sync/SupabaseBookCatalogSync');
  SupabaseBookCatalogSync = mod.SupabaseBookCatalogSync;
});

beforeEach(() => {
  vi.clearAllMocks();

  // Build a chainable query builder mock
  mockChainable = {
    upsert: vi.fn().mockResolvedValue({ error: null }),
    select: vi.fn().mockReturnThis(),
    eq: vi.fn().mockReturnThis(),
    order: vi.fn().mockResolvedValue({ data: [], error: null }),
    delete: vi.fn().mockReturnThis(),
    maybeSingle: vi.fn().mockResolvedValue({ data: null, error: null }),
  };

  mockFrom = vi.fn().mockReturnValue(mockChainable);

  mockStorage = {
    from: vi.fn().mockReturnValue({
      upload: vi.fn().mockResolvedValue({ error: null }),
      getPublicUrl: vi
        .fn()
        .mockReturnValue({ data: { publicUrl: 'https://cdn.example/cover.jpg' } }),
    }),
  };

  mockChannel = vi.fn().mockReturnValue({
    on: vi.fn().mockReturnThis(),
    subscribe: vi.fn().mockReturnValue({ unsubscribe: vi.fn() }),
    unsubscribe: vi.fn(),
  });
});

describe('SupabaseBookCatalogSync — upsertBook', () => {
  it('upserts a book row with correct table and columns', async () => {
    const sync = new SupabaseBookCatalogSync('user-1');
    const book = makeUserBookRow();

    await sync.upsertBook(book);

    expect(mockFrom).toHaveBeenCalledWith('user_books');
    expect(mockChainable.upsert).toHaveBeenCalledWith(
      expect.objectContaining({
        id: 'book-1',
        user_id: 'user-1',
        title: 'Test Book',
        format: 'epub',
      }),
      expect.objectContaining({
        onConflict: 'user_id, id',
        ignoreDuplicates: false,
      }),
    );
  });

  it('maps null fields correctly', async () => {
    const sync = new SupabaseBookCatalogSync('user-1');
    const book = makeUserBookRow({
      author: null,
      contentHash: null,
      filePath: null,
      coverUrl: null,
      description: null,
      totalPages: null,
      sourceDevice: null,
    });

    await sync.upsertBook(book);

    expect(mockChainable.upsert).toHaveBeenCalledWith(
      expect.objectContaining({
        author: null,
        content_hash: null,
        file_path: null,
        cover_url: null,
        description: null,
        total_pages: null,
        source_device: null,
      }),
      expect.any(Object),
    );
  });

  it('throws on Supabase error', async () => {
    const book = makeUserBookRow();

    mockChainable.upsert = vi.fn().mockResolvedValue({ error: new Error('DB error') });
    mockFrom = vi.fn().mockReturnValue(mockChainable);

    // Re-create sync so it picks up the updated mockFrom
    const sync2 = new SupabaseBookCatalogSync('user-1');
    await expect(sync2.upsertBook(book)).rejects.toThrow('DB error');
  });

  it('maps null fields correctly when no current row exists', async () => {
    const sync = new SupabaseBookCatalogSync('user-1');
    const book = makeUserBookRow({
      author: null,
      contentHash: null,
      filePath: null,
      coverUrl: null,
      description: null,
      totalPages: null,
      sourceDevice: null,
    });

    await sync.upsertBook(book);

    expect(mockChainable.upsert).toHaveBeenCalledWith(
      expect.objectContaining({
        author: null,
        content_hash: null,
        file_path: null,
        cover_url: null,
        description: null,
        total_pages: null,
        source_device: null,
      }),
      expect.any(Object),
    );
  });
});

describe('SupabaseBookCatalogSync — upsertBook merge semantics (DRP-2/DRP-5)', () => {
  /** Current row as returned by the read-before-upsert maybeSingle. */
  function currentRow(overrides: Record<string, unknown> = {}): Record<string, unknown> {
    return {
      id: 'book-1',
      user_id: 'user-1',
      title: 'Existing',
      format: 'epub',
      catalog_version: 3,
      content_hash: 'sha256:existing',
      remote_provider: 'google_drive',
      remote_file_id: 'file-keep-1',
      remote_path: 'Nexo/Books/book-1.epub',
      remote_name: 'book-1.epub',
      protocol_version: '1',
      recovery_protocol: 'recovery_protocol_v1',
      ...overrides,
    };
  }

  function upsertPayload(): Record<string, unknown> {
    return mockChainable.upsert.mock.calls[0]?.[0] as Record<string, unknown>;
  }

  it('RED: upsert without remote fields preserves existing refs + catalog_version (not reset to 1)', async () => {
    mockChainable.maybeSingle = vi.fn().mockResolvedValue({ data: currentRow(), error: null });
    mockFrom = vi.fn().mockReturnValue(mockChainable);

    const sync = new SupabaseBookCatalogSync('user-1');
    await sync.upsertBook(makeUserBookRow({ contentHash: null }));

    const payload = upsertPayload();
    expect(payload.catalog_version).toBe(3);
    expect(payload.remote_provider).toBe('google_drive');
    expect(payload.remote_file_id).toBe('file-keep-1');
    expect(payload.remote_path).toBe('Nexo/Books/book-1.epub');
    expect(payload.remote_name).toBe('book-1.epub');
  });

  it('RED: content_hash is preserved when incoming is null (no null write)', async () => {
    mockChainable.maybeSingle = vi.fn().mockResolvedValue({ data: currentRow(), error: null });
    mockFrom = vi.fn().mockReturnValue(mockChainable);

    const sync = new SupabaseBookCatalogSync('user-1');
    await sync.upsertBook(makeUserBookRow({ contentHash: null }));

    expect(upsertPayload().content_hash).toBe('sha256:existing');
  });

  it('RED: recovery_protocol_v1 is never downgraded when incoming omits it', async () => {
    mockChainable.maybeSingle = vi.fn().mockResolvedValue({ data: currentRow(), error: null });
    mockFrom = vi.fn().mockReturnValue(mockChainable);

    const sync = new SupabaseBookCatalogSync('user-1');
    await sync.upsertBook(makeUserBookRow());

    expect(upsertPayload().recovery_protocol).toBe('recovery_protocol_v1');
  });

  it('RED: protocol_version survives as text when incoming omits it', async () => {
    mockChainable.maybeSingle = vi.fn().mockResolvedValue({ data: currentRow(), error: null });
    mockFrom = vi.fn().mockReturnValue(mockChainable);

    const sync = new SupabaseBookCatalogSync('user-1');
    await sync.upsertBook(makeUserBookRow());

    expect(upsertPayload().protocol_version).toBe('1');
  });

  it('RED: new row with remote refs writes recovery_protocol_v1 and catalog_version 1', async () => {
    // No current row (fresh insert path)
    const sync = new SupabaseBookCatalogSync('user-1');
    await sync.upsertBook(
      makeUserBookRow({
        catalogVersion: 1,
        remoteProvider: 'google_drive',
        remoteFileId: 'file-new-1',
        remotePath: 'Nexo/Books/book-1.epub',
        remoteName: 'book-1.epub',
        protocolVersion: 1,
        recoveryProtocol: 'recovery_protocol_v1',
      }),
    );

    const payload = upsertPayload();
    expect(payload.recovery_protocol).toBe('recovery_protocol_v1');
    expect(payload.remote_file_id).toBe('file-new-1');
    expect(payload.protocol_version).toBe('1');
    expect(payload.catalog_version).toBe(1);
  });

  it('RED: new row without refs keeps recovery_protocol legacy and catalog_version 1', async () => {
    const sync = new SupabaseBookCatalogSync('user-1');
    await sync.upsertBook(makeUserBookRow({ contentHash: null }));

    const payload = upsertPayload();
    expect(payload.recovery_protocol).toBe('legacy');
    expect(payload.catalog_version).toBe(1);
  });

  it('RED: catalog_version takes max(current, incoming) — incoming bump wins', async () => {
    mockChainable.maybeSingle = vi
      .fn()
      .mockResolvedValue({ data: currentRow({ catalog_version: 3 }), error: null });
    mockFrom = vi.fn().mockReturnValue(mockChainable);

    const sync = new SupabaseBookCatalogSync('user-1');
    await sync.upsertBook(makeUserBookRow({ catalogVersion: 5 }));

    expect(upsertPayload().catalog_version).toBe(5);
  });
});

describe('SupabaseBookCatalogSync — fetchCatalog', () => {
  it('fetches catalog with correct user filter and ordering', async () => {
    mockChainable.order = vi.fn().mockResolvedValue({
      data: [makeRawRow()],
      error: null,
    });
    mockFrom = vi.fn().mockReturnValue(mockChainable);

    const sync = new SupabaseBookCatalogSync('user-1');
    const result = await sync.fetchCatalog();

    expect(mockFrom).toHaveBeenCalledWith('user_books');
    expect(mockChainable.select).toHaveBeenCalledWith('*');
    expect(mockChainable.eq).toHaveBeenCalledWith('user_id', 'user-1');
    expect(mockChainable.order).toHaveBeenCalledWith('updated_at', { ascending: false });
    expect(result).toHaveLength(1);
    expect(result[0].id).toBe('book-1');
    expect(result[0].title).toBe('Test Book');
  });

  it('returns empty array when no books in catalog', async () => {
    mockChainable.order = vi.fn().mockResolvedValue({ data: [], error: null });
    mockFrom = vi.fn().mockReturnValue(mockChainable);

    const sync = new SupabaseBookCatalogSync('user-1');
    const result = await sync.fetchCatalog();

    expect(result).toEqual([]);
  });

  it('throws on Supabase error during fetch', async () => {
    mockChainable.order = vi
      .fn()
      .mockResolvedValue({ data: null, error: new Error('Network error') });
    mockFrom = vi.fn().mockReturnValue(mockChainable);

    const sync = new SupabaseBookCatalogSync('user-1');
    await expect(sync.fetchCatalog()).rejects.toThrow('Network error');
  });

  it('maps all columns from snake_case DB to camelCase interface', async () => {
    mockChainable.order = vi.fn().mockResolvedValue({
      data: [
        makeRawRow({
          id: 'b2',
          user_id: 'u1',
          title: '1984',
          author: 'Orwell',
          format: 'pdf',
          content_hash: 'sha256:abc123',
          file_path: 'books/u1/b2.pdf',
          cover_url: 'https://example.com/cover.jpg',
          description: 'A dystopian novel',
          total_pages: 328,
          source_device: 'android',
          imported_at: '2025-03-01T00:00:00Z',
          updated_at: '2025-06-15T00:00:00Z',
        }),
      ],
      error: null,
    });
    mockFrom = vi.fn().mockReturnValue(mockChainable);

    const sync = new SupabaseBookCatalogSync('user-1');
    const result = await sync.fetchCatalog();

    expect(result).toHaveLength(1);
    expect(result[0]).toEqual({
      id: 'b2',
      userId: 'u1',
      title: '1984',
      author: 'Orwell',
      format: 'pdf',
      contentHash: 'sha256:abc123',
      filePath: 'books/u1/b2.pdf',
      coverUrl: 'https://example.com/cover.jpg',
      description: 'A dystopian novel',
      totalPages: 328,
      sourceDevice: 'android',
      importedAt: '2025-03-01T00:00:00Z',
      updatedAt: '2025-06-15T00:00:00Z',
      lifecycle: 'imported',
      catalogVersion: 1,
      remoteProvider: null,
      remoteFileId: null,
      remotePath: null,
      remoteName: null,
      protocolVersion: null,
      recoveryProtocol: null,
      deletedAt: null,
      deletedByDevice: null,
      coverBucket: null,
      coverObjectPath: null,
      coverHash: null,
      coverMediaType: null,
    });
  });
});

describe('SupabaseBookCatalogSync — deleteBook', () => {
  it('deletes a book row by user_id and id', async () => {
    mockChainable.delete = vi.fn().mockReturnThis();
    // First eq call returns this, second resolves
    mockChainable.eq = vi
      .fn()
      .mockReturnValueOnce(mockChainable) // user_id eq
      .mockResolvedValueOnce({ error: null }); // id eq
    mockFrom = vi.fn().mockReturnValue(mockChainable);

    const sync = new SupabaseBookCatalogSync('user-1');
    await sync.deleteBook('book-1');

    expect(mockFrom).toHaveBeenCalledWith('user_books');
    expect(mockChainable.delete).toHaveBeenCalled();
    expect(mockChainable.eq).toHaveBeenCalledWith('user_id', 'user-1');
    expect(mockChainable.eq).toHaveBeenCalledWith('id', 'book-1');
  });

  it('throws on Supabase error during delete', async () => {
    mockChainable.delete = vi.fn().mockReturnThis();
    mockChainable.eq = vi
      .fn()
      .mockReturnValueOnce(mockChainable)
      .mockResolvedValueOnce({ error: new Error('Delete failed') });
    mockFrom = vi.fn().mockReturnValue(mockChainable);

    const sync = new SupabaseBookCatalogSync('user-1');
    await expect(sync.deleteBook('book-1')).rejects.toThrow('Delete failed');
  });
});

describe('SupabaseBookCatalogSync — subscribeToCatalog', () => {
  it('subscribes to Realtime changes on user_books with userId filter', () => {
    const sync = new SupabaseBookCatalogSync('user-1');

    const unsubscribe = sync.subscribeToCatalog(vi.fn());

    expect(mockChannel).toHaveBeenCalledWith('catalog:user-1');

    const channelObj = mockChannel.mock.results[0].value;
    expect(channelObj.on).toHaveBeenCalledWith(
      'postgres_changes',
      expect.objectContaining({
        event: '*',
        schema: 'public',
        table: 'user_books',
        filter: 'user_id=eq.user-1',
      }),
      expect.any(Function),
    );
    expect(channelObj.subscribe).toHaveBeenCalled();
    expect(typeof unsubscribe).toBe('function');
  });

  it('calls callback on INSERT event', () => {
    const sync = new SupabaseBookCatalogSync('user-1');
    const callback = vi.fn();

    sync.subscribeToCatalog(callback);

    const channelObj = mockChannel.mock.results[0].value;
    const onHandler = channelObj.on.mock.calls[0][2];

    onHandler({
      eventType: 'INSERT',
      new: makeRawRow({ id: 'new-book', title: 'New Book' }),
      old: {},
      errors: [],
    });

    expect(callback).toHaveBeenCalledTimes(1);
    expect(callback).toHaveBeenCalledWith(
      expect.objectContaining({
        id: 'new-book',
        title: 'New Book',
      }),
    );
  });

  it('skips callback when payload.new is null', () => {
    const sync = new SupabaseBookCatalogSync('user-1');
    const callback = vi.fn();

    sync.subscribeToCatalog(callback);

    const channelObj = mockChannel.mock.results[0].value;
    const onHandler = channelObj.on.mock.calls[0][2];

    onHandler({
      eventType: 'DELETE',
      new: null,
      old: {},
      errors: [],
    });

    expect(callback).not.toHaveBeenCalled();
  });
});

describe('SupabaseBookCatalogSync — findByHash (PR 5 dedup)', () => {
  it('returns a book row when hash matches', async () => {
    // Override chainable for findByHash: select → eq(user_id) → eq(content_hash) → maybeSingle
    const maybeSingleMock = vi.fn().mockResolvedValue({
      data: makeRawRow({ content_hash: 'sha256:abc123' }),
      error: null,
    });
    mockChainable.maybeSingle = maybeSingleMock;
    // eq returns this twice (once for user_id, once for content_hash)
    mockChainable.eq = vi.fn().mockReturnThis();
    mockFrom = vi.fn().mockReturnValue(mockChainable);

    const sync = new SupabaseBookCatalogSync('user-1');
    const result = await sync.findByHash('sha256:abc123');

    expect(result).not.toBeNull();
    expect(result!.id).toBe('book-1');
    expect(result!.contentHash).toBe('sha256:abc123');
    expect(mockFrom).toHaveBeenCalledWith('user_books');
    expect(mockChainable.eq).toHaveBeenCalledWith('user_id', 'user-1');
    expect(mockChainable.eq).toHaveBeenCalledWith('content_hash', 'sha256:abc123');
  });

  it('returns null when hash does not match', async () => {
    mockChainable.maybeSingle = vi.fn().mockResolvedValue({ data: null, error: null });
    mockChainable.eq = vi.fn().mockReturnThis();
    mockFrom = vi.fn().mockReturnValue(mockChainable);

    const sync = new SupabaseBookCatalogSync('user-1');
    const result = await sync.findByHash('sha256:nonexistent');

    expect(result).toBeNull();
  });

  it('throws on Supabase error', async () => {
    mockChainable.maybeSingle = vi.fn().mockResolvedValue({
      data: null,
      error: new Error('DB error'),
    });
    mockChainable.eq = vi.fn().mockReturnThis();
    mockFrom = vi.fn().mockReturnValue(mockChainable);

    const sync = new SupabaseBookCatalogSync('user-1');
    await expect(sync.findByHash('sha256:error')).rejects.toThrow('DB error');
  });
});

describe('SupabaseBookCatalogSync — tombstoneBook (PR4 explicit delete)', () => {
  it('upserts a versioned tombstone instead of hard-deleting', async () => {
    // maybeSingle returns an existing row with catalog_version 3
    mockChainable.maybeSingle = vi.fn().mockResolvedValue({
      data: makeRawRow({ title: 'Keep Me', format: 'epub', catalog_version: 3 }),
      error: null,
    });
    mockChainable.eq = vi.fn().mockReturnThis();
    mockFrom = vi.fn().mockReturnValue(mockChainable);

    const sync = new SupabaseBookCatalogSync('user-1');
    await sync.tombstoneBook('book-1');

    // Must NOT call delete()
    expect(mockChainable.delete).not.toHaveBeenCalled();
    expect(mockChainable.upsert).toHaveBeenCalledWith(
      expect.objectContaining({
        id: 'book-1',
        user_id: 'user-1',
        lifecycle: 'deleted',
        catalog_version: 4,
        deleted_by_device: 'desktop',
        deleted_at: expect.any(String),
      }),
      expect.objectContaining({ onConflict: 'user_id, id' }),
    );
  });

  it('bumps version from 1 when row has no catalog_version', async () => {
    mockChainable.maybeSingle = vi.fn().mockResolvedValue({
      data: makeRawRow({ title: 'Legacy', format: 'epub', catalog_version: null }),
      error: null,
    });
    mockChainable.eq = vi.fn().mockReturnThis();
    mockFrom = vi.fn().mockReturnValue(mockChainable);

    const sync = new SupabaseBookCatalogSync('user-1');
    await sync.tombstoneBook('book-1');

    expect(mockChainable.upsert).toHaveBeenCalledWith(
      expect.objectContaining({ lifecycle: 'deleted', catalog_version: 2 }),
      expect.any(Object),
    );
  });
});

describe('SupabaseBookCatalogSync — destroy', () => {
  it('unsubscribes from Realtime channel and clears reference', () => {
    const sync = new SupabaseBookCatalogSync('user-1');

    sync.subscribeToCatalog(vi.fn());

    const channelObj = mockChannel.mock.results[0].value;

    sync.destroy();

    // destroy() calls the stored unsubscribe function which invokes channel.unsubscribe
    expect(channelObj.unsubscribe).toHaveBeenCalled();
  });
});

describe('decideCatalogChange — PR5 Realtime apply-if-newer convergence', () => {
  const local = (catalogVersion: number, lifecycle: 'available' | 'deleted' = 'available') => ({
    catalogVersion,
    lifecycle,
  });
  it('missing local state never becomes deletion: tombstone with no local row is ignored', () => {
    expect(decideCatalogChange(null, local(4, 'deleted'))).toBe('ignore-missing-local');
  });
  it('missing local state accepts a new available row', () => {
    expect(decideCatalogChange(null, local(1, 'available'))).toBe('apply');
  });
  it('local tombstone is never resurrected by any event', () => {
    expect(decideCatalogChange(local(5, 'deleted'), local(6, 'available'))).toBe(
      'ignore-local-tombstone',
    );
  });
  it('stale event (older version) is ignored', () => {
    expect(decideCatalogChange(local(5), local(4))).toBe('ignore-stale');
  });
  it('equal-version event is idempotent (ignored)', () => {
    expect(decideCatalogChange(local(5), local(5))).toBe('ignore-equal');
  });
  it('newer event applies (metadata or explicit tombstone)', () => {
    expect(decideCatalogChange(local(5), local(6))).toBe('apply');
    expect(decideCatalogChange(local(5), local(6, 'deleted'))).toBe('apply');
  });
  it('missing catalogVersion defaults to 0 for ordering', () => {
    expect(
      decideCatalogChange(
        { catalogVersion: 0, lifecycle: 'available' },
        { catalogVersion: 1, lifecycle: 'available' },
      ),
    ).toBe('apply');
    expect(
      decideCatalogChange(
        { catalogVersion: 1, lifecycle: 'available' },
        { catalogVersion: 0, lifecycle: 'available' },
      ),
    ).toBe('ignore-stale');
  });
});

describe('Reconciliation — gap detection logic', () => {
  it('identifies remote books not in local set as downloadable', () => {
    const localBooks = [
      { id: 'local-1', title: 'Local Book' },
      { id: 'local-2', title: 'Another Local' },
    ];

    const remoteBooks = [
      {
        id: 'local-1',
        title: 'Local Book',
        userId: 'u1',
        author: null,
        format: 'epub',
        contentHash: null,
        filePath: null,
        coverUrl: null,
        description: null,
        totalPages: null,
        sourceDevice: null,
        importedAt: '',
        updatedAt: '',
      },
      {
        id: 'remote-1',
        title: 'Remote Book',
        userId: 'u1',
        author: 'Remote Author',
        format: 'pdf',
        contentHash: null,
        filePath: null,
        coverUrl: null,
        description: null,
        totalPages: 300,
        sourceDevice: 'android',
        importedAt: '',
        updatedAt: '',
      },
      {
        id: 'remote-2',
        title: 'Another Remote',
        userId: 'u1',
        author: null,
        format: 'epub',
        contentHash: null,
        filePath: null,
        coverUrl: null,
        description: null,
        totalPages: null,
        sourceDevice: null,
        importedAt: '',
        updatedAt: '',
      },
    ];

    const localIds = new Set(localBooks.map((b) => b.id));
    const downloadable = remoteBooks.filter((rb) => !localIds.has(rb.id));

    expect(downloadable).toHaveLength(2);
    expect(downloadable.map((b) => b.id)).toEqual(['remote-1', 'remote-2']);
  });

  it('identifies local books missing from remote as needing push', () => {
    const localBooks = [
      { id: 'local-1', title: 'Shared Book' },
      { id: 'local-2', title: 'Missing from Remote' },
    ];

    const remoteBooks = [
      {
        id: 'local-1',
        title: 'Shared Book',
        userId: 'u1',
        author: null,
        format: 'epub',
        contentHash: null,
        filePath: null,
        coverUrl: null,
        description: null,
        totalPages: null,
        sourceDevice: null,
        importedAt: '',
        updatedAt: '',
      },
    ];

    const remoteIds = new Set(remoteBooks.map((b) => b.id));
    const needPush = localBooks.filter((lb) => !remoteIds.has(lb.id));

    expect(needPush).toHaveLength(1);
    expect(needPush[0].id).toBe('local-2');
  });
});

describe('SupabaseBookCatalogSync — uploadCover COVER_FAILED mapping', () => {
  it('maps upload failure to the stable COVER_FAILED code and never returns a blocking error', async () => {
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => undefined);
    mockStorage = {
      from: vi.fn().mockReturnValue({
        upload: vi.fn().mockResolvedValue({ error: new Error('storage denied') }),
        getPublicUrl: vi
          .fn()
          .mockReturnValue({ data: { publicUrl: 'https://cdn.example/cover.jpg' } }),
      }),
    };
    const sync = new SupabaseBookCatalogSync('user-1');
    const url = await sync.uploadCover('user-1', 'book-1', new Uint8Array([1]).buffer);
    expect(url).toBeNull();
    expect(mockStorage.from).toHaveBeenCalledWith('book-covers');
    // The failure is typed with the stable code (observability) while import stays non-blocking.
    expect(warn).toHaveBeenCalledWith(expect.stringContaining('COVER_FAILED'), expect.anything());
    warn.mockRestore();
  });

  it('returns the signed public URL and no COVER_FAILED warning on success', async () => {
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => undefined);
    const sync = new SupabaseBookCatalogSync('user-1');
    const url = await sync.uploadCover('user-1', 'book-1', new Uint8Array([1]).buffer);
    expect(url).toBe('https://cdn.example/cover.jpg');
    expect(warn).not.toHaveBeenCalled();
    warn.mockRestore();
  });
});
