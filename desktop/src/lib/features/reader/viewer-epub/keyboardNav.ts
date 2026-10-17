const INPUT_LIKE_TAGS = new Set(['INPUT', 'TEXTAREA', 'SELECT', 'BUTTON']);

const ARROW_INTENT_BY_KEY = {
  ArrowLeft: 'prevPage',
  ArrowRight: 'nextPage',
  ArrowUp: 'scrollUp',
  ArrowDown: 'scrollDown',
} as const;

export type ReaderArrowIntent = (typeof ARROW_INTENT_BY_KEY)[keyof typeof ARROW_INTENT_BY_KEY];

const hasEditableRole = (element: HTMLElement): boolean => {
  const role = element.getAttribute('role');
  if (!role) {
    return false;
  }

  return role === 'textbox' || role === 'searchbox' || role === 'combobox';
};

const isEditableElement = (element: HTMLElement): boolean => {
  if (element.isContentEditable) {
    return true;
  }

  const contentEditableAttr = element.getAttribute('contenteditable');
  if (contentEditableAttr === '' || contentEditableAttr === 'true') {
    return true;
  }

  if (element.contentEditable === 'true') {
    return true;
  }

  if (INPUT_LIKE_TAGS.has(element.tagName)) {
    return true;
  }

  return hasEditableRole(element);
};

const hasEditableContextInternal = (target: HTMLElement): boolean => {
  if (isEditableElement(target)) {
    return true;
  }

  let parent = target.parentElement;
  while (parent) {
    if (isEditableElement(parent)) {
      return true;
    }
    parent = parent.parentElement;
  }

  return false;
};

export const hasEditableContext = (element: Element | null): boolean => {
  if (!element) return false;
  if (!(element instanceof HTMLElement)) return false;
  return hasEditableContextInternal(element);
};

export const resolveFullscreenIntent = (event: KeyboardEvent): boolean => {
  if (event.defaultPrevented) return false;
  if (event.ctrlKey || event.metaKey || event.altKey) return false;
  if (event.key.toLowerCase() !== 'f') return false;
  const target = event.target;
  if (target instanceof HTMLElement && hasEditableContextInternal(target)) return false;
  if (target instanceof Element && hasEditableContext(target)) return false;
  return true;
};

export const isFullscreenToggle = resolveFullscreenIntent;

export const canHandleReaderArrowNav = (event: KeyboardEvent): boolean => {
  if (event.defaultPrevented) {
    return false;
  }

  if (event.altKey || event.ctrlKey || event.metaKey) {
    return false;
  }

  const target = event.target;
  if (!(target instanceof HTMLElement)) {
    return true;
  }

  if (hasEditableContext(target)) {
    return false;
  }

  return true;
};

export const resolveReaderArrowIntent = (event: KeyboardEvent): ReaderArrowIntent | null => {
  // Auto-repeat is the reader's *skip ahead* gesture, not a burst of intent.
  // Holding an arrow emits one event per repeat (~30/s on Windows after the
  // initial delay); resolving each one as a fresh intent would run a full
  // chapter render per repeat. Filtering here — in the resolution, not in a
  // consumer — means every caller inherits the rule instead of each one having
  // to remember it. The *first* event has `repeat === false`, so a deliberate
  // press still resolves and still moves.
  if (event.repeat) {
    return null;
  }

  if (!canHandleReaderArrowNav(event)) {
    return null;
  }

  return ARROW_INTENT_BY_KEY[event.key as keyof typeof ARROW_INTENT_BY_KEY] ?? null;
};
