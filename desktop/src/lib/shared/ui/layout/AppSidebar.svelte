<script lang="ts">
  import type { AppRoute } from '$lib/shared/stores/HomeState';
  import type { NavItem } from '$lib/shared/stores/NavigationState.svelte';
  import type { MessageKey } from '../../i18n';
  import ThemeToggle from '$lib/shared/ui/navigation/ThemeToggle.svelte';
  import NotificationCenter from '$lib/shared/ui/feedback/NotificationCenter.svelte';
  import NotificationArrivalAnnouncer from '$lib/shared/ui/feedback/NotificationArrivalAnnouncer.svelte';
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

  /**
   * Collapsing the rail used to remove every label in the first frame while the
   * width still animated over 350ms, so the text popped against a moving edge.
   * The labels and wordmark stay mounted and fade with opacity, using the CSS
   * token timing (a Svelte transition cannot read `--ease-smooth`). The global
   * `prefers-reduced-motion` block zeroes the transition.
   *
   * The collapse is an IN-FLOW box (`max-w-0` + `overflow-hidden` + `opacity-0`),
   * never `position: absolute`. An absolutely positioned label paints over the
   * expand button and swallows the click, and inside a `relative` button its
   * content widens the button's scrollWidth until the rail grows a horizontal
   * scrollbar. A zero-width clipped box stays in flow, so it can neither overlay
   * a control nor widen its parent; `max-w-full` restores the natural width.
   */
  const labelFadeClass =
    'overflow-hidden whitespace-nowrap transition-[max-width,opacity] duration-(--duration-fast) ease-(--ease-smooth)';

  let profile = $derived(profileSessionFromAuthState());

  let wordmarkSrc = $derived(
    $theme === 'light' ? './nexo-horizontal-light.svg' : './nexo-horizontal-dark.svg',
  );

  /**
   * Intent groups, in the order the navigation model already returns them
   * (home, library, discover, addons, stats, highlights, settings). Grouping
   * inserts restrained separators only; it never reorders destinations.
   */
  const groupOf = (id: AppRoute): 'reading' | 'tools' | 'system' =>
    id === 'home' || id === 'library' ? 'reading' : id === 'settings' ? 'system' : 'tools';

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

{#snippet navButton(item: NavItem)}
  {@const ItemIcon = item.icon}
  <Tooltip.Root>
    <Tooltip.Trigger
      class={`relative w-full rounded-lg px-3 py-2 text-sm font-medium transition-colors ${
        collapsed ? 'flex items-center justify-center' : 'flex items-center gap-3'
      } ${
        activeRoute === item.id
          ? 'bg-(--color-accent-nav-bg) text-(--color-accent-nav-fg)'
          : 'text-(--color-text-muted) hover:bg-(--color-panel-accent) hover:text-(--color-primary)'
      }`}
      aria-label={t(item.messageKey)}
      onclick={item.action}
    >
      {#if activeRoute === item.id}
        <span
          class="absolute left-0 top-1/2 h-4 w-0.5 -translate-y-1/2 rounded-full bg-(--color-accent-nav-bar)"
          aria-hidden="true"
        ></span>
      {/if}
      <ItemIcon size={16} strokeWidth={1.8} aria-hidden="true" />
      <span
        class={labelFadeClass}
        class:max-w-0={collapsed}
        class:opacity-0={collapsed}
        class:max-w-full={!collapsed}
        class:opacity-100={!collapsed}
        aria-hidden={collapsed ? 'true' : undefined}
      >
        {t(item.messageKey)}
      </span>
    </Tooltip.Trigger>
    <!--
      The label is already visible while expanded, so the tooltip would only
      duplicate it. It is mounted only in the icon rail, where it carries the
      destination name; `aria-label` on the trigger covers both states.
    -->
    {#if collapsed}
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
    {/if}
  </Tooltip.Root>
{/snippet}

<aside
  class="sticky top-0 flex h-full shrink-0 flex-col overflow-hidden border-r border-(--color-border) bg-(--color-sidebar-bg) transition-[width] duration-(--duration-slow) max-lg:hidden"
  class:w-64={!collapsed}
  class:w-18={collapsed}
>
  <div class="flex items-center p-3 {collapsed ? 'justify-center' : ''}">
    <span
      class="shrink-0 overflow-hidden transition-[max-width,opacity] duration-(--duration-fast) ease-(--ease-smooth)"
      class:max-w-0={collapsed}
      class:opacity-0={collapsed}
      class:max-w-full={!collapsed}
      class:opacity-100={!collapsed}
      aria-hidden={collapsed ? 'true' : undefined}
    >
      <img src={wordmarkSrc} alt="NEXO" class="h-10 w-auto shrink-0" />
    </span>
    {#if collapsed}
      <button
        type="button"
        onclick={() => (collapsed = !collapsed)}
        class="flex items-center justify-center rounded-lg p-1.5 text-(--color-text-muted) transition-colors hover:bg-(--color-panel-accent) hover:text-(--color-primary) focus-visible:ring-2 ring-(--color-accent-nav-fg)"
        aria-label={t('sidebar.expand')}
      >
        <ChevronRight size={14} class="h-3.5 w-3.5" aria-hidden="true" />
      </button>
    {:else}
      <button
        type="button"
        onclick={() => (collapsed = !collapsed)}
        class="ml-auto flex shrink-0 items-center justify-center rounded-lg p-1.5 text-(--color-text-muted) transition-colors hover:bg-(--color-panel-accent) hover:text-(--color-primary) focus-visible:ring-2 ring-(--color-accent-nav-fg)"
        aria-label={t('sidebar.collapse')}
      >
        <ChevronLeft size={14} class="h-3.5 w-3.5" aria-hidden="true" />
      </button>
    {/if}
  </div>

  <nav class="min-h-0 flex-1 overflow-y-auto px-3 py-2">
    <Tooltip.Provider delayDuration={0} skipDelayDuration={0}>
      {#each navItems as item, index (item.id)}
        {#if index > 0 && groupOf(navItems[index - 1].id) !== groupOf(item.id)}
          <div class="my-2 h-px bg-(--color-border) opacity-60" aria-hidden="true"></div>
        {/if}
        {@render navButton(item)}
      {/each}

      {#if dataNavItems.length > 0}
        <div class="my-2 h-px bg-(--color-border) opacity-60" aria-hidden="true"></div>
        {#each dataNavItems as item (item.id)}
          {@render navButton(item)}
        {/each}
      {/if}
    </Tooltip.Provider>
  </nav>

  <div class="flex flex-col gap-1.5 border-t border-(--color-border) p-3">
    {#if collapsed}
      <button
        type="button"
        onclick={toggleTheme}
        class="mx-auto flex size-8 shrink-0 items-center justify-center rounded-lg border border-(--color-border) text-(--color-text-muted) transition-colors hover:bg-(--color-panel-accent) hover:text-(--color-primary) focus-visible:ring-2 ring-(--color-accent-nav-fg)"
        aria-label={$theme === 'dark' ? t('theme.switchToLight') : t('theme.switchToDark')}
      >
        {#if $theme === 'dark'}
          <Moon size={14} class="h-3.5 w-3.5" aria-hidden="true" />
        {:else}
          <Sun size={14} class="h-3.5 w-3.5" aria-hidden="true" />
        {/if}
      </button>
    {:else}
      <ThemeToggle {t} />
    {/if}

    <button
      type="button"
      onclick={openNotificationCenter}
      class="relative flex w-full items-center rounded-lg px-3 py-2 text-sm font-medium text-(--color-text-muted) transition-colors hover:bg-(--color-panel-accent) hover:text-(--color-primary) focus-visible:ring-2 ring-(--color-accent-nav-fg)"
      class:justify-center={collapsed}
      class:gap-3={!collapsed}
      aria-label={notificationCenter.unreadCount > 0
        ? t('notifications.bell.unread', { count: notificationCenter.unreadCount })
        : t('notifications.bell.label')}
    >
      <span class="relative shrink-0">
        <Bell size={16} strokeWidth={1.8} aria-hidden="true" />
        {#if notificationCenter.unreadCount > 0}
          <span
            class="absolute -top-1.5 -right-1.5 flex min-h-4 min-w-4 items-center justify-center rounded-full bg-(--color-error) px-1 text-[10px] font-bold text-(--color-background)"
            aria-hidden="true"
          >
            {notificationCenter.unreadCount > 9 ? '9+' : notificationCenter.unreadCount}
          </span>
        {/if}
      </span>
      <span
        class={labelFadeClass}
        class:max-w-0={collapsed}
        class:opacity-0={collapsed}
        class:max-w-full={!collapsed}
        class:opacity-100={!collapsed}
        aria-hidden={collapsed ? 'true' : undefined}
      >
        {t('notifications.center.title')}
      </span>
    </button>

    <div
      class="relative flex w-full cursor-pointer items-center rounded-lg p-2 transition-colors hover:bg-(--color-panel-accent) focus-visible:ring-2 ring-(--color-accent-nav-fg)"
      class:justify-center={collapsed}
      class:gap-3={!collapsed}
      role="button"
      tabindex="0"
      aria-label={`${getProfileInitials(profile.name)} ${profile.name} · ${profile.email}`}
      onclick={onNavigateSettings}
      onkeydown={handleUserBlockKeydown}
    >
      {#if authState.isAuthenticated && profile.avatarUrl}
        <img
          src={profile.avatarUrl}
          alt=""
          class="size-8 shrink-0 rounded-full object-cover"
          aria-hidden="true"
        />
      {:else}
        <div
          class="flex size-8 shrink-0 items-center justify-center rounded-full bg-(--color-accent) text-xs font-bold text-(--color-background)"
          aria-hidden="true"
        >
          {getProfileInitials(profile.name)}
        </div>
      {/if}
      <p
        class="min-w-0 truncate text-xs {labelFadeClass}"
        class:max-w-0={collapsed}
        class:opacity-0={collapsed}
        class:max-w-full={!collapsed}
        class:opacity-100={!collapsed}
        aria-hidden={collapsed ? 'true' : undefined}
      >
        <span class="font-medium text-(--color-primary)">{profile.name}</span>
        <span class="text-(--color-text-muted)"> · {profile.email}</span>
      </p>
    </div>
  </div>
</aside>

<NotificationArrivalAnnouncer {t} />
<NotificationCenter bind:open={notificationOpen} {t} />
