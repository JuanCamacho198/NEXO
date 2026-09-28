import { defineConfig } from 'vitest/config';

export default defineConfig({
  resolve: {
    tsconfigPaths: true,
  },
  test: {
    environment: 'node',
    include: ['src/i18n/*.spec.ts', 'src/data/*.spec.ts'],
  },
});
