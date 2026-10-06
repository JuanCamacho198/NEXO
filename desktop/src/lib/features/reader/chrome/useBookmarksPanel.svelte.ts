import { createBookmarksState } from './bookmarksState.svelte';
import { SyncOutboxDao } from '$lib/shared/outbox/SyncOutboxDao';
import type { ViewerPort } from '$lib/shared/ports/ViewerPort';

export type BookmarksPanelDeps = {
  outboxDao?: SyncOutboxDao;
  viewerPort?: ViewerPort;
};

export function createBookmarksPanel(deps: BookmarksPanelDeps = {}): {
  readonly bookmarksState: ReturnType<typeof createBookmarksState>;
  showBookmarks: boolean;
  showBookmarkRibbon: boolean;
  bookmarksPanelEl: HTMLElement | undefined;
  readonly _ribbonTimer: ReturnType<typeof setTimeout> | null;
  triggerBookmarkRibbon(): void;
  toggleBookmarks(): void;
  closeBookmarks(): void;
  openBookmarks(): void;
  cleanup(): void;
} {
  const outboxDao = deps.outboxDao ?? new SyncOutboxDao();
  const bookmarksState = createBookmarksState({ outboxDao, viewerPort: deps.viewerPort });

  let showBookmarks = $state(false);
  let showBookmarkRibbon = $state(false);
  let ribbonTimer: ReturnType<typeof setTimeout> | null = null;
  let bookmarksPanelEl = $state<HTMLElement | undefined>(undefined);

  function triggerBookmarkRibbon(): void {
    showBookmarkRibbon = true;
    if (ribbonTimer) clearTimeout(ribbonTimer);
    ribbonTimer = setTimeout(() => {
      showBookmarkRibbon = false;
      ribbonTimer = null;
    }, 2200);
  }

  function toggleBookmarks(): void {
    showBookmarks = !showBookmarks;
  }

  function closeBookmarks(): void {
    showBookmarks = false;
  }

  function openBookmarks(): void {
    showBookmarks = true;
  }

  function cleanup(): void {
    if (ribbonTimer) {
      clearTimeout(ribbonTimer);
      ribbonTimer = null;
    }
  }

  return {
    get bookmarksState() {
      return bookmarksState;
    },
    get showBookmarks() {
      return showBookmarks;
    },
    set showBookmarks(v: boolean) {
      showBookmarks = v;
    },
    get showBookmarkRibbon() {
      return showBookmarkRibbon;
    },
    set showBookmarkRibbon(v: boolean) {
      showBookmarkRibbon = v;
    },
    get bookmarksPanelEl() {
      return bookmarksPanelEl;
    },
    set bookmarksPanelEl(v: HTMLElement | undefined) {
      bookmarksPanelEl = v;
    },
    get _ribbonTimer() {
      return ribbonTimer;
    },
    triggerBookmarkRibbon,
    toggleBookmarks,
    closeBookmarks,
    openBookmarks,
    cleanup,
  };
}

export type BookmarksPanel = ReturnType<typeof createBookmarksPanel>;
