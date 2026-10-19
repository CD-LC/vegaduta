// Shared tenant-provisioning + Keycloak-login helpers for every spec in
// this suite. Each spec file that needs a logged-in session asks for the
// `tenant`/`adminPage` fixtures below rather than sharing one global
// tenant - see playwright.config.js's `fullyParallel: false` comment for
// why: specs mutate real tenant-scoped data (agents, collections,
// approvals), so cross-spec sharing would make failures order-dependent
// and hard to reproduce.
//
// Deliberately calls POST /api/signup directly via Playwright's `request`
// fixture rather than driving the signup UI form for every spec that just
// needs "a tenant exists" - the signup UI itself gets its own dedicated
// coverage in specs/01-signup-and-login.spec.js. Confirmed (STAGE-F1-
// VERIFICATION.md, Section covering fresh-signup) that a tenant's schema is
// provisioned and immediately usable (ACTIVE status) before the Razorpay
// checkout step ever happens - so tests never need to complete a real
// payment to get a working, logged-in tenant.
const base = require("@playwright/test");

const API_BASE = process.env.E2E_API_BASE_URL || "http://localhost:8080";

function uniqueSuffix() {
  return `${Date.now()}-${Math.floor(Math.random() * 1e6)}`;
}

async function signupTenant(request, overrides = {}) {
  const suffix = uniqueSuffix();
  const payload = {
    orgName: overrides.orgName || `E2E Org ${suffix}`,
    firstName: overrides.firstName || "Ada",
    lastName: overrides.lastName || "E2E",
    adminEmail: overrides.adminEmail || `e2e.${suffix}@example.test`,
    adminPassword: overrides.adminPassword || "E2ePassw0rd!",
  };
  const res = await request.post(`${API_BASE}/api/signup`, { data: payload, timeout: 15_000 });
  if (!res.ok()) {
    throw new Error(`Signup failed: ${res.status()} ${await res.text()}`);
  }
  const body = await res.json(); // SignupResponse: organizationName, gatewaySubscriptionId, gatewayPublicKey, checkoutUrl, ...
  return { ...payload, suffix, ...body };
}

// Drives Keycloak's own login form. Uses the stock default-theme field ids
// (#username, #password, #kc-login) - if this deployment uses a customized
// Keycloak theme, these three selectors are the first thing to update. See
// e2e/README.md's troubleshooting section.
//
// Visual redesign: AuthProvider used to use Keycloak's onLoad: "login-required",
// which auto-redirected "/" straight to Keycloak's hosted login page - a
// visit to "/" landed directly on #username. It now uses "check-sso" (no
// forced redirect) and renders the branded MarketingHome landing page
// instead, so reaching Keycloak's form requires one extra, deliberate click
// on that page's persistent nav "Log in" button
// (data-testid="login-landing-button") first.
async function loginViaKeycloak(page, { adminEmail, adminPassword }) {
  await page.goto("/", { timeout: 15_000 });
  await page.click('[data-testid="login-landing-button"]', { timeout: 15_000 });
  await page.waitForURL(/\/realms\//, { timeout: 30_000 });
  await page.fill("#username", adminEmail, { timeout: 15_000 });
  await page.fill("#password", adminPassword, { timeout: 15_000 });
  await page.click("#kc-login", { timeout: 15_000 });
  await page.waitForURL((url) => !url.href.includes("/realms/"), { timeout: 30_000 });
}

// For a user created via the Admin Users panel (UserAdminController), whose
// password is Keycloak "temporary" by design (KeycloakCredentialRepresentation
// .temporaryPassword) - the first login always lands on Keycloak's
// UPDATE_PASSWORD required-action screen before the app itself ever loads.
// Exercises that real onboarding flow rather than avoiding it. Same
// LoginLanding click-through as loginViaKeycloak above.
async function loginAndSetPermanentPassword(page, { username, temporaryPassword, newPassword }) {
  await page.goto("/", { timeout: 15_000 });
  await page.click('[data-testid="login-landing-button"]', { timeout: 15_000 });
  await page.waitForURL(/\/realms\//, { timeout: 30_000 });
  await page.fill("#username", username, { timeout: 15_000 });
  await page.fill("#password", temporaryPassword, { timeout: 15_000 });
  await page.click("#kc-login", { timeout: 15_000 });
  await page.waitForSelector("#password-new", { timeout: 30_000 });
  await page.fill("#password-new", newPassword, { timeout: 15_000 });
  await page.fill("#password-confirm", newPassword, { timeout: 15_000 });
  await page.click('input[type="submit"], button[type="submit"]', { timeout: 15_000 });
  await page.waitForURL((url) => !url.href.includes("/realms/"), { timeout: 30_000 });
}

// Creates a second, non-admin user for `tenant` via the Admin Users panel
// API and returns their login-ready credentials. Needed because app/page.js
// redirects any tenant-admin straight to /admin - the main console
// (providers, agents, chat, MCP registry, knowledge base/collections) is
// only ever reachable by a non-admin console user, never by the signup
// admin themselves. SecurityConfig only locks specific endpoints to
// ROLE_tenant-admin; everything else this role needs is `anyRequest()
// .authenticated()`, so "agent-builder" (no special grants beyond that) is
// enough.
async function createConsoleUser(request, tenant, overrides = {}) {
  const suffix = uniqueSuffix();
  const username = overrides.username || `e2e-console-${suffix}`;
  const email = overrides.email || `${username}@example.test`;
  const temporaryPassword = overrides.temporaryPassword || "TempPassw0rd!";
  const res = await request.post(`${API_BASE}/api/admin/users`, {
    headers: { Authorization: `Bearer ${await keycloakPasswordGrantToken(request, tenant)}` },
    data: {
      username,
      email,
      firstName: "Console",
      lastName: "User",
      temporaryPassword,
      initialRole: overrides.initialRole || "agent-builder",
    },
    timeout: 15_000,
  });
  if (!res.ok()) {
    throw new Error(`Creating console user failed: ${res.status()} ${await res.text()}`);
  }
  return { username, email, temporaryPassword, permanentPassword: overrides.permanentPassword || "ConsolePassw0rd!" };
}

// Direct Resource Owner Password Credentials grant against Keycloak - used
// only to get a bearer token for setup/probing API calls (creating a
// console user, direct cross-tenant GET probes), not for anything a real
// user-facing flow does (the app itself only ever uses the PKCE browser
// flow via keycloak-js).
//
// Uses "agentic-ai-core" (the confidential service client core itself uses
// to call Keycloak's Admin API - see KeycloakAdminClient.java and the
// README's own "Get a token for testing" curl example), NOT
// "agentic-ai-web" - deliberately. agentic-ai-web is a public browser
// client with directAccessGrantsEnabled: false in
// deploy/keycloak/agentic-ai-realm.json, and enabling direct grants on a
// public client just to make this suite's life easier would be a real,
// exportable security regression to the realm config, not a test-only
// convenience. agentic-ai-core already has directAccessGrantsEnabled: true
// and already requires KEYCLOAK_CLIENT_SECRET to exist somewhere (core
// itself refuses to start without it - see run-core.ps1) - reusing it here
// costs nothing new except passing that same secret to this test run too.
async function keycloakPasswordGrantToken(request, tenant) {
  const keycloakUrl = process.env.E2E_KEYCLOAK_URL || "http://localhost:8180";
  const realm = process.env.E2E_KEYCLOAK_REALM || "agentic-ai";
  const clientId = process.env.E2E_KEYCLOAK_CLIENT_ID || "agentic-ai-core";
  const clientSecret = process.env.E2E_KEYCLOAK_CLIENT_SECRET;
  if (!clientSecret) {
    throw new Error(
      "E2E_KEYCLOAK_CLIENT_SECRET is not set - copy the same value as core\\.env.local's KEYCLOAK_CLIENT_SECRET " +
        "(Keycloak admin console: agentic-ai realm -> Clients -> agentic-ai-core -> Credentials tab). See e2e/README.md."
    );
  }
  const res = await request.post(`${keycloakUrl}/realms/${realm}/protocol/openid-connect/token`, {
    form: {
      grant_type: "password",
      client_id: clientId,
      client_secret: clientSecret,
      username: tenant.adminEmail,
      password: tenant.adminPassword,
    },
    timeout: 15_000,
  });
  if (!res.ok()) {
    throw new Error(
      `Keycloak password grant failed (${res.status()}) - check E2E_KEYCLOAK_CLIENT_SECRET matches the current ` +
        `agentic-ai-core client secret. Body: ${await res.text()}`
    );
  }
  const body = await res.json();
  return body.access_token;
}

// Console-wide native-<select> replacement (Select.js, visual-polish pass -
// user feedback: "selecting drop down feels old style"). Every spec that
// used to call Playwright's built-in `locator.selectOption(...)` against a
// real <select> now drives this instead: click the trigger button to open
// the floating options panel, then click the option by its visible text.
// `triggerLocator` is whatever locator used to resolve to the <select>
// itself (a getByTestId(...), or a CSS `section:has-text(...) select`
// locator with `select` swapped for the trigger's `.select-trigger` class).
async function pickCustomSelect(page, triggerLocator, optionText) {
  await triggerLocator.click();
  await page.locator(".select-popover .select-option", { hasText: optionText }).first().click();
}

// Index-based sibling of pickCustomSelect - for the handful of specs that
// used `.selectOption({ index })` because the option's exact label wasn't
// known ahead of time (e.g. "whichever agent happens to exist"), rather than
// asserting on specific option text.
async function pickCustomSelectByIndex(page, triggerLocator, index) {
  await triggerLocator.click();
  await page.locator(".select-popover .select-option").nth(index).click();
}

const test = base.test.extend({
  // Fresh tenant per test - see file header for why this isn't a
  // worker-scoped/shared fixture.
  // eslint-disable-next-line no-empty-pattern
  tenant: async ({ request }, use) => {
    const tenant = await signupTenant(request);
    await use(tenant);
  },
  // Logged-in tenant-admin session (the signup flow's first user always
  // gets the tenant-admin role - "You'll be the first admin" on the signup
  // form). Lands on /admin per the app's role-based redirect.
  adminPage: async ({ page, tenant }, use) => {
    await loginViaKeycloak(page, tenant);
    await page.waitForURL(/\/admin/, { timeout: 30_000 });
    await use(page);
  },
  // Logged-in NON-admin session, landing on the main console ("/"). See
  // createConsoleUser's comment above for why a second user is required at
  // all just to reach these panels.
  consolePage: async ({ page, request, tenant }, use) => {
    const consoleUser = await createConsoleUser(request, tenant);
    await loginAndSetPermanentPassword(page, {
      username: consoleUser.username,
      temporaryPassword: consoleUser.temporaryPassword,
      newPassword: consoleUser.permanentPassword,
    });
    await page.waitForURL((url) => url.pathname === "/" || url.pathname === "", { timeout: 30_000 });
    await use(page);
  },
});

module.exports = {
  test,
  expect: base.expect,
  signupTenant,
  loginViaKeycloak,
  loginAndSetPermanentPassword,
  createConsoleUser,
  keycloakPasswordGrantToken,
  uniqueSuffix,
  API_BASE,
  pickCustomSelect,
  pickCustomSelectByIndex,
};
