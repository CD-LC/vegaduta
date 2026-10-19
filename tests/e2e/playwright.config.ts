import { defineConfig, devices } from "@playwright/test";
import { APP_BASE_URL } from "./helpers/e2e-env";

// VegaDuta public-surface E2E suite. E2E_BASE_URL points it at an
// environment (local dev, a preview deploy, staging) without touching this
// file - see helpers/e2e-env.ts for all target URLs and their defaults.
const BASE_URL = APP_BASE_URL;

export default defineConfig({
  testDir: "./tests",
  fullyParallel: true,
  forbidOnly: !!process.env.CI,
  retries: 1,
  reporter: [["html", { open: "never" }], ["list"]],
  timeout: 45_000,
  expect: {
    timeout: 10_000,
  },
  use: {
    baseURL: BASE_URL,
    trace: "on-first-retry",
    screenshot: "only-on-failure",
    video: "retain-on-failure",
    actionTimeout: 15_000,
    navigationTimeout: 30_000,
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
