import { render, screen, waitFor } from '@testing-library/svelte';
import userEvent from '@testing-library/user-event';
import { tick } from 'svelte';
import { describe, expect, it, vi, beforeEach, afterEach } from 'vitest';
import { axe } from 'vitest-axe';
import { toHaveNoViolations } from 'vitest-axe/dist/matchers.js';
import AppSidebar from '$lib/shared/ui/layout/AppSidebar.svelte';
import { getNavItems } from '$lib/shared/stores/NavigationState.svelte';
import {
  notify,
  clearNotifications,
  setNotificationPort,
} from '$lib/shared/stores/notificationCenter.svelte';
import { MockNotificationAdapter } from '$lib/shared/ports/adapters/mock/MockNotificationAdapter';

expect.extend({ toHaveNoViolations });

function fakeT(key: string, params?: Record<string, string | number>): string {
  const labels: Record<string, string> = {
    'sidebar.home': 'Inicio',
    'sidebar.library': 'Estantería',
    'sidebar.stats': 'Estadísticas',
    'sidebar.highlights': 'Notas y resaltados',
    'sidebar.settings': 'Ajustes',
    'sidebar.expand': 'Expandir sidebar',
    'sidebar.collapse': 'Colapsar sidebar',
    'notifications.bell.label': 'Abrir notificaciones',
    'notifications.bell.unread': 'Abrir notificaciones, {{count}} sin leer',
    'notifications.center.title': 'Notificaciones',
    'notifications.center.newArrival': 'Nueva notificación: {{message}}',
    'theme.currentDark': 'Tema oscuro',
    'theme.currentLight': 'Tema claro',
    'theme.switchToLight': 'Cambiar a tema claro',
    'theme.switchToDark': 'Cambiar a tema oscuro',
  };
  let template = labels[key] ?? key;
  if (params) {
    template = template.replace(/\{\{\s*([\w.-]+)\s*\}\}/g, (_match, name: string) =>
      params[name] === undefined ? '' : String(params[name]),
    );
  }
  return template;
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

  it('names the profile button with the exact visible "name · email" text', async () => {
    render(AppSidebar, defaultProps);
    const userBlock = screen.getByRole('button', { name: /Reader/ });

    const visible = (userBlock.querySelector('p')?.textContent ?? '').replace(/\s+/g, ' ').trim();
    // The visible chrome separates name and email with a middle dot.
    expect(visible).toContain('·');
    expect(visible).toContain('No email available');

    const label = userBlock.getAttribute('aria-label') ?? '';
    // The accessible name must contain the visible text verbatim, including
    // the `·` separator (a comma did not satisfy label-content-name-mismatch).
    expect(label).toContain(visible);

    const results = await axe(userBlock);
    const mismatches = results.violations.filter((v) => v.id === 'label-content-name-mismatch');
    expect(mismatches).toHaveLength(0);
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

  it('collapses without overlaying controls or widening the rail (regression guard)', async () => {
    const user = userEvent.setup();
    const { container } = render(AppSidebar, defaultProps);

    await user.click(screen.getByLabelText('Colapsar sidebar'));

    // (a) The click toggled the rail into its collapsed state.
    const expand = screen.getByLabelText('Expandir sidebar');
    expect(expand).toBeInTheDocument();

    const aside = container.querySelector('aside');
    expect(aside).not.toBeNull();

    // (b) No label or wordmark is absolutely positioned. The original
    // regression took them out of flow with `absolute`, which painted over the
    // expand button (swallowing the click) and widened the `relative` nav
    // button until the rail grew a horizontal scrollbar. In flow they collapse
    // to zero width instead. The only absolute chrome left is the active-route
    // bar (`w-0.5`), the notification badge, and the unread dot — none carries
    // a collapsed label's `opacity-0`.
    expect(aside!.querySelectorAll('.absolute.opacity-0')).toHaveLength(0);

    // jsdom runs no layout and no hit-testing: `scrollWidth`/`clientWidth` are
    // 0 and `userEvent.click` (like `element.click()`) bypasses hit-testing,
    // which is exactly why the earlier suite passed while the app was broken.
    // So instead of measuring overflow we pin the structural invariant that
    // caused it: the wordmark is an in-flow box clipped to zero width, not an
    // absolute overlay.
    const wordmark = aside!.querySelector('img[alt="NEXO"]');
    expect(wordmark).not.toBeNull();
    const wordmarkBox = wordmark!.parentElement as HTMLElement;
    expect(wordmarkBox.className).not.toContain('absolute');
    expect(wordmarkBox.className).toContain('max-w-0');
    expect(wordmarkBox.className).toContain('overflow-hidden');
    expect(wordmarkBox.className).toContain('opacity-0');

    // (c) The expand control owns its centre. It is the LAST in-flow child of
    // its header, so nothing rendered after it can paint over it, and no
    // preceding sibling is taken out of flow to overlay it.
    const header = expand.parentElement as HTMLElement;
    expect(header.children[header.children.length - 1]).toBe(expand);
    const overlays = Array.from(header.children).filter(
      (el) => el !== expand && el.className.includes('absolute'),
    );
    expect(overlays).toHaveLength(0);

    // Every collapsed nav label is likewise in flow and clipped to zero.
    const collapsedLabels = Array.from(aside!.querySelectorAll('nav button > span')).filter((el) =>
      el.className.includes('max-w-0'),
    );
    expect(collapsedLabels).toHaveLength(5);
    for (const label of collapsedLabels) {
      expect(label.className).not.toContain('absolute');
      expect(label.className).toContain('overflow-hidden');
    }

    // A retained flex `gap-3` still separates a zero-width in-flow label from
    // its icon, which would widen the row past the 72px rail. The two rows that
    // keep `gap-3` while expanded must drop it while collapsed.
    for (const row of [
      screen.getByLabelText('Abrir notificaciones'),
      screen.getByRole('button', { name: /Reader/ }),
    ]) {
      expect(row.className).toContain('justify-center');
      expect(row.className).not.toContain('gap-3');
    }

    // The state round-trips: a click expands the rail back.
    await user.click(expand);
    expect(screen.getByLabelText('Colapsar sidebar')).toBeInTheDocument();
  });

  describe('notification bell and arrival announcements (NOTIF-06)', () => {
    beforeEach(() => {
      setNotificationPort(new MockNotificationAdapter());
      clearNotifications();
    });

    afterEach(() => {
      clearNotifications();
    });

    it('names the unread count on the bell while the tray is closed', () => {
      render(AppSidebar, defaultProps);
      expect(screen.getByLabelText('Abrir notificaciones')).toBeInTheDocument();
    });

    it('announces arrivals through a polite live region and counts them on the bell', async () => {
      render(AppSidebar, defaultProps);
      await tick();
      expect(screen.getByRole('status').textContent).toBe('');

      notify(
        {
          source: 'sync',
          severity: 'error',
          i18nKey: 'notifications.kind.syncFailure',
          i18nParams: { detail: 'Offline' },
        },
        { isWindowFocused: true, isReading: true },
      );
      notify(
        {
          source: 'sync',
          severity: 'error',
          i18nKey: 'notifications.kind.syncFailure',
          i18nParams: { detail: 'Timeout' },
        },
        { isWindowFocused: true, isReading: true },
      );
      await tick();

      expect(screen.getByRole('status').textContent).toContain(
        'Nueva notificación: notifications.kind.syncFailure',
      );
      expect(screen.getByLabelText('Abrir notificaciones, 2 sin leer')).toBeInTheDocument();
    });
  });
});
