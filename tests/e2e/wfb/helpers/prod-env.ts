import type { TestInfo } from "@playwright/test";

/**
 * Environment resolution for the deployed-instance E2E suite.
 *
 * Every host below is derived from one `E2E_DOMAIN` (the apex domain of the
 * deployment under test), so the same specs can be pointed at production or
 * staging without editing a single test:
 *
 *   E2E_DOMAIN=staging.example.com npx playwright test -c playwright.prod.config.ts
 *
 * No browser-facing URL may ever be localhost/127.0.0.1 - assertLocalhostFree
 * below is the enforcement of that rule at test time.
 */
export const DOMAIN = process.env.E2E_DOMAIN || "example.com";

export const ROOT_URL = `http://${DOMAIN}`;
export const APP_URL = `http://app.${DOMAIN}`;
export const ADMIN_URL = `http://admin.${DOMAIN}`;
export const API_URL = `http://api.${DOMAIN}`;
export const AUTH_URL = `http://auth.${DOMAIN}`;
export const ACADEMY_URL = `http://academy.${DOMAIN}`;

/**
 * Realm + client ids, as shipped in the default realm export. Note the tenant app and the admin app use DIFFERENT
 * clients, and the tenant one is `agentic-ai-web` - not the realm-shaped
 * `agentic-ai-platform` you might reasonably guess.
 */
export const KEYCLOAK_REALM = "agentic-ai";
export const APP_CLIENT_ID = "agentic-ai-web";
export const ADMIN_CLIENT_ID = "agentic-ai-platform-admin";

/**
 * MUTATION SAFETY GATE.
 *
 * This suite runs against real production. Signing up provisions a real
 * Keycloak user + a real tenant schema; creating agents/workflows writes real
 * rows; checkout touches a real payment gateway. None of that is acceptable
 * as a side effect of "run the tests", so every spec that writes anything is
 * gated behind an explicit opt-in:
 *
 *   E2E_ALLOW_MUTATIONS=1
 *
 * Without it, mutating specs SKIP with a clear reason rather than silently
 * passing (a skip is visible in the report; a quietly-absent test is not).
 */
export const ALLOW_MUTATIONS = process.env.E2E_ALLOW_MUTATIONS === "1";

/** Tenant-side console credentials. No self-signup is performed unless mutations are allowed. */
export const USER_EMAIL = process.env.E2E_USER_EMAIL;
export const USER_PASSWORD = process.env.E2E_USER_PASSWORD;

/** Platform-admin (PLATFORM_ADMIN_ROLE) credentials for the admin host. */
export const ADMIN_EMAIL = process.env.E2E_ADMIN_EMAIL;
export const ADMIN_PASSWORD = process.env.E2E_ADMIN_PASSWORD;

export function hasUserCredentials(): boolean {
  return Boolean(USER_EMAIL && USER_PASSWORD);
}

export function hasAdminCredentials(): boolean {
  return Boolean(ADMIN_EMAIL && ADMIN_PASSWORD);
}

/** Skips (not fails) when no tenant credentials are configured. */
export function requireUserCredentials(testInfo: TestInfo) {
  testInfo.skip(
    !hasUserCredentials(),
    "E2E_USER_EMAIL / E2E_USER_PASSWORD not set - set these to a real console " +
      "account on production to run authenticated console specs."
  );
}

/** Skips (not fails) when no platform-admin credentials are configured. */
export function requireAdminCredentials(testInfo: TestInfo) {
  testInfo.skip(
    !hasAdminCredentials(),
    "E2E_ADMIN_EMAIL / E2E_ADMIN_PASSWORD not set - set these to a real " +
      "PLATFORM_ADMIN_ROLE account on production to run admin-console specs."
  );
}

/** Skips (not fails) when the write opt-in is absent. */
export function requireMutationOptIn(testInfo: TestInfo) {
  testInfo.skip(
    !ALLOW_MUTATIONS,
    "E2E_ALLOW_MUTATIONS=1 not set - this spec writes REAL data to production " +
      "(tenants, agents, workflows). Opt in explicitly to run it."
  );
}

/**
 * RULE 0 enforcement. A build meant for the real domain must never ship a
 * browser-facing localhost URL - that exact regression has broken sign-in on
 * this platform before (a base-only `docker compose build web` bakes
 * localhost into NEXT_PUBLIC_*).
 */
export function assertLocalhostFree(value: string | null | undefined, what: string) {
  if (!value) return;
  const offenders = value.match(/https?:\/\/(localhost|127\.0\.0\.1)(:\d+)?/gi);
  if (offenders) {
    throw new Error(`${what} contains a localhost URL (no browser-facing URL may be localhost): ${offenders.join(", ")}`);
  }
}

/**
 * Cross-environment leak guard: a page served from production must not link
 * to the staging domain (and vice versa). Catches a staging-built image
 * accidentally deployed to production, which is otherwise invisible until a user
 * clicks through to the wrong environment.
 */
// Optional: set E2E_FOREIGN_DOMAIN to a sibling environment's apex domain
// (e.g. staging when testing production). Unset = a sentinel that never
// matches, so the leak checks pass vacuously.
export const FOREIGN_DOMAIN = process.env.E2E_FOREIGN_DOMAIN || "no-foreign-domain-configured.invalid";
