// End-to-end business-case suite (July 2026 "test everything in browser"
// pass). Drives the real Next.js console against a real running core +
// sidecar + Postgres + Keycloak + Redis stack - there is no mocking layer
// anywhere in this suite on purpose, matching every prior STAGE-*-
// VERIFICATION.md doc's "real stack, real data" convention. See
// e2e/README.md for how to bring that stack up before running this.
const { defineConfig, devices } = require("@playwright/test");

const BASE_URL = process.env.E2E_BASE_URL || "http://localhost:3000";

// Watch-mode knobs - both env-overridable rather than requiring a config
// edit every time you want to switch between "watch it run" and "run fast
// headless." Defaults below match what was hand-set here (visible browser,
// 1s pause between actions) - override for a fast/CI-style run with:
//   $env:E2E_HEADLESS = "true"; $env:E2E_SLOWMO_MS = "0"
const HEADLESS = process.env.E2E_HEADLESS ? process.env.E2E_HEADLESS === "true" : false;
const SLOWMO_MS = process.env.E2E_SLOWMO_MS ? Number(process.env.E2E_SLOWMO_MS) : 1000;

module.exports = defineConfig({
  testDir: "./e2e/specs",
  // Generous timeouts throughout: audio/video transcription jobs (F3c/F3d)
  // can take real minutes on a CPU-only sidecar, and Keycloak's own login
  // redirect round-trip is slower than an in-app navigation. Not shortened
  // to account for slowMo below - slowMo adds real wall-clock time on top
  // of these, so a slow watched run needs the same generous ceiling a fast
  // one does, just more of it in practice.
  timeout: 120_000,
  expect: { timeout: 15_000 },
  fullyParallel: false, // most specs share tenant-scoped state (agents, collections) - see e2e/fixtures.js
  workers: 1,
  retries: 0,
  reporter: [["list"], ["html", { open: "never" }]],
  use: {
    baseURL: BASE_URL,
    // Without these, Playwright's default actionTimeout/navigationTimeout is
    // 0 (unbounded) - any click/fill/selectOption/goto that never resolves
    // (e.g. a real backend issue, not just a bad locator) silently eats the
    // full 120s test timeout instead of failing fast with a specific error.
    // Doesn't affect explicit per-call timeouts elsewhere (waitForURL/
    // waitForSelector at 30s, the F3c/F3d transcription poll's own JS loop)
    // since an explicit timeout always overrides this default.
    actionTimeout: 15_000,
    navigationTimeout: 15_000,
    trace: "retain-on-failure",
    screenshot: "only-on-failure",
    video: "retain-on-failure",
    launchOptions: {
      slowMo: SLOWMO_MS,
    },
  },
  projects: [
    {
      name: "chromium",
      use: { ...devices["Desktop Chrome"] },
    },
  ],
});
