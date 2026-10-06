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

  it('submits the typed query through the submit button', async () => {
    const onSearchSubmit = renderHero();
    const user = userEvent.setup();

    await user.type(screen.getByLabelText('discover.searchAriaLabel'), 'dune');
    await user.click(screen.getByRole('button', { name: 'discover.search' }));

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
