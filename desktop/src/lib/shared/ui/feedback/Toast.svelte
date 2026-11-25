<script lang="ts">
  import type { Snippet } from 'svelte';
  import { fly } from 'svelte/transition';
  import { cubicOut } from 'svelte/easing';
  import CircleAlert from 'lucide-svelte/icons/circle-alert';
  import CircleCheck from 'lucide-svelte/icons/circle-check';
  import Info from 'lucide-svelte/icons/info';
  import X from 'lucide-svelte/icons/x';
  import type { MessageKey } from '$lib/shared/i18n';
  import { i18n } from '$lib/shared/i18n';

  let locale = $state(i18n?.DEFAULT_LOCALE ?? 'es');
  $effect(() => {
    if (!i18n?.locale) return;
    const unsub = i18n.locale.subscribe((l) => {
      locale = l;
    });
    return () => unsub();
  });
  const tFn = (key: MessageKey): string => i18n?.t?.(locale, key) ?? key;

  type Props = {
    type?: 'success' | 'info' | 'error';
    message: string;
    dismissible?: boolean;
    visible?: boolean;
    action?: Snippet;
    onDismiss?: () => void;
    /**
     * `true` when a host stacks several toasts (ToastHost): the toast is laid
     * out in the host's column. A standalone toast positions itself.
     */
    stacked?: boolean;
    class?: string;
  };

  let {
    type = 'info',
    message,
    dismissible = true,
    visible = $bindable(false),
    action,
    onDismiss,
    stacked = false,
    class: className = '',
  }: Props = $props();

  // The icon carries the tone; the card stays the same navy surface for every
  // type, so a toast reads as part of the app rather than a browser alert.
  const tones = {
    success: { icon: CircleCheck, color: 'text-(--color-success)' },
    info: { icon: Info, color: 'text-(--color-brand-text)' },
    error: { icon: CircleAlert, color: 'text-(--color-warning)' },
  };
  const tone = $derived(tones[type]);

  let timeoutId: ReturnType<typeof setTimeout> | null = null;

  $effect(() => {
    if (visible && !dismissible) {
      return;
    }
    if (visible) {
      if (timeoutId) clearTimeout(timeoutId);
      timeoutId = setTimeout(() => {
        handleDismiss();
      }, 5000);
    }
  });

  function handleDismiss(): void {
    visible = false;
    timeoutId = null;
    onDismiss?.();
  }
</script>

{#if visible}
  {@const ToneIcon = tone.icon}
  <div
    class="pointer-events-auto flex w-max max-w-[min(28rem,calc(100vw-2rem))] items-center gap-3 rounded-2xl border border-(--color-edge) bg-(--color-elevated) py-2.5 pr-2 pl-3.5 text-(--color-primary) shadow-[0_18px_40px_-18px_rgba(0,0,0,0.6)] {stacked
      ? ''
      : 'fixed bottom-20 left-1/2 z-[200] -translate-x-1/2'} {className}"
    role="alert"
    in:fly={{ y: 12, duration: 220, easing: cubicOut }}
    out:fly={{ y: 8, duration: 160, opacity: 0 }}
  >
    <ToneIcon size={18} strokeWidth={2} class="shrink-0 {tone.color}" aria-hidden="true" />
    <p class="m-0 min-w-0 flex-1 text-sm font-medium">{message}</p>
    {#if action}
      <div class="flex shrink-0 items-center">
        {@render action()}
      </div>
    {/if}
    {#if dismissible}
      <button
        type="button"
        class="flex size-7 shrink-0 cursor-pointer items-center justify-center rounded-full text-(--color-text-muted) transition-colors hover:bg-(--color-surface-hover) hover:text-(--color-primary) focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-(--color-brand)"
        onclick={handleDismiss}
        aria-label={tFn('toast.dismissAria')}
      >
        <X size={15} strokeWidth={2} aria-hidden="true" />
      </button>
    {/if}
  </div>
{/if}
