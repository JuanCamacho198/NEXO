<script lang="ts">
  import { GoogleLoginButton } from '$lib/features/library';
  import Dropdown from '$lib/shared/ui/navigation/Dropdown.svelte';
  import { Button } from '$lib/shared/ui';
  import Panel from '$lib/shared/ui/layout/Panel.svelte';
  import ProfileCard from './ProfileCard.svelte';
  import ConnectedDevices from './ConnectedDevices.svelte';
  import { authState } from '$lib/shared/stores/AuthState.svelte';
  import { settingsState } from '$lib/shared/stores/SettingsDomainState.svelte';
  import type { MessageKey } from '$lib/shared/i18n';
  import type { ProfileSessionViewModel } from '../profileSession';
  import type { DeviceViewModel } from '$lib/services/devices';
  import type { createSettingsProfile } from '../useSettingsProfile.svelte';
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

  // Daily goal — collapsed row + inline chips. The row always reports the
  // persisted value; the chips highlight the pending selection. Without a
  // session the goal is still editable and is stored on this device, so the
  // control is never disabled; `isDailyGoalLocal` only drives the hint.
  const isDailyGoalLocal = $derived(profileState?.isDailyGoalLocal ?? false);
  const savedDailyGoalMinutes = $derived(settingsState.dailyGoalMinutes);
  let isGoalEditorOpen = $state(false);

  const saveStatusMessage = $derived(
    isSavingDailyGoal
      ? t('settings.saving')
      : profileState?.dailyGoalSaveState === 'success'
        ? t('settings.daily_goal_saved')
        : profileState?.dailyGoalSaveState === 'error'
          ? t('settings.daily_goal_save_error')
          : '',
  );
  const isSaveStatusError = $derived(
    !isSavingDailyGoal && profileState?.dailyGoalSaveState === 'error',
  );

  // The goal trigger is an atom (a component instance cannot take
  // `bind:this` as an element), so focus moves through its forwarded id.
  const GOAL_TRIGGER_ID = 'daily-goal-trigger';
  function focusGoalTrigger(): void {
    document.getElementById(GOAL_TRIGGER_ID)?.focus();
  }

  function handleLocaleChange(value: string): void {
    if (localeState) void localeState.handleLocaleSelect(value);
    else legacyOnLocaleChange?.(value);
  }
  function handleSignOut(): void {
    if (!confirm(t('settings.signOutConfirm'))) return;
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

  function toggleGoalEditor(): void {
    isGoalEditorOpen = !isGoalEditorOpen;
  }

  async function chooseGoal(value: number): Promise<void> {
    // Collapse first so focus is never lost with the removed chips.
    isGoalEditorOpen = false;
    focusGoalTrigger();
    if (profileState) {
      await profileState.applyDailyGoal(value);
    } else {
      handleSelectDailyGoal(value);
      handleSaveDailyGoal();
    }
  }

  function handleGoalOptionsKeydown(event: KeyboardEvent): void {
    if (event.key === 'Escape') {
      event.preventDefault();
      isGoalEditorOpen = false;
      focusGoalTrigger();
    }
  }
</script>

<div
  role="tabpanel"
  id="tabpanel-cuenta"
  aria-labelledby="tab-cuenta"
  class="relative flex-1 overflow-y-auto p-4 flex flex-col gap-4"
>
  <section class="space-y-5 w-full max-w-none">
    <header class="flex flex-col gap-1">
      <h1 class="text-3xl font-semibold tracking-tight text-(--color-primary)">
        {t('settings.tab.account')}
      </h1>
      <p class="text-sm text-(--color-text-muted)">{t('settings.account.subtitle')}</p>
    </header>

    <Panel title={t('settings.authentication')} subtitle={t('settings.authDescription')}>
      <GoogleLoginButton {t} />
      {#if authState.isAuthenticated}
        <Button
          variant="danger"
          size="md"
          fullWidth
          class="mt-4"
          disabled={isSigningOut}
          onclick={handleSignOut}
        >
          {isSigningOut ? t('welcome.signingOut') : t('welcome.signOut')}
        </Button>
      {/if}
      {#if settingsUnavailable}
        <p
          class="mb-2 rounded border border-(--color-warning)/40 bg-(--color-warning)/10 px-2 py-1 text-xs text-(--color-primary)"
        >
          {settingsUnavailable}
        </p>
      {/if}
      {#if settingsError}
        <p
          class="mb-2 rounded border border-(--color-error)/40 bg-(--color-error-soft) px-2 py-1 text-xs text-(--color-primary)"
        >
          {settingsError}
        </p>
      {/if}
    </Panel>

    <Panel>
      <ProfileCard {profile} {isProfileLoading} {profileError} {t} />
    </Panel>

    {#if authState.isAuthenticated && authState.userId}
      <!-- Device rows are self-bordered cards, so this group stays borderless to keep one border level. -->
      <div class="space-y-3">
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

    <Panel>
      <div class="flex flex-col gap-4">
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
          <!-- Segmented pair: the active side takes the filled primary tone,
               the other stays secondary. Selection is also in `aria-pressed`. -->
          <div class="flex gap-2" role="group" aria-label={t('settings.theme')}>
            <Button
              variant={$theme === 'light' ? 'primary' : 'secondary'}
              size="sm"
              class="flex-1"
              aria-pressed={$theme === 'light'}
              onclick={() => setTheme('light')}
            >
              {t('settings.theme.light')}
            </Button>
            <Button
              variant={$theme === 'dark' ? 'primary' : 'secondary'}
              size="sm"
              class="flex-1"
              aria-pressed={$theme === 'dark'}
              onclick={() => setTheme('dark')}
            >
              {t('settings.theme.dark')}
            </Button>
          </div>
        </div>
      </div>
    </Panel>

    <!-- No Panel title: the h3 below must stay an h3 (heading-order test).
         Padding is none because the row keeps its own inner spacing. -->
    <Panel aria-label={t('settings.daily_goal_label')} padding="none">
      <div class="flex items-center justify-between gap-3 p-4">
        <h3 class="m-0 min-w-0 text-sm font-medium text-(--color-primary)">
          {t('settings.daily_goal_label')}
          <span class="font-normal text-(--color-text-muted)"> · {savedDailyGoalMinutes} min</span>
        </h3>
        <Button
          variant="secondary"
          size="sm"
          id={GOAL_TRIGGER_ID}
          aria-expanded={isGoalEditorOpen}
          aria-controls="daily-goal-options"
          onclick={toggleGoalEditor}
        >
          {isGoalEditorOpen ? t('settings.daily_goal_close') : t('settings.daily_goal_change')}
        </Button>
      </div>

      {#if isDailyGoalLocal}
        <p class="m-0 px-4 pb-4 text-xs text-(--color-text-muted)">
          {t('settings.daily_goal_local_hint')}
        </p>
      {/if}

      {#if isGoalEditorOpen}
        <!-- svelte-ignore a11y_no_noninteractive_element_interactions -->
        <div
          id="daily-goal-options"
          class="flex flex-wrap gap-2 border-t border-(--color-border) p-4"
          role="group"
          aria-label={t('settings.daily_goal_label')}
          onkeydown={handleGoalOptionsKeydown}
        >
          {#each dailyGoalCards as card}
            <!-- The pending pick takes the filled primary tone; the rest stay
                 secondary. The old pill shape has no atom equivalent, so the
                 chips intentionally render at the atom's radius. -->
            <Button
              variant={selectedDailyGoal === card.value ? 'primary' : 'secondary'}
              size="sm"
              aria-pressed={selectedDailyGoal === card.value}
              onclick={() => void chooseGoal(card.value)}
            >
              {t(card.labelKey)}
              <span> · {card.minutesLabel}</span>
            </Button>
          {/each}
        </div>
      {/if}

      <p
        role="status"
        aria-live="polite"
        class="m-0 text-xs {saveStatusMessage ? 'px-4 pb-4' : 'sr-only'}"
        class:text-(--color-error)={isSaveStatusError}
        class:text-(--color-text-muted)={!isSaveStatusError}
      >
        {saveStatusMessage}
      </p>
    </Panel>
  </section>
</div>
