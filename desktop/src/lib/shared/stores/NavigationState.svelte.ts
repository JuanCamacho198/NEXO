import type { AppRoute } from '$lib/shared/stores/HomeState';
import type { MessageKey } from '$lib/shared/i18n';
import House from 'lucide-svelte/icons/house';
import Library from 'lucide-svelte/icons/library';
import ChartColumn from 'lucide-svelte/icons/chart-column';
import Highlighter from 'lucide-svelte/icons/highlighter';
import Settings from 'lucide-svelte/icons/settings';
import Book from 'lucide-svelte/icons/book';
import Search from 'lucide-svelte/icons/search';
import LayoutGrid from 'lucide-svelte/icons/layout-grid';
import type { Icon as LucideIcon } from 'lucide-svelte';

export type NavItem = {
  id: AppRoute;
  messageKey: MessageKey;
  icon: typeof LucideIcon;
  action: () => void;
  /**
   * Optional status chip rendered next to the label while the rail is
   * expanded. Collapsed, the same signal reduces to a dot on the icon, so the
   * flag stays visible in both states without a per-route branch in the
   * sidebar. A nav item that carries a badge announces it through
   * `aria-label` as well, since the chip is decorative to a screen reader
   * reading only the destination name.
   */
  badgeKey?: MessageKey;
};

export type NavCallbacks = {
  onNavigateHome: () => void;
  onNavigateLibrary: () => void;
  onNavigateStats: () => void;
  onNavigateHighlights: () => void;
  onNavigateSettings: () => void;
  onNavigateDictionary?: () => void;
  onNavigateDiscover?: () => void;
  onNavigateAddons?: () => void;
  onNavigateStorage?: () => void;
  onNavigateSync?: () => void;
};

export function getNavItems(callbacks: NavCallbacks): NavItem[] {
  const items: NavItem[] = [
    { id: 'home', messageKey: 'sidebar.home', icon: House, action: callbacks.onNavigateHome },
    {
      id: 'library',
      messageKey: 'sidebar.library',
      icon: Library,
      action: callbacks.onNavigateLibrary,
    },
  ];
  if (callbacks.onNavigateDiscover) {
    items.push({
      id: 'discover',
      messageKey: 'sidebar.discover',
      icon: Search,
      action: callbacks.onNavigateDiscover,
    });
  }
  if (callbacks.onNavigateAddons) {
    items.push({
      id: 'addons',
      messageKey: 'sidebar.addons',
      icon: LayoutGrid,
      action: callbacks.onNavigateAddons,
      badgeKey: 'sidebar.beta',
    });
  }
  items.push(
    {
      id: 'stats',
      messageKey: 'sidebar.stats',
      icon: ChartColumn,
      action: callbacks.onNavigateStats,
    },
    {
      id: 'highlights',
      messageKey: 'sidebar.highlights',
      icon: Highlighter,
      action: callbacks.onNavigateHighlights,
    },
    {
      id: 'settings',
      messageKey: 'sidebar.settings',
      icon: Settings,
      action: callbacks.onNavigateSettings,
    },
  );
  return items;
}

export function getDataNavItems(callbacks: NavCallbacks): NavItem[] {
  const items: NavItem[] = [];
  if (callbacks.onNavigateDictionary) {
    items.push({
      id: 'dictionary',
      messageKey: 'sidebar.dictionary',
      icon: Book,
      action: callbacks.onNavigateDictionary,
    });
  }
  return items;
}
