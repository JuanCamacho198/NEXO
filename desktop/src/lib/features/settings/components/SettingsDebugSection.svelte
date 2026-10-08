<script lang="ts">
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

<Panel title={t('settings.debug.title')}>
  <div class="flex flex-col gap-3">
    <p class="text-xs text-(--color-text-muted)">{t('settings.debug.description')}</p>
    <Button
      variant="secondary"
      size="sm"
      role="switch"
      aria-checked={debugEnabled}
      data-testid="debug-enabled-switch"
      class="self-start gap-2.5"
      onclick={() => void onToggle()}
    >
      <span
        class={`w-9 h-5 rounded-full relative transition-colors ${debugEnabled ? 'bg-(--color-success)' : 'bg-(--color-border)'}`}
      >
        <span
          class={`absolute top-0.5 w-4 h-4 rounded-full bg-white transition-all ${debugEnabled ? 'left-4.5' : 'left-0.5'}`}
        ></span>
      </span>
      <span>{t('settings.debug.showDebug')}</span>
      <span class="text-xs text-(--color-text-muted)">
        {debugEnabled ? t('settings.debug.debugOn') : t('settings.debug.debugOff')}
      </span>
    </Button>
  </div>
</Panel>
