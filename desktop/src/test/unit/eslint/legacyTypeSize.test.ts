/**
 * Guardrail rule test for `local-rules/legacy-type-size` (ERROR).
 *
 * UI-07: `text-2sm` / `text-2xs` are retired — no consumer references them —
 * so any reintroduction errors instead of warning.
 * One positive case per size plus a ratified-scale negative; severity is
 * asserted through Linter — the diagnostic must report at error (2), which
 * keeps `bun run lint` red on any regression.
 */
import { describe, expect, it } from 'vitest';
import { Linter, RuleTester } from 'eslint';
import svelteParser from 'svelte-eslint-parser';
import tsParser from '@typescript-eslint/parser';
import legacyTypeSize from '../../../../eslint-local-rules/legacy-type-size.js';

const ruleTester = new RuleTester({
  languageOptions: {
    parser: svelteParser,
    parserOptions: {
      parser: tsParser,
    },
  },
});

ruleTester.run('legacy-type-size', legacyTypeSize, {
  valid: [
    {
      // Ratified roles stay clean.
      filename: 'RatifiedComponent.svelte',
      code: `<span class="text-xs text-(--color-text-muted)">meta</span>
<span class="text-xl font-semibold">Title</span>`,
    },
  ],
  invalid: [
    {
      filename: 'Legacy2smComponent.svelte',
      code: `<span class="text-2sm font-medium">label</span>`,
      errors: [{ messageId: 'legacyTypeSize', data: { actual: 'text-2sm' } }],
    },
    {
      filename: 'Legacy2xsComponent.svelte',
      code: `<span class="text-2xs text-(--color-text-muted)">meta</span>`,
      errors: [{ messageId: 'legacyTypeSize', data: { actual: 'text-2xs' } }],
    },
    {
      // Detection is per occurrence.
      filename: 'LegacyBothComponent.svelte',
      code: `<span class="text-2sm">a</span><span class="text-2xs">b</span>`,
      errors: [
        { messageId: 'legacyTypeSize', data: { actual: 'text-2sm' } },
        { messageId: 'legacyTypeSize', data: { actual: 'text-2xs' } },
      ],
    },
  ],
});

const linter = new Linter();

describe('legacy-type-size severity', () => {
  it('reports at error severity so regressions fail the build', () => {
    const messages = linter.verify(
      `<span class="text-2xs">meta</span>`,
      {
        files: ['**/*.svelte'],
        plugins: {
          'local-rules': { rules: { 'legacy-type-size': legacyTypeSize } },
        },
        languageOptions: {
          parser: svelteParser,
          parserOptions: { parser: tsParser },
        },
        rules: { 'local-rules/legacy-type-size': 'error' },
      },
      'LegacyComponent.svelte',
    );
    expect(messages).toHaveLength(1);
    expect(messages[0]?.severity).toBe(2);
    expect(messages[0]?.ruleId).toBe('local-rules/legacy-type-size');
  });
});
