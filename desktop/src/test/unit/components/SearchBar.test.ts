import { fireEvent, render, screen } from '@testing-library/svelte';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';
import SearchBar from '$lib/shared/ui/navigation/SearchBar.svelte';

function setup(props: Record<string, unknown> = {}) {
  return render(SearchBar, {
    props: { placeholder: 'Search books', ariaLabel: 'Search books', ...props },
  });
}

describe('SearchBar', () => {
  it('renders the magnifier as a flow sibling before the input', () => {
    const { container } = setup();
    const root = container.querySelector('[role="search"]')!;
    expect(root).toBeInTheDocument();
    expect(root).toHaveClass('search-field');

    const svg = root.querySelector('svg')!;
    expect(svg).toHaveAttribute('aria-hidden', 'true');
    expect(svg).toHaveAttribute('stroke-width', '1.8');
    const input = root.querySelector('input')!;
    expect(svg.compareDocumentPosition(input) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
  });

  it('forwards placeholder, aria-label and the test id to the inner input', () => {
    setup({ 'data-testid': 'shelf-search' });
    const input = screen.getByTestId('shelf-search');
    expect(input).toHaveAttribute('placeholder', 'Search books');
    expect(input).toHaveAttribute('aria-label', 'Search books');
    expect(input).toHaveAttribute('type', 'search');
  });

  it('reveals a clear affordance while typing and empties the field on click', async () => {
    const user = userEvent.setup();
    setup({ clearLabel: 'Clear shelf search' });

    const input = screen.getByRole('searchbox');
    expect(screen.queryByRole('button', { name: 'Clear shelf search' })).toBeNull();

    await user.type(input, 'dune');

    const clear = screen.getByRole('button', { name: 'Clear shelf search' });
    await user.click(clear);

    expect(input).toHaveValue('');
    expect(document.activeElement).toBe(input);
  });

  it('calls onShortcut when the keycap is activated', async () => {
    const user = userEvent.setup();
    const onShortcut = vi.fn();
    setup({ shortcutLabel: '/', shortcutAriaLabel: 'Search (/)', onShortcut });

    await user.click(screen.getByRole('button', { name: 'Search (/)' }));

    expect(onShortcut).toHaveBeenCalledTimes(1);
  });

  it('focuses the input from the keycap when no onShortcut is given', async () => {
    const user = userEvent.setup();
    setup({ shortcutLabel: '/' });

    const input = screen.getByRole('searchbox');
    await user.click(screen.getByRole('button', { name: '/' }));

    expect(document.activeElement).toBe(input);
  });

  it('submits the current value on Enter', async () => {
    const user = userEvent.setup();
    const onsubmit = vi.fn();
    setup({ onsubmit });

    await user.type(screen.getByRole('searchbox'), 'asimov{Enter}');

    expect(onsubmit).toHaveBeenCalledWith('asimov');
  });

  it('ignores Enter when no submit handler is provided', async () => {
    const { container } = setup();
    const input = container.querySelector('input')!;
    // A prevented default would still bubble; assert no throw and value intact.
    await fireEvent.keyDown(input, { key: 'Enter' });
    expect(input).toHaveValue('');
  });

  it('renders the keycap from shortcutKey alone and focuses the field from the window', async () => {
    const { container } = setup({ shortcutKey: '/' });
    const input = container.querySelector('input')!;

    const keycap = screen.getByRole('button', { name: '/' });
    expect(keycap).toBeInTheDocument();

    await fireEvent.keyDown(window, { key: '/' });
    expect(document.activeElement).toBe(input);
  });

  it('ignores the shortcut key while typing in another field', async () => {
    const { container } = setup({ shortcutKey: '/' });
    const input = container.querySelector('input')!;

    const external = document.createElement('input');
    document.body.appendChild(external);
    external.focus();

    await fireEvent.keyDown(external, { key: '/' });

    expect(document.activeElement).toBe(external);
    expect(document.activeElement).not.toBe(input);
    external.remove();
  });

  it('ignores the shortcut key when a modifier is held', async () => {
    const { container } = setup({ shortcutKey: '/' });
    const input = container.querySelector('input')!;

    await fireEvent.keyDown(window, { key: '/', ctrlKey: true });
    expect(document.activeElement).not.toBe(input);
  });

  it('does not handle the shortcut when shortcutKey is absent', async () => {
    const { container } = setup();
    const input = container.querySelector('input')!;

    await fireEvent.keyDown(window, { key: '/' });
    expect(document.activeElement).not.toBe(input);
  });

  it('lets only one mounted field handle the shortcut key', async () => {
    const focusSpy = vi.spyOn(HTMLInputElement.prototype, 'focus');
    setup({ shortcutKey: '/', ariaLabel: 'First field' });
    setup({ shortcutKey: '/', ariaLabel: 'Second field' });

    await fireEvent.keyDown(window, { key: '/' });

    expect(focusSpy).toHaveBeenCalledTimes(1);
    focusSpy.mockRestore();
  });
});
