<script lang="ts">
  import type { MessageKey } from '$lib/shared/i18n';
  import { theme, toggleTheme } from '$lib/shared/stores/theme';

  type Props = {
    t?: (key: MessageKey, params?: Record<string, string | number>) => string;
  };

  let { t }: Props = $props();

  const label = $derived(
    $theme === 'dark'
      ? (t?.('theme.currentDark') ?? 'Tema oscuro')
      : (t?.('theme.currentLight') ?? 'Tema claro'),
  );
  const actionLabel = $derived(
    $theme === 'dark'
      ? (t?.('theme.switchToLight') ?? 'Cambiar a tema claro')
      : (t?.('theme.switchToDark') ?? 'Cambiar a tema oscuro'),
  );
</script>

<button
  id="theme-toggle-btn"
  type="button"
  class="flex w-full items-center gap-2.5 rounded-lg border border-(--color-border) px-3 py-2 text-2sm font-medium text-(--color-text-muted) transition-colors hover:border-(--color-border-strong) hover:bg-(--color-panel-accent) hover:text-(--color-primary) focus-visible:ring-2 ring-(--color-accent-nav-fg)"
  style="font-family: var(--font-sans);"
  onclick={toggleTheme}
  aria-label={`${label}. ${actionLabel}`}
  title={actionLabel}
>
  <span class="relative flex size-5 shrink-0 items-center justify-center">
    <svg
      class="absolute transition-[opacity,transform] duration-300 ease-out"
      class:opacity-100={$theme === 'light'}
      class:opacity-0={$theme === 'dark'}
      class:scale-100={$theme === 'light'}
      class:scale-[0.4]={$theme === 'dark'}
      class:rotate-0={$theme === 'light'}
      class:rotate-90={$theme === 'dark'}
      width="18"
      height="18"
      viewBox="0 0 24 24"
      fill="none"
      xmlns="http://www.w3.org/2000/svg"
      aria-hidden="true"
    >
      <circle cx="12" cy="12" r="4" fill="currentColor" />
      <path
        d="M12 2v2M12 20v2M4.22 4.22l1.42 1.42M18.36 18.36l1.42 1.42M2 12h2M20 12h2M4.22 19.78l1.42-1.42M18.36 5.64l1.42-1.42"
        stroke="currentColor"
        stroke-width="2"
        stroke-linecap="round"
      />
    </svg>

    <svg
      class="absolute transition-[opacity,transform] duration-300 ease-out"
      class:opacity-100={$theme === 'dark'}
      class:opacity-0={$theme === 'light'}
      class:scale-100={$theme === 'dark'}
      class:scale-[0.4]={$theme === 'light'}
      class:rotate-0={$theme === 'dark'}
      class:rotate-90={$theme === 'light'}
      width="18"
      height="18"
      viewBox="0 0 24 24"
      fill="none"
      xmlns="http://www.w3.org/2000/svg"
      aria-hidden="true"
    >
      <path
        d="M21 12.79A9 9 0 1 1 11.21 3 7 7 0 0 0 21 12.79z"
        fill="currentColor"
        stroke="currentColor"
        stroke-width="1.5"
        stroke-linecap="round"
        stroke-linejoin="round"
      />
    </svg>
  </span>

  <span class="flex-1 text-left">{label}</span>
</button>
