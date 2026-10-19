// Business case: a gated action (Autonomy/Guardrails Strategy Stage A) gets
// queued for human approval instead of executing immediately, an admin
// approves it, and the whole thing shows up in the audit trail. Uses a
// synthetic, unique actionType per test (not the real
// "channel.whatsapp.send" one - see note below) via the WhatsApp send
// endpoint, which always calls HumanApprovalGateService.evaluate() first
// regardless of whether a real Twilio credential is configured - the actual
// Twilio API call only happens once/if the request is later approved and
// executed, so this test never needs a real Twilio account.
//
// Nav split: the policy override + approvals list (ApprovalsPanel) now live
// on /admin/approvals, the WhatsApp send trigger (ChannelsPanel) on
// /admin/channels, and the audit trail (AuditLogPanel) on /admin/audit -
// previously all three were stacked on one /admin page. Each test now
// hops between the three routes at the point it needs that panel.
const { test, expect, pickCustomSelect } = require("../fixtures");

test.describe("Approvals (HITL) and audit log", () => {
  test("a policy-gated WhatsApp send queues for approval, gets approved, and is audited", async ({
    adminPage: page,
  }) => {
    // Force approval for this action type before triggering the send - the
    // ChannelsPanel form (on /admin/channels) always goes through the same
    // TwilioWhatsAppChannelAdapter.ACTION_TYPE, so setting the policy here
    // affects it directly.
    await page.goto("/admin/approvals");
    await page.fill('section:has-text("Per-action policy override") input[type="text"]', "channel.whatsapp.send");
    const requiresApprovalCheckbox = page.locator(
      'section:has-text("Per-action policy override") input[type="checkbox"]'
    );
    if (!(await requiresApprovalCheckbox.isChecked())) await requiresApprovalCheckbox.check();
    await page.click('button:has-text("Save policy")');
    await expect(page.locator(".success-box")).toContainText("requires approval");

    await page.goto("/admin/channels");
    await page.fill('input[placeholder="whatsapp:+1..."]', "whatsapp:+15550001111");
    await page.locator('.field:has(label:text("Message")) input[type="text"]').fill("E2E gated send");
    await page.getByRole("button", { name: "Send", exact: true }).click();
    await expect(page.locator(".success-box")).toContainText("Queued for approval");

    // Approvals panel defaults to the PENDING filter - the new request
    // should already be visible without changing the filter.
    await page.goto("/admin/approvals");
    const requestRow = page.locator(".list-item", { hasText: "Send WhatsApp message" }).first();
    await expect(requestRow).toBeVisible({ timeout: 15_000 });
    await expect(requestRow.locator(".badge")).toHaveText("PENDING");

    await requestRow.getByRole("button", { name: "Approve" }).click();
    await expect(page.locator(".success-box")).toContainText("Approved");

    // Audit log: filter to this action type and confirm at least one entry
    // landed (PENDING_APPROVAL at minimum; APPROVED once the gate records
    // the decision - either is proof the audit write reached the right
    // schema, which is exactly what TENANT-CONTEXT-TOOLCALL-FIX-VERIFICATION.md
    // independently confirmed for tool calls).
    await page.goto("/admin/audit");
    await page.fill('section:has-text("Audit Log") input[placeholder="channel.whatsapp.send"]', "channel.whatsapp.send");
    await page.click('section:has-text("Audit Log") button:has-text("Filter")');
    await expect(page.locator('section:has-text("Audit Log") .list-item').first()).toBeVisible({ timeout: 15_000 });
  });

  test("rejecting a request records a reason and flips its status", async ({ adminPage: page }) => {
    await page.goto("/admin/approvals");
    await page.fill('section:has-text("Per-action policy override") input[type="text"]', "channel.whatsapp.send");
    const requiresApprovalCheckbox = page.locator(
      'section:has-text("Per-action policy override") input[type="checkbox"]'
    );
    if (!(await requiresApprovalCheckbox.isChecked())) await requiresApprovalCheckbox.check();
    await page.click('button:has-text("Save policy")');
    await expect(page.locator(".success-box")).toContainText("requires approval");

    await page.goto("/admin/channels");
    await page.fill('input[placeholder="whatsapp:+1..."]', "whatsapp:+15550002222");
    await page.locator('.field:has(label:text("Message")) input[type="text"]').fill("E2E rejected send");
    await page.getByRole("button", { name: "Send", exact: true }).click();
    await expect(page.locator(".success-box")).toContainText("Queued for approval");

    await page.goto("/admin/approvals");
    const requestRow = page.locator(".list-item", { hasText: "+15550002222" }).first();
    await expect(requestRow).toBeVisible({ timeout: 15_000 });

    page.once("dialog", (dialog) => dialog.accept("Not a real customer number"));
    await requestRow.getByRole("button", { name: "Reject" }).click();
    await expect(page.locator(".success-box")).toContainText("Rejected");

    await pickCustomSelect(page, page.getByTestId("approvals-status-filter-select"), "REJECTED");
    const rejectedRow = page.locator(".list-item", { hasText: "+15550002222" }).first();
    await expect(rejectedRow.locator(".badge")).toHaveText("REJECTED");
    await expect(rejectedRow).toContainText("Not a real customer number");
  });
});
