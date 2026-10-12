import { describe, expect, it } from 'vitest';
import { classifySelectionMessage } from '$lib/features/reader/viewer-epub/useEpubBridge.svelte';

describe('classifySelectionMessage (U1) — clears beat stale guards', () => {
  it('selects a current-chapter payload with text', () => {
    expect(classifySelectionMessage({ text: 'some text', pageNumber: 2 }, 2)).toBe('select');
  });

  it('selects text without a pageNumber (caller resolves the default)', () => {
    expect(classifySelectionMessage({ text: 'some text' }, 2)).toBe('select');
  });

  it('drops a stale selection WITH text', () => {
    expect(classifySelectionMessage({ text: 'some text', pageNumber: 5 }, 2)).toBe('drop');
  });

  it('clears on empty text even with a stale pageNumber', () => {
    expect(classifySelectionMessage({ text: '', pageNumber: 5 }, 2)).toBe('clear');
  });

  it('clears on missing text for the current chapter', () => {
    expect(classifySelectionMessage({ pageNumber: 2 }, 2)).toBe('clear');
  });
});
