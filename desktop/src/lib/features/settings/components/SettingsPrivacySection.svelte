<script lang="ts">
  import * as Sentry from '@sentry/browser';
  import Panel from '$lib/shared/ui/layout/Panel.svelte';
  import { getSentrySettings } from '$lib/shared/logger/sentryConfig';
  import type { MessageKey } from '$lib/shared/i18n';

  type Props = { t: (key: MessageKey) => string };
  let { t }: Props = $props();

  const SETTINGS_DSN_KEY = 'sentry.dsn';

  let telemetryEnabled = $state(false);

  $effect(() => {
    void getSentrySettings().then((settings) => {
      telemetryEnabled = settings.enabled && !!settings.dsn;
    });
  });

  const persistEnabled = (enabled: boolean): void => {
    try {
      const raw = localStorage.getItem(SETTINGS_DSN_KEY);
      const parsed = raw ? (JSON.parse(raw) as Record<string, unknown>) : {};
      parsed.enabled = enabled;
      localStorage.setItem(SETTINGS_DSN_KEY, JSON.stringify(parsed));
    } catch {
      // ignore storage failures
    }
  };

  const onToggle = async (): Promise<void> => {
    const next = !telemetryEnabled;
    telemetryEnabled = next;
    persistEnabled(next);
    if (!next) {
      // Stop egress immediately in this session; the sink is not registered on
      // the next launch (and re-checks `enabled` per-emit either way).
      await Sentry.close(2000);
    }
  };
</script>

<Panel title={t('settings.privacy.title')}>
  <div class="flex flex-col gap-3">
    <p class="text-xs text-(--color-text-muted)">{t('settings.privacy.description')}</p>
    <button
      type="button"
      role="switch"
      aria-checked={telemetryEnabled}
      class="self-start flex items-center gap-2.5 px-3 py-2 rounded-lg border cursor-pointer transition-all duration-200 text-xs disabled:opacity-60 disabled:cursor-not-allowed border-(--color-border) bg-(--color-background) hover:bg-(--color-surface-hover) focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-(--color-accent)"
      onclick={onToggle}
    >
      <span
        class={`w-9 h-5 rounded-full relative transition-colors ${telemetryEnabled ? 'bg-green-500' : 'bg-(--color-border)'}`}
      >
        <span
          class={`absolute top-0.5 w-4 h-4 rounded-full bg-white transition-all ${telemetryEnabled ? 'left-4.5' : 'left-0.5'}`}
        ></span>
      </span>
      <span>{t('settings.privacy.sendTelemetry')}</span>
      <span class="text-(--color-text-muted)">
        {telemetryEnabled ? t('settings.privacy.telemetryOn') : t('settings.privacy.telemetryOff')}
      </span>
    </button>
  </div>
</Panel>
