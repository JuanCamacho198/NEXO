<script lang="ts">
  import { GoogleLoginButton } from '$lib/features/library';
  import Dropdown from '$lib/shared/ui/navigation/Dropdown.svelte';
  import Check from 'lucide-svelte/icons/check';
  import { Button } from '$lib/shared/ui';
  import ProfileCard from './ProfileCard.svelte';
  import ConnectedDevices from './ConnectedDevices.svelte';
  import { authState } from '$lib/shared/stores/AuthState.svelte';
  import { settingsState } from '$lib/shared/stores/SettingsDomainState.svelte';
  import type { MessageKey } from '$lib/shared/i18n';
  import type { ProfileSessionViewModel } from '../profileSession';
  import type { DeviceViewModel } from '$lib/services/devices';
  import type { createSettingsProfile, DailyGoalIcon } from '../useSettingsProfile.svelte';
  import type { createSettingsLocale } from '../useSettingsLocale.svelte';
  import { setTheme, theme } from '$lib/shared/stores/theme';

  type ProfileState = ReturnType<typeof createSettingsProfile>;
  type LocaleState = ReturnType<typeof createSettingsLocale>;

  type Props = {
    t: (key: MessageKey, params?: Record<string, string | number>) => string;
    // New composable-driven API (preferred)
    profileState?: ProfileState;
    localeState?: LocaleState;
    // Legacy scalars (fallback)
    profile?: ProfileSessionViewModel;
    isProfileLoading?: boolean;
    profileError?: string | null;
    locale?: string;
    localeOptions?: { value: string; label: string }[];
    onLocaleChange?: (value: string) => void;
    isSigningOut?: boolean;
    onSignOut?: () => void;
    devices?: DeviceViewModel[];
    devicesError?: string | null;
    devicesLoading?: boolean;
    onRemoveDevice?: (id: string) => void;
    selectedDailyGoal?: number;
    dailyGoalCards?: {
      value: number;
      labelKey: MessageKey;
      shortLabel: string;
      icon: DailyGoalIcon;
      minutesLabel: string;
    }[];
    isSavingDailyGoal?: boolean;
    onSelectDailyGoal?: (value: number) => void;
    onSaveDailyGoal?: () => void;
    settingsUnavailable?: string | null;
    settingsError?: string | null;
  };

  let {
    t,
    profileState,
    localeState,
    profile: legacyProfile,
    isProfileLoading: legacyProfileLoading,
    profileError: legacyProfileError,
    locale: legacyLocale,
    localeOptions: legacyLocaleOptions,
    onLocaleChange: legacyOnLocaleChange,
    isSigningOut: legacySigningOut,
    onSignOut: legacyOnSignOut,
    devices: legacyDevices,
    devicesError: legacyDevicesError,
    devicesLoading: legacyDevicesLoading,
    onRemoveDevice: legacyOnRemoveDevice,
    selectedDailyGoal: legacySelectedGoal,
    dailyGoalCards: legacyCards,
    isSavingDailyGoal: legacySavingGoal,
    onSelectDailyGoal: legacyOnSelectGoal,
    onSaveDailyGoal: legacyOnSaveGoal,
    settingsUnavailable: legacyUnavailable,
    settingsError: legacyError,
  }: Props = $props();

  const profile = $derived(profileState?.profile ?? legacyProfile!);
  const isProfileLoading = $derived(
    profileState?.isProfileLoading ?? legacyProfileLoading ?? false,
  );
  const profileError = $derived(profileState?.profileError ?? legacyProfileError ?? null);
  const locale = $derived((localeState?.locale as string) ?? legacyLocale ?? 'es');
  const localeOptions = $derived(
    legacyLocaleOptions ?? [
      { value: 'es', label: t('settings.languageSpanish') },
      { value: 'en', label: t('settings.languageEnglish') },
    ],
  );
  const isSigningOut = $derived(profileState?.isSigningOut ?? legacySigningOut ?? false);
  const devices = $derived(profileState?.devicesState.devices ?? legacyDevices ?? []);
  const devicesError = $derived(profileState?.devicesState.error ?? legacyDevicesError ?? null);
  const devicesLoading = $derived(
    profileState?.devicesState.isLoading ?? legacyDevicesLoading ?? false,
  );
  const selectedDailyGoal = $derived(profileState?.selectedDailyGoal ?? legacySelectedGoal ?? 20);
  const dailyGoalCards = $derived(profileState?.dailyGoalCards ?? legacyCards ?? []);
  const isSavingDailyGoal = $derived(profileState?.isSavingDailyGoal ?? legacySavingGoal ?? false);
  const settingsUnavailable = $derived(
    localeState?.settingsUnavailable ?? legacyUnavailable ?? null,
  );
  const settingsError = $derived(localeState?.settingsError ?? legacyError ?? null);

  function handleLocaleChange(value: string): void {
    if (localeState) void localeState.handleLocaleSelect(value);
    else legacyOnLocaleChange?.(value);
  }
  function handleSignOut(): void {
    if (profileState) void profileState.handleSignOut();
    else legacyOnSignOut?.();
  }
  function handleRemoveDevice(id: string): void {
    if (profileState) void profileState.devicesState.remove(id, authState.userId!);
    else legacyOnRemoveDevice?.(id);
  }
  function handleSelectDailyGoal(v: number): void {
    if (profileState) profileState.handleSelectDailyGoal(v);
    else legacyOnSelectGoal?.(v);
  }
  function handleSaveDailyGoal(): void {
    if (profileState) void profileState.handleSaveDailyGoal();
    else legacyOnSaveGoal?.();
  }
</script>

<div
  role="tabpanel"
  id="tabpanel-cuenta"
  aria-labelledby="tab-cuenta"
  class="flex-1 overflow-y-auto p-4 flex flex-col gap-4"
>
  <section class="rounded-xl border border-(--color-border) bg-(--color-surface) overflow-visible">
    <div class="p-4 border-b border-(--color-border) last:border-b-0">
      <h3 class="mt-0 mb-2 text-sm font-semibold text-(--color-primary)">
        {t('settings.authentication')}
      </h3>
      <p class="text-2xs text-(--color-text-muted) mb-3">
        {t('settings.authDescription')}
      </p>
      <GoogleLoginButton {t} />
      {#if authState.isAuthenticated}
        <Button
          variant="danger"
          disabled={isSigningOut}
          onclick={handleSignOut}
          class="w-full flex items-center justify-center gap-2 px-3 py-2 text-sm mt-4"
        >
          {isSigningOut ? t('welcome.signingOut') : t('welcome.signOut')}
        </Button>
      {/if}
      {#if settingsUnavailable}
        <p
          class="mb-2 rounded border border-amber-300 bg-amber-50 px-2 py-1 text-xs text-amber-900"
        >
          {settingsUnavailable}
        </p>
      {/if}
      {#if settingsError}
        <p class="mb-2 rounded border border-red-300 bg-red-50 px-2 py-1 text-xs text-red-900">
          {settingsError}
        </p>
      {/if}
    </div>
    <div class="p-4 border-b border-(--color-border) last:border-b-0">
      <ProfileCard {profile} {isProfileLoading} {profileError} {t} />
    </div>
    {#if authState.isAuthenticated && authState.userId}
      <div class="p-4 border-b border-(--color-border) last:border-b-0">
        <h3 class="mt-0 mb-2 text-sm font-semibold text-(--color-primary)">
          {t('settings.connectedDevices.title')}
        </h3>
        <ConnectedDevices
          {devices}
          error={devicesError}
          isLoading={devicesLoading}
          onremove={handleRemoveDevice}
          {t}
        />
      </div>
    {/if}
    <div class="p-4 flex flex-col gap-4">
      <div>
        <span class="mb-1 block text-xs text-(--color-text-muted)">{t('settings.language')}</span>
        <Dropdown
          options={localeOptions}
          value={locale}
          class="w-full"
          onchange={({ value }) => handleLocaleChange(value)}
        />
      </div>
      <div>
        <span class="mb-1 block text-xs text-(--color-text-muted)">{t('settings.theme')}</span>
        <div class="flex gap-2" role="group" aria-label={t('settings.theme')}>
          <button
            type="button"
            class="flex-1 cursor-pointer rounded-lg border px-3 py-2 text-sm transition-all duration-200 {$theme ===
            'light'
              ? 'border-(--color-primary) text-(--color-primary)'
              : 'border-(--color-border) text-(--color-text-muted)'}"
            aria-pressed={$theme === 'light'}
            onclick={() => setTheme('light')}
          >
            {t('settings.theme.light')}
          </button>
          <button
            type="button"
            class="flex-1 cursor-pointer rounded-lg border px-3 py-2 text-sm transition-all duration-200 {$theme ===
            'dark'
              ? 'border-(--color-primary) text-(--color-primary)'
              : 'border-(--color-border) text-(--color-text-muted)'}"
            aria-pressed={$theme === 'dark'}
            onclick={() => setTheme('dark')}
          >
            {t('settings.theme.dark')}
          </button>
        </div>
      </div>
    </div>
  </section>

  <section
    class="mx-auto flex w-full max-w-[896px] flex-col gap-10 rounded-[24px] border border-[#1c2744] bg-[#161f335c] p-12 backdrop-blur-[10.5px]"
    aria-label={t('settings.daily_goal_title')}
  >
    <div class="flex flex-col items-center gap-2 text-center">
      <h3 class="text-[32px] font-bold leading-none text-[#d8e2ff]">
        {t('settings.daily_goal_title')}
      </h3>
      <p class="text-sm text-[#d8e2ff]/70">
        {t('settings.daily_goal_description')}
      </p>
      <p class="text-xs text-[#d8e2ff]/60">
        {t('stats.goalProgress', {
          current: String(settingsState.dailyGoalMinutes),
          goal: String(settingsState.dailyGoalMinutes),
          percent: '100',
        })} · {settingsState.dailyGoalMinutes} min
      </p>
    </div>

    <div class="grid grid-cols-2 gap-6">
      {#each dailyGoalCards as card}
        {@const CardIcon = card.icon}
        <button
          type="button"
          class="relative flex flex-col gap-3 rounded-xl border-2 p-8 text-left transition-all duration-200 {selectedDailyGoal ===
          card.value
            ? 'border-[#d8e2ff] bg-[#d8e2ff]/12'
            : 'border-[#2a3655] bg-[#161f33]'}"
          onclick={() => handleSelectDailyGoal(card.value)}
          aria-pressed={selectedDailyGoal === card.value}
          aria-label={`${card.shortLabel} ${card.minutesLabel}`}
        >
          {#if selectedDailyGoal === card.value}
            <span
              class="absolute right-3 top-3 flex h-6 w-6 items-center justify-center rounded-full bg-[#d8e2ff] text-[#161f33]"
            >
              <Check size={14} strokeWidth={1.8} class="h-3.5 w-3.5" aria-hidden="true" />
            </span>
          {/if}
          <span
            class="flex h-10 w-10 items-center justify-center rounded-full bg-[#d8e2ff]/10 text-[#d8e2ff]"
          >
            <CardIcon size={20} strokeWidth={1.8} class="h-5 w-5" aria-hidden="true" />
          </span>
          <span class="flex flex-col gap-1">
            <span class="text-sm font-semibold text-[#d8e2ff]">{t(card.labelKey)}</span>
            <span class="text-xs text-[#d8e2ff]/60">{card.minutesLabel}</span>
          </span>
        </button>
      {/each}
    </div>

    <div class="flex flex-col items-center gap-3">
      <button
        type="button"
        class="rounded-full bg-[#d8e2ff] px-10 py-4 text-sm font-semibold text-[#161f33] transition-opacity hover:opacity-90 disabled:opacity-60 disabled:cursor-not-allowed"
        onclick={handleSaveDailyGoal}
        disabled={isSavingDailyGoal}
      >
        {isSavingDailyGoal ? t('settings.saving') : t('settings.daily_goal_set')}
      </button>
      <p class="text-xs text-[#d8e2ff]/50">
        {settingsState.dailyGoalMinutes} min · {t('settings.daily_goal_description')}
      </p>
    </div>
  </section>
</div>
