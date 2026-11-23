/**
 * The reader's side drawers (contents, search, text settings, bookmarks) share
 * one frame: a floating sheet on the dialog scrim, inset from the window edge,
 * so the four panels read as one family instead of four hand-rolled columns.
 */
export const READER_DRAWER_SCRIM = 'absolute inset-0 bg-(--color-scrim)';

export const READER_DRAWER =
  'absolute top-3 right-3 bottom-3 flex w-80 max-w-[calc(100vw-1.5rem)] flex-col overflow-y-auto rounded-(--radius-xl) border border-(--color-edge) bg-(--color-reader-chrome) text-(--color-text-muted) shadow-[0_24px_60px_-24px_rgba(0,0,0,0.6)]';

/** How far the sheet travels when it flies in: its width plus the inset. */
export const READER_DRAWER_FLY_X = 340;
