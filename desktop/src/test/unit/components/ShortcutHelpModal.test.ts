import { render, screen, waitFor } from '@testing-library/svelte';
import { tick } from 'svelte';
import { beforeEach, describe, expect, it } from 'vitest';
import ShortcutHelpModal from '$lib/shared/shortcuts/ShortcutHelpModal.svelte';
import { helpState } from '$lib/shared/shortcuts/helpState.svelte';

const dictionary: Record<string, string> = {
  'settings.shortcuts.helpTitle': 'Keyboard shortcuts',
  'settings.shortcuts.group.readerNav': 'Reader navigation',
  'settings.shortcuts.group.readerView': 'Reader view',
  'settings.shortcuts.group.app': 'Application',
};

const t = (key: string): string => dictionary[key] ?? key;

describe('ShortcutHelpModal (? help surface)', () => {
  beforeEach(() => {
    helpState.hide();
  });

  it('renders the registry when the help state opens it', async () => {
    render(ShortcutHelpModal, { t });
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();

    helpState.show();
    await tick();

    await waitFor(() => expect(screen.getByRole('dialog')).toBeInTheDocument());
    expect(document.querySelectorAll('kbd').length).toBeGreaterThan(0);
    expect(screen.getByText('Keyboard shortcuts')).toBeInTheDocument();
  });

  it('closes when the help state hides it', async () => {
    helpState.show();
    render(ShortcutHelpModal, { t });
    await waitFor(() => expect(screen.getByRole('dialog')).toBeInTheDocument());

    helpState.hide();
    await tick();

    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
  });
});
