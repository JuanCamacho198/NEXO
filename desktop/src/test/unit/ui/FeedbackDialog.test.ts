/**
 * FeedbackDialog component tests (sdd/sentry-observability-v2 PR3 desktop — HOTFIX slice).
 *
 * Covers spec D1/D2/D3:
 *  - Copy audit (HYNft strings present per feedback-design #2460)
 *  - Live counter `{n} / 500` updates on input
 *  - Paste/typing over the 500-char cap is truncated by truncateMessage
 *  - Dismiss-once idempotence via feedbackStore.markDismissed
 *  - State machine: idle -> editing -> sending -> sent
 *  - captureFeedback is called with associatedEventId when eventId present
 *  - Offline path enqueues (no captureFeedback call, queue grows)
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { render, screen, fireEvent, waitFor } from '@testing-library/svelte';
import { tick } from 'svelte';
import { stubElementRect } from '../../harness/jsdomHarness';

// Mock Sentry BEFORE importing the dialog (which transitively imports Sentry).
const captureFeedback = vi.fn();
type ScopeLike = { setContext: ReturnType<typeof vi.fn>; setTag: ReturnType<typeof vi.fn> };
let lastScope: ScopeLike | null = null;
const withScope = vi.fn((cb: (scope: ScopeLike) => void) => {
  lastScope = { setContext: vi.fn(), setTag: vi.fn() };
  cb(lastScope);
});

vi.mock('@sentry/browser', () => ({
  captureFeedback: (...args: unknown[]) => captureFeedback(...args),
  withScope: (cb: (scope: ScopeLike) => void) => withScope(cb),
}));

import { invoke } from '@tauri-apps/api/core';

const invokeMock = vi.mocked(invoke);

import FeedbackDialog from '$lib/shared/ui/feedback/FeedbackDialog.svelte';
import {
  enqueueFeedback,
  isDismissed,
  markDismissed,
  readFeedbackQueue,
  FEEDBACK_QUEUE_CAP,
} from '$lib/shared/feedback/feedbackStore';
import {
  FEEDBACK_TITLE,
  FEEDBACK_SUBTITLE,
  FEEDBACK_INPUT_LABEL,
  FEEDBACK_INPUT_HINT,
  FEEDBACK_PRIVACY,
  FEEDBACK_RESTART_LABEL,
  FEEDBACK_SEND_LABEL,
  FEEDBACK_MAX_CHARS,
  FEEDBACK_CONTEXT_HEADER,
  FEEDBACK_PILLS,
} from '$lib/shared/feedback/feedbackDesign';

function renderDialog(
  overrides: {
    open?: boolean;
    eventId?: string | null;
    onDismiss?: (eventId: string | null) => void;
  } = {},
): ReturnType<typeof render<typeof FeedbackDialog>> {
  const onDismiss: (eventId: string | null) => void = overrides.onDismiss ?? vi.fn();
  // Distinguish `eventId` not passed (use default) from explicit `null`.
  const eventId: string | null =
    'eventId' in overrides ? (overrides.eventId ?? null) : 'evt-test-001';
  return render(FeedbackDialog, {
    open: overrides.open ?? true,
    eventId,
    onDismiss,
  });
}

describe('FeedbackDialog (sdd/sentry-observability-v2 PR3)', () => {
  beforeEach(() => {
    captureFeedback.mockReset();
    withScope.mockClear();
    captureFeedback.mockReturnValue(undefined);
    lastScope = null;
    invokeMock.mockReset();
    Object.defineProperty(navigator, 'onLine', { configurable: true, value: true });
    localStorage.clear();
  });

  afterEach(() => {
    Object.defineProperty(navigator, 'onLine', { configurable: true, value: true });
    localStorage.clear();
  });

  describe('D1 — copy audit (HYNft verbatim, #2460)', () => {
    it('renders the title verbatim', () => {
      renderDialog();
      expect(screen.getByText(FEEDBACK_TITLE)).toBeInTheDocument();
    });

    it('renders the subtitle verbatim', () => {
      renderDialog();
      expect(screen.getByText(FEEDBACK_SUBTITLE)).toBeInTheDocument();
    });

    it('renders the input label and hint verbatim', () => {
      renderDialog();
      expect(screen.getByText(FEEDBACK_INPUT_LABEL)).toBeInTheDocument();
      expect(screen.getByText(FEEDBACK_INPUT_HINT)).toBeInTheDocument();
    });

    it('renders the privacy shield verbatim', () => {
      renderDialog();
      expect(screen.getByText(FEEDBACK_PRIVACY)).toBeInTheDocument();
    });

    it('renders the restart and send button labels verbatim', () => {
      renderDialog();
      expect(screen.getByText(FEEDBACK_RESTART_LABEL)).toBeInTheDocument();
      expect(screen.getByText(FEEDBACK_SEND_LABEL)).toBeInTheDocument();
    });

    it('renders the LO QUE ESTABAS HACIENDO context header', () => {
      renderDialog();
      expect(screen.getByText(FEEDBACK_CONTEXT_HEADER)).toBeInTheDocument();
    });

    it('renders all three desktop-only stat pills (HYNft)', () => {
      renderDialog();
      for (const pill of FEEDBACK_PILLS) {
        expect(screen.getByText(pill.label)).toBeInTheDocument();
      }
    });

    it('FEEDBACK_MAX_CHARS equals 500 (desktop contract)', () => {
      expect(FEEDBACK_MAX_CHARS).toBe(500);
    });
  });

  describe('counter + truncation (D1, max 500 + live counter)', () => {
    it('starts at "0 / 500"', () => {
      renderDialog();
      expect(screen.getByText(`0 / ${FEEDBACK_MAX_CHARS}`)).toBeInTheDocument();
    });

    it('updates live as the user types', async () => {
      renderDialog();
      const ta = screen.getByLabelText(FEEDBACK_INPUT_LABEL) as HTMLTextAreaElement;
      await fireEvent.input(ta, { target: { value: 'hola mundo' } });
      await tick();
      expect(screen.getByText(`10 / ${FEEDBACK_MAX_CHARS}`)).toBeInTheDocument();
    });

    it('truncates pasted content beyond 500 chars to FEEDBACK_MAX_CHARS', async () => {
      renderDialog();
      const ta = screen.getByLabelText(FEEDBACK_INPUT_LABEL) as HTMLTextAreaElement;
      const tooLong = 'x'.repeat(FEEDBACK_MAX_CHARS + 50);
      await fireEvent.input(ta, { target: { value: tooLong } });
      await tick();
      // counter should reflect the truncated length
      expect(screen.getByText(`${FEEDBACK_MAX_CHARS} / ${FEEDBACK_MAX_CHARS}`)).toBeInTheDocument();
      // textarea value is the truncated string
      expect(ta.value.length).toBe(FEEDBACK_MAX_CHARS);
    });
  });

  describe('state machine (idle -> editing -> sending -> sent)', () => {
    it('does not render anything when open is false', () => {
      renderDialog({ open: false });
      expect(screen.queryByRole('dialog')).toBeNull();
    });

    it('renders the dialog when open is true', () => {
      renderDialog();
      expect(screen.getByRole('dialog')).toBeInTheDocument();
    });

    it('send button is disabled when message is empty (canSend guard)', () => {
      renderDialog();
      const btn = screen.getByRole('button', { name: FEEDBACK_SEND_LABEL });
      expect(btn).toBeDisabled();
    });

    it('send button enables once the user types', async () => {
      renderDialog();
      const ta = screen.getByLabelText(FEEDBACK_INPUT_LABEL) as HTMLTextAreaElement;
      await fireEvent.input(ta, { target: { value: 'algo' } });
      await tick();
      const btn = screen.getByRole('button', { name: FEEDBACK_SEND_LABEL });
      expect(btn).not.toBeDisabled();
    });

    it('submitting calls captureFeedback with associatedEventId and the trimmed message', async () => {
      renderDialog({ eventId: 'evt-abc-001' });
      const ta = screen.getByLabelText(FEEDBACK_INPUT_LABEL) as HTMLTextAreaElement;
      await fireEvent.input(ta, { target: { value: 'estaba leyendo' } });
      await tick();
      const btn = screen.getByRole('button', { name: FEEDBACK_SEND_LABEL });
      await fireEvent.click(btn);
      await waitFor(() => expect(captureFeedback).toHaveBeenCalledTimes(1));
      const call = captureFeedback.mock.calls[0]?.[0] as {
        message: string;
        associatedEventId?: string;
      };
      expect(call.message).toBe('estaba leyendo');
      expect(call.associatedEventId).toBe('evt-abc-001');
    });

    it('submitting with a null eventId sends captureFeedback without associatedEventId', async () => {
      renderDialog({ eventId: null });
      const ta = screen.getByLabelText(FEEDBACK_INPUT_LABEL) as HTMLTextAreaElement;
      await fireEvent.input(ta, { target: { value: 'no event id' } });
      await tick();
      const btn = screen.getByRole('button', { name: FEEDBACK_SEND_LABEL });
      await fireEvent.click(btn);
      await waitFor(() => expect(captureFeedback).toHaveBeenCalledTimes(1));
      const call = captureFeedback.mock.calls[0]?.[0] as { associatedEventId?: string };
      expect(call.associatedEventId).toBeUndefined();
    });

    it('submitting closes the dialog (open becomes false via onDismiss)', async () => {
      const onDismiss = vi.fn();
      renderDialog({ onDismiss });
      const ta = screen.getByLabelText(FEEDBACK_INPUT_LABEL) as HTMLTextAreaElement;
      await fireEvent.input(ta, { target: { value: 'listo' } });
      await tick();
      await fireEvent.click(screen.getByRole('button', { name: FEEDBACK_SEND_LABEL }));
      await waitFor(() => expect(onDismiss).toHaveBeenCalledTimes(1));
    });
  });

  describe('D3 — dismiss-once idempotence', () => {
    it('marks the eventId dismissed after a successful send', async () => {
      renderDialog({ eventId: 'evt-dismiss-1' });
      const ta = screen.getByLabelText(FEEDBACK_INPUT_LABEL) as HTMLTextAreaElement;
      await fireEvent.input(ta, { target: { value: 'mensaje' } });
      await tick();
      await fireEvent.click(screen.getByRole('button', { name: FEEDBACK_SEND_LABEL }));
      await waitFor(() => expect(isDismissed('evt-dismiss-1')).toBe(true));
    });

    it('close (X) also marks dismissed and never re-nags the same event', async () => {
      renderDialog({ eventId: 'evt-dismiss-2' });
      const closeBtn = screen.getByRole('button', { name: /cerrar/i });
      await fireEvent.click(closeBtn);
      expect(isDismissed('evt-dismiss-2')).toBe(true);
    });

    it('marking the same event twice is idempotent (store contract)', () => {
      markDismissed('evt-dup');
      markDismissed('evt-dup');
      const raw = localStorage.getItem('np.feedback.dismissed') ?? '[]';
      const set = JSON.parse(raw) as string[];
      expect(set.filter((id) => id === 'evt-dup')).toHaveLength(1);
    });
  });

  describe('M2 — bits-ui Dialog interaction contract (perf-stability-cleanup)', () => {
    it('Escape dismisses once and marks the event dismissed', async () => {
      const onDismiss = vi.fn();
      renderDialog({ eventId: 'evt-esc-1', onDismiss });
      expect(screen.getByRole('dialog')).toBeInTheDocument();

      await fireEvent.keyDown(document, { key: 'Escape' });
      await waitFor(() => expect(onDismiss).toHaveBeenCalledTimes(1));
      expect(isDismissed('evt-esc-1')).toBe(true);
      // The exit transition defers unmount, so wait for removal rather than
      // asserting it synchronously.
      await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
    });

    it('an outside pointerdown dismisses once through the same bookkeeping', async () => {
      const onDismiss = vi.fn();
      renderDialog({ eventId: 'evt-out-1', onDismiss });
      const content = document.querySelector('[data-dialog-content]') as HTMLElement;
      expect(content).toBeTruthy();
      stubElementRect(content, { left: 100, top: 100, width: 400, height: 300 });
      const overlay = document.querySelector('[data-dialog-overlay]') as HTMLElement;
      await new Promise((resolve) => setTimeout(resolve, 10));

      await fireEvent.pointerDown(overlay, {
        clientX: 5,
        clientY: 5,
        pointerId: 1,
        pointerType: 'mouse',
        button: 0,
      });
      await fireEvent.pointerUp(overlay, {
        clientX: 5,
        clientY: 5,
        pointerId: 1,
        pointerType: 'mouse',
        button: 0,
      });

      await waitFor(() => expect(onDismiss).toHaveBeenCalledTimes(1));
      expect(isDismissed('evt-out-1')).toBe(true);
      // The exit transition defers unmount, so wait for removal rather than
      // asserting it synchronously.
      await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
    });
  });

  describe('D3 — offline enqueue path', () => {
    it('enqueues and does NOT call captureFeedback when navigator.onLine is false', async () => {
      Object.defineProperty(navigator, 'onLine', { configurable: true, value: false });
      renderDialog({ eventId: 'evt-offline-1' });
      const ta = screen.getByLabelText(FEEDBACK_INPUT_LABEL) as HTMLTextAreaElement;
      await fireEvent.input(ta, { target: { value: 'estoy sin red' } });
      await tick();
      await fireEvent.click(screen.getByRole('button', { name: FEEDBACK_SEND_LABEL }));
      await waitFor(() => expect(readFeedbackQueue().length).toBe(1));
      expect(captureFeedback).not.toHaveBeenCalled();
    });

    it('offline queue respects FEEDBACK_QUEUE_CAP FIFO', () => {
      // This is the store-level invariant; the dialog delegates to it.
      for (let i = 0; i < FEEDBACK_QUEUE_CAP + 3; i++) {
        enqueueFeedback({
          eventId: `e${i}`,
          message: `m${i}`,
          contexts: {
            book: { bookId: 'b', chapterIndex: 0, page: 1, title: '', chapterLabel: '' },
          },
          enqueuedAt: Date.now(),
        });
      }
      const q = readFeedbackQueue();
      expect(q).toHaveLength(FEEDBACK_QUEUE_CAP);
      expect(q[0]?.eventId).toBe('e3');
    });
  });

  describe('HYNft scrubber allowlist (bookTitle/chapterLabel only via feedback events)', () => {
    it('the entry sent to captureFeedback carries book context via withScope + setContext', async () => {
      renderDialog({ eventId: 'evt-scope-1' });
      const ta = screen.getByLabelText(FEEDBACK_INPUT_LABEL) as HTMLTextAreaElement;
      await fireEvent.input(ta, { target: { value: 'algo' } });
      await tick();
      await fireEvent.click(screen.getByRole('button', { name: FEEDBACK_SEND_LABEL }));
      await waitFor(() => expect(withScope).toHaveBeenCalledTimes(1));
      // The scope callback receives setContext; verify it was called with 'book'
      const scopeArg = withScope.mock.calls[0]?.[0];
      expect(typeof scopeArg).toBe('function');
    });
  });

  describe('opt-in bundle attachment (spec feedback delta)', () => {
    const BUNDLE = { appVersion: '9.9.9', logTail: ['line-1', 'line-2'] };

    it('renders the attach checkbox unchecked by default', () => {
      renderDialog();
      expect(screen.getByTestId('feedback-attach-bundle')).not.toBeChecked();
    });

    it('default submission neither invokes the bundle command nor adds a diagnostics context', async () => {
      renderDialog({ eventId: 'evt-default-bundle' });
      const ta = screen.getByLabelText(FEEDBACK_INPUT_LABEL) as HTMLTextAreaElement;
      await fireEvent.input(ta, { target: { value: 'sin bundle' } });
      await tick();
      await fireEvent.click(screen.getByRole('button', { name: FEEDBACK_SEND_LABEL }));
      await waitFor(() => expect(captureFeedback).toHaveBeenCalledTimes(1));

      expect(invokeMock).not.toHaveBeenCalledWith('collectDiagnosticsBundle');
      // Byte-identical bundle-free shape: only the book context is attached.
      const contextKeys = (lastScope?.setContext.mock.calls ?? []).map((c) => c[0]);
      expect(contextKeys).toEqual(['book']);
    });

    it('opt-in submission invokes the command and attaches the bundle text to the feedback context', async () => {
      invokeMock.mockResolvedValue(BUNDLE);
      renderDialog({ eventId: 'evt-optin-bundle' });
      const box = screen.getByTestId('feedback-attach-bundle') as HTMLInputElement;
      await fireEvent.click(box);
      expect(box).toBeChecked();

      const ta = screen.getByLabelText(FEEDBACK_INPUT_LABEL) as HTMLTextAreaElement;
      await fireEvent.input(ta, { target: { value: 'con bundle' } });
      await tick();
      await fireEvent.click(screen.getByRole('button', { name: FEEDBACK_SEND_LABEL }));
      await waitFor(() => expect(captureFeedback).toHaveBeenCalledTimes(1));

      expect(invokeMock).toHaveBeenCalledWith('collectDiagnosticsBundle');
      const diagCall = (lastScope?.setContext.mock.calls ?? []).find((c) => c[0] === 'diagnostics');
      expect(diagCall?.[1]).toEqual({ bundle: JSON.stringify(BUNDLE) });
    });

    it('resets the checkbox to unchecked on the next dialog open', async () => {
      const { rerender } = renderDialog({ eventId: 'evt-first-open' });
      const box = screen.getByTestId('feedback-attach-bundle') as HTMLInputElement;
      await fireEvent.click(box);
      expect(box).toBeChecked();

      const onDismiss = vi.fn();
      await rerender({ open: false, eventId: 'evt-first-open', onDismiss });
      await tick();
      // Fresh, non-dismissed eventId so the reopen prompt path does not bail.
      await rerender({ open: true, eventId: 'evt-second-open', onDismiss });
      await tick();

      const reopened = screen.getByTestId('feedback-attach-bundle') as HTMLInputElement;
      expect(reopened).not.toBeChecked();
    });

    it('offline opt-in queues the entry WITH the bundle text (carried on flush)', async () => {
      invokeMock.mockResolvedValue(BUNDLE);
      Object.defineProperty(navigator, 'onLine', { configurable: true, value: false });
      renderDialog({ eventId: 'evt-offline-bundle' });
      await fireEvent.click(screen.getByTestId('feedback-attach-bundle'));

      const ta = screen.getByLabelText(FEEDBACK_INPUT_LABEL) as HTMLTextAreaElement;
      await fireEvent.input(ta, { target: { value: 'offline con bundle' } });
      await tick();
      await fireEvent.click(screen.getByRole('button', { name: FEEDBACK_SEND_LABEL }));
      await waitFor(() => expect(readFeedbackQueue().length).toBe(1));

      expect(readFeedbackQueue()[0]?.bundle).toBe(JSON.stringify(BUNDLE));
      expect(captureFeedback).not.toHaveBeenCalled();
    });
  });
});
