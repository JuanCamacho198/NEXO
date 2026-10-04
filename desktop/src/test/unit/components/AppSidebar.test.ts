import { render, screen, waitFor } from '@testing-library/svelte';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';
import AppSidebar from '$lib/shared/ui/layout/AppSidebar.svelte';
import { getNavItems } from '$lib/shared/stores/NavigationState.svelte';

function fakeT(key: string): string {
  const labels: Record<string, string> = {
    'sidebar.home': 'Inicio',
    'sidebar.library': 'Estantería',
    'sidebar.stats': 'Estadísticas',
    'sidebar.highlights': 'Notas y resaltados',
    'sidebar.settings': 'Ajustes',
    'sidebar.expand': 'Expandir sidebar',
    'sidebar.collapse': 'Colapsar sidebar',
    'notifications.bell.label': 'Abrir notificaciones',
    'notifications.center.title': 'Notificaciones',
    'theme.currentDark': 'Tema oscuro',
    'theme.currentLight': 'Tema claro',
    'theme.switchToLight': 'Cambiar a tema claro',
    'theme.switchToDark': 'Cambiar a tema oscuro',
  };
  return labels[key] ?? key;
}

function makeNavItems(
  overrides: Partial<{
    onNavigateHome: () => void;
    onNavigateLibrary: () => void;
    onNavigateStats: () => void;
    onNavigateHighlights: () => void;
    onNavigateSettings: () => void;
  }> = {},
) {
  return getNavItems({
    onNavigateHome: vi.fn(),
    onNavigateLibrary: vi.fn(),
    onNavigateStats: vi.fn(),
    onNavigateHighlights: vi.fn(),
    onNavigateSettings: vi.fn(),
    ...overrides,
  });
}

describe('AppSidebar', () => {
  const defaultProps = {
    activeRoute: 'home' as const,
    navItems: makeNavItems(),
    t: fakeT,
  };

  function findNavButton(name: string): HTMLElement | null {
    const buttons = screen.getAllByRole('button');
    return buttons.find((btn) => btn.textContent?.trim().includes(name)) ?? null;
  }

  it('renders all five navigation items with labels', () => {
    render(AppSidebar, defaultProps);
    expect(screen.getAllByText('Inicio').length).toBeGreaterThanOrEqual(1);
    expect(screen.getAllByText('Estantería').length).toBeGreaterThanOrEqual(1);
    expect(screen.getAllByText('Estadísticas').length).toBeGreaterThanOrEqual(1);
    expect(screen.getAllByText('Notas y resaltados').length).toBeGreaterThanOrEqual(1);
    expect(screen.getAllByText('Ajustes').length).toBeGreaterThanOrEqual(1);
  });

  it('highlights the active route button with a quiet tinted selection', () => {
    render(AppSidebar, { ...defaultProps, activeRoute: 'settings' });
    const activeButton = findNavButton('Ajustes');
    expect(activeButton).not.toBeNull();
    expect(activeButton!.className).toContain('accent');
    // HOME-04: the oversized cyan block and its glow are gone.
    expect(activeButton!.className).not.toContain('accent-blue');
    expect(activeButton!.className).not.toContain('shadow-glow');
  });

  it('separates navigation groups with restrained dividers', () => {
    const { container } = render(AppSidebar, defaultProps);
    const nav = container.querySelector('nav');
    expect(nav).not.toBeNull();
    const dividers = nav!.querySelectorAll('div[aria-hidden="true"].h-px');
    // reading | tools | system boundaries in the default order
    expect(dividers.length).toBeGreaterThanOrEqual(2);
    // Dividers never wrap destinations: nav buttons stay direct children.
    const buttons = nav!.querySelectorAll(':scope > button');
    expect(buttons.length).toBe(5);
  });

  it('collapses to an icon rail that keeps every destination reachable', async () => {
    const user = userEvent.setup();
    render(AppSidebar, defaultProps);

    await user.click(screen.getByLabelText('Colapsar sidebar'));

    for (const name of ['Inicio', 'Estantería', 'Estadísticas', 'Notas y resaltados', 'Ajustes']) {
      expect(screen.getByRole('button', { name })).toBeInTheDocument();
    }

    // The active brand stays visible in the rail.
    expect(screen.getByRole('button', { name: 'Inicio' }).className).toContain('accent');
    // Profile collapses to the avatar only, still labelled by name.
    expect(screen.getByRole('button', { name: /Reader/ })).toBeInTheDocument();
  });

  it('calls onNavigateHome when home is clicked', async () => {
    const onNavigateHome = vi.fn();
    const user = userEvent.setup();
    render(AppSidebar, {
      ...defaultProps,
      navItems: makeNavItems({ onNavigateHome }),
    });
    const btn = findNavButton('Inicio');
    expect(btn).not.toBeNull();
    await user.click(btn as HTMLElement);
    expect(onNavigateHome).toHaveBeenCalledTimes(1);
  });

  it('calls onNavigateLibrary when library is clicked', async () => {
    const onNavigateLibrary = vi.fn();
    const user = userEvent.setup();
    render(AppSidebar, {
      ...defaultProps,
      navItems: makeNavItems({ onNavigateLibrary }),
    });
    const btn = findNavButton('Estantería');
    expect(btn).not.toBeNull();
    await user.click(btn as HTMLElement);
    expect(onNavigateLibrary).toHaveBeenCalledTimes(1);
  });

  it('calls onNavigateStats when stats is clicked', async () => {
    const onNavigateStats = vi.fn();
    const user = userEvent.setup();
    render(AppSidebar, {
      ...defaultProps,
      navItems: makeNavItems({ onNavigateStats }),
    });
    const btn = findNavButton('Estadísticas');
    expect(btn).not.toBeNull();
    await user.click(btn as HTMLElement);
    expect(onNavigateStats).toHaveBeenCalledTimes(1);
  });

  it('calls onNavigateHighlights when highlights is clicked', async () => {
    const onNavigateHighlights = vi.fn();
    const user = userEvent.setup();
    render(AppSidebar, {
      ...defaultProps,
      navItems: makeNavItems({ onNavigateHighlights }),
    });
    const btn = findNavButton('Notas y resaltados');
    expect(btn).not.toBeNull();
    await user.click(btn as HTMLElement);
    expect(onNavigateHighlights).toHaveBeenCalledTimes(1);
  });

  it('calls onNavigateSettings when settings is clicked', async () => {
    const onNavigateSettings = vi.fn();
    const user = userEvent.setup();
    render(AppSidebar, {
      ...defaultProps,
      navItems: makeNavItems({ onNavigateSettings }),
    });
    const btn = findNavButton('Ajustes');
    expect(btn).not.toBeNull();
    await user.click(btn as HTMLElement);
    expect(onNavigateSettings).toHaveBeenCalledTimes(1);
  });

  it('renders the user block as a clickable button (REQ-12)', () => {
    render(AppSidebar, defaultProps);
    const userBlock = screen.getByRole('button', { name: /Reader/ });
    expect(userBlock).toHaveAttribute('role', 'button');
    expect(userBlock).toHaveAttribute('tabindex', '0');
  });

  it('calls onNavigateSettings when the user block is clicked (REQ-12)', async () => {
    const onNavigateSettings = vi.fn();
    const user = userEvent.setup();
    render(AppSidebar, { ...defaultProps, onNavigateSettings });
    const userBlock = screen.getByRole('button', { name: /Reader/ });
    await user.click(userBlock);
    expect(onNavigateSettings).toHaveBeenCalledTimes(1);
  });

  it('calls onNavigateSettings from the user block via Enter and Space (REQ-12)', async () => {
    const onNavigateSettings = vi.fn();
    const user = userEvent.setup();
    render(AppSidebar, { ...defaultProps, onNavigateSettings });
    const userBlock = screen.getByRole('button', { name: /Reader/ });
    userBlock.focus();
    await user.keyboard('{Enter}');
    expect(onNavigateSettings).toHaveBeenCalledTimes(1);
    await user.keyboard(' ');
    expect(onNavigateSettings).toHaveBeenCalledTimes(2);
  });

  it('does not throw when onNavigateSettings is omitted', async () => {
    const user = userEvent.setup();
    render(AppSidebar, defaultProps);
    const userBlock = screen.getByRole('button', { name: /Reader/ });
    await user.click(userBlock);
    expect(userBlock).toBeTruthy();
  });

  it('has semantic aside element', () => {
    const { container } = render(AppSidebar, defaultProps);
    expect(container.querySelector('aside')).toBeInTheDocument();
  });

  it('does not duplicate the visible destination label with a tooltip when expanded (D5)', async () => {
    render(AppSidebar, defaultProps);
    const trigger = findNavButton('Estantería');
    expect(trigger).not.toBeNull();

    trigger!.focus();
    // The label is already on screen, so the portalled tooltip never mounts.
    await waitFor(() => expect(screen.queryByRole('tooltip')).toBeNull());
    expect(trigger!.getAttribute('aria-label')).toBe('Estantería');
  });

  it('describes the collapsed nav trigger with the portalled tooltip content (D5)', async () => {
    const user = userEvent.setup();
    render(AppSidebar, defaultProps);

    await user.click(screen.getByLabelText('Colapsar sidebar'));

    const trigger = screen.getByRole('button', { name: 'Estantería' });
    expect(screen.queryByRole('tooltip')).toBeNull();

    // The tooltip content is portalled to `document.body`, so the jsdom
    // read goes through the a11y tree rather than the render target.
    trigger.focus();
    const tooltip = await waitFor(() => screen.getByRole('tooltip'));
    expect(tooltip.textContent?.trim()).toBe('Estantería');
    expect(trigger.getAttribute('aria-describedby')).toBe(tooltip.id);
    // The trigger is still the nav button itself: `Tooltip.Root` and
    // `Tooltip.Provider` render no wrapper element, which is what keeps
    // `aside nav > button` (the visual gate's locator) valid.
    expect(trigger.tagName).toBe('BUTTON');

    trigger.blur();
    // Pointer/focus close is asynchronous and the content unmounts.
    await waitFor(() => expect(screen.queryByRole('tooltip')).toBeNull());
  });
});
