import { defineConfig } from "vite";
import { svelte } from "@sveltejs/vite-plugin-svelte";
import tailwindcss from "@tailwindcss/vite";
import { sentryVitePlugin } from "@sentry/vite-plugin";
import { fileURLToPath, URL } from "node:url";
import fs from "node:fs";
import path from "node:path";
import { execSync } from "node:child_process";

// Derived once at config-load time. Falls back to "unknown" so `vite dev` and
// `vite build` don't crash when git isn't available (e.g. Docker scratch image).
// `--short=12` matches the cross-platform release scheme mandated by spec C1
// (same sha12 must appear on Rust + Android for a single commit); 12 chars
// avoid the 7-char collision risk at scale. Truncated to 12 if the repo
// somehow returns a shorter SHA (e.g. shallow clone).
let gitShortSha = "unknown";
try {
  gitShortSha = execSync("git rev-parse --short=12 HEAD", {
    stdio: ["ignore", "pipe", "ignore"],
  })
    .toString()
    .trim()
    .slice(0, 12);
} catch {
  // leave as "unknown"
}

const packageJson = JSON.parse(
  fs.readFileSync(path.resolve("package.json"), "utf-8"),
) as { version: string };

// `nexo-desktop@<version>+<git-sha>`. The plugin uploads source maps
// against the same release name so stack traces deobfuscate correctly.
// The prefix MUST match the Sentry project slug and the Rust side
// (`desktop/src-tauri/src/sentry_init.rs`, `SENTRY_RELEASE_PREFIX`), or the
// renderer's source maps land under a different release than its events.
// Sourced from `sdd/sentry-cross-platform/design` decision #2.
const sentryRelease = `nexo-desktop@${packageJson.version}+${gitShortSha}`;

const sentryAuthToken = process.env.SENTRY_AUTH_TOKEN;

// Disable the plugin entirely when no auth token is present (local dev).
// `vite build` must never fail in CI because someone forgot to set a token
// they don't have. See `sdd/sentry-cross-platform/design` risk table.
const sentryPluginOptions = {
  org: process.env.SENTRY_ORG,
  project: process.env.SENTRY_PROJECT,
  authToken: sentryAuthToken,
  release: { name: sentryRelease, inject: true },
  telemetry: false,
  // Skip the plugin in dev and when no auth token is set.
  devtool: false,
  disable: !sentryAuthToken,
};

function cfiLockstepPlugin() {
  return {
    name: "cfi-lockstep",
    buildStart() {
      // Gate: ensure parent CFI_RE / TERMINUS_RE stay in sync with iframe bridge string
      // We read the TS sources as text and verify inclusion without needing TS transpilation.
      const cfiBridgePath = path.resolve("src/lib/features/reader/viewer-epub/cfiBridge.ts");
      const iframePath = path.resolve("src/lib/features/reader/viewer-epub/cfiBridgeIframe.ts");
      try {
        const cfiBridge = fs.readFileSync(cfiBridgePath, "utf8");
        const iframe = fs.readFileSync(iframePath, "utf8");
        const expectedCFI = "^epubcfi\\(\\/6\\/(\\d+)!(.+)\\)$";
        const expectedTerminus = "^(\\d+):(\\d+)$";
        // Check cfiBridge exports the constants
        if (!cfiBridge.includes("export const CFI_RE") || !cfiBridge.includes(expectedCFI)) {
          throw new Error(`cfiBridge.ts missing CFI_RE ${expectedCFI}`);
        }
        if (!cfiBridge.includes("export const TERMINUS_RE") || !cfiBridge.includes(expectedTerminus)) {
          throw new Error(`cfiBridge.ts missing TERMINUS_RE ${expectedTerminus}`);
        }
        // Check iframe file contains double-escaped forms that produce single-escape runtime
        // File has \\( and \\d (2 backslashes) which after template evaluation become \( and \d in runtime.
        // The runtime string must contain the single-escape sources.
        // To simulate runtime unescaping: \\ in file -> \ in runtime
        const iframeRuntimeSim = iframe.replace(/\\\\/g, "\\");
        if (!iframeRuntimeSim.includes(expectedCFI)) {
          throw new Error(`IFRAME_CFI_BRIDGE_SCRIPT drift: missing CFI_RE ${expectedCFI} in runtime simulation`);
        }
        if (!iframeRuntimeSim.includes(expectedTerminus)) {
          throw new Error(`IFRAME_CFI_BRIDGE_SCRIPT drift: missing TERMINUS_RE ${expectedTerminus} in runtime simulation`);
        }
        // Detect double-escape bug: runtime should NOT contain \\( or \\d
        // After simulation, if we still see \\( it means file had \\\\ (4) -> runtime still has \\ (bug)
        if (iframeRuntimeSim.includes("\\\\(") || iframeRuntimeSim.includes("\\\\d")) {
          throw new Error("IFRAME_CFI_BRIDGE_SCRIPT contains double-escaped regex (\\\\( or \\\\d) — remove extra backslash");
        }
        // Also ensure assertLockstep is exported
        if (!cfiBridge.includes("export function assertLockstep")) {
          throw new Error("cfiBridge.ts missing assertLockstep export");
        }
      } catch (e) {
        // Surface as build failure
        const msg = e instanceof Error ? e.message : String(e);
        throw new Error(`[cfi-lockstep] Build blocked: ${msg}`);
      }
    },
  };
}

// In dev (no auth token / no upload), we pass an empty array to keep `dev`
// fast. The release name is still injected via `__SENTRY_RELEASE__` so
// `@sentry/browser` tags events correctly even without source-map upload.
const plugins = sentryAuthToken
  ? [tailwindcss(), svelte(), cfiLockstepPlugin(), sentryVitePlugin(sentryPluginOptions)]
  : [tailwindcss(), svelte(), cfiLockstepPlugin()];

export default defineConfig({
  // Tauri loads files from local bundle paths in production.
  // Relative asset URLs avoid a blank window caused by absolute /assets paths.
  base: "./",
  plugins,
    clearScreen: false,
    // Load .env from desktop folder (not parent)
    envDir: ".",
    define: {
      __APP_VERSION__: JSON.stringify(process.env.npm_package_version || '0.1.0'),
      // Exposed for `SentrySettings.release` in `sentryConfig.ts` and used
      // by `@sentry/browser`'s init config.
      __SENTRY_RELEASE__: JSON.stringify(sentryRelease),
    },
    resolve: {
      alias: {
        $lib: fileURLToPath(new URL("./src/lib", import.meta.url))
      }
    },
    server: {
      port: 1420,
      strictPort: true,
      watch: {
        ignored: ["**/src-tauri/**", "**/android/**"]
      }
    },
  build: {
    rollupOptions: {
      output: {
        manualChunks(id) {
          if (id.includes("pdfjs-dist")) {
            return "pdfjs";
          }
        }
      }
    }
  }
});
