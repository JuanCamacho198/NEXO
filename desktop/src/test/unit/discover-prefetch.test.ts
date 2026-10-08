import { fireEvent, render, screen } from '@testing-library/svelte';
import { describe, expect, it, vi } from 'vitest';
import DiscoverCard from '$lib/features/discover/DiscoverCard.svelte';
import {
  DISCOVER_PREFETCH_CONCURRENCY,
  DiscoverDomainState,
} from '$lib/features/discover/DiscoverDomainState.svelte';
import type { CatalogBook, CatalogProvider } from '$lib/shared/services/catalog/CatalogProvider';
import { catalogError } from '$lib/shared/services/catalog/errors';

function fakeBook(overrides: Partial<CatalogBook> = {}): CatalogBook {
  return {
    id: 'gutendex:1342',
    provider: 'builtin:gutendex',
    title: 'Pride and Prejudice',
    authors: ['Jane Austen'],
    coverUrl: null,
    languages: ['en'],
    subjects: ['Fiction'],
    downloadUrl: null,
    ...overrides,
  };
}

function fakeProvider(getDetails: (id: string) => Promise<CatalogBook>): CatalogProvider {
  return {
    async search() {
      return { results: [], nexoPage: null, totalCount: 0 };
    },
    getDetails,
    resolveDownloadUrl() {
      throw catalogError('UNAVAILABLE_DOWNLOAD', 'no usable url');
    },
    listSources() {
      return [];
    },
    async featured() {
      return { results: [], nexoPage: null, totalCount: 0 };
    },
    supportsFeatured() {
      return false;
    },
    async searchSource() {
      return { results: [], nexoPage: null, totalCount: 0 };
    },
  };
}

/** Let already-settled promises publish. */
const flush = (): Promise<void> => new Promise((resolve) => setTimeout(resolve, 0));

describe('DiscoverCard intent prefetch (DISC-03)', () => {
  it('does not prefetch on render', () => {
    const book = fakeBook();
    const onPrefetch = vi.fn();
    render(DiscoverCard, { props: { book, onOpen: vi.fn(), onPrefetch } });
    expect(onPrefetch).not.toHaveBeenCalled();
  });

  it('pointer-enter prefetches the card id exactly once through to getDetails', async () => {
    const book = fakeBook();
    const getDetails = vi.fn(async () => book);
    const state = new DiscoverDomainState(fakeProvider(getDetails));
    render(DiscoverCard, {
      props: { book, onOpen: vi.fn(), onPrefetch: (b) => state.prefetchDetail(b) },
    });

    await fireEvent.pointerEnter(screen.getByRole('button'));
    await flush();

    expect(getDetails).toHaveBeenCalledTimes(1);
    expect(getDetails).toHaveBeenCalledWith(book.id);
  });

  it('focus prefetches the card id exactly once through to getDetails', async () => {
    const book = fakeBook();
    const getDetails = vi.fn(async () => book);
    const state = new DiscoverDomainState(fakeProvider(getDetails));
    render(DiscoverCard, {
      props: { book, onOpen: vi.fn(), onPrefetch: (b) => state.prefetchDetail(b) },
    });

    await fireEvent.focus(screen.getByRole('button'));
    await flush();

    expect(getDetails).toHaveBeenCalledTimes(1);
    expect(getDetails).toHaveBeenCalledWith(book.id);
  });

  it('still opens the detail on click', async () => {
    const book = fakeBook();
    const onOpen = vi.fn();
    render(DiscoverCard, { props: { book, onOpen, onPrefetch: vi.fn() } });

    await fireEvent.click(screen.getByRole('button'));

    expect(onOpen).toHaveBeenCalledOnce();
    expect(onOpen).toHaveBeenCalledWith(book);
  });
});

describe('DiscoverDomainState.prefetchDetail (DISC-03)', () => {
  it('dedupes repeated intent for the same id into one in-flight request', async () => {
    const book = fakeBook();
    let resolveDetails!: (value: CatalogBook) => void;
    const getDetails = vi.fn(
      () =>
        new Promise<CatalogBook>((resolve) => {
          resolveDetails = resolve;
        }),
    );
    const state = new DiscoverDomainState(fakeProvider(getDetails));

    state.prefetchDetail(book);
    state.prefetchDetail(book);
    state.prefetchDetail(book);

    expect(getDetails).toHaveBeenCalledTimes(1);

    resolveDetails(book);
    await flush();
  });

  it('swallows a rejecting prefetch and leaves visible detail state untouched', async () => {
    const book = fakeBook();
    const getDetails = vi.fn(async () => {
      throw catalogError('NETWORK_ERROR', 'offline');
    });
    const state = new DiscoverDomainState(fakeProvider(getDetails));

    state.prefetchDetail(book);
    await flush();

    expect(getDetails).toHaveBeenCalledTimes(1);
    expect(state.detail).toBeNull();
    expect(state.detailStatus).toBe('closed');
    expect(state.errorCode).toBeNull();
  });

  it('does not prefetch an id whose detail is already open', async () => {
    const book = fakeBook();
    const getDetails = vi.fn(async () => book);
    const state = new DiscoverDomainState(fakeProvider(getDetails));

    await state.openDetail(book);
    expect(getDetails).toHaveBeenCalledTimes(1);

    state.prefetchDetail(book);

    expect(getDetails).toHaveBeenCalledTimes(1);
  });

  it('bounds concurrent prefetches across distinct ids at the cap', () => {
    const books = Array.from({ length: 8 }, (_, index) =>
      fakeBook({ id: `gutendex:${index}`, title: `Title ${index}` }),
    );
    const getDetails = vi.fn(() => new Promise<CatalogBook>(() => {}));
    const state = new DiscoverDomainState(fakeProvider(getDetails));

    for (const book of books) state.prefetchDetail(book);

    expect(DISCOVER_PREFETCH_CONCURRENCY).toBeLessThan(books.length);
    expect(getDetails).toHaveBeenCalledTimes(DISCOVER_PREFETCH_CONCURRENCY);
  });

  it('drops new intent rather than queueing it once the cap is reached', async () => {
    const first = fakeBook({ id: 'gutendex:1' });
    const second = fakeBook({ id: 'gutendex:2' });
    const third = fakeBook({ id: 'gutendex:3' });
    const dropped = fakeBook({ id: 'gutendex:4' });
    const resolvers: Array<(value: CatalogBook) => void> = [];
    const getDetails = vi.fn(
      () =>
        new Promise<CatalogBook>((resolve) => {
          resolvers.push(resolve);
        }),
    );
    const state = new DiscoverDomainState(fakeProvider(getDetails));

    state.prefetchDetail(first);
    state.prefetchDetail(second);
    state.prefetchDetail(third);
    state.prefetchDetail(dropped);
    expect(getDetails).toHaveBeenCalledTimes(DISCOVER_PREFETCH_CONCURRENCY);

    // Settle the running set; the dropped id never surfaces later (no queue).
    for (const resolve of resolvers) resolve(first);
    await flush();

    expect(getDetails).toHaveBeenCalledTimes(DISCOVER_PREFETCH_CONCURRENCY);
  });
});
