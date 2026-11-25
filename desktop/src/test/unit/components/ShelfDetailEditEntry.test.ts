import { fireEvent, render, screen, waitFor } from '@testing-library/svelte';
import { describe, expect, it, vi } from 'vitest';
import ShelfDetailModal from '$lib/features/library/ShelfDetailModal.svelte';
import ShelfBookActions from '$lib/features/library/components/ShelfBookActions.svelte';
import type { ShelfBook } from '$lib/features/library/utils';
import type { LibraryBookDto, ReaderBook } from '$lib/shared/types';

/**
 * Every entry point opens the ONE metadata editor (`libraryState.handleEditBook`
 * → `EditMetadataModal` in AppModals): the details view, Home's shelf menu and
 * the library's "⋯" menu.
 */

const t = (key: string): string => key;
const noop = (): void => {};

const BOOK: ReaderBook = {
  id: 'b1',
  title: 'Hábitos Atómicos',
  author: 'James Clear',
  format: 'epub',
  currentPage: 0,
  totalPages: 0,
  progressPercentage: 0,
  coverPath: null,
  minutesRead: 0,
  updatedAt: '2026-01-01T00:00:00Z',
  createdAt: '2026-01-01T00:00:00Z',
  filePath: 'C:/books/b1.epub',
};

describe('metadata editor entry points', () => {
  it('ShelfDetailModal hands the book to onEdit and renders no inline form', async () => {
    const onEdit = vi.fn();
    render(ShelfDetailModal, {
      open: true,
      book: BOOK as LibraryBookDto,
      collections: [],
      t,
      onClose: noop,
      onStartReading: noop,
      onEdit,
      onStatusChange: noop,
      onToggleFavorite: noop,
    });

    const edit = await waitFor(() => screen.getByRole('button', { name: /shelf\.editMetadata/ }));
    await fireEvent.click(edit);

    expect(onEdit).toHaveBeenCalledWith(BOOK);
    expect(document.getElementById('edit-title')).toBeNull();
  });

  it('the library "⋯" menu offers the same editor, and only an unfinished book can be completed', async () => {
    const onEdit = vi.fn();
    render(ShelfBookActions, {
      book: { ...BOOK, readingStatus: 'completed' } as ShelfBook,
      t,
      onEdit,
    });

    await fireEvent.click(screen.getByRole('button', { name: /shelf\.bookOptions/ }));
    const edit = await waitFor(() => screen.getByText('shelf.editMetadata'));
    await fireEvent.click(edit);

    expect(onEdit).toHaveBeenCalledWith(expect.objectContaining({ id: 'b1' }));
    expect(screen.queryByText('shelf.markCompleted')).toBeNull();
    // Picking an item is a choice: the menu closes instead of staying up over
    // the dialog the item opened.
    await waitFor(() => expect(screen.queryByRole('menu')).toBeNull());
  });
});
