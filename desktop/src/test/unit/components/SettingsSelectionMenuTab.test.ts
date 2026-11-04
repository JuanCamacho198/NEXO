/**
 * "Menú de selección" (D6) — the composed settings tab.
 *
 * What is pinned here is the composition the user asked for: ONE tab that holds
 * the selection menu's options as labelled blocks, with translation MOVED into
 * it rather than duplicated, and a definition block beside it. The per-block
 * logic already has its own tests (translationPreferences, definitionLanguage,
 * readerDefinition); this file only proves they are wired into one surface.
 */
import { render, screen, fireEvent, waitFor } from '@testing-library/svelte';
import { describe, expect, it, vi } from 'vitest';
import SettingsSelectionMenuTab from '$lib/features/settings/components/SettingsSelectionMenuTab.svelte';

const t = (key: string) => key;

const baseProps = {
  t,
  locale: 'es' as const,
  sourceLanguage: null,
  targetLanguage: null,
  definitionLanguage: null,
  history: [],
  onSourceChange: vi.fn(),
  onTargetChange: vi.fn(),
  onDefinitionLanguageChange: vi.fn(),
  onSave: vi.fn(),
  onClearHistory: vi.fn(),
};

describe('SettingsSelectionMenuTab', () => {
  /**
   * bits-ui's `Select` opens on `pointerdown`, and the label of an option is
   * resolved through the tab's own `t`. The definition selector is the LAST
   * trigger in the tab (the pair's two come first).
   */
  async function pickDefinitionLanguage(
    container: HTMLElement,
    label: string,
    partial = false,
  ): Promise<void> {
    const triggers = Array.from(
      container.querySelectorAll<HTMLElement>('[aria-haspopup="listbox"]'),
    );
    expect(triggers.length).toBeGreaterThan(0);
    const trigger = triggers[triggers.length - 1];

    await fireEvent.pointerDown(trigger, { pointerId: 1, pointerType: 'mouse', button: 0 });
    await fireEvent.pointerUp(trigger, { pointerId: 1, pointerType: 'mouse', button: 0 });
    await waitFor(() => expect(screen.queryByRole('listbox')).not.toBeNull());

    const option = screen.getAllByRole('option').find((item) => {
      const text = item.textContent?.trim() ?? '';
      return partial ? text.startsWith(label) : text === label;
    });
    expect(option).toBeTruthy();

    await fireEvent.pointerDown(option as HTMLElement, {
      pointerId: 1,
      pointerType: 'mouse',
      button: 0,
    });
    await fireEvent.pointerUp(option as HTMLElement, {
      pointerId: 1,
      pointerType: 'mouse',
      button: 0,
    });
    await fireEvent.click(option as HTMLElement);
    await waitFor(() => expect(screen.queryByRole('listbox')).toBeNull());
  }

  it('renders the tab title and clicks through to both option blocks', () => {
    render(SettingsSelectionMenuTab, baseProps);

    expect(screen.getByText('settings.selectionMenu.title')).toBeTruthy();
    // The two options of the menu this tab configures.
    expect(screen.getByText('settings.selectionMenu.translation.title')).toBeTruthy();
    expect(screen.getByText('settings.selectionMenu.definition.title')).toBeTruthy();
  });

  /**
   * The translation block must not lose anything it had as its own tab: the
   * source and target selectors, its Save button and the history section are
   * all still here.
   */
  it('keeps the whole translation block inside the section', () => {
    render(SettingsSelectionMenuTab, baseProps);

    expect(screen.getByText('settings.translation.sourceLabel')).toBeTruthy();
    expect(screen.getByText('settings.translation.targetLabel')).toBeTruthy();
    expect(screen.getByText('settings.translation.save')).toBeTruthy();
    expect(screen.getByText('settings.translation.historyTitle')).toBeTruthy();
  });

  it('renders the history it was given, not just the header', () => {
    render(SettingsSelectionMenuTab, {
      ...baseProps,
      history: [
        {
          cacheKey: 'k1',
          sourceLanguage: 'es',
          targetLanguage: 'en',
          originalText: 'hola',
          translatedText: 'hello',
          createdAt: '2026-01-02T00:00:00Z',
        },
      ],
    });

    expect(screen.getByText('hola')).toBeTruthy();
    expect(screen.getByText('hello')).toBeTruthy();
  });

  /**
   * Requirement 3: the definition selector must PERSIST the value. It saves the
   * moment it changes — there is no Save button of its own, so a change that did
   * not reach the callback would be a setting that silently does nothing.
   */
  it('reports a definition language change through the callback', async () => {
    const onDefinitionLanguageChange = vi.fn();
    const { container } = render(SettingsSelectionMenuTab, {
      ...baseProps,
      onDefinitionLanguageChange,
    });

    // The label is the language NAME resolved through `t`; with an identity `t`
    // here it falls back to the bare uppercase code, which is what the option
    // reads as on screen.
    await pickDefinitionLanguage(container, 'FR');

    expect(onDefinitionLanguageChange).toHaveBeenCalledWith('fr');
  });

  it('maps the app-language option back to null, the stored "infer" value', async () => {
    const onDefinitionLanguageChange = vi.fn();
    const { container } = render(SettingsSelectionMenuTab, {
      ...baseProps,
      definitionLanguage: 'fr',
      onDefinitionLanguageChange,
    });

    await pickDefinitionLanguage(container, 'settings.selectionMenu.definition.languageApp', true);

    // `''` never reaches the command: it is the placeholder slot, and `null` is
    // the documented "use the app locale".
    expect(onDefinitionLanguageChange).toHaveBeenCalledWith(null);
  });

  it('shows an error when the settings surface reports one', () => {
    render(SettingsSelectionMenuTab, { ...baseProps, error: 'boom' });

    expect(screen.getByText('boom')).toBeTruthy();
  });

  /**
   * The translation block carries its OWN error slot, and the tab passes `null`
   * there so one failure is not announced twice.
   */
  it('renders the settings error once, not once per block', () => {
    render(SettingsSelectionMenuTab, { ...baseProps, error: 'translation unavailable' });

    expect(screen.getAllByText('translation unavailable')).toHaveLength(1);
  });

  it('shows a loading state in the history for the translation block', () => {
    render(SettingsSelectionMenuTab, { ...baseProps, isLoading: true });

    expect(screen.getByText('settings.translation.loading')).toBeTruthy();
  });
});
