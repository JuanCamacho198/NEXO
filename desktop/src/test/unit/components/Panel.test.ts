import { render, screen } from '@testing-library/svelte';
import { createRawSnippet } from 'svelte';
import { describe, expect, it } from 'vitest';
import Panel from '$lib/shared/ui/layout/Panel.svelte';

function textSnippet(text: string) {
  return createRawSnippet(() => ({ render: () => `<span>${text}</span>` }));
}

describe('Panel title', () => {
  it('renders the card heading at text-base, below the page title', () => {
    const { container } = render(Panel, {
      props: { title: 'Statistics', children: textSnippet('body') },
    });
    const heading = container.querySelector('h2');

    expect(heading).not.toBeNull();
    expect(heading?.classList.contains('text-base')).toBe(true);
    expect(heading?.classList.contains('text-lg')).toBe(false);
    expect(screen.getByText('Statistics')).toBeInTheDocument();
  });

  it('renders an open section under a hairline, not a card', () => {
    const { container } = render(Panel, {
      props: { title: 'Section', children: textSnippet('body') },
    });
    const section = container.querySelector('section') as HTMLElement;
    expect(section.classList.contains('border-t')).toBe(true);
    expect(section.classList.contains('border-(--color-edge)')).toBe(true);
    expect(section.className).not.toMatch(/rounded|bg-/);

    const body = screen.getByText('body').parentElement;
    expect(body?.classList.contains('mt-4')).toBe(true);
  });
});

describe('Panel inline row', () => {
  it('puts the title block and the controls in one row', () => {
    const { container } = render(Panel, {
      props: { title: 'Export', subtitle: 'One file', inline: true, children: textSnippet('go') },
    });
    const heading = container.querySelector('h2') as HTMLElement;
    const controls = screen.getByText('go').parentElement as HTMLElement;
    const row = heading.parentElement?.parentElement as HTMLElement;

    expect(screen.getByText('One file')).toBeInTheDocument();
    expect(row.classList.contains('flex')).toBe(true);
    expect(controls.parentElement).toBe(row);
  });
});

describe('Panel forwards HTML attributes to its root element', () => {
  it('places data-testid and aria-* on the rendered <section>', () => {
    const { container } = render(Panel, {
      props: {
        title: 'Section',
        'data-testid': 'panel-root',
        'aria-label': 'Data section',
        children: textSnippet('body'),
      },
    });
    const section = container.querySelector('section') as HTMLElement;

    expect(section).toHaveAttribute('data-testid', 'panel-root');
    expect(section).toHaveAttribute('aria-label', 'Data section');
  });
});
