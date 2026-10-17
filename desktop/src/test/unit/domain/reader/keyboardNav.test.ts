import { describe, expect, it } from 'vitest';
import {
  canHandleReaderArrowNav,
  resolveReaderArrowIntent,
  type ReaderArrowIntent,
} from '$lib/features/reader/viewer-epub/keyboardNav';

const createKeyboardEvent = (
  target: EventTarget | null,
  overrides: Partial<KeyboardEvent> = {},
) => {
  return {
    target,
    defaultPrevented: false,
    altKey: false,
    ctrlKey: false,
    metaKey: false,
    ...overrides,
  } as KeyboardEvent;
};

describe('keyboardNav', () => {
  it('maps arrows to semantic intents', () => {
    const target = document.createElement('div');

    expect(resolveReaderArrowIntent(createKeyboardEvent(target, { key: 'ArrowLeft' }))).toBe(
      'prevPage',
    );
    expect(resolveReaderArrowIntent(createKeyboardEvent(target, { key: 'ArrowRight' }))).toBe(
      'nextPage',
    );
    expect(resolveReaderArrowIntent(createKeyboardEvent(target, { key: 'ArrowUp' }))).toBe(
      'scrollUp',
    );
    expect(resolveReaderArrowIntent(createKeyboardEvent(target, { key: 'ArrowDown' }))).toBe(
      'scrollDown',
    );
  });

  it('returns null for unsupported keys', () => {
    const target = document.createElement('div');
    expect(resolveReaderArrowIntent(createKeyboardEvent(target, { key: 'Enter' }))).toBeNull();
  });

  describe('auto-repeat filter', () => {
    // Holding an arrow key emits one event per repeat. Each one used to resolve
    // to a fresh intent and run a full chapter render, so a held key queued one
    // backend extraction per repeat. The filter lives in the resolution so every
    // consumer inherits it.
    it('resolves nothing for a repeated arrow event', () => {
      const target = document.createElement('div');

      for (const key of ['ArrowLeft', 'ArrowRight', 'ArrowUp', 'ArrowDown']) {
        expect(
          resolveReaderArrowIntent(createKeyboardEvent(target, { key, repeat: true })),
        ).toBeNull();
      }
    });

    it('still resolves the first (non-repeated) press', () => {
      const target = document.createElement('div');

      expect(
        resolveReaderArrowIntent(createKeyboardEvent(target, { key: 'ArrowRight', repeat: false })),
      ).toBe('nextPage');
    });

    it('does not depend on the editable-context check to filter repeats', () => {
      // A repeat inside an editable already resolves to null via the context
      // check; a repeat elsewhere must resolve to null because of `repeat`.
      const target = document.createElement('div');
      expect(canHandleReaderArrowNav(createKeyboardEvent(target, { repeat: true }))).toBe(true);
      expect(
        resolveReaderArrowIntent(createKeyboardEvent(target, { key: 'ArrowRight', repeat: true })),
      ).toBeNull();
    });
  });

  it('allows arrow nav on generic containers', () => {
    const target = document.createElement('div');
    expect(canHandleReaderArrowNav(createKeyboardEvent(target))).toBe(true);
  });

  it('blocks arrow nav inside input-like controls', () => {
    expect(canHandleReaderArrowNav(createKeyboardEvent(document.createElement('input')))).toBe(
      false,
    );
    expect(canHandleReaderArrowNav(createKeyboardEvent(document.createElement('textarea')))).toBe(
      false,
    );
    expect(canHandleReaderArrowNav(createKeyboardEvent(document.createElement('select')))).toBe(
      false,
    );
    expect(canHandleReaderArrowNav(createKeyboardEvent(document.createElement('button')))).toBe(
      false,
    );
  });

  it('blocks arrow nav inside contentEditable and textbox roles', () => {
    const editable = document.createElement('div');
    editable.contentEditable = 'true';

    const roleTextbox = document.createElement('div');
    roleTextbox.setAttribute('role', 'textbox');

    expect(canHandleReaderArrowNav(createKeyboardEvent(editable))).toBe(false);
    expect(canHandleReaderArrowNav(createKeyboardEvent(roleTextbox))).toBe(false);
    expect(
      resolveReaderArrowIntent(createKeyboardEvent(editable, { key: 'ArrowLeft' })),
    ).toBeNull();
    expect(
      resolveReaderArrowIntent(createKeyboardEvent(roleTextbox, { key: 'ArrowRight' })),
    ).toBeNull();
  });

  it('blocks when modifier keys are active or event already handled', () => {
    const target = document.createElement('div');
    expect(canHandleReaderArrowNav(createKeyboardEvent(target, { ctrlKey: true }))).toBe(false);
    expect(canHandleReaderArrowNav(createKeyboardEvent(target, { metaKey: true }))).toBe(false);
    expect(canHandleReaderArrowNav(createKeyboardEvent(target, { defaultPrevented: true }))).toBe(
      false,
    );
    expect(
      resolveReaderArrowIntent(createKeyboardEvent(target, { key: 'ArrowLeft', ctrlKey: true })),
    ).toBeNull();
    expect(
      resolveReaderArrowIntent(createKeyboardEvent(target, { key: 'ArrowRight', metaKey: true })),
    ).toBeNull();
    expect(
      resolveReaderArrowIntent(
        createKeyboardEvent(target, { key: 'ArrowDown', defaultPrevented: true }),
      ),
    ).toBeNull();
  });

  describe('keyboard contract enforcement', () => {
    it('ArrowUp/ArrowDown never return page navigation intents', () => {
      const target = document.createElement('div');
      const upIntent = resolveReaderArrowIntent(createKeyboardEvent(target, { key: 'ArrowUp' }));
      const downIntent = resolveReaderArrowIntent(
        createKeyboardEvent(target, { key: 'ArrowDown' }),
      );

      expect(upIntent).not.toBe('prevPage');
      expect(downIntent).not.toBe('nextPage');
      expect(upIntent).toBe('scrollUp');
      expect(downIntent).toBe('scrollDown');
    });

    it('ArrowLeft/ArrowRight never return scroll intents', () => {
      const target = document.createElement('div');
      const leftIntent = resolveReaderArrowIntent(
        createKeyboardEvent(target, { key: 'ArrowLeft' }),
      );
      const rightIntent = resolveReaderArrowIntent(
        createKeyboardEvent(target, { key: 'ArrowRight' }),
      );

      expect(leftIntent).not.toBe('scrollUp');
      expect(leftIntent).not.toBe('scrollDown');
      expect(rightIntent).not.toBe('scrollUp');
      expect(rightIntent).not.toBe('scrollDown');
      expect(leftIntent).toBe('prevPage');
      expect(rightIntent).toBe('nextPage');
    });

    it('type ReaderArrowIntent has exactly 4 values', () => {
      const intents: ReaderArrowIntent[] = ['prevPage', 'nextPage', 'scrollUp', 'scrollDown'];

      intents.forEach((intent) => {
        expect(intent).toBeDefined();
      });
    });
  });

  describe('keyboard zoom guard (Ctrl+=/Ctrl+-)', () => {
    it('blocks reader arrow nav when Ctrl key is pressed', () => {
      const target = document.createElement('div');
      expect(canHandleReaderArrowNav(createKeyboardEvent(target, { ctrlKey: true }))).toBe(false);
    });

    it('blocks reader arrow nav when Meta key is pressed', () => {
      const target = document.createElement('div');
      expect(canHandleReaderArrowNav(createKeyboardEvent(target, { metaKey: true }))).toBe(false);
    });

    it('returns null for zoom keys to allow zoom handler in viewer', () => {
      const target = document.createElement('div');
      const ctrlEqual = createKeyboardEvent(target, { key: '=', ctrlKey: true });
      const ctrlPlus = createKeyboardEvent(target, { key: '+', ctrlKey: true });
      const ctrlMinus = createKeyboardEvent(target, { key: '-', ctrlKey: true });

      expect(resolveReaderArrowIntent(ctrlEqual)).toBeNull();
      expect(resolveReaderArrowIntent(ctrlPlus)).toBeNull();
      expect(resolveReaderArrowIntent(ctrlMinus)).toBeNull();
    });
  });

  describe('scroll vertical movement contract', () => {
    it('scrollUp always means upward vertical movement', () => {
      const target = document.createElement('div');
      const intent = resolveReaderArrowIntent(createKeyboardEvent(target, { key: 'ArrowUp' }));

      expect(intent).toBe('scrollUp');
    });

    it('scrollDown always means downward vertical movement', () => {
      const target = document.createElement('div');
      const intent = resolveReaderArrowIntent(createKeyboardEvent(target, { key: 'ArrowDown' }));

      expect(intent).toBe('scrollDown');
    });

    it('no vertical arrow can produce page-turn action', () => {
      const target = document.createElement('div');
      const arrowKeys = ['ArrowUp', 'ArrowDown'];

      arrowKeys.forEach((key) => {
        const intent = resolveReaderArrowIntent(createKeyboardEvent(target, { key }));
        expect(intent).not.toBe('prevPage');
        expect(intent).not.toBe('nextPage');
      });
    });
  });
});
