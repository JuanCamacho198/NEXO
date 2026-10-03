<script lang="ts">
  import type { AppRoute } from '$lib/shared/stores/HomeState';
  import type { NavItem } from '$lib/shared/stores/NavigationState.svelte';
  import type { MessageKey } from '../../i18n';
  import ThemeToggle from '$lib/shared/ui/navigation/ThemeToggle.svelte';
  import NotificationCenter from '$lib/shared/ui/feedback/NotificationCenter.svelte';
  import { notificationCenter, markAllRead } from '$lib/shared/stores/notificationCenter.svelte';
  import { Tooltip } from 'bits-ui';
  import Bell from 'lucide-svelte/icons/bell';
  import ChevronLeft from 'lucide-svelte/icons/chevron-left';
  import ChevronRight from 'lucide-svelte/icons/chevron-right';
  import Moon from 'lucide-svelte/icons/moon';
  import Sun from 'lucide-svelte/icons/sun';
  import { theme, toggleTheme } from '$lib/shared/stores/theme';
  import { authState } from '$lib/shared/stores/AuthState.svelte';
  import {
    profileSessionFromAuthState,
    getProfileInitials,
  } from '$lib/features/settings/profileSession';

  type Props = {
    activeRoute: AppRoute;
    navItems: NavItem[];
    dataNavItems?: NavItem[];
    t: (key: MessageKey, params?: Record<string, string | number>) => string;
    onNavigateSettings?: () => void;
  };

  let { activeRoute, navItems, dataNavItems = [], t, onNavigateSettings }: Props = $props();

  let collapsed = $state(false);

  let notificationOpen = $state(false);

  let profile = $derived(profileSessionFromAuthState());

  let wordmarkSrc = $derived(
    $theme === 'light' ? './nexo-horizontal-light.svg' : './nexo-horizontal-dark.svg',
  );

  const handleUserBlockKeydown = (event: KeyboardEvent): void => {
    if (event.key === 'Enter' || event.key === ' ') {
      event.preventDefault();
      onNavigateSettings?.();
    }
  };

  const openNotificationCenter = (): void => {
    notificationOpen = true;
    markAllRead();
  };
</script>

<aside
  class="sticky top-0 h-full shrink-0 border-r border-(--color-border) bg-[rgba(12,20,32,0.6)] backdrop-blur-xl max-lg:hidden lg:flex lg:flex-col transition-all duration-300"
  class:w-64={!collapsed}
  class:w-18={collapsed}
>
  <div class="flex items-center justify-center p-4 pb-2">
    {#if collapsed}
      <button
        onclick={() => (collapsed = !collapsed)}
        class="flex items-center justify-center rounded-lg p-1.5 text-(--color-text-muted) hover:bg-(--color-panel-accent) hover:text-(--color-primary) transition-colors"
        aria-label={t('sidebar.expand')}
      >
        <ChevronRight size={14} class="h-3.5 w-3.5" aria-hidden="true" />
      </button>
    {:else}
      <div class="flex items-center gap-3 w-full">
        <img src={wordmarkSrc} alt="NEXO" class="h-13 w-auto shrink-0" />
        <button
          onclick={() => (collapsed = !collapsed)}
          class="ml-auto flex items-center justify-center rounded-lg p-1.5 text-(--color-text-muted) hover:bg-(--color-panel-accent) hover:text-(--color-primary) transition-colors shrink-0"
          aria-label={t('sidebar.collapse')}
        >
          <ChevronLeft size={14} class="h-3.5 w-3.5" aria-hidden="true" />
        </button>
      </div>
    {/if}
  </div>

  <nav class="flex-1 space-y-1 overflow-y-auto p-4">
    <Tooltip.Provider delayDuration={0} skipDelayDuration={0}>
      {#each navItems as item}
        {@const ItemIcon = item.icon}
        <Tooltip.Root>
          <Tooltip.Trigger
            class={`w-full rounded-xl px-3 py-2.5 text-sm font-medium transition-all duration-200 ${
              collapsed ? 'flex items-center justify-center' : 'flex items-center gap-3'
            } ${
              activeRoute === item.id || (activeRoute === 'home' && item.id === 'home')
                ? 'bg-(--color-accent-blue) text-(--color-background) shadow-(--shadow-glow)'
                : 'text-(--color-text-muted) hover:bg-(--color-panel-accent) hover:text-(--color-primary)'
            }`}
            onclick={item.action}
          >
            <ItemIcon size={16} strokeWidth={1.8} aria-hidden="true" />
            {#if !collapsed}
              {t(item.messageKey)}
            {/if}
          </Tooltip.Trigger>
          <Tooltip.Portal>
            <Tooltip.Content
              role="tooltip"
              side="right"
              sideOffset={8}
              class="z-50 whitespace-nowrap rounded bg-(--color-surface) p-1 text-xs text-(--color-primary) shadow-[0_2px_8px_rgba(0,0,0,0.2)]"
            >
              {t(item.messageKey)}
            </Tooltip.Content>
          </Tooltip.Portal>
        </Tooltip.Root>
      {/each}

      {#if dataNavItems.length > 0}
        <div class="mt-4 pt-3 border-t border-(--color-border)/50">
          {#each dataNavItems as item}
            {@const ItemIcon = item.icon}
            <Tooltip.Root>
              <Tooltip.Trigger
                class={`w-full rounded-xl px-3 py-2.5 text-sm font-medium transition-all duration-200 ${
                  collapsed ? 'flex items-center justify-center' : 'flex items-center gap-3'
                } ${
                  activeRoute === item.id
                    ? 'bg-(--color-accent-blue) text-(--color-background) shadow-(--shadow-glow)'
                    : 'text-(--color-text-muted) hover:bg-(--color-panel-accent) hover:text-(--color-primary)'
                }`}
                onclick={item.action}
              >
                <ItemIcon size={16} strokeWidth={1.8} aria-hidden="true" />
                {#if !collapsed}
                  {t(item.messageKey)}
                {/if}
              </Tooltip.Trigger>
              <Tooltip.Portal>
                <Tooltip.Content
                  role="tooltip"
                  side="right"
                  sideOffset={8}
                  class="z-50 whitespace-nowrap rounded bg-(--color-surface) p-1 text-xs text-(--color-primary) shadow-[0_2px_8px_rgba(0,0,0,0.2)]"
                >
                  {t(item.messageKey)}
                </Tooltip.Content>
              </Tooltip.Portal>
            </Tooltip.Root>
          {/each}
        </div>
      {/if}
    </Tooltip.Provider>
  </nav>

  <div class="p-4 border-t border-(--color-border) flex flex-col gap-2">
    <!-- Theme toggle button -->
    {#if collapsed}
      <button
        onclick={toggleTheme}
        class="flex items-center justify-center rounded-lg size-8 bg-transparent border border-(--color-border) text-(--color-text-muted) hover:bg-(--color-panel-accent) hover:text-(--color-primary) transition-all duration-200 shrink-0"
        aria-label={$theme === 'dark' ? 'Cambiar a tema claro' : 'Cambiar a tema oscuro'}
      >
        {#if $theme === 'dark'}
          <Moon size={14} class="h-3.5 w-3.5" aria-hidden="true" />
        {:else}
          <Sun size={14} class="h-3.5 w-3.5" aria-hidden="true" />
        {/if}
      </button>
    {:else}
      <ThemeToggle />
    {/if}

    <!-- Notification bell (FR-DN2): next to the profile block, badge while unread exist -->
    <button
      onclick={openNotificationCenter}
      class="relative flex w-full items-center gap-3 rounded-xl px-3 py-2.5 text-sm font-medium text-(--color-text-muted) transition-all duration-200 hover:bg-(--color-panel-accent) hover:text-(--color-primary)"
      class:justify-center={collapsed}
      aria-label={t('notifications.bell.label')}
    >
      <span class="relative shrink-0">
        <Bell size={16} strokeWidth={1.8} aria-hidden="true" />
        {#if notificationCenter.unreadCount > 0}
          <span
            class="absolute -top-1.5 -right-1.5 flex min-h-4 min-w-4 items-center justify-center rounded-full bg-red-500 px-1 text-[10px] font-bold text-white"
            aria-hidden="true"
          >
            {notificationCenter.unreadCount > 9 ? '9+' : notificationCenter.unreadCount}
          </span>
        {/if}
      </span>
      {#if !collapsed}
        {t('notifications.center.title')}
      {/if}
    </button>

    <!-- User section (REQ-12): clickable → Settings → Cuenta -->
    <div
      class="w-full flex items-center rounded-xl p-3 cursor-pointer transition-colors hover:bg-(--color-panel-accent)"
      class:justify-between={!collapsed}
      class:justify-center={collapsed}
      class:bg-(--color-surface)={!collapsed}
      class:border={!collapsed}
      class:border-(--color-border)={!collapsed}
      role="button"
      tabindex="0"
      onclick={onNavigateSettings}
      onkeydown={handleUserBlockKeydown}
    >
      <div class="flex items-center gap-3">
        {#if authState.isAuthenticated && profile.avatarUrl}
          <img
            src={profile.avatarUrl}
            alt={profile.name}
            class="size-8 shrink-0 rounded-full object-cover"
          />
        {:else}
          <div
            class="flex h-8 w-8 shrink-0 items-center justify-center rounded-full bg-blue-600 text-xs font-bold text-white"
          >
            {getProfileInitials(profile.name)}
          </div>
        {/if}
        {#if !collapsed}
          <div class="text-left">
            <p class="text-sm font-medium text-(--color-primary) truncate max-w-36">
              {profile.name}
            </p>
            <p class="text-xs text-(--color-text-muted) truncate max-w-36">{profile.email}</p>
          </div>
        {/if}
      </div>
    </div>
  </div>
</aside>

<NotificationCenter bind:open={notificationOpen} {t} />
