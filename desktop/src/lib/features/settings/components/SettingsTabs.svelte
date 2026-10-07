<script lang="ts">
  import Bookmark from 'lucide-svelte/icons/bookmark';
  import CloudCheck from 'lucide-svelte/icons/cloud-check';
  import Database from 'lucide-svelte/icons/database';
  import Info from 'lucide-svelte/icons/info';
  import User from 'lucide-svelte/icons/user';
  import type { Icon as LucideIcon } from 'lucide-svelte';
  import Button from '$lib/shared/ui/forms/Button.svelte';
  import type { MessageKey } from '$lib/shared/i18n';
  import type { SettingsTab } from '../useSettingsRouter.svelte';

  type Props = {
    activeTab: SettingsTab;
    onTabChange: (tab: SettingsTab) => void;
    onKeydown: (e: KeyboardEvent) => void;
    t: (key: MessageKey, params?: Record<string, string | number>) => string;
  };

  let { activeTab, onTabChange, onKeydown, t }: Props = $props();

  type TabIcon = typeof LucideIcon;

  type TabMeta = {
    id: SettingsTab;
    icon: TabIcon;
    labelKey: MessageKey;
    fallback: string;
  };

  const tabs: TabMeta[] = [
    { id: 'cuenta', icon: User, labelKey: 'settings.tab.account', fallback: 'Cuenta' },
    { id: 'datos', icon: Database, labelKey: 'settings.tab.data', fallback: 'Datos' },
    {
      id: 'almacenamiento',
      icon: Database,
      labelKey: 'sidebar.storage',
      fallback: 'Almacenamiento',
    },
    {
      id: 'sincronizacion',
      icon: CloudCheck,
      labelKey: 'sidebar.sync',
      fallback: 'Sincronización',
    },
    { id: 'atajos', icon: Bookmark, labelKey: 'settings.shortcuts.title', fallback: 'Atajos' },
    { id: 'acerca', icon: Info, labelKey: 'settings.tab.about', fallback: 'Acerca' },
  ];
</script>

<div
  role="tablist"
  aria-label={t('settings.title')}
  onkeydown={onKeydown}
  tabindex={0}
  class="flex border-b border-(--color-border)"
>
  {#each tabs as tab (tab.id)}
    {@const TabIcon = tab.icon}
    <!-- The atom owns the control chrome; the selected tab takes the filled
         secondary tone and the rest stay ghost. The old accent-soft wash with
         an accent-start underline has no atom equivalent, so the selected
         state is intentionally quieter — selection is still exposed through
         `aria-selected`. -->
    <Button
      variant={activeTab === tab.id ? 'secondary' : 'ghost'}
      size="sm"
      role="tab"
      aria-selected={activeTab === tab.id}
      aria-controls="tabpanel-{tab.id}"
      id="tab-{tab.id}"
      tabindex={activeTab === tab.id ? 0 : -1}
      class="flex-1"
      onclick={() => onTabChange(tab.id)}
    >
      {#snippet leadingIcon()}
        <TabIcon size={14} strokeWidth={1.8} class="h-3.5 w-3.5" aria-hidden="true" />
      {/snippet}
      <span>{t(tab.labelKey as MessageKey) || tab.fallback}</span>
    </Button>
  {/each}
</div>
