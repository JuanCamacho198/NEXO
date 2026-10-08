/**
 * Guardrail rule test for `local-rules/settings-atom-drift` (ERROR).
 *
 * UI-06: the settings tree renders through the atoms (zero raw `<button>`,
 * `text-lg` banned from UI chrome), and this suite proves the guard is not
 * vacuous — one positive case per diagnostic plus scope negatives. Severity
 * is asserted through Linter: both diagnostics must report at error (2).
 */
import { describe, expect, it } from 'vitest';
import { Linter, RuleTester } from 'eslint';
import svelteParser from 'svelte-eslint-parser';
import tsParser from '@typescript-eslint/parser';
import settingsAtomDrift from '../../../../eslint-local-rules/settings-atom-drift.js';

const SETTINGS_FILE = 'src/lib/features/settings/components/DriftProbe.svelte';
const OUTSIDE_FILE = 'src/lib/features/library/components/OutsideProbe.svelte';

const ruleTester = new RuleTester({
  languageOptions: {
    parser: svelteParser,
    parserOptions: {
      parser: tsParser,
    },
  },
});

ruleTester.run('settings-atom-drift', settingsAtomDrift, {
  valid: [
    {
      // Atoms plus ratified roles: zero diagnostics expected.
      filename: SETTINGS_FILE,
      code: `<script lang="ts">import Button from '$lib/shared/ui/forms/Button.svelte';</script>
<Button variant="primary" size="sm">Save</Button>
<span class="text-xl font-semibold">Title</span>`,
    },
    {
      // Scope gate: raw <button> and text-lg outside the settings tree stay clean.
      filename: OUTSIDE_FILE,
      code: `<button class="text-lg">legacy</button>`,
    },
  ],
  invalid: [
    {
      filename: SETTINGS_FILE,
      code: `<button onclick={() => {}}>Save</button>`,
      errors: [{ messageId: 'rawButton' }],
    },
    {
      filename: SETTINGS_FILE,
      code: `<span class="text-lg font-semibold">About</span>`,
      output: `<span class="text-xl font-semibold">About</span>`,
      errors: [{ messageId: 'bannedTextLg' }],
    },
    {
      // Detection is per occurrence, even in an otherwise clean file.
      filename: SETTINGS_FILE,
      code: `<script lang="ts">import Button from '$lib/shared/ui/forms/Button.svelte';</script>
<Button variant="primary" size="sm">Save</Button>
<button>drift</button>
<span class="text-lg">drift</span>`,
      output: `<script lang="ts">import Button from '$lib/shared/ui/forms/Button.svelte';</script>
<Button variant="primary" size="sm">Save</Button>
<button>drift</button>
<span class="text-xl">drift</span>`,
      errors: [{ messageId: 'rawButton' }, { messageId: 'bannedTextLg' }],
    },
  ],
});

const linter = new Linter();

function lint(code: string, filename: string, severity: 'error' | 'warn'): Linter.LintMessage[] {
  return linter.verify(
    code,
    {
      files: ['**/*.svelte'],
      plugins: {
        'local-rules': { rules: { 'settings-atom-drift': settingsAtomDrift } },
      },
      languageOptions: {
        parser: svelteParser,
        parserOptions: { parser: tsParser },
      },
      rules: { 'local-rules/settings-atom-drift': severity },
    },
    filename,
  );
}

describe('settings-atom-drift severity', () => {
  it('reports raw <button> at error severity', () => {
    const messages = lint(`<button>Save</button>`, SETTINGS_FILE, 'error');
    expect(messages).toHaveLength(1);
    expect(messages[0]?.severity).toBe(2);
    expect(messages[0]?.ruleId).toBe('local-rules/settings-atom-drift');
  });

  it('reports text-lg at error severity', () => {
    const messages = lint(`<span class="text-lg">About</span>`, SETTINGS_FILE, 'error');
    expect(messages).toHaveLength(1);
    expect(messages[0]?.severity).toBe(2);
  });

  it('stays silent outside the settings tree', () => {
    expect(lint(`<button class="text-lg">x</button>`, OUTSIDE_FILE, 'error')).toHaveLength(0);
  });
});
