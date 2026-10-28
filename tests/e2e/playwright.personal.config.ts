import { defineConfig, devices } from "@playwright/test";
import { APP_BASE_URL } from "./helpers/e2e-env";

/**
 * Playwright project for the PERSONAL edition of the console (VEGADUTA_EDITION=personal: local sign-in, no
 * Keycloak, no billing, one owner). It runs the personal specs — tests/personal-edition.spec.ts (the route/
 * feature contract) and tests/personal-smoke.spec.ts (the release smoke: sign-in, keyboard, edge surface, one
 * real assistant turn) — against a running personal stack; the Keycloak-stack suites do not run in this project.
 *
 *   # 1. a personal stack is up (scripts/vegaduta personal) and its owner has finished first-run setup
 *   # 2. from tests/e2e:
 *   E2E_BASE_URL=http://localhost:3000 \
 *   E2E_API_BASE_URL=http://localhost:8080 \
 *   E2E_PERSONAL_EMAIL=<owner e-mail> E2E_PERSONAL_PASSWORD=<owner password> \
 *   npx playwright test -c playwright.personal.config.ts
 *
 * Without E2E_PERSONAL_EMAIL / E2E_PERSONAL_PASSWORD the signed-out checks (HTTP 404s, redirects, robots.txt,
 * sitemap, /api/public/status) still run and the signed-in checks (rail, Ctrl+K, channels grid, /usage) are
 * skipped with a reason. Set E2E_PERSONAL_PLATFORM_ADMIN=1 when the owner holds the platform-admin role
 * (APP_AUTH_LOCAL_OWNER_EXTRA_ROLES=platform-admin) to also check the /platform-admin allowlist navigation.
 *
 * The spec file is skipped unless E2E_EDITION=personal, which this config sets, so the default config
 * (playwright.config.ts, whose testDir is the same folder) reports it as skipped instead of failing it against a
 * Keycloak stack. Credentials come from the environment only; never put them in a file.
 */
process.env.E2E_EDITION ||= "personal";

export default defineConfig({
  testDir: "./tests",
  testMatch: /personal-(edition|smoke)\.spec\.ts/,
  // One owner, one login throttle: keep the signed-in checks sequential.
  fullyParallel: false,
  workers: 1,
  forbidOnly: !!process.env.CI,
  retries: 1,
  reporter: [
    ["html", { open: "never", outputFolder: "playwright-report-personal" }],
    ["list"],
  ],
  outputDir: "test-results-personal",
  timeout: 60_000,
  expect: { timeout: 10_000 },
  use: {
    baseURL: APP_BASE_URL,
    // The assertions compare against the English labels in web/messages/en.json.
    locale: "en-US",
    trace: "on-first-retry",
    screenshot: "only-on-failure",
    video: "retain-on-failure",
    actionTimeout: 15_000,
    navigationTimeout: 30_000,
  },
  projects: [
    {
      name: "personal-chromium",
      use: {
        ...devices["Desktop Chrome"],
        viewport: { width: 1440, height: 900 },
      },
    },
  ],
});
