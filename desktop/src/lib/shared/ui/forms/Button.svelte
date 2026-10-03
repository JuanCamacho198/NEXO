<script lang="ts">
  import type { Snippet } from 'svelte';

  type ButtonProps = {
    children?: Snippet;
    onclick?: () => void;
    type?: 'button' | 'submit' | 'reset';
    variant?: 'primary' | 'secondary' | 'danger' | 'ghost' | 'accent';
    size?: 'sm' | 'md' | 'lg';
    disabled?: boolean;
    class?: string;
  };

  let {
    children,
    onclick,
    type = 'button',
    variant = 'primary',
    size = 'md',
    disabled = false,
    class: className = '',
  }: ButtonProps = $props();

  let isPressed = $state(false);

  const baseClasses =
    'inline-flex items-center justify-center font-sans font-medium rounded-lg transition-all duration-150 focus:outline-none focus:ring-2 focus:ring-offset-2 focus:ring-offset-(--color-bg-app) disabled:opacity-50 disabled:cursor-not-allowed';

  const pressStyles = $derived(isPressed ? 'scale-[0.96] shadow-inner' : 'scale-100 shadow-sm');

  const variants = {
    primary:
      'bg-(--color-primary) text-(--color-background) hover:opacity-90 focus:ring-(--color-primary)',
    secondary:
      'bg-(--color-surface) text-(--color-primary) border border-(--color-border) hover:bg-(--color-surface-hover) focus:ring-(--color-primary)',
    danger:
      'bg-(--color-error) text-(--color-background) hover:opacity-90 focus:ring-(--color-error)',
    ghost:
      'bg-transparent text-(--color-primary) hover:bg-(--color-surface-hover) focus:ring-(--color-primary)',
    // NEXO-blue primary action for reading CTAs (HOME-04). Global `primary`
    // stays untouched so unrelated screens are unaffected.
    accent:
      'bg-(--color-accent) text-(--color-accent-on) hover:opacity-90 focus:ring-(--color-accent)',
  };

  const sizes = {
    sm: 'px-3 py-1.5 text-sm',
    md: 'px-4 py-2 text-base',
    lg: 'px-6 py-3 text-lg',
  };
</script>

<button
  {type}
  class="{baseClasses} {pressStyles} {variants[variant]} {sizes[size]} {className}"
  {disabled}
  {onclick}
  onmousedown={() => (isPressed = true)}
  onmouseup={() => (isPressed = false)}
  onmouseleave={() => (isPressed = false)}
>
  {@render children?.()}
</button>
