import type {
  ActivityPoint,
  BookmarkDto,
  HighlightDto,
  ReadingProgressDto,
  ReadingStatsSummaryDto,
  SaveBookmarkInput,
  SaveHighlightInput,
  SaveProgressInput,
  SearchBookTextInput,
  SearchBookTextResponse,
  TagDto,
  RemoteHighlightRow,
  RemoteReadingSessionRow,
  UpsertRemoteSummary,
} from '$lib/shared/types';

export type UpdateHighlightInput = {
  id: string;
  color?: string;
  note?: string;
  page?: number;
  pageNumber?: number;
};

export interface ViewerPort {
  listHighlights(bookId?: string): Promise<HighlightDto[]>;
  saveHighlight(input: SaveHighlightInput): Promise<void>;
  deleteHighlight(id: string): Promise<void>;
  updateHighlight(input: UpdateHighlightInput): Promise<HighlightDto>;
  listTags(): Promise<TagDto[]>;
  listTagsForHighlight(highlightId: string): Promise<TagDto[]>;
  createTag(payload: { name: string; color?: string }): Promise<TagDto>;
  saveHighlightTags(payload: { highlightId: string; tagIds: string[] }): Promise<TagDto[]>;
  listBookmarks(bookId?: string): Promise<BookmarkDto[]>;
  saveBookmark(input: SaveBookmarkInput): Promise<void>;
  deleteBookmark(id: string): Promise<void>;
  getProgress(bookId: string): Promise<ReadingProgressDto | null>;
  saveProgress(payload: SaveProgressInput): Promise<void>;
  upsertProgress(progress: ReadingProgressDto): Promise<void>;
  saveReadingSession(
    payload: import('$lib/shared/types').ReadingSessionInput,
  ): Promise<import('$lib/shared/types').ReadingSessionSavedDto>;
  searchBookText(payload: SearchBookTextInput): Promise<SearchBookTextResponse>;
  upsertRemoteHighlights(rows: RemoteHighlightRow[]): Promise<UpsertRemoteSummary>;
  upsertRemoteReadingSessions(rows: RemoteReadingSessionRow[]): Promise<number>;
  getReadingStats(bookId?: string, userId?: string): Promise<ReadingStatsSummaryDto>;
  getReadingStatsForRange(
    from: string,
    to: string,
    bookId?: string,
    userId?: string,
  ): Promise<ReadingStatsSummaryDto>;
  getReadingActivity(
    period: string,
    granularity: string,
    bookId?: string,
  ): Promise<ActivityPoint[]>;
  getReadingStreak(bookId?: string, userId?: string): Promise<number>;
  getLogs(): Promise<string[]>;
  diagnose(): Promise<import('$lib/shared/types').DiagnoseResult>;
}
