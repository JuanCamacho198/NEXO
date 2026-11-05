<script lang="ts">
  import { tick } from 'svelte';
  import type { MessageKey } from '$lib/shared/i18n';

  type Props = {
    open: boolean;
    anchor: HTMLElement | null;
    currentColor: string;
    onSelect: (color: string) => void;
    onClose: () => void;
    t?: (key: MessageKey, params?: Record<string, string | number>) => string;
  };

  let { open, anchor, currentColor, onSelect, onClose, t: _t = undefined }: Props = $props();

  const tFn = (key: MessageKey): string => (_t ? _t(key) : key);

  let inputValue = $state('');
  let popoverEl = $state<HTMLDivElement | null>(null);

  const HEX_REGEX = /^#([0-9A-Fa-f]{6})$/;

  function isValidHex(value: string): boolean {
    return HEX_REGEX.test(value);
  }

  function handleNativeInput(event: Event): void {
    const target = event.target as HTMLInputElement;
    inputValue = target.value.toUpperCase();
  }

  function handleTextInput(event: Event): void {
    const target = event.target as HTMLInputElement;
    let value = target.value.trim();
    if (value && !value.startsWith('#')) {
      value = `#${value}`;
    }
    inputValue = value;
  }

  function handleSave(): void {
    if (!isValidHex(inputValue)) return;
    onSelect(inputValue);
    onClose();
  }

  function handleKeydown(event: KeyboardEvent): void {
    if (event.key === 'Escape') {
      event.preventDefault();
      onClose();
    } else if (event.key === 'Enter') {
      event.preventDefault();
      handleSave();
    }
  }

  $effect(() => {
    if (open) {
      inputValue = currentColor.toUpperCase();
      void tick().then(() => {
        popoverEl?.focus();
      });
    }
  });
</script>

{#if open && anchor}
  <div
    bind:this={popoverEl}
    class="fixed z-[110] w-52 rounded-xl border border-(--color-highlight-menu-border) bg-(--color-color-picker-bg) p-3 shadow-xl"
    data-floating-menu="true"
    style="left: {Math.max(
      8,
      Math.min(anchor.getBoundingClientRect().left, window.innerWidth - 224),
    )}px; top: {anchor.getBoundingClientRect().bottom + 8}px;"
    role="dialog"
    aria-label={tFn('colorPicker.title')}
    tabindex="-1"
    onclick={(e) => e.stopPropagation()}
    onkeydown={handleKeydown}
  >
    <label class="block text-xs font-medium text-(--color-text-inverse)" for="custom-color-input">
      {tFn('colorPicker.hexLabel')}
    </label>
    <div class="mt-1 flex items-center gap-2">
      <input
        id="custom-color-native"
        type="color"
        value={inputValue}
        oninput={handleNativeInput}
        class="h-8 w-8 cursor-pointer rounded border-0 p-0"
        aria-label={tFn('colorPicker.chooseColor')}
      />
      <input
        id="custom-color-input"
        type="text"
        value={inputValue}
        oninput={handleTextInput}
        maxlength="7"
        class="w-24 rounded-md border border-(--color-highlight-menu-border) bg-white px-2 py-1 text-sm font-mono uppercase text-(--color-text-inverse) focus:outline-none focus:ring-1 focus:ring-(--color-accent-sky)"
        class:border-red-500={!isValidHex(inputValue) && inputValue.length >= 7}
      />
    </div>
    <div class="mt-2 flex justify-end gap-2">
      <button
        type="button"
        class="rounded-md px-2 py-1 text-xs text-(--color-text-auxiliary) hover:text-(--color-text-inverse)"
        onclick={onClose}
      >
        {tFn('colorPicker.cancel')}
      </button>
      <button
        type="button"
        class="rounded-md bg-(--color-accent-sky) px-2 py-1 text-xs text-(--color-bg-deep) hover:bg-(--color-accent-blue) disabled:opacity-50"
        disabled={!isValidHex(inputValue)}
        onclick={handleSave}
      >
        {tFn('colorPicker.save')}
      </button>
    </div>
  </div>
{/if}
