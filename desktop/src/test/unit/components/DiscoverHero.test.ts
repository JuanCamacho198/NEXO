import { fireEvent, render, screen } from '@testing-library/svelte';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';
import DiscoverHero from '$lib/features/discover/DiscoverHero.svelte';

const t = (key: string): string => key;

type HeroProps = {
  selectedChip?: string | null;
  isOnline?: boolean;
};

function renderHero(props: HeroProps = {}): {
  onSearchSubmit: ReturnType<typeof vi.fn>;
  onSelectChip: ReturnType<typeof vi.fn>;
} {
  const onSearchSubmit = vi.fn();
  const onSelectChip = vi.fn();
  render(DiscoverHero, {
    props: {
      t,
      sourceCount: 3,
      isOnline: props.isOnline ?? true,
      selectedChip: props.selectedChip ?? null,
      onSelectChip,
      onSearchSubmit,
    },
  });
  return { onSearchSubmit, onSelectChip };
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
    const { onSearchSubmit } = renderHero();
    const user = userEvent.setup();
    const input = screen.getByLabelText('discover.searchAriaLabel');

    await user.type(input, 'dune');
    await fireEvent.submit(input.closest('form')!);

    expect(onSearchSubmit).toHaveBeenCalledWith('dune');
  });

  it('submits the typed query on Enter through the unified field', async () => {
    const { onSearchSubmit } = renderHero();
    const input = screen.getByLabelText('discover.searchAriaLabel');

    await fireEvent.input(input, { target: { value: 'dune' } });
    await fireEvent.keyDown(input, { key: 'Enter' });

    expect(onSearchSubmit).toHaveBeenCalledWith('dune');
  });
});

describe('DiscoverHero genre chips', () => {
  it('marks the leading "All" chip pressed while no genre is active', () => {
    renderHero();

    expect(screen.getByRole('button', { name: 'home.shelfTab.all' })).toHaveAttribute(
      'aria-pressed',
      'true',
    );
  });

  it('leaves the "All" chip unpressed while a genre is active', () => {
    renderHero({ selectedChip: 'Ficción' });

    expect(screen.getByRole('button', { name: 'home.shelfTab.all' })).toHaveAttribute(
      'aria-pressed',
      'false',
    );
  });

  it('clears the genre selection when the "All" chip is pressed', async () => {
    const { onSelectChip } = renderHero({ selectedChip: 'Ficción' });
    const user = userEvent.setup();

    await user.click(screen.getByRole('button', { name: 'home.shelfTab.all' }));

    expect(onSelectChip).toHaveBeenCalledWith(null);
  });

  it('emits the stable chip id from a genre chip', async () => {
    const { onSelectChip } = renderHero();
    const user = userEvent.setup();

    await user.click(screen.getByRole('button', { name: 'discover.rail.thematic.fiction' }));

    expect(onSelectChip).toHaveBeenCalledWith('fiction');
  });

  it('marks the active genre chip pressed by its stable id', () => {
    renderHero({ selectedChip: 'fiction' });

    expect(screen.getByRole('button', { name: 'discover.rail.thematic.fiction' })).toHaveAttribute(
      'aria-pressed',
      'true',
    );
  });
});
