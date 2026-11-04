import { describe, expect, it, vi, beforeEach, afterEach } from 'vitest';

// One mock per module: registering `tauriClient` twice (once sparsely, once
// spread over the real module) made the effective mock depend on registration
// order, so a later change to who imports what flipped which one won.
vi.mock('$lib/features/reader/highlight/highlightColors', async () => {
  const actual = await vi.importActual<
    typeof import('$lib/features/reader/highlight/highlightColors')
  >('$lib/features/reader/highlight/highlightColors');
  return actual;
});

import { createHighlights } from '$lib/features/reader/chrome/useHighlights.svelte';
import { createHighlightMenu } from '$lib/features/reader/highlight/useHighlightMenu.svelte';
import { createSpineResolver } from '$lib/features/reader/chrome/useSpineResolver.svelte';
import { SyncOutboxDao } from '$lib/shared/outbox/SyncOutboxDao';

vi.mock('$lib/shared/outbox/SyncOutboxDao', () => ({
  SyncOutboxDao: class {
    add = vi.fn().mockResolvedValue('id');
  },
}));

vi.mock('$lib/shared/stores/ReaderDomainState.svelte', () => ({
  readerState: { locatorJson: '', highlightsVersion: 0, cfiLocation: '' },
}));
vi.mock('$lib/shared/debug/debugState.svelte', () => ({
  debugState: {
    epub: {
      colorPickCount: 0,
      lastPickedColor: '',
      saveHighlightCallCount: 0,
      saveHighlightLastError: '',
      failedHighlightIds: [] as string[],
    },
  },
}));
vi.mock('$lib/shared/stores/AuthState.svelte', () => ({ authState: { userId: null } }));
vi.mock('$lib/shared/api/tauriClient', async () => {
  const actual = await vi.importActual<typeof import('$lib/shared/api/tauriClient')>(
    '$lib/shared/api/tauriClient',
  );
  return {
    ...actual,
    saveHighlight: vi.fn().mockResolvedValue(undefined),
    deleteHighlight: vi.fn().mockResolvedValue(undefined),
    updateHighlight: vi.fn().mockResolvedValue({}),
    listHighlights: vi.fn().mockResolvedValue([]),
    saveHighlightTags: vi.fn().mockResolvedValue([{ id: 't1', name: 'tag1' }]),
    createTag: vi.fn().mockResolvedValue({ id: 't-new', name: 'New' }),
    listTags: vi.fn().mockResolvedValue([{ id: 't1', name: 't1' }]),
    listTagsForHighlight: vi.fn().mockResolvedValue([{ id: 't1', name: 't1' }]),
  };
});

describe('useHighlightMenu', () => {
  beforeEach(() => {
    vi.useFakeTimers();
  });
  afterEach(() => {
    vi.useRealTimers();
    vi.clearAllMocks();
  });

  function makeHighlights() {
    const spine = createSpineResolver({ parseEpub: vi.fn().mockResolvedValue({ spineHrefs: [] }) });
    const outbox = new SyncOutboxDao();
    const book = { id: 'b1', filePath: 'a.epub', format: 'epub' } as never;
    const h = createHighlights({ getBook: () => book as never, spine, outbox });
    // seed persisted highlight
    h.persistedHighlights = [
      {
        id: 'h1',
        color: '#facc15',
        pageNumber: 0,
        rects: [],
        cfi: null,
        text: 'hello',
        note: null,
      },
    ];
    return h;
  }

  it('openHighlightMenu sets position and loads tags', async () => {
    const h = makeHighlights();
    const listTagsMock = vi.fn().mockResolvedValue([{ id: 't1', name: 't1' }]);
    const listForMock = vi.fn().mockResolvedValue([{ id: 't1', name: 't1' }]);
    const m = createHighlightMenu({
      highlights: h,
      listTagsFn: listTagsMock,
      listTagsForHighlightFn: listForMock,
    });
    m.openHighlightMenu('h1', { x: 100, y: 200, color: '#ff0000', text: 'hello' });
    expect(m.highlightMenu.open).toBe(true);
    expect(m.highlightMenu.highlightId).toBe('h1');
    expect(m.highlightMenu.position).toEqual({ x: 100, y: 200 });
    expect(m.highlightMenu.color).toBe('#ff0000');
    // refreshTags called async
    await Promise.resolve();
    expect(listTagsMock).toHaveBeenCalled();
    expect(listForMock).toHaveBeenCalledWith('h1');
    m.cleanup();
  });

  it('handleHighlightAction routes open/close/updateColor/delete', () => {
    const h = makeHighlights();
    const m = createHighlightMenu({ highlights: h });
    m.handleHighlightAction('open', 'h1', { x: 10, y: 10 });
    expect(m.highlightMenu.open).toBe(true);
    m.handleHighlightAction('updateColor', 'h1', { color: '#00ff00' });
    expect(m.highlightMenu.color).toBe('#00ff00');
    expect(h.persistedHighlights[0].color).toBe('#00ff00');
    m.handleHighlightAction('delete', 'h1');
    expect(m.highlightMenu.open).toBe(false);
    expect(h.persistedHighlights).toHaveLength(0);
    m.cleanup();
  });

  it('220ms scheduleToolbarDismiss fires once and clears on cleanup', () => {
    const h = makeHighlights();
    const m = createHighlightMenu({ highlights: h });
    const dismiss = vi.fn();
    m.scheduleToolbarDismiss(dismiss);
    expect(m._dismissTimer).not.toBeNull();
    expect(dismiss).not.toHaveBeenCalled();
    vi.advanceTimersByTime(219);
    expect(dismiss).not.toHaveBeenCalled();
    vi.advanceTimersByTime(1);
    expect(dismiss).toHaveBeenCalledTimes(1);
    expect(m._dismissTimer).toBeNull();
    // reschedule then cleanup cancels
    m.scheduleToolbarDismiss(dismiss);
    expect(m._dismissTimer).not.toBeNull();
    m.cleanup();
    expect(m._dismissTimer).toBeNull();
    vi.advanceTimersByTime(220);
    expect(dismiss).toHaveBeenCalledTimes(1);
  });

  it('handleTagToggle and handleTagCreate update assignedTags', async () => {
    const h = makeHighlights();
    const saveMock = vi.fn().mockResolvedValue([
      { id: 't1', name: 't1' },
      { id: 't-new', name: 'New' },
    ]);
    const createMock = vi.fn().mockResolvedValue({ id: 't-new', name: 'New' });
    const m = createHighlightMenu({
      highlights: h,
      saveHighlightTagsFn: saveMock,
      createTagFn: createMock,
      listTagsFn: vi.fn().mockResolvedValue([]),
      listTagsForHighlightFn: vi.fn().mockResolvedValue([]),
    });
    m.openHighlightMenu('h1');
    await Promise.resolve();
    // toggle adds tag
    m.highlightMenu.assignedTags = [{ id: 't1', name: 't1' } as never];
    await m.handleTagToggle('t-new');
    expect(saveMock).toHaveBeenCalledWith({
      highlightId: 'h1',
      tagIds: expect.arrayContaining(['t1', 't-new']),
    });
    // create new tag
    await m.handleTagCreate('New');
    expect(createMock).toHaveBeenCalledWith({ name: 'New', color: undefined });
    expect(saveMock).toHaveBeenCalledTimes(2);
    m.cleanup();
  });

  it('handleColorPickerSelect updates color and closes picker', () => {
    const h = makeHighlights();
    const m = createHighlightMenu({ highlights: h });
    m.openHighlightMenu('h1');
    m.showColorPicker = true;
    m.handleColorPickerSelect('#123456');
    expect(h.persistedHighlights[0].color).toBe('#123456');
    expect(m.showColorPicker).toBe(false);
    expect(m.highlightMenu.color).toBe('#123456');
    m.cleanup();
  });

  it('handleMenuCopy and close reset state', () => {
    const h = makeHighlights();
    const m = createHighlightMenu({ highlights: h });
    Object.defineProperty(navigator, 'clipboard', {
      configurable: true,
      value: { writeText: vi.fn().mockResolvedValue(undefined) },
    });
    m.openHighlightMenu('h1', { text: 'hello' });
    expect(m.highlightMenu.text).toBe('hello');
    m.handleMenuCopy();
    expect(m.highlightMenu.open).toBe(false);
    m.cleanup();
  });
});
