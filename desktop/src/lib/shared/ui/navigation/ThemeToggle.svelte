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
  class="group/theme flex w-full items-center gap-3 rounded-(--radius-lg) border border-(--color-edge) bg-(--color-sidebar-card) py-2.5 pr-3 pl-3.5 text-sm font-medium text-(--color-secondary) transition-colors duration-(--duration-fast) ease-(--ease-smooth) hover:border-(--color-edge-strong) hover:text-(--color-primary) focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-(--color-accent-nav-bar)"
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
      width="17"
      height="17"
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
      width="17"
      height="17"
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

  <span class="flex-1 truncate text-left">{label}</span>

  <!-- Visual switch: on = dark theme, matching the label it sits beside. -->
  <span
    class="relative h-6 w-11 shrink-0 rounded-full border transition-colors duration-(--duration-normal) ease-(--ease-smooth) {$theme ===
    'dark'
      ? 'border-transparent bg-(--color-brand)'
      : 'border-(--color-edge-strong) bg-(--color-surface-hover)'}"
    aria-hidden="true"
  >
    <span
      class="absolute top-1/2 left-0.5 size-4.5 -translate-y-1/2 rounded-full bg-white shadow-[0_1px_4px_rgba(0,0,0,0.35)] transition-transform duration-(--duration-normal) ease-(--ease-smooth)"
      class:translate-x-5={$theme === 'dark'}
    ></span>
  </span>
</button>
