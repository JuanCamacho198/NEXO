import { fireEvent, render, screen } from '@testing-library/svelte';
import userEvent from '@testing-library/user-event';
import { createRawSnippet } from 'svelte';
import { describe, expect, it, vi } from 'vitest';
import Button from '$lib/shared/ui/forms/Button.svelte';

function textSnippet(text: string) {
  return createRawSnippet(() => ({ render: () => `<span>${text}</span>` }));
}

function htmlSnippet(html: string) {
  return createRawSnippet(() => ({ render: () => html }));
}

describe('Button type role is independent of size', () => {
  const padding: Record<'sm' | 'md' | 'lg', string[]> = {
    sm: ['px-3', 'py-1.5'],
    md: ['px-4', 'py-2'],
    lg: ['px-6', 'py-3'],
  };

  it.each(['sm', 'md', 'lg'] as const)('size %s sets padding and keeps the Label type', (size) => {
    const { container } = render(Button, {
      props: { size, children: textSnippet('Save') },
    });
    const button = container.querySelector('button');
    expect(button).not.toBeNull();

    for (const cls of padding[size]) {
      expect(button?.classList.contains(cls)).toBe(true);
    }
    for (const other of Object.entries(padding)
      .filter(([key]) => key !== size)
      .flatMap(([, values]) => values)) {
      expect(button?.classList.contains(other)).toBe(false);
    }

    // Label type at every size: text-sm, never Body/Reading/Micro.
    expect(button?.classList.contains('text-sm')).toBe(true);
    expect(button?.classList.contains('text-base')).toBe(false);
    expect(button?.classList.contains('text-lg')).toBe(false);
    expect(button?.classList.contains('text-xs')).toBe(false);
  });
});

describe('Button defaults are preserved', () => {
  it('renders a primary md button with the press affordance', () => {
    const onclick = vi.fn();
    const { container } = render(Button, {
      props: { onclick, children: textSnippet('Save') },
    });
    const button = container.querySelector('button');

    expect(button).toHaveAttribute('type', 'button');
    expect(button?.classList.contains('px-4')).toBe(true);
    expect(button?.classList.contains('bg-(--color-primary)')).toBe(true);
    expect(button?.classList.contains('shadow-sm')).toBe(true);
    expect(button?.classList.contains('w-full')).toBe(false);
    expect(container.querySelector('label')).toBeNull();

    fireEvent.click(button as Element);
    expect(onclick).toHaveBeenCalledTimes(1);
  });

  it('keeps the disabled affordance and ignores interaction', async () => {
    const user = userEvent.setup();
    const onclick = vi.fn();
    const { container } = render(Button, {
      props: { onclick, disabled: true, children: textSnippet('Save') },
    });
    const button = container.querySelector('button') as HTMLButtonElement;

    expect(button.disabled).toBe(true);
    await user.click(button);
    expect(onclick).not.toHaveBeenCalled();
  });
});

describe('Button modifiers', () => {
  it('renders a leading icon before the label', () => {
    const { container } = render(Button, {
      props: {
        leadingIcon: htmlSnippet('<svg data-testid="leading" viewBox="0 0 24 24"></svg>'),
        children: textSnippet('Save'),
      },
    });

    const button = container.querySelector('button') as HTMLButtonElement;
    const icon = screen.getByTestId('leading');
    expect(button.firstElementChild?.contains(icon)).toBe(true);
    expect(button.textContent).toContain('Save');
  });

  it('stretches to the container with fullWidth', () => {
    const { container } = render(Button, {
      props: { fullWidth: true, children: textSnippet('Save') },
    });
    expect(container.querySelector('button')?.classList.contains('w-full')).toBe(true);
  });

  it('disables and shows a spinner while loading', () => {
    const { container } = render(Button, {
      props: { loading: true, loadingLabel: 'Saving', children: textSnippet('Save') },
    });
    const button = container.querySelector('button') as HTMLButtonElement;

    expect(button.disabled).toBe(true);
    expect(button).toHaveAttribute('aria-busy', 'true');
    expect(button.querySelector('.animate-spin')).not.toBeNull();
    expect(button.textContent).toContain('Saving');
    expect(button.textContent).not.toContain('Save');
  });

  it('keeps the label visible while loading without a loadingLabel', () => {
    const { container } = render(Button, {
      props: { loading: true, children: textSnippet('Save') },
    });
    const button = container.querySelector('button') as HTMLButtonElement;

    expect(button.querySelector('.animate-spin')).not.toBeNull();
    expect(button.textContent).toContain('Save');
  });
});

describe('Button as="label" file-input render', () => {
  it('renders a label wrapping the hidden input instead of a button', () => {
    const { container } = render(Button, {
      props: {
        as: 'label',
        variant: 'secondary',
        children: htmlSnippet(
          '<span><input type="file" class="hidden" data-testid="file" /><span>Import</span></span>',
        ),
      },
    });

    expect(container.querySelector('label')).not.toBeNull();
    expect(container.querySelector('button')).toBeNull();

    const label = container.querySelector('label') as HTMLLabelElement;
    expect(label.querySelector('[data-testid="file"]')).not.toBeNull();
    expect(label.classList.contains('inline-flex')).toBe(true);
    expect(label.textContent).toContain('Import');
  });

  it('exposes disabled through aria-disabled and the visual affordance', () => {
    const { container } = render(Button, {
      props: {
        as: 'label',
        disabled: true,
        children: htmlSnippet('<input type="file" class="hidden" data-testid="file" />'),
      },
    });
    const label = container.querySelector('label') as HTMLLabelElement;

    expect(label).toHaveAttribute('aria-disabled', 'true');
    expect(label.classList.contains('pointer-events-none')).toBe(true);
    expect(label.classList.contains('opacity-50')).toBe(true);
  });
});

describe('Button forwards HTML attributes to its root element', () => {
  it('places data-testid and aria-* on the rendered <button>', () => {
    const { container } = render(Button, {
      props: {
        'data-testid': 'save-button',
        'aria-label': 'Save the draft',
        title: 'Save',
        children: textSnippet('Save'),
      },
    });
    const button = container.querySelector('button') as HTMLButtonElement;

    expect(button).toHaveAttribute('data-testid', 'save-button');
    expect(button).toHaveAttribute('aria-label', 'Save the draft');
    expect(button).toHaveAttribute('title', 'Save');
  });

  it('places data-testid on the rendered <label> in as="label" mode', () => {
    const { container } = render(Button, {
      props: {
        as: 'label',
        'data-testid': 'import-control',
        children: htmlSnippet('<input type="file" class="hidden" />'),
      },
    });
    const label = container.querySelector('label') as HTMLLabelElement;

    expect(label).toHaveAttribute('data-testid', 'import-control');
  });
});
