// Business case: configuring the WhatsApp channel (Omnichannel Stage 2) and
// approving strangers who message in for the first time (Autonomy/
// Guardrails Strategy Stage A pairing gate).
//
// Selectors track the ShadCN redesign of /admin/channels (ChannelsPanel.tsx):
// the page is a card grid (`[data-testid="channel-card"]`, one per channel)
// driving a contextual inspector on the right, and WhatsApp specifically has
// two official connection methods behind ShadCN tabs (role="tab": "Meta Cloud
// API" default / "Twilio") rather than one always-visible Twilio form. Form fields are reached via their accessible label (getByLabel), and
// every channel's error/success state is one shared pair of ShadCN alerts
// (`[data-testid="channels-alert-error"]` / `channels-alert-success`).
// Credential activation makes a real validation call to
// Twilio's API (TwilioCredentialService#validate), so the happy-path
// activation test needs real Twilio credentials and is opt-in; the
// rejection path (deliberately wrong credentials) is real coverage either
// way, since Twilio's API is expected to reject those too.
const { test, expect, signupTenant, loginViaKeycloak } = require("../fixtures");

test.describe("Channels: WhatsApp / Twilio", () => {
  test("an invalid Twilio credential is rejected with a clear error", async ({ adminPage: page }) => {
    await page.goto("/admin/channels");
    await expect(page.getByRole("heading", { name: "Connect WhatsApp" })).toBeVisible({ timeout: 15_000 });

    await page.getByRole("tab", { name: "Twilio" }).click();
    await page.getByLabel("Account SID").fill("ACdefinitelynotreal00000000000000");
    await page.getByLabel("Auth Token").fill("not-a-real-auth-token");
    await page.locator('input[placeholder="whatsapp:+1..."]').fill("whatsapp:+15005550006");
    await page.getByRole("button", { name: "Connect", exact: true }).click();
    await expect(page.getByTestId("channels-alert-error")).toBeVisible({ timeout: 30_000 });
  });

  // Takes `{ page, request }` rather than `adminPage` and signs up manually
  // after the skip check - see e2e/README.md's rate-limit note (same reason
  // as spec 02's gated chat test).
  test("activate a real Twilio credential and set the default WhatsApp agent", async ({ page, request }) => {
    test.skip(
      !process.env.E2E_TWILIO_ACCOUNT_SID || !process.env.E2E_TWILIO_AUTH_TOKEN,
      "Set E2E_TWILIO_ACCOUNT_SID/E2E_TWILIO_AUTH_TOKEN/E2E_TWILIO_WHATSAPP_FROM to run this test."
    );
    const tenant = await signupTenant(request);
    await loginViaKeycloak(page, tenant);
    await page.waitForURL(/\/admin/, { timeout: 30_000 });
    await page.goto("/admin/channels");

    await page.getByRole("tab", { name: "Twilio" }).click();
    await page.getByLabel("Account SID").fill(process.env.E2E_TWILIO_ACCOUNT_SID);
    await page.getByLabel("Auth Token").fill(process.env.E2E_TWILIO_AUTH_TOKEN);
    await page.locator('input[placeholder="whatsapp:+1..."]').fill(process.env.E2E_TWILIO_WHATSAPP_FROM || "whatsapp:+14155238886");
    await page.getByRole("button", { name: "Connect", exact: true }).click();
    await expect(page.getByTestId("channels-alert-success")).toContainText("Twilio connected.", { timeout: 15_000 });

    // Select.js only renders <button role="option"> rows while its popover
    // is open (progressive rendering, not a static <option> list) - so
    // counting them means opening the trigger first, same as the actual
    // pick that follows. RouteBox's inline select has no data-testid, so
    // it's reached via the "ROUTE INBOUND TO" label that precedes it.
    const routeBox = page.getByTestId("route-box");
    await routeBox.locator(".select-trigger").click();
    const optionCount = await page.locator(".select-popover .select-option").count();
    if (optionCount < 2) {
      await page.keyboard.press("Escape");
      test.skip(true, "No agents exist yet in this tenant to set as the default.");
    }
    await page.locator(".select-popover .select-option").nth(1).click();
    await routeBox.getByRole("button", { name: "Save" }).click();
  });

  test("a fresh tenant's recent-inbound log starts empty", async ({ adminPage: page }) => {
    await page.goto("/admin/channels");
    await expect(page.getByText("No inbound messages yet.")).toBeVisible({ timeout: 15_000 });
  });
});
