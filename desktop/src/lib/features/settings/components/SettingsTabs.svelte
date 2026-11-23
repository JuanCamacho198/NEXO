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
    /** A shorter visible label; the full one stays the accessible name and tooltip. */
    shortKey?: MessageKey;
    fallback: string;
  };

  const tabs: TabMeta[] = [
    { id: 'cuenta', icon: User, labelKey: 'settings.tab.account', fallback: 'Cuenta' },
    { id: 'datos', icon: Database, labelKey: 'settings.tab.data', fallback: 'Datos' },
    {
      id: 'seleccion',
      icon: Languages,
      labelKey: 'settings.tab.selectionMenu',
      shortKey: 'settings.tabShort.selectionMenu',
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
    {
      id: 'atajos',
      icon: Keyboard,
      labelKey: 'settings.shortcuts.title',
      shortKey: 'settings.tabShort.shortcuts',
      fallback: 'Atajos',
    },
    { id: 'acerca', icon: Info, labelKey: 'settings.tab.about', fallback: 'Acerca' },
  ];

  const labelOf = (tab: TabMeta): string => t(tab.labelKey as MessageKey) || tab.fallback;
  const shortLabelOf = (tab: TabMeta): string =>
    (tab.shortKey ? t(tab.shortKey) : '') || labelOf(tab);
</script>

<!-- A segmented track that always fits its row. Tabs show short labels
     ("Selección", "Atajos") so the whole track fits beside an open sidebar
     (~53rem in Spanish). Where the row is narrower still, the idle tabs fold to
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
        aria-label={labelOf(tab)}
        class={isSelected ? '' : '@max-[54rem]:px-3'}
        onclick={() => onTabChange(tab.id)}
      >
        <TabIcon size={14} strokeWidth={1.8} class="h-3.5 w-3.5 shrink-0" aria-hidden="true" />
        <span class="ml-1.5 {isSelected ? '' : '@max-[54rem]:sr-only'}" aria-hidden="true"
          >{shortLabelOf(tab)}</span
        >
      </Button>
    {/each}
  </div>
</div>
