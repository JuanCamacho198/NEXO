import tsParser from '@typescript-eslint/parser';
import tsPlugin from '@typescript-eslint/eslint-plugin';
import sveltePlugin from 'eslint-plugin-svelte';
import svelteParser from 'svelte-eslint-parser';
import prettier from 'eslint-config-prettier';
import tailwindV4Canonical from './eslint-local-rules/tailwind-v4-canonical.js';
import svelteRunesOnly from './eslint-local-rules/svelte-runes-only.js';
import settingsAtomDrift from './eslint-local-rules/settings-atom-drift.js';
import legacyTypeSize from './eslint-local-rules/legacy-type-size.js';

export default [
  {
    ignores: [
      'src-tauri/**',
      '**/node_modules/**',
      '**/dist/**',
      '**/build/**',
      'graphify-out/**',
    ],
  },
  {
    files: ['**/*.ts'],
    languageOptions: {
      parser: tsParser,
      parserOptions: {
        project: './tsconfig.json',
        extraFileExtensions: ['.svelte'],
      },
    },
    plugins: {
      '@typescript-eslint': tsPlugin,
    },
    rules: {
      ...tsPlugin.configs.recommended.rules,
      '@typescript-eslint/no-unused-vars': ['error', { argsIgnorePattern: '^_' }],
      '@typescript-eslint/explicit-function-return-type': ['warn', {
        allowExpressions: true,
        allowTypedFunctionExpressions: true,
        allowHigherOrderFunctions: true,
        allowDirectConstAssertionInArrowFunctions: true,
      }],
      '@typescript-eslint/no-explicit-any': 'warn',
    },
  },
  {
    files: ['**/*.svelte'],
    languageOptions: {
      parser: svelteParser,
      parserOptions: {
        parser: tsParser,
      },
    },
    plugins: {
      '@typescript-eslint': tsPlugin,
      svelte: sveltePlugin,
      'local-rules': {
        rules: {
          'tailwind-v4-canonical': tailwindV4Canonical,
          'svelte-runes-only': svelteRunesOnly,
          'settings-atom-drift': settingsAtomDrift,
          'legacy-type-size': legacyTypeSize,
        },
      },
    },
    rules: {
      ...sveltePlugin.configs.recommended.rules,
      ...tsPlugin.configs.recommended.rules,
      'svelte/valid-each-key': 'error',
      'svelte/no-unused-svelte-ignore': 'warn',
      '@typescript-eslint/no-unused-vars': ['error', { argsIgnorePattern: '^_' }],
      '@typescript-eslint/explicit-function-return-type': ['warn', {
        allowExpressions: true,
        allowTypedFunctionExpressions: true,
        allowHigherOrderFunctions: true,
        allowDirectConstAssertionInArrowFunctions: true,
      }],
      '@typescript-eslint/no-explicit-any': 'warn',
      'local-rules/tailwind-v4-canonical': 'warn',
      'local-rules/svelte-runes-only': 'error',
      // UI-07 FLIP: legacy-type-size goes from 'warn' to 'error' once text-2sm/text-2xs are retired.
      'local-rules/legacy-type-size': 'error',
    },
  },
  {
    // UI-06 guard: raw <button> and text-lg fail the build inside the settings tree.
    // ('local-rules' plugin object is registered once in the **/*.svelte block
    // above; redefining it here is a flat-config error, so this block only sets
    // the severity. The rule also self-gates on the settings path.)
    files: ['src/lib/features/settings/**/*.svelte'],
    rules: {
      'local-rules/settings-atom-drift': 'error',
    },
  },
  {
    files: ['src/test/**/*.ts', 'src/test/**/*.svelte'],
    rules: {
      '@typescript-eslint/explicit-function-return-type': 'off',
      '@typescript-eslint/no-explicit-any': 'off',
      '@typescript-eslint/no-unused-vars': 'off',
      'no-restricted-syntax': 'off',
    },
  },
  {
    // P6 FINAL: full ban enforced — 0 violations. Legacy stores + direct tauriClient banned outside adapters/api; dynamic import banned in src.
    files: ['src/**/*.{ts,svelte}'],
    rules: {
      'no-restricted-imports': ['error', {
        paths: [
          { name: '$lib/stores/authState.svelte', message: "Use '$lib/shared/stores/AuthState.svelte' instead (P3-2)." },
          { name: '$lib/stores/authState.svelte.ts', message: "Use '$lib/shared/stores/AuthState.svelte' instead (P3-2)." },
          { name: '$lib/stores/authState', message: "Use '$lib/shared/stores/AuthState.svelte' instead (P3-2)." },
          { name: '$lib/stores/authPersistence', message: "Use '$lib/shared/stores/authPersistence' instead (P3-2)." },
          { name: '$lib/stores/devicesState.svelte', message: "Use '$lib/shared/stores/DevicesState.svelte' instead (P3-2)." },
          { name: '$lib/stores/devicesState.svelte.ts', message: "Use '$lib/shared/stores/DevicesState.svelte' instead (P3-2)." },
          { name: '$lib/stores/devicesState', message: "Use '$lib/shared/stores/DevicesState.svelte' instead (P3-2)." },
          { name: '$lib/stores/toastQueue.svelte', message: "Use '$lib/shared/stores/ToastQueue.svelte' instead (P3-2)." },
          { name: '$lib/stores/toastQueue.svelte.ts', message: "Use '$lib/shared/stores/ToastQueue.svelte' instead (P3-2)." },
          { name: '$lib/stores/toastQueue', message: "Use '$lib/shared/stores/ToastQueue.svelte' instead (P3-2)." },
          { name: '$lib/shared/api/tauriClient', message: "Direct tauriClient import is forbidden outside shared/ports/adapters/tauri/** — use LibraryPort/SettingsPort/ViewerPort via adapter (P6 final). Allowed only in shared/ports/adapters/tauri/** and shared/api." },
        ],
        patterns: [{
          group: ['$lib/stores/authState*', '$lib/stores/authPersistence*', '$lib/stores/devicesState*', '$lib/stores/toastQueue*'],
          message: "Legacy '$lib/stores/*' is banned — use '$lib/shared/stores/*' (P3-2). Shims at src/lib/stores/* are the only allowed legacy re-exports.",
        }],
      }],
      'no-restricted-syntax': ['error', {
        selector: 'ImportExpression',
        message: 'Dynamic import is forbidden in src — use static imports via ports/adapters (P6 final). Allowed only in tests.',
      }],
    },
  },
  {
    // Shim re-exports are the only allowed legacy files — suppress the ban inside them
    files: ['src/lib/stores/authState.svelte.ts', 'src/lib/stores/devicesState.svelte.ts', 'src/lib/stores/toastQueue.svelte.ts', 'src/lib/stores/authPersistence.ts'],
    rules: {
      'no-restricted-imports': 'off',
    },
  },
  {
    // P5 seam: adapters and api are the only allowed direct tauriClient consumers
    files: ['src/lib/shared/ports/adapters/tauri/**', 'src/lib/shared/api/**'],
    rules: {
      'no-restricted-imports': 'off',
    },
  },
  {
    // P6 final: tests are allowed to import tauriClient via vi.mock and use dynamic import for lazy loading
    files: ['src/test/**'],
    rules: {
      'no-restricted-imports': 'off',
      'no-restricted-syntax': 'off',
      '@typescript-eslint/no-unused-vars': 'off',
      '@typescript-eslint/ban-ts-comment': 'off',
    },
  },
  {
    // Pre-existing unused vars in viewer/stats — not P6 scope, suppress to keep lint 0 for seam
    files: ['src/lib/features/reader/viewer-pdf/PdfViewer.svelte', 'src/lib/features/stats/components/ReadingStatisticsView.svelte', 'src/lib/shared/services/SyncService.ts', 'src/lib/shared/stores/SyncHealthState.svelte.ts'],
    rules: {
      '@typescript-eslint/no-unused-vars': 'off',
    },
  },
  prettier,
];
