/**
 * Addon provenance on the desktop side of the catalog sync.
 *
 * The three provenance columns (`source_addon_id`, `source_item_id`, `isbn13`)
 * must reach `user_books` on write and come back on read, so a book imported
 * from an addon on any client still knows where it came from. They are
 * nullable: books imported before the v3 protocol have no provenance at all.
 */
import { describe, it, expect, vi, beforeEach, beforeAll } from 'vitest';
import type { SupabaseUserBookRow } from '$lib/shared/sync/SupabaseBookCatalogSync';

let mockChainable: Record<string, ReturnType<typeof vi.fn>>;
let mockFrom = vi.fn();

vi.mock('$lib/services/supabase', () => ({
  getSessionClient: () => ({
    from: mockFrom,
    channel: vi.fn(),
    storage: { from: vi.fn() },
  }),
}));

function makeUserBookRow(overrides: Partial<SupabaseUserBookRow> = {}): SupabaseUserBookRow {
  return {
    id: 'book-1',
    userId: 'user-1',
    title: 'Test Book',
    author: 'Test Author',
    format: 'epub',
    filePath: null,
    coverUrl: null,
    description: null,
    totalPages: 100,
    sourceDevice: 'desktop',
    importedAt: '2026-01-01T00:00:00Z',
    updatedAt: '2026-01-01T00:00:00Z',
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
  mockChainable = {
    upsert: vi.fn().mockResolvedValue({ error: null }),
    select: vi.fn().mockReturnThis(),
    eq: vi.fn().mockReturnThis(),
    order: vi.fn().mockResolvedValue({ data: [], error: null }),
    delete: vi.fn().mockReturnThis(),
    maybeSingle: vi.fn().mockResolvedValue({ data: null, error: null }),
  };
  mockFrom = vi.fn().mockReturnValue(mockChainable);
});

describe('user_books provenance write path', () => {
  it('writes the three provenance columns from the camelCase row', async () => {
    const sync = new SupabaseBookCatalogSync('user-1');

    await sync.upsertBook(
      makeUserBookRow({
        sourceAddonId: 'org.example.gutendex',
        sourceItemId: 'gutenberg:1342',
        isbn13: '9780141439518',
      }),
    );

    expect(mockChainable.upsert).toHaveBeenCalledWith(
      expect.objectContaining({
        source_addon_id: 'org.example.gutendex',
        source_item_id: 'gutenberg:1342',
        isbn13: '9780141439518',
      }),
      expect.any(Object),
    );
  });

  it('writes NULL provenance for books imported before the protocol', async () => {
    const sync = new SupabaseBookCatalogSync('user-1');

    await sync.upsertBook(makeUserBookRow());

    const payload = mockChainable.upsert.mock.calls[0][0] as Record<string, unknown>;
    expect(payload.source_addon_id).toBeNull();
    expect(payload.source_item_id).toBeNull();
    expect(payload.isbn13).toBeNull();
  });

  it('reads provenance back into camelCase when fetching the catalog', async () => {
    const sync = new SupabaseBookCatalogSync('user-1');
    mockChainable.order = vi.fn().mockResolvedValue({
      data: [
        {
          id: 'book-1',
          user_id: 'user-1',
          title: 'Test Book',
          author: 'Test Author',
          format: 'epub',
          imported_at: '2026-01-01T00:00:00Z',
          updated_at: '2026-01-01T00:00:00Z',
          source_addon_id: 'org.example.gutendex',
          source_item_id: 'gutenberg:1342',
          isbn13: '9780141439518',
        },
      ],
      error: null,
    });

    const [row] = await sync.fetchCatalog();

    expect(row.sourceAddonId).toBe('org.example.gutendex');
    expect(row.sourceItemId).toBe('gutenberg:1342');
    expect(row.isbn13).toBe('9780141439518');
  });

  it('reads NULL provenance as null, never as the string "null"', async () => {
    const sync = new SupabaseBookCatalogSync('user-1');
    mockChainable.order = vi.fn().mockResolvedValue({
      data: [
        {
          id: 'book-1',
          user_id: 'user-1',
          title: 'Legacy Book',
          format: 'epub',
          imported_at: '2026-01-01T00:00:00Z',
          updated_at: '2026-01-01T00:00:00Z',
          source_addon_id: null,
          source_item_id: null,
          isbn13: null,
        },
      ],
      error: null,
    });

    const [row] = await sync.fetchCatalog();

    expect(row.sourceAddonId).toBeNull();
    expect(row.sourceItemId).toBeNull();
    expect(row.isbn13).toBeNull();
  });
});
