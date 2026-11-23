<script lang="ts">
  import SwitchTrack from '$lib/shared/ui/forms/SwitchTrack.svelte';
  import Button from '$lib/shared/ui/forms/Button.svelte';
  import Panel from '$lib/shared/ui/layout/Panel.svelte';
  import { debugState } from '$lib/shared/debug/debugState.svelte';
  import {
    getCachedDebugEnabled,
    loadDebugEnabled,
    setDebugEnabled,
  } from '$lib/shared/services/debugPreferences';
  import type { MessageKey } from '$lib/shared/i18n';

  type Props = { t: (key: MessageKey) => string };
  let { t }: Props = $props();

  let debugEnabled = $state(getCachedDebugEnabled());

  $effect(() => {
    void loadDebugEnabled().then((loaded) => {
      debugEnabled = loaded;
      debugState.enabled = loaded;
    });
  });

  async function onToggle(): Promise<void> {
    const next = !debugEnabled;
    debugEnabled = next;
    debugState.enabled = next;
    await setDebugEnabled(next);
  }
</script>

<Panel title={t('settings.debug.title')} subtitle={t('settings.debug.description')}>
  <Button
    variant="ghost"
    size="md"
    role="switch"
    aria-checked={debugEnabled}
    data-testid="debug-enabled-switch"
    class="-mx-4 w-[calc(100%+2rem)] gap-4"
    onclick={() => void onToggle()}
  >
    <span class="flex min-w-0 flex-1 flex-col items-start gap-0.5 text-left whitespace-normal">
      <span class="text-(--color-primary)">{t('settings.debug.showDebug')}</span>
      <span class="text-xs font-normal text-(--color-text-muted)"
        >{debugEnabled ? t('settings.debug.debugOn') : t('settings.debug.debugOff')}</span
      >
    </span>
    <SwitchTrack checked={debugEnabled} />
  </Button>
</Panel>
