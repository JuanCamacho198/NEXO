<script lang="ts">
  import SwitchTrack from '$lib/shared/ui/forms/SwitchTrack.svelte';
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

<Panel title={t('settings.privacy.title')} subtitle={t('settings.privacy.description')}>
  <Button
    variant="ghost"
    size="md"
    role="switch"
    aria-checked={telemetryEnabled}
    data-testid="privacy-telemetry-switch"
    class="-mx-4 w-[calc(100%+2rem)] gap-4"
    onclick={() => void onToggle()}
  >
    <span class="flex min-w-0 flex-1 flex-col items-start gap-0.5 text-left whitespace-normal">
      <span class="text-(--color-primary)">{t('settings.privacy.sendTelemetry')}</span>
      <span class="text-xs font-normal text-(--color-text-muted)"
        >{telemetryEnabled
          ? t('settings.privacy.telemetryOn')
          : t('settings.privacy.telemetryOff')}</span
      >
    </span>
    <SwitchTrack checked={telemetryEnabled} />
  </Button>
</Panel>
