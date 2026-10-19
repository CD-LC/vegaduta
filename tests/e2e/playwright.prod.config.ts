import { defineConfig, devices } from "@playwright/test";
import { APP_URL } from "./helpers/prod-env";

/**
 * Deployed-instance (E2E_DOMAIN) E2E configuration.
 *
 * Deliberately separate from playwright.config.ts (which targets .xyz
 * staging) rather than a flag on it: per RULE 0 these are two different
 * environments, and a suite that can be pointed at production by forgetting
 * an env var is a footgun. testDir is its own folder for the same reason.
 *
 * Read-only by default. Specs that write to production are gated behind
 * E2E_ALLOW_MUTATIONS=1 (see helpers/prod-env.ts).
 */
export default defineConfig({
  testDir: "./tests-prod",
  fullyParallel: true,
  forbidOnly: !!process.env.CI,
  // Production is behind Cloudflare and cold Next.js routes can be slow on
  // first hit; one retry absorbs that without masking a real failure (a
  // genuinely broken page fails both attempts).
  retries: process.env.CI ? 2 : 1,
  // Bounded concurrency: this is real production infrastructure, not a local
  // stack. Kept at 4 - at 6 the run started producing load-induced flakes
  // (pages sampled before hydration) that were not real defects.
  workers: 4,
  reporter: [
    ["html", { outputFolder: "playwright-report-prod", open: "never" }],
    ["json", { outputFile: "test-results-prod/results.json" }],
    ["list"],
  ],
  // The per-test budget MUST stay comfortably above navigationTimeout below.
  // When it did not (60s test vs 90s navigation), a slow page blew the TEST
  // budget instead of failing its navigation gracefully, and 41 tests went
  // red at once for what was purely production latency.
  timeout: 150_000,
  expect: { timeout: 15_000 },
  use: {
    baseURL: APP_URL,
    trace: "on-first-retry",
    screenshot: "only-on-failure",
    video: "retain-on-failure",
    actionTimeout: 20_000,
    // 90s, not the more usual 30-45s: production sits behind Cloudflare and
    // its asset-heavy marketing pages intermittently take >45s to reach the
    // "load" event under concurrent workers. At 45s the suite reported that
    // latency as functional failures, and which tests it hit rotated between
    // runs - the tell that it was contention, not a defect.
    navigationTimeout: 90_000,
    // Identify this traffic in prod access logs so it can be told apart from
    // (and excluded from) real user analytics.
    userAgent:
      "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
      "Chrome/131.0.0.0 Safari/537.36 VegaDuta-E2E/1.0 (+prod-smoke)",
  },
  projects: [
    {
      name: "chromium",
      use: { ...devices["Desktop Chrome"], viewport: { width: 1280, height: 800 } },
    },
    {
      name: "mobile-chrome",
      use: { ...devices["Pixel 7"] },
    },
  ],
});
