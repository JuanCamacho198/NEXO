import { defineConfig } from "vitest/config";
import { svelte } from "@sveltejs/vite-plugin-svelte";
import { fileURLToPath, URL } from "node:url";

export default defineConfig({
  plugins: [svelte()],
  resolve: {
    conditions: ["browser"],
    alias: {
      $lib: fileURLToPath(new URL("./src/lib", import.meta.url)),
    },
  },
  test: {
    environment: "jsdom",
    globals: true,
    setupFiles: ["./src/test/setup.ts"],
    include: ["src/test/**/*.test.ts", "src/test/**/*.test.tsx", "src/test/**/*.test.svelte.ts"],
    coverage: {
      provider: "v8",
      reporter: ["text", "html", "json-summary"],
      reportsDirectory: "./coverage",
      // Product code only. Measuring the suite's own fixtures/doubles would
      // inflate the numbers with files that exist to be partially executed.
      include: ["src/**/*.{ts,svelte.ts,svelte}"],
      exclude: [
        "src/test/**",
        "src/**/*.d.ts",
        "src/**/index.ts",
        "src/main.ts",
        "src/lib/dev/**",
        "src/lib/shared/i18n/messages.*.ts",
        "**/*.config.*",
      ],
      // Thresholds are set a little below the first measured baseline
      // (lines 70.15 / statements 67.92 / functions 67.64 / branches 58.00 on
      // the 3031-test suite). The gaps leave room for small refactors without
      // blocking every run, while still catching a real regression. Raise these
      // as the suite grows; do not lower them to make a failing run pass.
      thresholds: {
        lines: 66,
        statements: 63,
        functions: 64,
        branches: 52,
      },
    },
  },
});
