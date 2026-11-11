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

/**
 * URL scheme of the deployment under test. Production is https; the local kind stage serves the console over
 * plain http (its NEXT_PUBLIC_* point at http://api.* / http://auth.*), and an https page calling those would be
 * blocked as mixed content. Set E2E_SCHEME=http for the stage. E2E_STAGE_SCHEME is accepted as an alias because
 * the stage-*-live specs already use that name.
 */
export const SCHEME = (process.env.E2E_SCHEME || process.env.E2E_STAGE_SCHEME || "https").replace(/:\/*$/, "");

/**
 * Which public site the deployment serves. "classic" is the commercial marketing site (landing hero, pricing,
 * compare, hire, earn, galleries ...); "oss" is the open-source distribution, where those routes redirect to `/`
 * and "Sign in" is a link to /login instead of a header button. Defaults to classic so production runs are
 * unchanged. Use skipIfOssSite(testInfo) in specs that only make sense on the classic site.
 */
export type SiteMode = "oss" | "classic";
export const SITE_MODE: SiteMode = process.env.E2E_SITE_MODE === "oss" ? "oss" : "classic";
export const IS_OSS_SITE = SITE_MODE === "oss";

export const ROOT_URL = `${SCHEME}://${DOMAIN}`;
export const APP_URL = `${SCHEME}://app.${DOMAIN}`;
export const ADMIN_URL = `${SCHEME}://admin.${DOMAIN}`;
export const API_URL = `${SCHEME}://api.${DOMAIN}`;
export const AUTH_URL = `${SCHEME}://auth.${DOMAIN}`;
export const ACADEMY_URL = `${SCHEME}://academy.${DOMAIN}`;

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

/**
 * Prod-only opt-in for specs that must never run against a stage or a local stack, e.g. a real signup that
 * provisions a Keycloak user + tenant for a throwaway mailbox. Requires E2E_PROD_ONLY=1 in addition to the
 * mutation opt-in.
 */
export const PROD_ONLY = process.env.E2E_PROD_ONLY === "1";

/** Skips specs that exercise the commercial/classic marketing site when E2E_SITE_MODE=oss. */
export function skipIfOssSite(testInfo: TestInfo) {
  testInfo.skip(
    IS_OSS_SITE,
    "E2E_SITE_MODE=oss - the OSS site has no commercial marketing routes (they redirect to /); " +
      "run with E2E_SITE_MODE=classic against a classic deployment"
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
/**
 * Matches a URL whose HOST is localhost / 127.0.0.1 (at the start of the string or right after `//` or `@`).
 * A bare /localhost|127\.0\.0\.1/ also matches the stage's own wildcard-DNS domain `127.0.0.1.nip.io`, which is a
 * legitimate public-style host, so URL assertions must use this host-anchored form instead.
 */
export const LOCALHOST_HOST_RE = /(^|\/\/|@)(localhost|127\.0\.0\.1)(?=[:/?#]|$)/i;

export function assertLocalhostFree(value: string | null | undefined, what: string) {
  if (!value) return;
  const offenders = value.match(/https?:\/\/(localhost|127\.0\.0\.1)(?=[:/?#\s"']|$)(:\d+)?/gi);
  if (offenders) {
    throw new Error(
      `${what} contains a localhost URL (no browser-facing URL may be localhost): ${offenders.join(", ")}`
    );
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

/**
 * Set when the tenant's model is a deterministic mock (E2E_LLM_IS_MOCK=1):
 * output-content assertions cannot be judged on canned text, so specs call
 * skipIfMockLlm to skip them. Defaults false - a real model serves the stack.
 */
export const LLM_IS_MOCK = process.env.E2E_LLM_IS_MOCK === "1";

/** Skips (not fails) an output-content assertion when the model is the deterministic mock. */
export function skipIfMockLlm(testInfo: TestInfo, what: string): void {
  testInfo.skip(
    LLM_IS_MOCK,
    `the configured model is a deterministic mock - ${what} cannot be asserted on generated output`
  );
}
