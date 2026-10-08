import { fireEvent, render, screen, waitFor } from '@testing-library/svelte';
import { tick } from 'svelte';
import { describe, expect, it } from 'vitest';
import DiscoverRailSection from '$lib/features/discover/DiscoverRailSection.svelte';
import { DISCOVER_RAIL_LIMIT, deriveVisibleRailCount } from '$lib/features/discover/railPlan';
import ShelfActionMenu from '$lib/features/library/components/ShelfActionMenu.svelte';
import { flushResizeObservers, stubElementRect } from '../../harness/jsdomHarness';

/**
 * Non-vacuity proof for the jsdom harness.
 *
 * Each test here fails against the previous noop harness (and against the
 * missing scrollIntoView global), so the suite can no longer be green while the
 * harness silently delivers nothing.
 */

function mountedElement(): HTMLElement {
  const element = document.createElement('div');
  document.body.appendChild(element);
  return element;
}

function skeletonCount(container: HTMLElement): number {
  return container.querySelectorAll('section [aria-hidden="true"]').length;
}

function renderLoadingRail(): HTMLElement {
  const { container } = render(DiscoverRailSection, {
    props: {
      title: 'Newest',
      state: { kind: 'Loading' } as const,
      books: [],
      t: (key: string): string => key,
      onOpen: (): void => undefined,
      onRetry: (): void => undefined,
      onViewAll: (): void => undefined,
    },
  });
  return container;
}

describe('harness fidelity — ResizeObserver', () => {
  it('delivers nothing until a test opts in with a flush', () => {
    const element = mountedElement();
    const entries: ResizeObserverEntry[] = [];
    const observer = new ResizeObserver((observed) => {
      entries.push(...observed);
    });
    observer.observe(element);

    expect(entries).toHaveLength(0);

    stubElementRect(element, { width: 320, height: 200 });
    expect(entries).toHaveLength(0);

    flushResizeObservers();
    expect(entries).toHaveLength(1);
  });

  it('recomputes contentRect from the observed element, so a wrong claim fails', () => {
    const element = mountedElement();
    const entries: ResizeObserverEntry[] = [];
    const observer = new ResizeObserver((observed) => {
      entries.push(...observed);
    });
    observer.observe(element);

    stubElementRect(element, { left: 40, top: 120, width: 320, height: 200 });
    flushResizeObservers();

    const entry = entries[0];
    expect(entry.target).toBe(element);
    expect(entry.contentRect.width).toBe(320);
    expect(entry.contentRect.height).toBe(200);
    expect(entry.contentRect.left).toBe(40);
    expect(entry.contentRect.top).toBe(120);

    // A broken positioning claim throws instead of passing silently.
    expect(() => expect(entry.contentRect.top).toBe(999)).toThrow();
  });

  it('stops delivering to an unobserved target and to a disconnected observer', () => {
    const kept = mountedElement();
    const dropped = mountedElement();
    const delivered: Element[] = [];
    const observer = new ResizeObserver((observed) => {
      delivered.push(...observed.map((entry) => entry.target));
    });
    const disconnected = new ResizeObserver((observed) => {
      delivered.push(...observed.map((entry) => entry.target));
    });

    observer.observe(kept);
    observer.observe(dropped);
    disconnected.observe(mountedElement());

    observer.unobserve(dropped);
    disconnected.disconnect();

    flushResizeObservers();

    expect(delivered).toEqual([kept]);
  });

  it('feeds a real production consumer, so a stale width cannot pass', async () => {
    const container = renderLoadingRail();

    // jsdom measures every container as 0, so the rail falls back until a
    // flush delivers a real width.
    expect(skeletonCount(container)).toBe(DISCOVER_RAIL_LIMIT);

    const section = container.querySelector('section') as HTMLElement;
    stubElementRect(section, { width: 1280, height: 400 });

    flushResizeObservers();
    await tick();

    expect(deriveVisibleRailCount(1280)).toBe(7);
    expect(skeletonCount(container)).toBe(7);
  });
});

describe('harness fidelity — client rects', () => {
  it('stubs getClientRects alongside getBoundingClientRect, so the display gate can see the element', () => {
    const element = mountedElement();

    // jsdom has no layout: every element reports zero client rects, which
    // `tabbable` reads as "hidden" and therefore as "not focusable".
    expect(element.getClientRects()).toHaveLength(0);

    stubElementRect(element, { left: 40, top: 120, width: 320, height: 200 });

    const rects = element.getClientRects();
    expect(rects).toHaveLength(1);
    expect(rects[0].width).toBe(320);
    expect(rects[0].height).toBe(200);
    expect(rects[0].left).toBe(40);
    expect(rects[0].top).toBe(120);
    // A separate element keeps its own geometry, so the stub is per-element.
    expect(mountedElement().getClientRects()).toHaveLength(0);
  });
});

describe('harness fidelity — PointerEvent', () => {
  it('provides a MouseEvent subclass carrying the pointer fields', () => {
    expect(typeof globalThis.PointerEvent).toBe('function');

    const event = new PointerEvent('pointerdown', { pointerId: 3, pointerType: 'touch' });

    expect(event).toBeInstanceOf(MouseEvent);
    expect(event.pointerId).toBe(3);
    expect(event.pointerType).toBe('touch');
    expect(typeof event.pressure).toBe('number');
  });

  it('dispatches a pointerdown that drives a real production pointer listener', async () => {
    render(ShelfActionMenu, {
      props: {
        bookId: 'book-1',
        isFavorite: false,
        readLabel: 'Read',
        editLabel: 'Edit',
        removeLabel: 'Remove',
        favoriteAddLabel: 'Add to favorites',
        favoriteRemoveLabel: 'Remove from favorites',
        triggerLabel: 'Book actions',
        onEdit: (): void => undefined,
        onRemove: (): void => undefined,
        onToggleFavorite: (): void => undefined,
      },
    });
    await tick();

    const seen: PointerEvent[] = [];
    const spy = (event: Event): void => {
      seen.push(event as PointerEvent);
    };
    document.addEventListener('pointerdown', spy, true);

    await fireEvent.click(screen.getByRole('button', { name: 'Book actions' }));
    expect(screen.getByRole('menu')).toBeTruthy();

    await fireEvent.pointerDown(document.body, { pointerId: 11, pointerType: 'touch' });
    await tick();

    // The dispatched event is a real PointerEvent carrying the pointer fields...
    expect(seen).toHaveLength(1);
    expect(seen[0].pointerId).toBe(11);
    expect(seen[0].pointerType).toBe('touch');
    expect(seen[0]).toBeInstanceOf(MouseEvent);

    // ...and the production document-level pointerdown handler reacted to it.
    // The menu exits with a 150ms transition, so its node leaves the DOM just
    // after the close rather than in the same tick.
    await waitFor(() => expect(screen.queryByRole('menu')).toBeNull());

    document.removeEventListener('pointerdown', spy, true);
  });
});

describe('harness fidelity — pointer capture', () => {
  it('makes the pointer-capture guard callable, so a library handler cannot abort mid-open', () => {
    const element = mountedElement();

    // jsdom ships none of the three, and bits-ui's Select reads
    // `hasPointerCapture` unguarded inside its own pointerdown handler.
    expect(typeof element.hasPointerCapture).toBe('function');
    expect(element.hasPointerCapture(1)).toBe(false);
    expect(() => element.setPointerCapture(1)).not.toThrow();
    expect(() => element.releasePointerCapture(1)).not.toThrow();
  });

  it('lets a guard-shaped pointerdown handler reach its own body', () => {
    const element = mountedElement();
    let reached = false;

    element.addEventListener('pointerdown', (event) => {
      const target = event.target as Element;
      if (target.hasPointerCapture((event as PointerEvent).pointerId)) return;
      reached = true;
    });

    expect(() =>
      element.dispatchEvent(new PointerEvent('pointerdown', { pointerId: 1 })),
    ).not.toThrow();
    expect(reached).toBe(true);
  });
});

describe('harness fidelity — scroll stubs', () => {
  it('provides callable scrollIntoView and scrollTo no-ops', () => {
    const element = mountedElement();

    expect(typeof element.scrollIntoView).toBe('function');
    expect(typeof element.scrollTo).toBe('function');

    expect(() => element.scrollIntoView()).not.toThrow();
    expect(() => element.scrollIntoView({ block: 'center', behavior: 'smooth' })).not.toThrow();
    expect(() => element.scrollTo({ top: 10 })).not.toThrow();
  });
});
