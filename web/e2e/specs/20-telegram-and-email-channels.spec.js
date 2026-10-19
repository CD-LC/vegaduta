// Business case: Telegram Bot and Email/IMAP channels (#472, closing the gap
// e2e/README.md flagged - both were implemented in the same AXON rebuild
// that added the rest of ChannelsPanel.js's nine-channel grid, but never got
// a dedicated spec). The admin-only gate on /admin/channels is already
// covered by 08-channels-whatsapp.spec.js and 17-social-channels.spec.js
// for this same page, so it isn't repeated here.
//
// Both credentials use a real "prove it before persisting" check server-side
// - TelegramCredentialService#activate calls Telegram's own getMe API with
// the bot token, and EmailCredentialService#activate opens a real IMAP
// connect()+inbox-open/close - so, unlike Google/Meta/Slack/Discord/X (which
// need a live app registered on an external developer console), a
// deliberately-wrong Telegram token or IMAP host is real, unconditional
// rejection coverage: no opt-in credential needed to prove the validation
// works, only to prove a *real* one succeeds (which isn't attempted here,
// same "stop at the credential-form boundary" posture as every other BYOK
// channel in this suite).
const { test, expect, signupTenant, keycloakPasswordGrantToken, API_BASE } = require("../fixtures");

async function selectChannelCard(page, label) {
  await page.locator('[data-testid="channel-card"]', { hasText: label }).first().click();
}

test.describe("Channels: Telegram", () => {
  test("an invalid Bot Token is rejected by Telegram's own API, not silently accepted", async ({ adminPage: page }) => {
    await page.goto("/admin/channels");
    await expect(page.getByRole("heading", { name: "Connect WhatsApp" })).toBeVisible({ timeout: 15_000 });

    await selectChannelCard(page, "Telegram");
    await expect(page.getByRole("heading", { name: "Connect Telegram" })).toBeVisible();
    await page.getByLabel("Bot Token").fill("000000:definitely-not-a-real-bot-token");
    await page.getByRole("button", { name: "Connect", exact: true }).click();
    // TelegramApiClient makes a real HTTPS call to api.telegram.org - a
    // fabricated token gets a real 404/"Not Found" back, wrapped by
    // ChannelConfigurationException into a 400 the frontend surfaces here.
    await expect(page.getByTestId("channels-alert-error")).toBeVisible({ timeout: 20_000 });
  });

  test("connecting with a blank Bot Token is blocked client-side before any API call", async ({ adminPage: page }) => {
    await page.goto("/admin/channels");
    await selectChannelCard(page, "Telegram");
    await page.getByRole("button", { name: "Connect", exact: true }).click();
    await expect(page.getByTestId("channels-alert-error")).toContainText("Bot Token is required.");
  });
});

test.describe("Channels: Email / IMAP", () => {
  test("an unreachable IMAP host is rejected with a clear error, not a silent save", async ({ adminPage: page }) => {
    await page.goto("/admin/channels");
    await selectChannelCard(page, "Email");
    await expect(page.getByRole("heading", { name: "Connect Email" })).toBeVisible({ timeout: 15_000 });

    await page.getByLabel("IMAP host").fill("imap.e2e-definitely-not-a-real-host.invalid");
    await page.getByLabel("Username").fill("e2e-test@example.com");
    await page.getByLabel("Password").fill("not-a-real-password");
    await page.getByRole("button", { name: "Connect", exact: true }).click();
    // EmailCredentialService#verifyConnection does a real Store#connect() -
    // an unresolvable host fails DNS lookup fast rather than hanging for the
    // full 10s connect timeout, either way ending in the same
    // ChannelConfigurationException -> 400 path.
    await expect(page.getByTestId("channels-alert-error")).toContainText("Could not connect to this IMAP mailbox", { timeout: 20_000 });
  });

  test("connecting with missing fields is blocked client-side", async ({ adminPage: page }) => {
    await page.goto("/admin/channels");
    await selectChannelCard(page, "Email");
    // Host/username/password all left blank on purpose; port keeps its 993 default.
    await page.getByRole("button", { name: "Connect", exact: true }).click();
    await expect(page.getByTestId("channels-alert-error")).toContainText("Host, username, and password are required.");
  });
});

test.describe("Telegram + Email channel APIs (server-side enforcement)", () => {
  test("both credential endpoints reject requests with no bearer token", async ({ request }) => {
    for (const path of ["/api/channels/telegram/credential", "/api/channels/email/credential"]) {
      const res = await request.get(`${API_BASE}${path}`);
      expect(res.status(), path).toBe(401);
    }
  });

  test("a fresh tenant has neither credential configured yet", async ({ request }) => {
    const tenant = await signupTenant(request);
    const token = await keycloakPasswordGrantToken(request, tenant);
    for (const path of ["/api/channels/telegram/credential", "/api/channels/email/credential"]) {
      const res = await request.get(`${API_BASE}${path}`, { headers: { Authorization: `Bearer ${token}` } });
      expect([200, 204]).toContain(res.status());
      if (res.status() === 200) expect(await res.json()).toBeFalsy();
    }
  });

  test("Telegram activation rejects a fabricated Bot Token server-side, independent of the UI", async ({ request }) => {
    const tenant = await signupTenant(request);
    const token = await keycloakPasswordGrantToken(request, tenant);
    const res = await request.post(`${API_BASE}/api/channels/telegram/credential`, {
      headers: { Authorization: `Bearer ${token}` },
      data: { botToken: "000000:definitely-not-a-real-bot-token" },
    });
    expect(res.status()).toBe(400);
  });

  test("Telegram activation rejects a blank Bot Token as a validation error, not a 500", async ({ request }) => {
    const tenant = await signupTenant(request);
    const token = await keycloakPasswordGrantToken(request, tenant);
    const res = await request.post(`${API_BASE}/api/channels/telegram/credential`, {
      headers: { Authorization: `Bearer ${token}` },
      data: { botToken: "" },
    });
    expect(res.status()).toBeGreaterThanOrEqual(400);
    expect(res.status()).toBeLessThan(500);
  });

  test("Email activation rejects an unreachable IMAP host server-side, independent of the UI", async ({ request }) => {
    const tenant = await signupTenant(request);
    const token = await keycloakPasswordGrantToken(request, tenant);
    const res = await request.post(`${API_BASE}/api/channels/email/credential`, {
      headers: { Authorization: `Bearer ${token}` },
      data: {
        imapHost: "imap.e2e-definitely-not-a-real-host.invalid",
        imapPort: 993,
        imapUsername: "e2e-test@example.com",
        imapPassword: "not-a-real-password",
        useSsl: true,
      },
    });
    expect(res.status()).toBe(400);
  });

  test("Email activation rejects a request missing required fields, not a 500", async ({ request }) => {
    const tenant = await signupTenant(request);
    const token = await keycloakPasswordGrantToken(request, tenant);
    const res = await request.post(`${API_BASE}/api/channels/email/credential`, {
      headers: { Authorization: `Bearer ${token}` },
      data: { imapHost: "", imapPort: 993, imapUsername: "", imapPassword: "" },
    });
    expect(res.status()).toBeGreaterThanOrEqual(400);
    expect(res.status()).toBeLessThan(500);
  });
});
