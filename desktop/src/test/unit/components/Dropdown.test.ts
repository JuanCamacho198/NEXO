import { fireEvent, render, screen, waitFor } from '@testing-library/svelte';
import { describe, expect, it, vi } from 'vitest';
import LibraryShelfScreen from '$lib/features/library/components/LibraryShelfScreen.svelte';
import { flushResizeObservers, stubElementRect } from '../../harness/jsdomHarness';
import DropdownInDialogStub from '../../stubs/DropdownInDialogStub.svelte';
import DropdownOptionStub from '../../stubs/DropdownOptionStub.svelte';
import DropdownValueStub from '../../stubs/DropdownValueStub.svelte';

/**
 * `Dropdown` now runs on bits-ui `Select` with its public API unchanged. These
 * tests drive the real `LibraryShelfScreen` (the single caller that supplies a
 * `trigger` snippet) and stubs shaped like the other 15 call sites, never a
 * simplified stand-in.
 *
 * Two behaviours are library-specific rather than obvious:
 *  - bits-ui opens a Select on `pointerdown` (its `click` handler only refocuses
 *    the trigger), so every opening here dispatches a pointer event.
 *  - jsdom ships no pointer-capture API and bits-ui reads `hasPointerCapture`
 *    unguarded inside that same handler; `installJsdomHarness()` stubs it, which
 *    is why an opening is observable at all.
 */

const OPTIONS = [
  { value: '', label: 'All' },
  { value: 'beta', label: 'Beta' },
  { value: 'gamma', label: 'Gamma' },
];

function triggerOf(container: HTMLElement): HTMLElement {
  const trigger = container.querySelector('[aria-haspopup="listbox"]');
  expect(trigger).toBeTruthy();
  return trigger as HTMLElement;
}

async function open(trigger: HTMLElement): Promise<void> {
  await fireEvent.pointerDown(trigger, { pointerId: 1, pointerType: 'mouse', button: 0 });
  await fireEvent.pointerUp(trigger, { pointerId: 1, pointerType: 'mouse', button: 0 });
  await waitFor(() => expect(screen.queryByRole('listbox')).not.toBeNull());
}

async function pick(item: HTMLElement): Promise<void> {
  await fireEvent.pointerDown(item, { pointerId: 1, pointerType: 'mouse', button: 0 });
  await fireEvent.pointerUp(item, { pointerId: 1, pointerType: 'mouse', button: 0 });
  await fireEvent.click(item);
  await waitFor(() => expect(screen.queryByRole('listbox')).toBeNull());
}

function option(label: string): HTMLElement {
  const found = screen.getAllByRole('option').find((item) => item.textContent?.trim() === label);
  expect(found).toBeTruthy();
  return found as HTMLElement;
}

function renderValueStub(props: Record<string, unknown> = {}) {
  return render(DropdownValueStub, { props: { options: OPTIONS, ...props } });
}

describe('Dropdown — value contract', () => {
  it('renders the matching option label while closed, including the empty value', () => {
    const { container, unmount } = renderValueStub({ initialValue: 'beta' });
    expect(triggerOf(container).textContent?.trim()).toBe('Beta');
    unmount();

    // `LibraryView` and `HighlightsView` ship an "All" option whose value is
    // `""`; bits-ui's own label resolution would print the placeholder there.
    const empty = renderValueStub({ initialValue: '' });
    expect(triggerOf(empty.container).textContent?.trim()).toBe('All');
    empty.unmount();
  });

  it('falls back to the placeholder, not to a raw value, when nothing matches', () => {
    const matched = renderValueStub({ initialValue: 'nope' });
    expect(triggerOf(matched.container).textContent?.trim()).toBe('Select...');
    matched.unmount();

    const custom = renderValueStub({ initialValue: null, placeholder: 'Pick a genre' });
    expect(triggerOf(custom.container).textContent?.trim()).toBe('Pick a genre');
  });

  it('writes the bound value back exactly once per choice', async () => {
    const onvalue = vi.fn();
    const { container } = renderValueStub({ onvalue, initialValue: null });

    await open(triggerOf(container));
    await pick(option('Beta'));

    expect(onvalue.mock.calls.flat()).toEqual([null, 'beta']);
    expect(triggerOf(container).textContent?.trim()).toBe('Beta');
  });

  it('fires onchange exactly once per change, and not at all for a no-op choice', async () => {
    const onchange = vi.fn();
    const { container } = renderValueStub({ onchange, initialValue: null });
    const trigger = triggerOf(container);

    await open(trigger);
    await pick(option('Beta'));
    expect(onchange).toHaveBeenCalledTimes(1);
    expect(onchange).toHaveBeenCalledWith({ value: 'beta' });

    await open(trigger);
    await pick(option('Gamma'));
    expect(onchange).toHaveBeenCalledTimes(2);
    expect(onchange).toHaveBeenLastCalledWith({ value: 'gamma' });

    // Re-picking the current option closes without a change and without
    // deselecting, so the callback stays at two calls.
    await open(trigger);
    await pick(option('Gamma'));
    expect(onchange).toHaveBeenCalledTimes(2);
    expect(triggerOf(container).textContent?.trim()).toBe('Gamma');
  });
  it('resolves a letter typed on the closed trigger through the caller list', async () => {
    const onchange = vi.fn();
    const { container } = renderValueStub({ onchange, initialValue: null });
    const trigger = triggerOf(container);

    trigger.focus();
    await fireEvent.keyDown(trigger, { key: 'g' });

    // `items={options}` is what enables this: the adapter hands bits-ui the
    // caller's list so it can resolve a label without waiting for the content to
    // mount. Without it the trigger would stay silent on a typed letter.
    await waitFor(() => expect(onchange).toHaveBeenCalledTimes(1));
    expect(onchange).toHaveBeenCalledWith({ value: 'gamma' });
    expect(triggerOf(container).textContent?.trim()).toBe('Gamma');
  });
});

describe('Dropdown — caller-owned snippets', () => {
  it('renders the single caller trigger snippet inside a real button with nothing interactive nested', async () => {
    const { container } = render(LibraryShelfScreen, {
      props: { books: [], t: (key: string) => key },
    });
    const trigger = triggerOf(container);

    expect(trigger.tagName).toBe('BUTTON');
    expect(trigger.textContent?.trim()).toBe('Fecha agregada');
    expect(trigger).toHaveAttribute('aria-expanded', 'false');

    // The caller's snippet must not smuggle a second control into the button...
    expect(trigger.querySelector('button, input, a[href], [tabindex]')).toBeNull();

    // ...and nothing between the button and the document may be interactive,
    // which is the wrapper the pre-swap markup used to render.
    let ancestor = trigger.parentElement;
    while (ancestor && ancestor !== document.body) {
      expect(ancestor.getAttribute('role')).not.toBe('button');
      expect(ancestor.hasAttribute('tabindex')).toBe(false);
      ancestor = ancestor.parentElement;
    }

    await open(trigger);
    await pick(option('Ultima lectura'));
    expect(triggerOf(container).textContent?.trim()).toBe('Ultima lectura');
  });

  it('renders the option snippet once per item', async () => {
    const { container } = render(DropdownOptionStub, {
      props: { options: OPTIONS, value: 'beta' },
    });
    expect(triggerOf(container).textContent?.trim()).toBe('Beta');

    await open(triggerOf(container));

    const custom = screen.getAllByTestId('custom-option');
    expect(custom.map((node) => node.textContent)).toEqual([':All', 'beta:Beta', 'gamma:Gamma']);
  });
});

describe('Dropdown — selection semantics and disabled state', () => {
  it('announces the selected option as selected and only that one', async () => {
    const { container } = renderValueStub({ initialValue: 'beta' });
    await open(triggerOf(container));

    const selected = screen
      .getAllByRole('option')
      .filter((item) => item.getAttribute('aria-selected') === 'true');
    expect(selected.map((item) => item.textContent?.trim())).toEqual(['Beta']);
  });

  it('closes on Escape without changing the value', async () => {
    const onchange = vi.fn();
    const { container } = renderValueStub({ onchange, initialValue: 'beta' });
    await open(triggerOf(container));

    await fireEvent.keyDown(document.body, { key: 'Escape' });
    await waitFor(() => expect(screen.queryByRole('listbox')).toBeNull());

    expect(onchange).not.toHaveBeenCalled();
    expect(triggerOf(container).textContent?.trim()).toBe('Beta');
  });

  it('blocks opening and never reports a change when disabled', async () => {
    const onchange = vi.fn();
    const { container } = renderValueStub({ onchange, disabled: true, initialValue: 'beta' });
    const trigger = triggerOf(container);

    expect(trigger).toBeDisabled();

    await fireEvent.pointerDown(trigger, { pointerId: 1, pointerType: 'mouse', button: 0 });
    await fireEvent.pointerUp(trigger, { pointerId: 1, pointerType: 'mouse', button: 0 });
    await waitFor(() => expect(screen.queryByRole('listbox')).toBeNull());

    expect(onchange).not.toHaveBeenCalled();
  });
});

describe('Dropdown — portalled popup', () => {
  it('takes its width from the trigger through the anchor-width variable', async () => {
    const { container } = renderValueStub({ initialValue: 'beta' });
    const trigger = triggerOf(container);
    stubElementRect(trigger, { left: 10, top: 200, width: 130, height: 40 });

    await open(trigger);
    const content = screen.getByRole('listbox');

    // Portalled: the popup lives under the floating wrapper in `document.body`,
    // not inside the caller's container.
    const wrapper = content.closest('[data-bits-floating-content-wrapper]');
    expect(wrapper?.parentElement).toBe(document.body);
    expect(container.contains(content)).toBe(false);

    // The old `w-full` resolved against the local parent; under the floating
    // wrapper that would resolve against the wrapper's own box instead, so the
    // width comes from the anchor. jsdom never measures on its own, so the
    // harness flush is what makes this an observation rather than a claim.
    flushResizeObservers();
    await waitFor(() =>
      expect(
        getComputedStyle(content.parentElement as Element).getPropertyValue(
          '--bits-floating-anchor-width',
        ),
      ).toBe('130px'),
    );
    expect(content.className).toContain('w-(--bits-select-anchor-width)');
    expect(content.className).not.toContain('w-full');
    expect(content.className).toContain('z-(--layer-popover)');
    expect(content).toHaveAttribute('data-side', 'bottom');
    expect(content).toHaveAttribute('data-align', 'start');
  });

  it('selects inside an open dialog without closing it', async () => {
    const onchange = vi.fn();
    const onvalue = vi.fn();
    render(DropdownInDialogStub, { props: { options: OPTIONS, onchange, onvalue } });

    const dialog = screen.getByRole('dialog');
    const trigger = triggerOf(dialog);
    stubElementRect(trigger, { left: 10, top: 200, width: 240, height: 40 });

    await open(trigger);
    await pick(option('Gamma'));

    expect(screen.getByRole('dialog')).toBe(dialog);
    expect(onvalue.mock.calls.flat()).toEqual([null, 'gamma']);
    expect(onchange).toHaveBeenCalledTimes(1);
    expect(trigger.textContent?.trim()).toBe('Gamma');
  });
});
