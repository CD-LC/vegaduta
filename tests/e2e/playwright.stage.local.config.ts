import base from "./playwright.prod.config";

/**
 * Playwright wrapper for the local kind stage.
 *
 *   cd tests/e2e
 *   E2E_DOMAIN=127.0.0.1.nip.io E2E_SCHEME=http E2E_SITE_MODE=oss \
 *   E2E_USER_EMAIL=... E2E_USER_PASSWORD=... E2E_ALLOW_MUTATIONS=1 \
 *   npx playwright test -c playwright.stage.local.config.ts tests-prod/<spec> --project=chromium --workers=1
 *
 * The stage serves the console over plain http on *.127.0.0.1.nip.io (no TLS), so:
 *   - E2E_SCHEME=http is required (helpers/prod-env.ts) - an https page would block the http api/auth calls;
 *   - Chromium must treat those http origins as secure, otherwise keycloak-js has no WebCrypto (PKCE) and the
 *     app has no crypto.subtle: the flag is set once here for every project instead of per spec;
 *   - ignoreHTTPSErrors covers the ingress fake certificate if https is used.
 * Credentials come from the environment only (E2E_USER_EMAIL / E2E_USER_PASSWORD); never put them in a file.
 */

// One id per runner so parallel runs keep separate captured-session files (helpers/prod-auth-state.ts).
process.env.E2E_RUN_ID ||= String(process.pid);

const domain = process.env.E2E_DOMAIN || "127.0.0.1.nip.io";
const scheme = process.env.E2E_SCHEME || process.env.E2E_STAGE_SCHEME || "https"; // same default as helpers/prod-env.ts: pass E2E_SCHEME=http
const secureOrigins = ["app", "api", "auth", "admin"].map((h) => `${scheme}://${h}.${domain}`).join(",");
const launchOptions = { args: [`--unsafely-treat-insecure-origin-as-secure=${secureOrigins}`] };

export default {
  ...base,
  outputDir: process.env.E2E_OUT || "test-results-stage",
  reporter: [["list"], ["json", { outputFile: process.env.E2E_JSON || "stage-results.json" }]],
  use: { ...base.use, ignoreHTTPSErrors: true, launchOptions },
  projects: (base.projects ?? []).map((p) => ({ ...p, use: { ...p.use, launchOptions } })),
};
