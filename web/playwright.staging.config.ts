import { defineConfig, devices } from "@playwright/test";

/**
 * Playwright Configuration for VegaDūta STAGING Testing
 * Tests against a deployed staging environment. Point it at yours with
 * STAGING_BASE_URL (or BASE_URL); defaults to the local dev server.
 */

export default defineConfig({
  testDir: "./e2e",
  fullyParallel: true,
  forbidOnly: false,
  retries: 1,
  workers: 4, // Parallel workers for speed

  reporter: [
    ["html", { outputFolder: "test-results/staging" }],
    ["json", { outputFile: "test-results/staging/results.json" }],
    ["junit", { outputFile: "test-results/staging/results.xml" }],
    ["list"],
  ],

  use: {
    // STAGING ENVIRONMENT URLs
    baseURL: process.env.STAGING_BASE_URL || process.env.BASE_URL || "http://localhost:3000",
    trace: "on-first-retry",
    screenshot: "only-on-failure",
    video: "retain-on-failure",
    actionTimeout: 15000, // Longer for staging
  },

  projects: [
    {
      name: "chromium",
      use: { ...devices["Desktop Chrome"] },
    },
    {
      name: "firefox",
      use: { ...devices["Desktop Firefox"] },
    },
    {
      name: "mobile-chrome",
      use: { ...devices["Pixel 5"] },
    },
  ],

  // Don't start web server - connect to staging
  webServer: undefined,
});
