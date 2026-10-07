import { render, screen } from '@testing-library/svelte';
import { createRawSnippet } from 'svelte';
import { describe, expect, it } from 'vitest';
import Panel from '$lib/shared/ui/layout/Panel.svelte';

function textSnippet(text: string) {
  return createRawSnippet(() => ({ render: () => `<span>${text}</span>` }));
}

describe('Panel title uses the Title role', () => {
  it('renders the heading at text-xl, not the previous text-lg', () => {
    const { container } = render(Panel, {
      props: { title: 'Statistics', children: textSnippet('body') },
    });
    const heading = container.querySelector('h2');

    expect(heading).not.toBeNull();
    expect(heading?.classList.contains('text-xl')).toBe(true);
    expect(heading?.classList.contains('text-lg')).toBe(false);
    expect(screen.getByText('Statistics')).toBeInTheDocument();
  });

  it('keeps the documented padding and variant defaults', () => {
    const { container } = render(Panel, {
      props: { title: 'Section', children: textSnippet('body') },
    });
    const section = container.querySelector('section');
    expect(section?.classList.contains('bg-(--color-surface)')).toBe(true);

    const body = screen.getByText('body').parentElement;
    expect(body?.classList.contains('p-4')).toBe(true);
  });

  it('renders the recessed surface variant', () => {
    const { container } = render(Panel, {
      props: { variant: 'surface', children: textSnippet('body') },
    });
    expect(container.querySelector('section')?.classList.contains('bg-(--color-surface-dim)')).toBe(
      true,
    );
  });
});
