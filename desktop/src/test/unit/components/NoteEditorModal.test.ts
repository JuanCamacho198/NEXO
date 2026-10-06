import { fireEvent, render, screen, waitFor } from '@testing-library/svelte';
import { tick } from 'svelte';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { stubElementRect } from '../../harness/jsdomHarness';
import NoteEditorModal from '$lib/features/reader/highlight/NoteEditorModal.svelte';

/**
 * `NoteEditorModal` runs on bits-ui `Dialog` with its public API unchanged:
 * one-way `open` plus `onClose`/`onSave`, same classes, same copy.
 *
 * Library-specific behaviours (same harness rules as Modal.test.ts):
 *  - the dialog is portalled to `document.body`;
 *  - bits-ui closes on an outside `pointerdown` and reads the content box,
 *    so the backdrop case stubs a real rect first;
 *  - the dismissible layer attaches listeners ~1ms after mount, so backdrop
 *    cases settle before dispatching;
 *  - Escape and backdrop close both funnel through the single `onClose`
 *    prop via `onOpenChange` — the component never writes `open` itself,
 *    so no duplicate close is possible by construction.
 */

const t = (key: string, _params?: Record<string, string | number>): string => {
  const translations: Record<string, string> = {
    'highlight.noteModalTitle': 'Add note',
    'highlight.cancel': 'Cancel',
    'highlight.noteReference': 'Reference',
    'highlight.notePlaceholder': 'Write a note…',
    'highlight.save': 'Save',
  };
  return translations[key] ?? key;
};

function renderOpen(props: Record<string, unknown> = {}) {
  return render(NoteEditorModal, {
    props: {
      open: true,
      note: null,
      highlightText: null,
      onSave: vi.fn(),
      onClose: vi.fn(),
      t,
      ...props,
    },
  });
}

function dialog(): HTMLElement {
  const found = document.querySelector('[data-dialog-content]');
  expect(found).toBeTruthy();
  return found as HTMLElement;
}

function overlay(): HTMLElement {
  const found = document.querySelector('[data-dialog-overlay]');
  expect(found).toBeTruthy();
  return found as HTMLElement;
}

function settle(): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, 10));
}

/**
 * Measured: bits-ui's scroll lock writes inline styles onto `document.body`
 * (`overflow: hidden`, `pointer-events: none`, an accumulating
 * `padding-right`) and jsdom never releases them, so a later test in the
 * same file inherits a body that cannot take focus back. The focus test
 * passes alone and failed in file order until this reset existed.
 */
afterEach(() => {
  document.body.removeAttribute('style');
});

async function clickBackdrop(): Promise<void> {
  const content = dialog();
  stubElementRect(content, { left: 100, top: 100, width: 400, height: 300 });
  const target = overlay();
  await fireEvent.pointerDown(target, {
    clientX: 5,
    clientY: 5,
    pointerId: 1,
    pointerType: 'mouse',
    button: 0,
  });
  await fireEvent.pointerUp(target, {
    clientX: 5,
    clientY: 5,
    pointerId: 1,
    pointerType: 'mouse',
    button: 0,
  });
}

describe('NoteEditorModal — open contract', () => {
  it('renders nothing while closed', () => {
    const { container } = renderOpen({ open: false });
    expect(container.querySelector('[data-dialog-content]')).toBeNull();
    expect(document.querySelector('[data-dialog-content]')).toBeNull();
  });

  it('renders portalled with title, textarea and actions when open', async () => {
    renderOpen({ note: 'existing', highlightText: 'quoted passage' });
    const content = dialog();
    expect(document.body.contains(content)).toBe(true);
    expect(screen.getByRole('heading', { name: 'Add note' })).toBeInTheDocument();
    expect(screen.getByText(/quoted passage/)).toBeInTheDocument();
    // The draft init runs in a mount effect: let Svelte flush before reading.
    await tick();
    await settle();
    const area = screen.getByPlaceholderText('Write a note…') as HTMLTextAreaElement;
    expect(area.value).toBe('existing');
    expect(screen.getByRole('button', { name: 'Save' })).toBeInTheDocument();
    // Two "Cancel" controls by design: the header X (aria-label) and the footer button.
    expect(screen.getAllByRole('button', { name: 'Cancel' })).toHaveLength(2);
  });

  it('calls onClose exactly once on Escape', async () => {
    const onClose = vi.fn();
    renderOpen({ onClose });
    dialog();

    await fireEvent.keyDown(document, { key: 'Escape' });
    await waitFor(() => expect(document.querySelector('[data-dialog-content]')).toBeNull());
    expect(onClose).toHaveBeenCalledTimes(1);
  });

  it('calls onClose exactly once on a backdrop pointerdown', async () => {
    const onClose = vi.fn();
    renderOpen({ onClose });
    await settle();

    await clickBackdrop();
    await waitFor(() => expect(document.querySelector('[data-dialog-content]')).toBeNull());
    expect(onClose).toHaveBeenCalledTimes(1);
  });

  it('does not close when the pointer goes down inside the content', async () => {
    const onClose = vi.fn();
    renderOpen({ onClose });
    await settle();

    const content = dialog();
    stubElementRect(content, { left: 100, top: 100, width: 400, height: 300 });
    await fireEvent.pointerDown(content, {
      clientX: 200,
      clientY: 200,
      pointerId: 1,
      pointerType: 'mouse',
      button: 0,
    });
    await settle();

    expect(document.querySelector('[data-dialog-content]')).not.toBeNull();
    expect(onClose).not.toHaveBeenCalled();
  });
});

describe('NoteEditorModal — focus contract', () => {
  it('moves focus into the textarea on open', async () => {
    renderOpen();
    dialog();
    const area = screen.getByPlaceholderText('Write a note…');
    await waitFor(() => expect(document.activeElement).toBe(area));
  });

  it('traps Tab inside the dialog (last -> first)', async () => {
    renderOpen();
    dialog();
    // tabbable (which bits-ui's focus scope uses) reads geometry: jsdom
    // reports zero rects for everything, so every focusable gets a box first.
    const focusables = Array.from(
      document.querySelectorAll('[data-dialog-content] button, [data-dialog-content] textarea'),
    );
    expect(focusables.length).toBeGreaterThan(0);
    focusables.forEach((el, i) => {
      stubElementRect(el as HTMLElement, { left: 100 + i * 10, top: 100, width: 60, height: 30 });
    });
    const save = screen.getByRole('button', { name: 'Save' });
    (save as HTMLElement).focus();
    expect(document.activeElement).toBe(save);

    await fireEvent.keyDown(document.activeElement as Element, { key: 'Tab' });
    await waitFor(() => {
      expect(dialog().contains(document.activeElement)).toBe(true);
      expect(document.activeElement).not.toBe(save);
    });
  });
});

describe('NoteEditorModal — save contract', () => {
  it('saves the trimmed draft and closes', async () => {
    const onSave = vi.fn();
    const onClose = vi.fn();
    renderOpen({ onSave, onClose });
    dialog();

    const area = screen.getByPlaceholderText('Write a note…') as HTMLTextAreaElement;
    await fireEvent.input(area, { target: { value: '  my note  ' } });
    await fireEvent.click(screen.getByRole('button', { name: 'Save' }));

    expect(onSave).toHaveBeenCalledTimes(1);
    expect(onSave).toHaveBeenCalledWith('my note');
    expect(onClose).toHaveBeenCalledTimes(1);
  });

  it('saves null for a blank draft', async () => {
    const onSave = vi.fn();
    renderOpen({ onSave, note: 'old' });
    dialog();

    const area = screen.getByPlaceholderText('Write a note…') as HTMLTextAreaElement;
    await fireEvent.input(area, { target: { value: '   ' } });
    await fireEvent.click(screen.getByRole('button', { name: 'Save' }));

    expect(onSave).toHaveBeenCalledWith(null);
  });
});
