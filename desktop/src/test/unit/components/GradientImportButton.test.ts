import { render, screen } from '@testing-library/svelte';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';
import Book from 'lucide-svelte/icons/book';
import GradientImportButton from '$lib/features/home/components/GradientImportButton.svelte';

const hexPattern = /#[0-9a-fA-F]{3,8}/;

describe('GradientImportButton', () => {
  it('renders the label and the default add icon', () => {
    const { container } = render(GradientImportButton, {
      props: { label: 'Importar', onclick: () => undefined },
    });

    expect(screen.getByRole('button', { name: 'Importar' })).toBeInTheDocument();
    // Icon.svelte renders an svg with aria-hidden="true"
    expect(container.querySelector('svg')).not.toBeNull();
  });

  it('invokes onclick when clicked', async () => {
    const user = userEvent.setup();
    const spy = vi.fn();
    render(GradientImportButton, {
      props: { label: 'Import', onclick: spy },
    });

    await user.click(screen.getByRole('button', { name: 'Import' }));
    expect(spy).toHaveBeenCalledTimes(1);
  });

  it('does not fire onclick when disabled', async () => {
    const user = userEvent.setup();
    const spy = vi.fn();
    render(GradientImportButton, {
      props: { label: 'Import', onclick: spy, disabled: true },
    });

    const button = screen.getByRole('button', { name: 'Import' });
    expect(button).toBeDisabled();
    await user.click(button);
    expect(spy).not.toHaveBeenCalled();
  });

  it('renders a secondary outline using the --color-import token without hex', () => {
    const { container } = render(GradientImportButton, {
      props: { label: 'Import', onclick: () => undefined },
    });

    const html = container.innerHTML;
    expect(html).toContain('--color-import');
    expect(html).not.toContain('gradient');
    expect(html).not.toMatch(hexPattern);
  });

  it('renders a custom icon when provided', () => {
    const { container } = render(GradientImportButton, {
      props: { label: 'Import', onclick: () => undefined, icon: Book },
    });

    expect(container.querySelector('svg')).not.toBeNull();
  });
});
