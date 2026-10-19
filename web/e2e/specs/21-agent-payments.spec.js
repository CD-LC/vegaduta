// Agentic UPI Payments (Build Specs §E1) - console surface coverage.
//
// Scope: the /admin/payments mandate console renders for a MANAGE_AGENTS
// holder, shows the mandate-empty state, and the grant wizard's review step
// displays the canonical mandate JSON + SHA-256 before signing. The spec
// deliberately does NOT complete a grant: POST /api/payments/mandates needs
// a signing key provisioned for the tenant and a real agent id, both of which
// exist on staging but leave durable ledger/audit rows - the grant click is
// exercised by the unit tests and a manual staging pass instead.
//
// Written against the fixture conventions in e2e/fixtures.js. NOT yet run:
// the feature has to be deployed to the staging compose stack first, and the
// local toolchain (npm install) is disk-blocked at time of writing.
const { test, expect } = require("../fixtures");

test.describe("Agent payments console (mandates)", () => {
  test("renders the mandate console with grant wizard and empty states", async ({
    adminPage: page,
  }) => {
    await page.goto("/admin/payments");
    await expect(page).toHaveURL(/\/admin\/payments/);
    await expect(page.getByText("Payment mandates")).toBeVisible();
    await expect(page.getByText("Transactions")).toBeVisible();
    await expect(page.locator("#payments-agent")).toBeVisible();
  });

  test("grant wizard review step shows the canonical JSON and its hash", async ({
    adminPage: page,
  }) => {
    await page.goto("/admin/payments");

    // The picker only enables the wizard when the tenant actually has agents.
    const picker = page.locator("#payments-agent");
    await expect(picker).toBeVisible();
    const optionCount = await picker.locator("option").count();
    if (optionCount === 0) {
      test.skip(true, "tenant has no agents - nothing to grant a mandate on");
    }

    await page.getByRole("button", { name: "Grant mandate" }).click();
    await page.fill("#mandate-perTxn", "500");
    await page.fill("#mandate-daily", "5000");
    await page.fill("#mandate-monthly", "50000");
    // validUntil is required; leave validFrom empty (defaults to now).
    const until = new Date(Date.now() + 30 * 24 * 3600 * 1000);
    const untilLocal = until.toISOString().slice(0, 16);
    await page.fill("#mandate-validUntil", untilLocal);

    await page.getByRole("button", { name: "Review mandate" }).click();
    // The review step must surface the exact bytes to be signed plus the
    // SHA-256 fingerprint - this is the AP2-style "verify what you sign" UI.
    await expect(page.getByText("SHA-256:")).toBeVisible();
    await expect(page.getByRole("button", { name: "Sign & grant" })).toBeVisible();
    await expect(page.locator("pre")).toContainText('"agentId"');
  });
});
