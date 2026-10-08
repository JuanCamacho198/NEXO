<!--
  Crash Feedback Dialog — desktop modal (sdd/sentry-observability-v2 PR3, spec D1).
  Rendered on the shared Modal facade (shared/ui/layout/Modal.svelte); copy comes
  from $lib/shared/feedback/feedbackDesign.

  Triggers (spec D3): ErrorFallback dispatches `np:open-feedback`; AppModals
  also opens this on next-launch when a lastEventId was persisted.

  State machine: idle -> editing -> sending -> sent | error. The dialog is
  host-aware: Sentry is the only egress, and we never block on it.
-->
<script lang="ts">
  import {
    FEEDBACK_TITLE,
    FEEDBACK_SUBTITLE,
    FEEDBACK_CONTEXT_HEADER,
    FEEDBACK_PILLS,
    FEEDBACK_INPUT_LABEL,
    FEEDBACK_INPUT_HINT,
    FEEDBACK_PRIVACY,
    FEEDBACK_RESTART_LABEL,
    FEEDBACK_SEND_LABEL,
    FEEDBACK_MAX_CHARS,
    FEEDBACK_SAMPLE_BOOK,
  } from '$lib/shared/feedback/feedbackDesign';
  import {
    enqueueFeedback,
    flushFeedbackQueue,
    isDismissed,
    markDismissed,
    truncateMessage,
    buildBookContext,
    type BookContext,
    type QueuedFeedback,
  } from '$lib/shared/feedback/feedbackStore';
  import * as Sentry from '@sentry/browser';
  import { invoke } from '@tauri-apps/api/core';
  import Modal from '$lib/shared/ui/layout/Modal.svelte';
  import Button from '$lib/shared/ui/forms/Button.svelte';
  import type { MessageKey } from '$lib/shared/i18n';
  import { i18n } from '$lib/shared/i18n';

  type DialogState = 'idle' | 'editing' | 'sending' | 'sent' | 'error';

  interface Props {
    open: boolean;
    eventId: string | null;
    onDismiss: (eventId: string | null) => void;
  }

  let { open = $bindable(false), eventId, onDismiss }: Props = $props();

  let locale = $state(i18n?.DEFAULT_LOCALE ?? 'es');
  $effect(() => {
    if (!i18n?.locale) return;
    const unsub = i18n.locale.subscribe((l) => {
      locale = l;
    });
    return () => unsub();
  });
  const tFn = (key: MessageKey): string => i18n?.t?.(locale, key) ?? key;

  let message = $state('');
  let dialogState: DialogState = $state('idle');
  // Opt-in diagnostics attachment. Never persisted: reset to false on every
  // dialog open so consent is strictly per-submission (spec feedback delta).
  let attachBundle = $state(false);
  let context: BookContext = $state(
    buildBookContext({
      bookId: 'sample',
      chapterIndex: 0,
      page: FEEDBACK_SAMPLE_BOOK.page,
      title: FEEDBACK_SAMPLE_BOOK.title,
      chapterLabel: FEEDBACK_SAMPLE_BOOK.chapter,
    }),
  );

  $effect(() => {
    if (!open) return;
    if (eventId && isDismissed(eventId)) {
      // Same-event dismiss-once: spec D3 — never re-nag.
      open = false;
      return;
    }
    dialogState = 'editing';
    message = '';
    attachBundle = false;
  });

  const counter = $derived(`${message.length} / ${FEEDBACK_MAX_CHARS}`);
  const canSend = $derived(message.length > 0 && message.length <= FEEDBACK_MAX_CHARS);

  function handleInput(event: Event): void {
    const value = (event.target as HTMLTextAreaElement).value;
    message = truncateMessage(value, FEEDBACK_MAX_CHARS);
  }

  function handleSend(): void {
    if (!canSend) return;
    dialogState = 'sending';
    void doSend();
  }

  async function doSend(): Promise<void> {
    const entry: QueuedFeedback = {
      eventId,
      message,
      contexts: { book: context },
      enqueuedAt: Date.now(),
      ...(attachBundle ? await consentBundle() : {}),
    };
    if (typeof navigator !== 'undefined' && !navigator.onLine) {
      enqueueFeedback(entry);
      finish('sent', true);
      return;
    }
    try {
      // The browser Sentry SDK does not accept `contexts` on captureFeedback,
      // so the book context is attached via withScope → setContext (same
      // pattern AppModals transport uses). The opt-in diagnostics bundle is
      // attached the same way, only for this submission. captureFeedback only
      // supports message + associatedEventId at the call site.
      Sentry.withScope((scope) => {
        scope.setContext('book', entry.contexts.book as unknown as Record<string, unknown>);
        if (entry.bundle) {
          scope.setContext('diagnostics', { bundle: entry.bundle });
        }
        Sentry.captureFeedback({
          message: entry.message,
          associatedEventId: eventId ?? undefined,
        });
      });
      finish('sent', true);
    } catch {
      // Offline mid-flight: queue and resolve as sent (eventually delivered).
      enqueueFeedback(entry);
      finish('error', true);
    }
  }

  /**
   * Fetch the diagnostics bundle text for an explicitly opted-in submission.
   * Any collection failure degrades to a bundle-free send: the submission must
   * never fail because diagnostics could not be assembled.
   */
  async function consentBundle(): Promise<{ bundle?: string }> {
    try {
      const bundle = await invoke<unknown>('collectDiagnosticsBundle');
      const text = JSON.stringify(bundle);
      return typeof text === 'string' && text.length > 0 ? { bundle: text } : {};
    } catch {
      return {};
    }
  }

  function finish(next: DialogState, dismissed: boolean): void {
    dialogState = next;
    if (dismissed && eventId) markDismissed(eventId);
    if (typeof navigator !== 'undefined' && navigator.onLine) {
      // Best-effort: drain any prior queued items now.
      void flushFeedbackQueue();
    }
    onDismiss(eventId);
    open = false;
  }

  function handleRestart(): void {
    if (eventId) markDismissed(eventId);
    onDismiss(eventId);
    open = false;
    if (typeof window !== 'undefined') window.location.reload();
  }

  /**
   * Every facade close path (X, Escape, backdrop) routes through the Modal's
   * `onOpenChange`. They carry the SAME bookkeeping as a send: mark the event
   * dismissed so the dialog never re-nags, then hand the close to the parent.
   * `onDismiss` is fired exactly once per close.
   */
  function handleFacadeClose(): void {
    if (eventId) markDismissed(eventId);
    onDismiss(eventId);
    open = false;
  }
</script>

{#if open}
  <Modal
    bind:open
    title={FEEDBACK_TITLE}
    size="md"
    onOpenChange={(v) => {
      if (!v) handleFacadeClose();
    }}
  >
    {#snippet children()}
      <div class="flex flex-col gap-5">
        <p class="text-sm leading-relaxed text-(--color-secondary)">{FEEDBACK_SUBTITLE}</p>

        <!-- Context block -->
        <div class="flex flex-col gap-3 rounded-xl bg-(--color-surface-dim) p-4">
          <p
            class="flex items-center gap-1.5 text-micro font-bold tracking-[0.15em] text-(--color-accent) uppercase"
          >
            <svg
              class="h-3 w-3"
              viewBox="0 0 24 24"
              fill="none"
              stroke="currentColor"
              stroke-width="2"
              aria-hidden="true"
            >
              <circle cx="12" cy="12" r="10" />
              <line x1="12" y1="16" x2="12" y2="12" />
              <line x1="12" y1="8" x2="12.01" y2="8" />
            </svg>
            {FEEDBACK_CONTEXT_HEADER}
          </p>
          <div class="flex items-center gap-3">
            <div
              class="h-14 w-10 rounded bg-gradient-to-br from-(--color-primary)/20 to-(--color-primary)/5"
              aria-hidden="true"
            ></div>
            <div class="flex flex-1 flex-col">
              <p class="text-sm font-bold">{context.title}</p>
              <p class="text-micro text-(--color-text-muted)">
                {FEEDBACK_SAMPLE_BOOK.author} · {context.chapterLabel} · p. {context.page} /
                {FEEDBACK_SAMPLE_BOOK.totalPages}
              </p>
            </div>
          </div>
          <!-- Stat pills (desktop-only per HYNft) -->
          <div class="flex flex-wrap gap-2">
            {#each FEEDBACK_PILLS as pill (pill.label)}
              <span
                class="flex items-center gap-1 rounded-full border border-(--color-border) bg-(--color-surface) px-2 py-0.5 text-xs font-semibold text-(--color-secondary)"
              >
                {pill.label}
              </span>
            {/each}
          </div>
        </div>

        <!-- Input section -->
        <div class="flex flex-col gap-2">
          <div class="flex items-center justify-between">
            <label for="feedback-text" class="text-sm font-semibold">
              {FEEDBACK_INPUT_LABEL}
            </label>
            <span class="text-micro text-(--color-text-muted)">{FEEDBACK_INPUT_HINT}</span>
          </div>
          <div
            class="flex min-h-27.5 flex-col gap-1.5 rounded-lg border border-(--color-border) bg-(--color-background) p-3"
          >
            <textarea
              id="feedback-text"
              class="min-h-16 flex-1 resize-none bg-transparent text-sm leading-normal text-(--color-primary) outline-none placeholder:text-(--color-text-muted)"
              placeholder=""
              value={message}
              oninput={handleInput}
              maxlength={FEEDBACK_MAX_CHARS}
              disabled={dialogState === 'sending' || dialogState === 'sent'}></textarea>
            <span
              class="self-end text-micro {message.length > FEEDBACK_MAX_CHARS
                ? 'text-(--color-error)'
                : 'text-(--color-text-muted)'}"
            >
              {counter}
            </span>
          </div>
        </div>

        <!-- Diagnostics bundle opt-in (spec feedback delta): unchecked by
             default, consent applies to this submission only. -->
        <label class="flex cursor-pointer items-start gap-2 text-xs text-(--color-secondary)">
          <input
            type="checkbox"
            class="mt-0.5 size-3.5 shrink-0 cursor-pointer accent-(--color-primary)"
            checked={attachBundle}
            disabled={dialogState === 'sending' || dialogState === 'sent'}
            onchange={(event) => {
              attachBundle = (event.currentTarget as HTMLInputElement).checked;
            }}
            data-testid="feedback-attach-bundle"
          />
          <span class="flex flex-col gap-0.5">
            <span class="font-medium">{tFn('feedback.attachBundle')}</span>
            <span class="text-(--color-text-muted)">{tFn('feedback.attachBundleHint')}</span>
          </span>
        </label>
      </div>
    {/snippet}

    {#snippet footer()}
      <div class="flex w-full items-center justify-between">
        <p class="flex items-center gap-1.5 text-micro text-(--color-text-muted)">
          <svg
            class="h-3 w-3"
            viewBox="0 0 24 24"
            fill="none"
            stroke="currentColor"
            stroke-width="2"
            aria-hidden="true"
          >
            <path d="M12 22s8-4 8-10V5l-8-3-8 3v7c0 6 8 10 8 10z" />
          </svg>
          {FEEDBACK_PRIVACY}
        </p>
        <div class="flex items-center gap-2">
          <Button variant="secondary" size="sm" onclick={handleRestart}>
            <svg
              class="h-3.5 w-3.5"
              viewBox="0 0 24 24"
              fill="none"
              stroke="currentColor"
              stroke-width="2"
              aria-hidden="true"
            >
              <path d="M3 12a9 9 0 1 0 3-6.7L3 8" />
              <path d="M3 3v5h5" />
            </svg>
            {FEEDBACK_RESTART_LABEL}
          </Button>
          <Button
            size="sm"
            onclick={handleSend}
            disabled={!canSend || dialogState === 'sending' || dialogState === 'sent'}
          >
            {dialogState === 'sending' ? tFn('feedback.sending') : FEEDBACK_SEND_LABEL}
            <svg
              class="h-3.5 w-3.5"
              viewBox="0 0 24 24"
              fill="none"
              stroke="currentColor"
              stroke-width="2"
              aria-hidden="true"
            >
              <line x1="5" y1="12" x2="19" y2="12" />
              <polyline points="12 5 19 12 12 19" />
            </svg>
          </Button>
        </div>
      </div>
    {/snippet}
  </Modal>
{/if}
