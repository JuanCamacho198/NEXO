/**
 * `SettingsTranslationTab` — the translation pair's own editing state.
 *
 * The behaviours pinned here are the ones that were broken while the "draft"
 * was a `$derived` copy of the props: Save never enabled, a change discarded
 * when the tab went away, and a warning describing the stored pair instead of
 * the one on screen. None of them are composition concerns, which is why they
 * live in the block's own test and not in `SettingsSelectionMenuTab.test.ts`.
 *
 * The dropdowns are the `Dropdown` atom (bits-ui `Select`), so opening goes
 * through pointer events — bits-ui opens on `pointerdown` — and picking
 * dispatches pointer + click, exactly as `Dropdown.test.ts` does.
 */
import { fireEvent, render, screen, waitFor } from '@testing-library/svelte';
import { describe, expect, it, vi } from 'vitest';
import SettingsTranslationTab from '$lib/features/settings/components/SettingsTranslationTab.svelte';

const t = (key: string) => key;

const baseProps = {
  t,
  sourceLanguage: null as string | null,
  targetLanguage: null as string | null,
  history: [],
  onSourceChange: vi.fn(),
  onTargetChange: vi.fn(),
  onSave: vi.fn(),
  onClearHistory: vi.fn(),
};

/**
 * The block's positional contract: the pair's two selectors are the FIRST two
 * listbox triggers (the history's clear button is not one).
 */
function pairTriggers(container: HTMLElement): { source: HTMLElement; target: HTMLElement } {
  const triggers = Array.from(container.querySelectorAll<HTMLElement>('[aria-haspopup="listbox"]'));
  expect(triggers).toHaveLength(2);
  return { source: triggers[0], target: triggers[1] };
}

async function openDropdown(trigger: HTMLElement): Promise<void> {
  await fireEvent.pointerDown(trigger, { pointerId: 1, pointerType: 'mouse', button: 0 });
  await fireEvent.pointerUp(trigger, { pointerId: 1, pointerType: 'mouse', button: 0 });
  await waitFor(() => expect(screen.queryByRole('listbox')).not.toBeNull());
}

async function pickOption(label: string): Promise<void> {
  const item = screen.getAllByRole('option').find((o) => o.textContent?.trim() === label);
  expect(item).toBeTruthy();
  await fireEvent.pointerDown(item!, { pointerId: 1, pointerType: 'mouse', button: 0 });
  await fireEvent.pointerUp(item!, { pointerId: 1, pointerType: 'mouse', button: 0 });
  await fireEvent.click(item!);
  await waitFor(() => expect(screen.queryByRole('listbox')).toBeNull());
}

/** Selects one language end to end, by visible label (identity `t` → bare code). */
async function chooseLanguage(trigger: HTMLElement, label: string): Promise<void> {
  await openDropdown(trigger);
  await pickOption(label);
}

function saveButton(): HTMLButtonElement {
  return screen.getByText('settings.translation.save').closest('button') as HTMLButtonElement;
}

function warning(): HTMLElement | null {
  return screen.queryByTestId('translation-same-language-warning');
}

/**
 * A stand-in for the real caller: it holds the STORED pair, and only a save
 * moves it — an edit announced through the change callbacks is deliberately not
 * written to it, exactly as `translation.save()` is what moves
 * `translation.sourceLanguage`. Folding edits into the stored pair here would
 * erase the difference between "edited" and "saved" that this design turns on.
 */
function accumulatingParent(initialSource: string | null, initialTarget: string | null) {
  const stored = { source: initialSource, target: initialTarget };
  const edits = {
    onSourceChange: vi.fn(),
    onTargetChange: vi.fn(),
    onSave: vi.fn((source: string | null, target: string | null) => {
      // A successful save: the backend echoes the pair straight back.
      stored.source = source;
      stored.target = target;
    }),
  };
  return { stored, edits };
}

/**
 * The stored pair, read at the moment of the read, the way the real one is
 * (`translation.sourceLanguage` is a getter on its hook): spreading a plain
 * object would freeze both sides at their first value and no re-render would
 * ever deliver anything.
 */
function storedPair(stored: { source: string | null; target: string | null }) {
  return {
    get sourceLanguage(): string | null {
      return stored.source;
    },
    get targetLanguage(): string | null {
      return stored.target;
    },
  };
}

describe('SettingsTranslationTab — draft state', () => {
  it('enables Save only once the reader has changed the pair', async () => {
    const { container } = render(SettingsTranslationTab, { ...baseProps });
    const { target } = pairTriggers(container);

    // Bug 1: the button was dead from the start and stayed dead after an edit.
    expect(saveButton().disabled).toBe(true);

    await chooseLanguage(target, 'EN');

    expect(saveButton().disabled).toBe(false);
  });

  it('keeps an unsaved change in the draft instead of pushing it out as saved', async () => {
    const onTargetChange = vi.fn();
    const onSave = vi.fn();
    const { container } = render(SettingsTranslationTab, {
      ...baseProps,
      targetLanguage: 'es',
      onTargetChange,
      onSave,
    });
    const { target } = pairTriggers(container);

    await chooseLanguage(target, 'EN');

    // Bug 2: the dropdown on screen is the draft, and nothing is persisted yet.
    expect(target.textContent?.trim()).toBe('EN');
    expect(onSave).not.toHaveBeenCalled();
    // The caller is told about the edit; being told is not being saved.
    expect(onTargetChange).toHaveBeenCalledWith('en');

    await fireEvent.click(saveButton());

    expect(onSave).toHaveBeenCalledTimes(1);
    expect(onSave).toHaveBeenCalledWith(null, 'en');
  });

  it('persists the edited pair, not the props the save is replacing', async () => {
    const onSave = vi.fn();
    const { container } = render(SettingsTranslationTab, {
      ...baseProps,
      sourceLanguage: 'es',
      targetLanguage: 'es',
      onSave,
    });
    const { target } = pairTriggers(container);

    await chooseLanguage(target, 'EN');
    await fireEvent.click(saveButton());

    // The stored pair is `es` / `es`; a save that read it back would persist the
    // very pair the reader just replaced.
    expect(onSave).toHaveBeenCalledWith('es', 'en');
  });

  it('describes the pair on screen in the degenerate warning, not the stored one', async () => {
    const { container } = render(SettingsTranslationTab, {
      ...baseProps,
      sourceLanguage: 'es',
      targetLanguage: 'en',
    });
    const { target } = pairTriggers(container);

    expect(warning()).toBeNull();

    // Bug 3: the warning read the props, so it appeared while the dropdowns
    // still showed `es` → `en` — a collision nobody could see.
    await chooseLanguage(target, 'ES');

    expect(warning()).not.toBeNull();
  });

  it('retires the warning as soon as the draft stops being a collision', async () => {
    const { container } = render(SettingsTranslationTab, {
      ...baseProps,
      sourceLanguage: 'es',
      targetLanguage: 'es',
    });
    const { target } = pairTriggers(container);

    expect(warning()).not.toBeNull();

    await chooseLanguage(target, 'EN');

    expect(warning()).toBeNull();
  });

  it('does not warn on the infer value and does warn on a regional twin', () => {
    const { unmount } = render(SettingsTranslationTab, {
      ...baseProps,
      sourceLanguage: 'es',
      targetLanguage: null,
    });
    // An unresolved side is a statement about inference, not a collision.
    expect(warning()).toBeNull();
    unmount();

    render(SettingsTranslationTab, {
      ...baseProps,
      sourceLanguage: 'en-US',
      targetLanguage: 'en-GB',
    });
    // Same primary subtag: the service refuses this pair, so the warning must.
    expect(warning()).not.toBeNull();
  });

  it('seeds the draft from the stored pair on mount', () => {
    const { container } = render(SettingsTranslationTab, {
      ...baseProps,
      sourceLanguage: 'es',
      targetLanguage: 'en',
    });

    const { source, target } = pairTriggers(container);
    expect(source.textContent?.trim()).toBe('ES');
    expect(target.textContent?.trim()).toBe('EN');
    // Seeded values are not edits: there is nothing to save.
    expect(saveButton().disabled).toBe(true);
  });

  /**
   * The dangerous half of seeding: an edit belongs to the reader, so it has to
   * survive every render the parent makes while it is in flight.
   */
  it('keeps an edit in progress across a parent re-render', async () => {
    const { stored, edits } = accumulatingParent(null, 'es');
    const { container, rerender } = render(SettingsTranslationTab, {
      ...baseProps,
      ...storedPair(stored),
      ...edits,
    });
    const { target } = pairTriggers(container);

    await chooseLanguage(target, 'EN');

    // The parent re-renders with the pair it holds — a load landing, a sibling
    // setting moving, anything. The edit is not this component's to discard.
    await rerender({ ...baseProps, ...storedPair(stored), ...edits });

    expect(target.textContent?.trim()).toBe('EN');
    expect(saveButton().disabled).toBe(false);
  });

  it('adopts the stored pair when the parent publishes it after mount', async () => {
    const { container, rerender } = render(SettingsTranslationTab, { ...baseProps });

    // Mounted with nothing loaded yet, then the preferences arrive.
    await rerender({ ...baseProps, targetLanguage: 'en' });

    const { target } = pairTriggers(container);
    expect(target.textContent?.trim()).toBe('EN');
    // Adopted, not edited: there is still nothing of the reader's to save.
    expect(saveButton().disabled).toBe(true);
  });

  it('does not offer to save a side it cannot display, while the other adopts', async () => {
    const { container, rerender } = render(SettingsTranslationTab, { ...baseProps });

    await rerender({ ...baseProps, sourceLanguage: 'not a language', targetLanguage: 'en' });

    const { target } = pairTriggers(container);
    expect(target.textContent?.trim()).toBe('EN');
    // The draft cannot be shown whole, so persisting it would write an "infer"
    // the reader never chose.
    expect(saveButton().disabled).toBe(true);
  });

  /**
   * The seeded draft follows the pair through a SAVE: the parent echoes back
   * what it was given, so holding the pair it replaced afterwards would show a
   * value the reader had already moved past.
   */
  it('settles on the saved pair once the save round-trips', async () => {
    const { stored, edits } = accumulatingParent(null, 'es');
    const { container, rerender } = render(SettingsTranslationTab, {
      ...baseProps,
      ...storedPair(stored),
      ...edits,
    });
    const { target } = pairTriggers(container);

    await chooseLanguage(target, 'EN');
    await fireEvent.click(saveButton());

    await rerender({ ...baseProps, ...storedPair(stored), ...edits });

    expect(target.textContent?.trim()).toBe('EN');
    expect(saveButton().disabled).toBe(true);
    expect(warning()).toBeNull();
  });
});
