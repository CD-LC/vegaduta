import { type Page, type TestInfo, expect } from "@playwright/test";
import { completeKeycloakLogin } from "./keycloak-login";
import { ADMIN_BASE_URL, AUTH_HOST_RE } from "./e2e-env";

// The platform-admin section (E2E_ADMIN_BASE_URL, was web-admin/) is the
// PLATFORM_ADMIN_ROLE-gated section of web/, a completely
// separate credential from any tenant-side test identity this suite creates
// for itself. There is no self-service way to obtain a platform-admin
// account (by design - it's a super-admin override surface), so unlike
// test-tenant.ts this CANNOT generate its own throwaway identity. It must be
// supplied out-of-band as env vars and is never typed into this repo.
const ADMIN_EMAIL = process.env.E2E_ADMIN_EMAIL;
const ADMIN_PASSWORD = process.env.E2E_ADMIN_PASSWORD;

/**
 * Skips the current test (rather than failing) when no platform-admin test
 * credentials are configured, so the suite is still runnable end-to-end
 * without them - only the admin-only specs no-op with a clear reason
 * instead of red-failing on every environment that hasn't set these up.
 */
export function requireAdminCredentials(testInfo: TestInfo) {
  if (!ADMIN_EMAIL || !ADMIN_PASSWORD) {
    testInfo.skip(
      true,
      "E2E_ADMIN_EMAIL / E2E_ADMIN_PASSWORD not set - set these to a real " +
        "platform-admin (PLATFORM_ADMIN_ROLE) test account to run admin-gated specs."
    );
  }
}

/**
 * Logs into the platform-admin console as the platform admin. Assumes it redirects an
 * unauthenticated visit straight to Keycloak (no in-app "Sign in" button to
 * click first, unlike the tenant-facing app) - if that assumption is wrong
 * for a given deploy, this will simply time out on the Keycloak
 * wait and the failure will point straight at this line.
 */
export async function signInAsPlatformAdmin(page: Page) {
  if (!ADMIN_EMAIL || !ADMIN_PASSWORD) {
    throw new Error("signInAsPlatformAdmin called without E2E_ADMIN_EMAIL/E2E_ADMIN_PASSWORD set");
  }
  await page.goto(ADMIN_BASE_URL);
  await completeKeycloakLoginOnAdminHost(page, ADMIN_EMAIL, ADMIN_PASSWORD);
  await expect(page.locator("header")).toBeVisible({ timeout: 15_000 });
}

async function completeKeycloakLoginOnAdminHost(page: Page, email: string, password: string) {
  await page.waitForURL(AUTH_HOST_RE, { timeout: 30_000 });
  const usernameField = page.locator("#username");
  const currentValue = await usernameField.inputValue().catch(() => "");
  if (currentValue !== email) {
    await usernameField.fill(email);
  }
  await page.locator("#password").fill(password);
  await page.locator("#kc-login").click();
  await page.waitForURL(new RegExp(new URL(ADMIN_BASE_URL).hostname.replace(/\./g, "\\.")), {
    timeout: 30_000,
  });
}

/**
 * Super-admin plan override via the platform-admin Tenant Roster & Plans screen
 * (web/app/platform-admin/tenants/page.tsx) - the same UI action documented on
 * PlatformTenantPlanUpdateRequest.java as "PUT /api/platform/tenants/{id}/plan
 * - the Subscription Plan management page's (platform-admin) one write action".
 * Writes Tenant.plan directly, bypassing Razorpay entirely (see that
 * endpoint's javadoc) - this is the sanctioned way to get a PREMIUM/UNLIMITED
 * test tenant for gate testing without completing a real payment, matching
 * this suite's "UI-only verification" stance on the checkout flow itself
 * (tests/checkout-ui.spec.ts never submits real payment details; this
 * function is the alternative path to a paid-tier tenant).
 *
 * Call this on a `page` that has already run signInAsPlatformAdmin.
 */
export async function setTenantPlan(page: Page, orgName: string, plan: "FREE" | "PREMIUM" | "UNLIMITED") {
  await page.goto(`${ADMIN_BASE_URL}/tenants`);
  const searchBox = page.getByPlaceholder(/search/i).first();
  await searchBox.fill(orgName);

  const row = page.locator("div", { hasText: orgName }).filter({ has: page.getByText("Plan") }).first();
  await expect(row).toBeVisible({ timeout: 15_000 });

  const planSelect = row.locator("select");
  await planSelect.selectOption(plan);
  await row.getByRole("button", { name: /^save$/i }).click();

  await expect(page.getByText(new RegExp(`moved to ${plan}`, "i"))).toBeVisible({ timeout: 15_000 });
}
