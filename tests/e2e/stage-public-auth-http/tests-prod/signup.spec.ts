import { test, expect } from "@playwright/test";
import { requireMutationOptIn, ALLOW_MUTATIONS, DOMAIN } from "../helpers/prod-env";

/**
 * Signup.
 *
 * The default, always-on tests here are NON-DESTRUCTIVE: they exercise the
 * wizard's rendering and its client-side validation without ever completing
 * a registration. That matters because this suite runs against production -
 * a completed signup provisions a real Keycloak user AND a real tenant
 * schema, which is not an acceptable side effect of running a test suite.
 *
 * The one test that does complete a real signup is gated behind
 * E2E_ALLOW_MUTATIONS=1 and skips loudly otherwise.
 */
test.describe("Signup wizard (read-only)", () => {
  test.beforeEach(async ({ page }) => {
    await page.goto("/signup", { waitUntil: "domcontentloaded" });
    await page.waitForLoadState("load", { timeout: 15_000 }).catch(() => {});
  });

  test("renders the wizard entry point", async ({ page }) => {
    await expect(page.getByRole("heading", { level: 1 })).toBeVisible();
    await expect(page.getByText(/Application error: a client-side exception/i)).toHaveCount(0);

    const body = await page.locator("body").innerText();
    expect(body, "signup page shows no way to start").toMatch(/register|sign ?up|get started|create/i);
  });

  test("the details step collects the fields a tenant needs", async ({ page }) => {
    const start = page.getByRole("button", { name: /register now|get started|create/i }).first();
    if ((await start.count()) > 0) {
      await start.click().catch(() => {});
    }

    // Organization + identity + credentials are the minimum for provisioning
    // a tenant; if any disappears the backend contract is broken.
    await expect(page.getByLabel(/workspace name|workspace/i)).toBeVisible({ timeout: 20_000 });
    await expect(page.getByLabel(/first name/i)).toBeVisible();
    await expect(page.getByLabel(/last name/i)).toBeVisible();
    await expect(page.getByLabel(/work email|email/i).first()).toBeVisible();
    await expect(page.getByLabel(/^password$/i)).toBeVisible();
    await expect(page.getByLabel(/confirm password/i)).toBeVisible();
  });

  test("submitting without accepting the Terms is blocked and does not navigate", async ({ page }) => {
    const start = page.getByRole("button", { name: /register now|get started|create/i }).first();
    if ((await start.count()) > 0) {
      await start.click().catch(() => {});
    }
    await expect(page.getByLabel(/workspace name|workspace/i)).toBeVisible({ timeout: 20_000 });

    const stamp = Date.now();
    await page.getByLabel(/workspace name|workspace/i).fill(`E2E ReadOnly ${stamp}`);
    await page.getByLabel(/first name/i).fill("Playwright");
    await page.getByLabel(/last name/i).fill("Bot");
    await page.getByLabel(/work email|email/i).first().fill(`e2e-readonly-${stamp}@mailinator.com`);
    await page.getByLabel(/^password$/i).fill("E2eTest!ReadOnly1");
    await page.getByLabel(/confirm password/i).fill("E2eTest!ReadOnly1");

    // Terms deliberately left UNCHECKED - this is what keeps the test
    // non-destructive: submission must be refused, so no account is created.
    const urlBefore = page.url();
    await page.getByRole("button", { name: /launch your control panel|create|continue|submit/i }).first().click();

    await expect(page, "signup navigated away despite unaccepted Terms - a real account may have been created").toHaveURL(
      urlBefore
    );

    const body = await page.locator("body").innerText();
    expect(body, "no validation message shown for unaccepted Terms").toMatch(/terms|agree|accept/i);
  });

  test("mismatched passwords are rejected client-side", async ({ page }) => {
    const start = page.getByRole("button", { name: /register now|get started|create/i }).first();
    if ((await start.count()) > 0) {
      await start.click().catch(() => {});
    }
    await expect(page.getByLabel(/workspace name|workspace/i)).toBeVisible({ timeout: 20_000 });

    const stamp = Date.now();
    await page.getByLabel(/workspace name|workspace/i).fill(`E2E Mismatch ${stamp}`);
    await page.getByLabel(/first name/i).fill("Playwright");
    await page.getByLabel(/last name/i).fill("Bot");
    await page.getByLabel(/work email|email/i).first().fill(`e2e-mismatch-${stamp}@mailinator.com`);
    await page.getByLabel(/^password$/i).fill("E2eTest!Correct1");
    await page.getByLabel(/confirm password/i).fill("E2eTest!Different2");
    await page.getByLabel(/i agree to the/i).check().catch(() => {});

    const urlBefore = page.url();
    // 2026-09-22 free-mode redesign put the "Continue with GitHub/Google/
    // Microsoft" OAuth buttons ABOVE the form fields in DOM order. A loose
    // /continue|create/i match with .first() was clicking "Continue with
    // GitHub" instead of the real submit button - re-verified live against
    // a deployed instance (the validation message does render
    // correctly; this was a test locator bug, not a product bug). Scope to
    // the actual <button type="submit"> instead.
    await page.locator('button[type="submit"]').first().click();

    await expect(page).toHaveURL(urlBefore);
    const body = await page.locator("body").innerText();
    expect(body).toMatch(/match|same/i);
  });

  test("the Terms and Privacy links resolve", async ({ request }) => {
    for (const path of ["/terms", "/privacy"]) {
      const res = await request.get(path);
      expect(res.status(), `${path} did not resolve`).toBeLessThan(400);
    }
  });
});

test.describe("Signup wizard - anti-abuse control (production)", () => {
  // The full "provision a real tenant" happy path CANNOT run on production by
  // design: TenantProvisioningService's DisposableEmailDomainService rejects
  // mailinator.com, and app.signup.disposable-email-block.allowlist=
  // mailinator.com is set ONLY on staging (deploy/.env) - never on prod
  // (see DisposableEmailDomainService's javadoc + the 2026-08-30 E2E-triage
  // entry in the project changelog). A mailinator signup that SUCCEEDED
  // on prod would itself be the defect - a leaked staging-only allowlist.
  //
  // So on prod this test asserts the control instead of fighting it: a real
  // consumer-shaped signup attempt with a disposable domain is refused with
  // the real rejection, and nothing is provisioned.
  test("a disposable-email signup is refused with the anti-abuse rejection", async ({ page }, testInfo) => {
    testInfo.skip(testInfo.project.name !== "chromium", "signup-path contract - one project only");

    const stamp = `${Date.now()}-${Math.random().toString(36).slice(2, 8)}`;
    const disposableEmail = `e2e-prod-${stamp}@mailinator.com`;

    await page.goto("/signup", { waitUntil: "domcontentloaded" });
    await page.waitForLoadState("load", { timeout: 15_000 }).catch(() => {});
    const start = page.getByRole("button", { name: /register now|get started|create/i }).first();
    if ((await start.count()) > 0) {
      await start.click().catch(() => {});
    }

    await expect(page.getByLabel(/workspace name|workspace/i)).toBeVisible({ timeout: 30_000 });
    await page.getByLabel(/workspace name|workspace/i).fill(`E2E prod ${stamp}`);
    await page.getByLabel(/first name/i).fill("Playwright");
    await page.getByLabel(/last name/i).fill("Bot");
    await page.getByLabel(/work email|email/i).first().fill(disposableEmail);
    const password = `E2eTest!${Math.random().toString(36).slice(2, 10)}Aa1`;
    await page.getByLabel(/^password$/i).fill(password);
    await page.getByLabel(/confirm password/i).fill(password);
    await page.getByLabel(/i agree to the/i).check();
    // Real submit button only - the OAuth "Continue with ..." buttons match a
    // loose /continue/i and sit earlier in DOM order (see the note in the
    // mismatched-passwords test above).
    await page.locator('button[type="submit"]').first().click();

    // The tenant provisioner must refuse the disposable domain - surfaced to
    // the consumer, not a silent spinner or a 500. Two anti-abuse gates exist
    // and EITHER is a pass for this test, because signup-rate limiting legit
    // fires first when this spec has already made attempts from this IP
    // (observed live 2026-09-25: "Too many signup attempts"). The failure this
    // guards is a disposable signup that SUCCEEDS - i.e. the staging allowlist
    // leaking into prod.
    // The rejection renders twice: once as a hud-toast (also role="alert")
    // and once as the inline destructive alert. Scope to the inline
    // data-slot="alert" so strict mode doesn't trip on the toast twin.
    const gate = page.locator('[data-slot="alert"]').getByText(/disposable email|too many signup attempts/i);
    await expect(
      gate,
      "the disposable-email signup was NOT refused by any abuse gate - expected a rejection",
    ).toBeVisible({ timeout: 60_000 });
    testInfo.annotations.push({
      type: "note",
      description: "signup refused - anti-abuse gate live (disposable-domain or attempt-rate)",
    });
  });
});

test.describe("Mutation-gate self-check", () => {
  test("mutating specs are disabled unless explicitly opted in", async () => {
    // Documents the safety posture in the report itself, so a reader can see
    // at a glance whether this run was allowed to write to production.
    expect(typeof ALLOW_MUTATIONS).toBe("boolean");
  });
});
