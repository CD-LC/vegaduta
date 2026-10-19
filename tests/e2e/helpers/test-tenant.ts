import { type Page, expect } from "@playwright/test";
import { completeKeycloakLogin, expectSignedIn } from "./keycloak-login";

export interface TestTenant {
  email: string;
  password: string;
  orgName: string;
  firstName: string;
  lastName: string;
}

/**
 * Generates a fresh, never-reused test identity. No pre-existing credentials
 * are needed - this is a throwaway account the test itself creates via the
 * real self-service signup wizard, same as any human signing up would. Every
 * account created this way lands on FREE plan (the only self-serve tier -
 * see web/app/signup/page.tsx's "FREE-tier signup (the only kind produced
 * today)" comment); elevating to PREMIUM/UNLIMITED for gate testing is a
 * separate, admin-credential-gated step - see admin-session.ts.
 *
 * Uses @mailinator.com (NOT @example.com) - the Keycloak realm enforces real
 * email verification on new signups (see keycloak-login.ts/mailinator.ts's
 * header comments), and only a real, readable inbox can complete that step.
 */
export function freshIdentity(label: string): TestTenant {
  const stamp = `${Date.now()}-${Math.random().toString(36).slice(2, 8)}`;
  return {
    // Mailinator reads the verification mail on a hosted stack. A self-hosted stack with email
    // verification off (and a block on disposable addresses) needs E2E_EMAIL_DOMAIN=example.com.
    email: `e2e-${label}-${stamp}@${process.env.E2E_EMAIL_DOMAIN || "mailinator.com"}`,
    password: `E2eTest!${Math.random().toString(36).slice(2, 10)}Aa1`,
    orgName: `E2E ${label} ${stamp}`,
    firstName: "Playwright",
    lastName: "Bot",
  };
}

/**
 * Runs the full signup wizard (web/app/signup/page.tsx) through to a
 * provisioned FREE tenant, then rides the app's own auto-redirect-to-Keycloak
 * flash (FreeWorkspaceReadyStep -> auth.login({ loginHint, prompt: "login" })
 * after ~1.2s) and completes that login, landing back on the authenticated
 * app shell at "/". Side effects are real: this provisions an actual tenant
 * on whatever E2E_BASE_URL points at (production by default).
 */
export async function signUpFreshTenant(page: Page, label: string): Promise<TestTenant> {
  const identity = freshIdentity(label);

  await page.goto("/signup");
  // The step-1 button and two labels were renamed between the hosted site and the self-hosted
  // (lite) build ("Register now" / "Organization name" / "Launch your control panel" became
  // "Create free account" / "Workspace name" / "Create my workspace"): accept either.
  await page.getByRole("button", { name: /register now|create free account/i }).click();
  const orgField = page.getByLabel(/^(organization|workspace) name$/i);
  await expect(orgField).toBeVisible();

  await orgField.fill(identity.orgName);
  await page.getByLabel("First name").fill(identity.firstName);
  await page.getByLabel("Last name").fill(identity.lastName);
  await page.getByLabel(/^email$/i).fill(identity.email);
  await page.getByLabel("Password", { exact: true }).fill(identity.password);
  await page.getByLabel("Confirm password").fill(identity.password);
  await page.getByLabel(/I agree to the/i).check();
  await page.getByRole("button", { name: /launch your control panel|create my workspace/i }).click();

  // FreeWorkspaceReadyStep's success box, keyed off the org name - confirms
  // the POST /api/signup actually succeeded before we wait on the redirect.
  // Generous timeout: signup provisions a real Keycloak user + tenant
  // schema, and can take a while under concurrent E2E load (several agents'
  // worth of signups landing on production around the same time).
  await expect(page.getByText(identity.orgName)).toBeVisible({ timeout: 60_000 });

  // The hosted site redirects to sign-in by itself after ~1.2 s; the self-hosted (lite) build shows
  // a "Continue to sign-in" button instead.
  const continueButton = page.getByRole("button", { name: /continue to sign-in/i });
  if (await continueButton.isVisible().catch(() => false)) await continueButton.click();

  await completeKeycloakLogin(page, identity.email, identity.password);
  await expectSignedIn(page);

  return identity;
}

/**
 * Logs an already-provisioned identity back in (e.g. a second browser
 * context in the same test, or a later test reusing a tenant created in a
 * fixture). Goes through the header "Sign in" button rather than assuming
 * any particular deep link, matching how a real returning user would land.
 */
export async function signIn(page: Page, identity: Pick<TestTenant, "email" | "password">) {
  await page.goto("/");
  await page.locator("header").getByRole("button", { name: "Sign in" }).click();
  await completeKeycloakLogin(page, identity.email, identity.password);
  await expectSignedIn(page);
}
