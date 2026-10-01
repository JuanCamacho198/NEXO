export type SettingsTab =
  'cuenta' | 'datos' | 'almacenamiento' | 'sincronizacion' | 'atajos' | 'acerca';

export const SETTINGS_TABS: SettingsTab[] = [
  'cuenta',
  'datos',
  'almacenamiento',
  'sincronizacion',
  'atajos',
  'acerca',
];

export function createSettingsRouter(options: { initialTab?: SettingsTab } = {}): {
  activeTab: SettingsTab;
  handleTabChange: (tab: SettingsTab) => Promise<void>;
  handleTabKeydown: (e: KeyboardEvent) => void;
  tabs: SettingsTab[];
} {
  let activeTab = $state<SettingsTab>(options.initialTab ?? 'cuenta');

  async function handleTabChange(tab: SettingsTab): Promise<void> {
    activeTab = tab;
  }

  function handleTabKeydown(e: KeyboardEvent): void {
    const idx = SETTINGS_TABS.indexOf(activeTab);
    let next: number | null = null;

    if (e.key === 'ArrowRight') {
      e.preventDefault();
      next = (idx + 1) % SETTINGS_TABS.length;
    } else if (e.key === 'ArrowLeft') {
      e.preventDefault();
      next = (idx - 1 + SETTINGS_TABS.length) % SETTINGS_TABS.length;
    } else if (e.key === 'Home') {
      e.preventDefault();
      next = 0;
    } else if (e.key === 'End') {
      e.preventDefault();
      next = SETTINGS_TABS.length - 1;
    }

    if (next !== null) {
      const nextTab = SETTINGS_TABS[next];
      void handleTabChange(nextTab);
      document.getElementById(`tab-${nextTab}`)?.focus();
    }
  }

  return {
    get activeTab(): SettingsTab {
      return activeTab;
    },
    set activeTab(v: SettingsTab) {
      activeTab = v;
    },
    handleTabChange,
    handleTabKeydown,
    get tabs(): SettingsTab[] {
      return SETTINGS_TABS;
    },
  };
}
