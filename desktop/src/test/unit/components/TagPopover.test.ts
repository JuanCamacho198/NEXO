import { render, fireEvent } from '@testing-library/svelte';
import { describe, expect, it, vi } from 'vitest';
import TagPopover from '$lib/features/reader/highlight/TagPopover.svelte';

const t = (key: string, params?: Record<string, string | number>) => {
  const translations: Record<string, string> = {
    'highlight.tagPopoverAriaLabel': 'Tags',
    'highlight.tags': 'Tags',
    'highlight.createTag': 'Create',
    'highlight.newTagPlaceholder': 'New tag',
    'highlight.noTagsYet': 'No tags yet',
    'highlight.createTagNamed': 'Create “{{name}}”',
  };
  let value = translations[key] ?? key;
  for (const [k, v] of Object.entries(params ?? {})) value = value.replace(`{{${k}}}`, String(v));
  return value;
};

describe('TagPopover', () => {
  function createAnchor(): HTMLElement {
    const el = document.createElement('button');
    document.body.appendChild(el);
    el.getBoundingClientRect = () =>
      ({
        left: 100,
        top: 100,
        width: 24,
        height: 24,
        right: 124,
        bottom: 124,
        x: 100,
        y: 100,
      }) as DOMRect;
    return el;
  }

  it('toggles assigned tag', async () => {
    const anchor = createAnchor();
    const onToggle = vi.fn();
    const { getByText } = render(TagPopover, {
      props: {
        open: true,
        anchor,
        assignedTagIds: [],
        allTags: [{ id: 'tag-1', name: 'Review', createdAt: '2024-01-01T00:00:00Z' }],
        onCreate: () => undefined,
        onToggle,
        onClose: () => undefined,
        t,
      },
    });

    await fireEvent.click(getByText('Review'));
    expect(onToggle).toHaveBeenCalledWith('tag-1');
  });

  it('creates a new tag on Enter', async () => {
    const anchor = createAnchor();
    const onCreate = vi.fn();
    const { getByPlaceholderText } = render(TagPopover, {
      props: {
        open: true,
        anchor,
        assignedTagIds: [],
        allTags: [],
        onCreate,
        onToggle: () => undefined,
        onClose: () => undefined,
        t,
      },
    });

    const input = getByPlaceholderText('New tag') as HTMLInputElement;
    await fireEvent.input(input, { target: { value: 'Idea' } });
    await fireEvent.keyDown(input, { key: 'Enter' });
    expect(onCreate).toHaveBeenCalledWith('Idea');
  });

  it('toggles existing tag instead of creating duplicate', async () => {
    const anchor = createAnchor();
    const onCreate = vi.fn();
    const onToggle = vi.fn();
    const { getByPlaceholderText } = render(TagPopover, {
      props: {
        open: true,
        anchor,
        assignedTagIds: [],
        allTags: [{ id: 'tag-1', name: 'Review', createdAt: '2024-01-01T00:00:00Z' }],
        onCreate,
        onToggle,
        onClose: () => undefined,
        t,
      },
    });

    const input = getByPlaceholderText('New tag') as HTMLInputElement;
    await fireEvent.input(input, { target: { value: 'review' } });
    await fireEvent.keyDown(input, { key: 'Enter' });
    expect(onCreate).not.toHaveBeenCalled();
    expect(onToggle).toHaveBeenCalledWith('tag-1');
  });

  // One field filters the list and, when nothing has that exact name, offers
  // to create it as the first row.
  it('filters as the reader types and offers to create a missing tag', async () => {
    const anchor = createAnchor();
    const onCreate = vi.fn();
    const { getByPlaceholderText, queryByText, getByText } = render(TagPopover, {
      props: {
        open: true,
        anchor,
        assignedTagIds: [],
        allTags: [
          { id: 'tag-1', name: 'Review', createdAt: '2024-01-01T00:00:00Z' },
          { id: 'tag-2', name: 'Quotes', createdAt: '2024-01-01T00:00:00Z' },
        ],
        onCreate,
        onToggle: vi.fn(),
        onClose: () => undefined,
        t,
      },
    });

    const input = getByPlaceholderText('New tag') as HTMLInputElement;
    await fireEvent.input(input, { target: { value: 'quo' } });
    expect(queryByText('Review')).toBeNull();
    expect(getByText('Quotes')).toBeTruthy();
    expect(getByText('Create “quo”')).toBeTruthy();

    await fireEvent.click(getByText('Create “quo”'));
    expect(onCreate).toHaveBeenCalledWith('quo');
  });
});
