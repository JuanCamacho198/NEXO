import { HIGHLIGHT_COLORS } from '$lib/features/reader/highlight/highlightColors';
import type { TagDto } from '$lib/shared/types/book';
import type { ViewerPort } from '$lib/shared/ports/ViewerPort';
import { TauriViewerAdapter } from '$lib/shared/ports/adapters/tauri/TauriViewerAdapter';
import type { HighlightActionKind, HighlightActionOpts } from '$lib/shared/types/book';
import type { HighlightsState } from '../chrome/useHighlights.svelte';
import { handleError } from '$lib/shared/utils/errors';

export type HighlightMenuState = {
  open: boolean;
  highlightId: string | null;
  color: string;
  text: string;
  position: { x: number; y: number } | null;
  assignedTags: TagDto[];
};

export type HighlightMenuDeps = {
  highlights: HighlightsState;
  viewerPort?: ViewerPort;
  listTagsFn?: () => Promise<TagDto[]>;
  listTagsForHighlightFn?: (id: string) => Promise<TagDto[]>;
  createTagFn?: (args: { name: string; color?: string }) => Promise<TagDto>;
  saveHighlightTagsFn?: (args: { highlightId: string; tagIds: string[] }) => Promise<TagDto[]>;
  /**
   * "The reader asked for the highlight menu."
   *
   * The composable does NOT decide that the menu is open: the workspace owns the
   * single `OpenMenu` state and calls back here (through `setOpen`) once it has
   * raised the menu. This callback is the workspace's chance to close the
   * selection toolbar in the SAME step, so the two cannot both be on screen —
   * the reported defect where selecting a run that contains a highlight raised
   * both menus. Optional so a caller that has only one menu (tests, embeddings)
   * keeps working.
   */
  onIntentOpen?: () => void;
  /** "The reader dismissed the highlight menu." The workspace folds this into
   * the same single state, so closing here cannot leave the other menu up. */
  onIntentClose?: () => void;
};

export function createHighlightMenu(deps: HighlightMenuDeps): {
  highlightMenu: HighlightMenuState;
  allTags: TagDto[];
  showColorPicker: boolean;
  showTagPopover: boolean;
  showNoteModal: boolean;
  colorPickerAnchor: HTMLElement | null;
  tagPopoverAnchor: HTMLElement | null;
  readonly _dismissTimer: ReturnType<typeof setTimeout> | null;
  refreshTags(): Promise<void>;
  refreshHighlightTags(highlightId: string): Promise<void>;
  openHighlightMenu(id: string, opts?: HighlightActionOpts): void;
  closeHighlightMenu(): void;
  handleHighlightAction(action: HighlightActionKind, id: string, opts?: HighlightActionOpts): void;
  updateHighlightColor(id: string, color: string): void;
  updateHighlightNote(id: string, note: string | null): void;
  deleteHighlightById(id: string): void;
  enqueueHighlightUpdate(id: string, changes: { color?: string; note?: string | null }): void;
  handleMenuCustomColor(): void;
  handleMenuCopy(): void;
  handleMenuTag(): void;
  handleMenuNote(): void;
  handleMenuDelete(): void;
  handleNoteSave(note: string | null): void;
  handleTagCreate(name: string, color?: string): Promise<void>;
  handleTagToggle(tagId: string): Promise<void>;
  handleColorPickerSelect(color: string): void;
  scheduleToolbarDismiss(dismiss: () => void): void;
  /**
   * Raise or drop this menu's own open flag.
   *
   * The workspace is the single owner of "which menu is open" and drives this:
   * `setOpen(true)` shows the menu (after the workspace has closed the
   * selection toolbar), `setOpen(false)` hides it and resets the sub-popovers.
   * It is deliberately separate from `openHighlightMenu`, which ALSO loads the
   * menu's data — the workspace calls `openHighlightMenu` for the intent, and
   * this for the final visibility decision.
   */
  setOpen(open: boolean): void;
  cleanup(): void;
} {
  const highlights = deps.highlights;
  const viewerPort = deps.viewerPort ?? new TauriViewerAdapter();
  const listTagsFn = deps.listTagsFn ?? (() => viewerPort.listTags());
  const listTagsForHighlightFn =
    deps.listTagsForHighlightFn ?? ((id: string) => viewerPort.listTagsForHighlight(id));
  const createTagFn =
    deps.createTagFn ?? ((args: { name: string; color?: string }) => viewerPort.createTag(args));
  const saveHighlightTagsFn =
    deps.saveHighlightTagsFn ??
    ((args: { highlightId: string; tagIds: string[] }) => viewerPort.saveHighlightTags(args));

  let highlightMenu = $state<HighlightMenuState>({
    open: false,
    highlightId: null,
    color: HIGHLIGHT_COLORS[0].hex,
    text: '',
    position: null,
    assignedTags: [],
  });
  let allTags = $state<TagDto[]>([]);
  let showColorPicker = $state(false);
  let showTagPopover = $state(false);
  let showNoteModal = $state(false);
  let colorPickerAnchor = $state<HTMLElement | null>(null);
  let tagPopoverAnchor = $state<HTMLElement | null>(null);

  // 220ms dismiss timer for selection toolbar (preserve out:scale transition)
  let dismissTimer: ReturnType<typeof setTimeout> | null = null;

  async function refreshTags(): Promise<void> {
    try {
      allTags = await listTagsFn();
    } catch (err) {
      handleError(err, 'reader', { action: 'tag_refresh' });
    }
  }

  async function refreshHighlightTags(highlightId: string): Promise<void> {
    try {
      const tags = await listTagsForHighlightFn(highlightId);
      highlightMenu.assignedTags = tags;
    } catch (err) {
      handleError(err, 'reader', { highlightId, action: 'tag_refresh_for_highlight' });
    }
  }

  function openHighlightMenu(id: string, opts?: HighlightActionOpts): void {
    const hl = highlights.persistedHighlights.find((h) => h.id === id);
    highlightMenu = {
      open: true,
      highlightId: id,
      color: opts?.color ?? hl?.color ?? HIGHLIGHT_COLORS[0].hex,
      text: opts?.text ?? hl?.text ?? '',
      position:
        opts?.x !== undefined && opts?.y !== undefined
          ? { x: opts.x, y: opts.y }
          : {
              x: typeof window !== 'undefined' ? window.innerWidth / 2 : 0,
              y: typeof window !== 'undefined' ? window.innerHeight / 2 : 0,
            },
      assignedTags: [],
    };
    // Notify the single owner BEFORE the data loads: the workspace closes the
    // selection toolbar in the same step, so the two menus are never both up.
    deps.onIntentOpen?.();
    void refreshTags();
    if (hl) {
      void refreshHighlightTags(id);
    }
  }

  function closeHighlightMenu(): void {
    highlightMenu = {
      open: false,
      highlightId: null,
      color: HIGHLIGHT_COLORS[0].hex,
      text: '',
      position: null,
      assignedTags: [],
    };
    showColorPicker = false;
    showTagPopover = false;
    showNoteModal = false;
    deps.onIntentClose?.();
  }

  /**
   * The final visibility decision, driven BY the workspace's single `OpenMenu`
   * state. `open` false hides the menu (and its popovers); `open` true is a
   * no-op because `openHighlightMenu` already loaded the data and set the flag.
   * Kept idempotent so the workspace may call it on every state change.
   */
  function setOpen(open: boolean): void {
    if (open) return;
    if (!highlightMenu.open && !showColorPicker && !showTagPopover && !showNoteModal) {
      return;
    }
    highlightMenu = {
      open: false,
      highlightId: null,
      color: HIGHLIGHT_COLORS[0].hex,
      text: '',
      position: null,
      assignedTags: [],
    };
    showColorPicker = false;
    showTagPopover = false;
    showNoteModal = false;
  }

  function handleHighlightAction(
    action: HighlightActionKind,
    id: string,
    opts?: HighlightActionOpts,
  ): void {
    if (action === 'open') {
      openHighlightMenu(id, opts);
      return;
    }
    if (action === 'close') {
      closeHighlightMenu();
      return;
    }
    if (action === 'updateColor' && opts?.color) {
      updateHighlightColor(id, opts.color);
      return;
    }
    if (action === 'delete') {
      deleteHighlightById(id);
      return;
    }
  }

  function updateHighlightColor(id: string, color: string): void {
    highlights.updateHighlightColor(id, color);
    if (highlightMenu.highlightId === id) {
      highlightMenu.color = color;
    }
  }

  function updateHighlightNote(id: string, note: string | null): void {
    highlights.updateHighlightNote(id, note);
  }

  function deleteHighlightById(id: string): void {
    highlights.deleteHighlightById(id);
    closeHighlightMenu();
  }

  function enqueueHighlightUpdate(
    id: string,
    changes: { color?: string; note?: string | null },
  ): void {
    highlights.enqueueHighlightUpdate(id, changes);
  }

  function handleMenuCustomColor(): void {
    showColorPicker = !showColorPicker;
  }

  function handleMenuCopy(): void {
    if (highlightMenu.text) {
      void navigator.clipboard.writeText(highlightMenu.text);
    }
    closeHighlightMenu();
  }

  function handleMenuTag(): void {
    showTagPopover = !showTagPopover;
  }

  function handleMenuNote(): void {
    showNoteModal = true;
  }

  function handleMenuDelete(): void {
    if (!highlightMenu.highlightId) return;
    deleteHighlightById(highlightMenu.highlightId);
  }

  function handleNoteSave(note: string | null): void {
    if (!highlightMenu.highlightId) return;
    updateHighlightNote(highlightMenu.highlightId, note);
    showNoteModal = false;
  }

  async function handleTagCreate(name: string, color?: string): Promise<void> {
    try {
      const tag = await createTagFn({ name, color });
      allTags = [...allTags, tag];
      if (highlightMenu.highlightId) {
        const currentIds = highlightMenu.assignedTags.map((t) => t.id);
        const updated = await saveHighlightTagsFn({
          highlightId: highlightMenu.highlightId,
          tagIds: [...currentIds, tag.id],
        });
        highlightMenu.assignedTags = updated;
      }
    } catch (err) {
      // PII: pass tag name LENGTH, never the name. The scrubber would also
      // redact a `tagName` key, but length is the safest shape (no embedded PII).
      handleError(err, 'reader', {
        tagNameLength: name?.length ?? 0,
        action: 'create_tag',
      });
    }
  }

  async function handleTagToggle(tagId: string): Promise<void> {
    if (!highlightMenu.highlightId) return;
    const currentIds = new Set(highlightMenu.assignedTags.map((t) => t.id));
    if (currentIds.has(tagId)) {
      currentIds.delete(tagId);
    } else {
      currentIds.add(tagId);
    }
    try {
      const updated = await saveHighlightTagsFn({
        highlightId: highlightMenu.highlightId,
        tagIds: Array.from(currentIds),
      });
      highlightMenu.assignedTags = updated;
    } catch (err) {
      handleError(err, 'reader', {
        highlightId: highlightMenu.highlightId,
        tagCount: Array.from(currentIds).length,
        action: 'save_highlight_tags',
      });
    }
  }

  function handleColorPickerSelect(color: string): void {
    if (!highlightMenu.highlightId) return;
    updateHighlightColor(highlightMenu.highlightId, color);
    showColorPicker = false;
  }

  // 220ms dismiss for selection toolbar after color pick (preserve transition)
  function scheduleToolbarDismiss(dismiss: () => void): void {
    if (dismissTimer) clearTimeout(dismissTimer);
    dismissTimer = setTimeout(() => {
      dismissTimer = null;
      dismiss();
    }, 220);
  }

  function cleanup(): void {
    if (dismissTimer) {
      clearTimeout(dismissTimer);
      dismissTimer = null;
    }
  }

  return {
    get highlightMenu() {
      return highlightMenu;
    },
    set highlightMenu(v: HighlightMenuState) {
      highlightMenu = v;
    },
    get allTags() {
      return allTags;
    },
    set allTags(v: TagDto[]) {
      allTags = v;
    },
    get showColorPicker() {
      return showColorPicker;
    },
    set showColorPicker(v: boolean) {
      showColorPicker = v;
    },
    get showTagPopover() {
      return showTagPopover;
    },
    set showTagPopover(v: boolean) {
      showTagPopover = v;
    },
    get showNoteModal() {
      return showNoteModal;
    },
    set showNoteModal(v: boolean) {
      showNoteModal = v;
    },
    get colorPickerAnchor() {
      return colorPickerAnchor;
    },
    set colorPickerAnchor(v: HTMLElement | null) {
      colorPickerAnchor = v;
    },
    get tagPopoverAnchor() {
      return tagPopoverAnchor;
    },
    set tagPopoverAnchor(v: HTMLElement | null) {
      tagPopoverAnchor = v;
    },
    get _dismissTimer() {
      return dismissTimer;
    },
    refreshTags,
    refreshHighlightTags,
    openHighlightMenu,
    closeHighlightMenu,
    handleHighlightAction,
    updateHighlightColor,
    updateHighlightNote,
    deleteHighlightById,
    enqueueHighlightUpdate,
    handleMenuCustomColor,
    handleMenuCopy,
    handleMenuTag,
    handleMenuNote,
    handleMenuDelete,
    handleNoteSave,
    handleTagCreate,
    handleTagToggle,
    handleColorPickerSelect,
    scheduleToolbarDismiss,
    setOpen,
    cleanup,
  };
}

export type HighlightMenu = ReturnType<typeof createHighlightMenu>;
