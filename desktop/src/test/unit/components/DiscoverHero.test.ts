import { fireEvent, render, screen } from '@testing-library/svelte';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';
import DiscoverHero from '$lib/features/discover/DiscoverHero.svelte';

const t = (key: string): string => key;

function renderHero(): ReturnType<typeof vi.fn> {
  const onSearchSubmit = vi.fn();
  render(DiscoverHero, {
    props: {
      t,
      sourceCount: 3,
      isOnline: true,
      selectedChip: null,
      onSelectChip: vi.fn(),
      onSearchSubmit,
    },
  });
  return onSearchSubmit;
}

describe('DiscoverHero search field', () => {
  it('no longer renders the breadcrumb', () => {
    renderHero();

    expect(screen.queryByRole('navigation', { name: 'Breadcrumb' })).toBeNull();
    expect(screen.queryByText('sidebar.home')).toBeNull();
  });

  it('shows the `/` keycap and focuses the field from the shortcut', async () => {
    renderHero();

    expect(screen.getByRole('button', { name: 'discover.searchShortcutAria' })).toBeInTheDocument();

    const input = screen.getByLabelText('discover.searchAriaLabel');
    await fireEvent.keyDown(window, { key: '/' });

    expect(document.activeElement).toBe(input);
  });

  it('no longer renders a separate submit button', () => {
    renderHero();

    expect(screen.queryByRole('button', { name: 'discover.search' })).toBeNull();
  });

  it('submits the typed query through the form', async () => {
    const onSearchSubmit = renderHero();
    const user = userEvent.setup();
    const input = screen.getByLabelText('discover.searchAriaLabel');

    await user.type(input, 'dune');
    await fireEvent.submit(input.closest('form')!);

    expect(onSearchSubmit).toHaveBeenCalledWith('dune');
  });

  it('submits the typed query on Enter through the unified field', async () => {
    const onSearchSubmit = renderHero();
    const input = screen.getByLabelText('discover.searchAriaLabel');

    await fireEvent.input(input, { target: { value: 'dune' } });
    await fireEvent.keyDown(input, { key: 'Enter' });

    expect(onSearchSubmit).toHaveBeenCalledWith('dune');
  });
});
