<script lang="ts">
  import CloudCheck from 'lucide-svelte/icons/cloud-check';
  import Database from 'lucide-svelte/icons/database';
  import HardDrive from 'lucide-svelte/icons/hard-drive';
  import Info from 'lucide-svelte/icons/info';
  import Keyboard from 'lucide-svelte/icons/keyboard';
  import Languages from 'lucide-svelte/icons/languages';
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
      id: 'seleccion',
      icon: Languages,
      labelKey: 'settings.tab.selectionMenu',
      fallback: 'Menú de selección',
    },
    {
      id: 'almacenamiento',
      icon: HardDrive,
      labelKey: 'sidebar.storage',
      fallback: 'Almacenamiento',
    },
    {
      id: 'sincronizacion',
      icon: CloudCheck,
      labelKey: 'sidebar.sync',
      fallback: 'Sincronización',
    },
    { id: 'atajos', icon: Keyboard, labelKey: 'settings.shortcuts.title', fallback: 'Atajos' },
    { id: 'acerca', icon: Info, labelKey: 'settings.tab.about', fallback: 'Acerca' },
  ];

  const labelOf = (tab: TabMeta): string => t(tab.labelKey as MessageKey) || tab.fallback;
</script>

<!-- A segmented track that always fits its row. Where the row is too narrow
     for every label (the Spanish labels need ~61rem), the idle tabs fold to
     their icon, keeping the label as their accessible name and tooltip, and
     only the selected tab keeps its text. No scrollbar is ever painted. -->
<div class="@container min-w-0 flex-1">
  <div
    role="tablist"
    aria-label={t('settings.title')}
    onkeydown={onKeydown}
    tabindex={0}
    class="scrollbar-hidden flex w-max max-w-full gap-1 overflow-x-auto rounded-full border border-(--color-edge) bg-(--color-home-card) p-1 focus:outline-none focus-visible:ring-2 focus-visible:ring-(--color-brand)"
  >
    {#each tabs as tab (tab.id)}
      {@const TabIcon = tab.icon}
      {@const isSelected = activeTab === tab.id}
      <Button
        variant="tab"
        selected={isSelected}
        role="tab"
        aria-selected={isSelected}
        aria-controls="tabpanel-{tab.id}"
        id="tab-{tab.id}"
        tabindex={isSelected ? 0 : -1}
        title={labelOf(tab)}
        class={isSelected ? '' : '@max-[62rem]:px-3'}
        onclick={() => onTabChange(tab.id)}
      >
        <TabIcon size={14} strokeWidth={1.8} class="h-3.5 w-3.5 shrink-0" aria-hidden="true" />
        <span class="ml-1.5 {isSelected ? '' : '@max-[62rem]:sr-only'}">{labelOf(tab)}</span>
      </Button>
    {/each}
  </div>
</div>
