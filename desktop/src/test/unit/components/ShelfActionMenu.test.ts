import { fireEvent, render, screen } from '@testing-library/svelte';
import { describe, expect, it, vi } from 'vitest';
import ShelfActionMenu from '$lib/features/library/components/ShelfActionMenu.svelte';

function setup() {
  const onEdit = vi.fn();
  const view = render(ShelfActionMenu, {
    bookId: 'b1',
    isFavorite: false,
    readLabel: 'Leer',
    editLabel: 'Editar',
    removeLabel: 'Quitar',
    favoriteAddLabel: 'Agregar a favoritos',
    favoriteRemoveLabel: 'Quitar de favoritos',
    triggerLabel: 'Acciones',
    onEdit,
    onRemove: vi.fn(),
    onToggleFavorite: vi.fn(),
  });
  return { ...view, onEdit };
}

describe('ShelfActionMenu', () => {
  it('opens on <body>, outside the card, so the next card cannot paint over it', async () => {
    const { container } = setup();
    await fireEvent.click(screen.getByTestId('shelf-actions-trigger-b1'));
    const menu = screen.getByRole('menu');
    expect(menu.parentElement).toBe(document.body);
    expect(container.contains(menu)).toBe(false);
  });

  it('still runs an item picked from the moved menu', async () => {
    const { onEdit } = setup();
    await fireEvent.click(screen.getByTestId('shelf-actions-trigger-b1'));
    const item = screen.getByRole('menuitem', { name: 'Editar' });
    await fireEvent.pointerDown(item);
    await fireEvent.click(item);
    expect(onEdit).toHaveBeenCalledOnce();
  });
});
