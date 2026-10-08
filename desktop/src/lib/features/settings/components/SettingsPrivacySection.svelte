<script lang="ts">
  import * as Sentry from '@sentry/browser';
  import Button from '$lib/shared/ui/forms/Button.svelte';
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
    <Button
      variant="secondary"
      size="sm"
      role="switch"
      aria-checked={telemetryEnabled}
      data-testid="privacy-telemetry-switch"
      class="self-start gap-2.5"
      onclick={() => void onToggle()}
    >
      <span
        class={`w-9 h-5 rounded-full relative transition-colors ${telemetryEnabled ? 'bg-(--color-success)' : 'bg-(--color-border)'}`}
      >
        <span
          class={`absolute top-0.5 w-4 h-4 rounded-full bg-white transition-all ${telemetryEnabled ? 'left-4.5' : 'left-0.5'}`}
        ></span>
      </span>
      <span>{t('settings.privacy.sendTelemetry')}</span>
      <span class="text-xs text-(--color-text-muted)">
        {telemetryEnabled ? t('settings.privacy.telemetryOn') : t('settings.privacy.telemetryOff')}
      </span>
    </Button>
  </div>
</Panel>
