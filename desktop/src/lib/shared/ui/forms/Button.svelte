<script lang="ts">
  import type { Snippet } from 'svelte';
  import type { HTMLAttributes } from 'svelte/elements';

  // Global HTML attributes are accepted and forwarded to the root element, so
  // a consumer can pass `data-testid`, `aria-*`, `id`, `title` and the like
  // without the atom inventing a prop for each. `HTMLElement` (not
  // `HTMLButtonElement`) keeps the handler signatures assignable to both the
  // `<button>` and the `<label>` root.
  type ButtonProps = Omit<HTMLAttributes<HTMLElement>, 'onclick' | 'class'> & {
    children?: Snippet;
    /** Optional icon rendered before the label. */
    leadingIcon?: Snippet;
    onclick?: () => void;
    type?: 'button' | 'submit' | 'reset';
    variant?:
      'primary' | 'secondary' | 'danger' | 'danger-outline' | 'ghost' | 'accent' | 'link' | 'tab';
    /** Padding only — the type role is always Label (0.875rem/500). */
    size?: 'sm' | 'md' | 'lg';
    /** Only meaningful with `variant="tab"`: paints the selected tab treatment. */
    selected?: boolean;
    /** Render a <label> so the control can wrap a hidden file input. */
    as?: 'button' | 'label';
    fullWidth?: boolean;
    /** Disables the button and shows a spinner until the action settles. */
    loading?: boolean;
    /** Replaces the label while `loading` is set. */
    loadingLabel?: string;
    disabled?: boolean;
    class?: string;
  };

  let {
    children,
    leadingIcon,
    onclick,
    type = 'button',
    variant = 'primary',
    size = 'md',
    selected = false,
    as = 'button',
    fullWidth = false,
    loading = false,
    loadingLabel,
    disabled = false,
    class: className = '',
    ...rest
  }: ButtonProps = $props();

  let isPressed = $state(false);

  const isInactive = $derived(disabled || loading);

  // Every button is the Label role (0.875rem / 500). `size` sets padding only.
  const isTab = $derived(variant === 'tab');

  // The tab variant rides a bare track as a border-bottom indicator, so it drops
  // the rounded chrome, the press shadow and the size padding the other
  // variants share.
  const baseClasses = $derived(
    isTab
      ? 'inline-flex items-center justify-center gap-1.5 px-2 py-3 text-sm font-medium rounded-none border-b-2 transition-all duration-200 focus:outline-none focus:ring-2 focus:ring-(--color-primary)'
      : 'inline-flex items-center justify-center whitespace-nowrap font-sans text-sm font-medium rounded-full transition-all duration-150 focus:outline-none focus-visible:ring-2 focus-visible:ring-offset-2 focus-visible:ring-offset-(--color-background) disabled:opacity-50 disabled:cursor-not-allowed',
  );

  // Flat variants press with scale only; an inner shadow on a pill or a text
  // link reads as a dent, not a press.
  const pressStyles = $derived(isTab ? '' : isPressed ? 'scale-[0.96]' : 'scale-100');

  // The NEXO ember language (DESIGN.md): `accent` is the one filled action of a
  // surface, `secondary` a quiet pill on the edge token, `link` an ember text
  // action ("view all", "back").
  const variants = {
    primary:
      'bg-(--color-primary) text-(--color-background) hover:opacity-90 focus-visible:ring-(--color-primary)',
    secondary:
      'bg-(--color-surface-subtle) text-(--color-primary) border border-(--color-edge) hover:border-(--color-brand)/60 hover:text-(--color-brand-text) focus-visible:ring-(--color-brand)',
    danger:
      'bg-(--color-error) text-(--color-background) hover:opacity-90 focus-visible:ring-(--color-error)',
    'danger-outline':
      'border border-(--color-danger)/45 bg-transparent text-(--color-danger) hover:bg-(--color-danger)/10 focus-visible:ring-(--color-danger)',
    ghost:
      'bg-transparent text-(--color-primary) hover:bg-(--color-surface-hover) focus-visible:ring-(--color-brand)',
    accent:
      'bg-linear-to-r from-(--color-brand-start) to-(--color-brand) font-semibold text-(--color-brand-on) shadow-[0_8px_20px_-10px_var(--color-brand-glow)] hover:brightness-110 focus-visible:ring-(--color-brand)',
    link: 'bg-transparent font-semibold text-(--color-brand-text) hover:bg-(--color-brand-soft) focus-visible:ring-(--color-brand)',
  };

  const sizes = {
    sm: 'px-3 py-1.5',
    md: 'px-4 py-2',
    lg: 'px-6 py-3',
  };

  const widthClasses = $derived(fullWidth ? 'w-full' : '');

  // Selected/idle treatment of the tab variant: the accent-soft wash with the
  // accent-start underline the strip used before the atoms migration. Keeping it
  // in the atom is what lets the settings tree stay free of a raw <button>.
  const tabClasses = $derived(
    selected
      ? 'border-(--color-brand) bg-(--color-brand-soft) text-(--color-primary) font-semibold'
      : 'border-transparent text-(--color-text-muted,var(--color-secondary)) hover:text-(--color-primary)',
  );

  const variantClasses = $derived(variant === 'tab' ? tabClasses : variants[variant]);

  const sizeClasses = $derived(isTab ? '' : sizes[size]);

  // A <label> has no :disabled state, so the disabled affordance is applied
  // through classes instead; focus moves to the wrapped control, so the ring
  // is drawn with focus-within. The press affordance is CSS `active:` because a
  // non-interactive element must not take mouse listeners.
  const labelStateClasses = $derived(
    as === 'label'
      ? `scale-100 transition-transform active:scale-[0.96] focus-within:ring-2 focus-within:ring-offset-2 focus-within:ring-offset-(--color-background) ${isInactive ? 'pointer-events-none cursor-not-allowed opacity-50' : ''}`
      : '',
  );

  function handlePressStart(): void {
    isPressed = true;
  }

  function handlePressEnd(): void {
    isPressed = false;
  }

  const buttonClasses = $derived(
    `${baseClasses} ${pressStyles} ${variantClasses} ${sizeClasses} ${widthClasses} ${className}`,
  );

  const labelClasses = $derived(
    `${baseClasses} ${variantClasses} ${sizeClasses} ${widthClasses} ${labelStateClasses} ${className}`,
  );
</script>

{#snippet content()}
  {#if loading}
    <svg
      class="mr-1.5 h-3.5 w-3.5 shrink-0 animate-spin"
      viewBox="0 0 24 24"
      fill="none"
      aria-hidden="true"
    >
      <circle class="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" stroke-width="4" />
      <path
        class="opacity-75"
        fill="currentColor"
        d="M4 12a8 8 0 0 1 8-8V0C5.373 0 0 5.373 0 12h4z"
      />
    </svg>
  {:else if leadingIcon}
    <span class="mr-1.5 inline-flex shrink-0 items-center">{@render leadingIcon()}</span>
  {/if}
  {#if loading && loadingLabel}
    <span>{loadingLabel}</span>
  {:else}
    {@render children?.()}
  {/if}
{/snippet}

{#if as === 'label'}
  <label
    {...rest}
    class={labelClasses}
    aria-disabled={isInactive}
    aria-busy={loading ? 'true' : undefined}
  >
    {@render content()}
  </label>
{:else}
  <button
    {...rest}
    {type}
    class={buttonClasses}
    disabled={isInactive}
    aria-busy={loading ? 'true' : undefined}
    {onclick}
    onmousedown={handlePressStart}
    onmouseup={handlePressEnd}
    onmouseleave={handlePressEnd}
  >
    {@render content()}
  </button>
{/if}
