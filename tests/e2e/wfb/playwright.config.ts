import base from "../playwright.prod.config";
import { devices } from "@playwright/test";
const D = process.env.E2E_DOMAIN!;
const secure = `--unsafely-treat-insecure-origin-as-secure=http://app.${D},http://auth.${D},http://api.${D},http://admin.${D}`;
const baseURL = `http://app.${D}`;
export default {
  ...base, testDir: "./tests-prod", workers: 1, fullyParallel: false, retries: 0,
  outputDir: process.env.E2E_OUT || "test-results-wfb",
  reporter: [["list"], ["json", { outputFile: process.env.E2E_JSON || "wfb-results.json" }]],
  projects: [{ name: "chromium", use: { ...devices["Desktop Chrome"], viewport: { width: 1280, height: 800 }, baseURL, launchOptions: { args: [secure] } } }],
  use: { ...base.use, baseURL, ignoreHTTPSErrors: true },
};
