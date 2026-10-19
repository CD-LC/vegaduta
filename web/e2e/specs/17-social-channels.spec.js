// Business case: social channel integrations (#195/#196/#197) - Instagram DM
// + Facebook Messenger (one shared Meta Page connection), Slack, Discord,
// and X (Twitter) DMs, each a credential form on the admin-only
// /admin/channels page.
//
// These four channels used to live in a separate SocialChannelsPanel.js
// component, but that component is no longer rendered anywhere - the #472
// AXON rebuild folded it into ChannelsPanel.js's own unified card grid +
// contextual inspector (confirmed by grepping the whole app for
// SocialChannelsPanel: only an in-code comment in ChannelsPanel.js itself
// and one unrelated array copy in ConnectedPlatformsPanel.js reference it
// now - nothing imports/renders it). This spec was originally written
// against that old, now-dead component's `.field`/`.error-box` markup;
// rewritten here to drive the real thing: click a channel's card
// (`[data-testid="channel-card"]`) to select it (this sets `selected` and
// swaps the inspector's form), then assert against the one shared
// `[data-testid="channels-alert-error"]` every channel's validation now
// renders into (see ChannelsPanel.tsx's top-level `error` state, shared
// across all nine channels including WhatsApp).
//
// None of these four platforms can be connected end to end with a real,
// working credential in an automated suite - each requires a live app
// registered on that platform's own developer console. What IS reliably
// testable without any external dependency is each form's client-side
// "required fields" validation (checked before ever calling the API) and,
// for X specifically, its ?x=connected|error OAuth-callback-param handling
// - the same pattern already covered for Google in
// 16-google-integration.spec.js.
const { test, expect } = require("../fixtures");

async function selectChannelCard(page, label) {
  await page.locator('[data-testid="channel-card"]', { hasText: label }).first().click();
}

test.describe("Social channel integrations", () => {
  test("a non-admin console user is redirected away from /admin/channels", async ({ consolePage: page }) => {
    await page.goto("/admin/channels");
    await expect(page).toHaveURL((url) => url.pathname === "/" || url.pathname === "", { timeout: 15_000 });
  });

  test("Messenger + Instagram: connecting with missing fields is blocked client-side", async ({ adminPage: page }) => {
    await page.goto("/admin/channels");
    await expect(page.getByRole("heading", { name: "Connect WhatsApp" })).toBeVisible({ timeout: 15_000 });

    await selectChannelCard(page, "Messenger");
    await expect(page.getByRole("heading", { name: "Connect Messenger + Instagram" })).toBeVisible();
    await page.getByLabel("Page ID").fill("123456789");
    // Page Access Token and App Secret left blank on purpose.
    await page.getByRole("button", { name: "Connect", exact: true }).click();
    await expect(page.getByTestId("channels-alert-error")).toContainText("Page ID, Page Access Token, and App Secret are required.");
  });

  test("Slack: connecting with only a Bot Token (no Signing Secret) is blocked client-side", async ({ adminPage: page }) => {
    await page.goto("/admin/channels");
    await selectChannelCard(page, "Slack");
    await expect(page.getByRole("heading", { name: "Connect Slack" })).toBeVisible({ timeout: 15_000 });
    await page.getByLabel("Bot Token").fill("xoxb-fake-token-for-e2e");
    await page.getByRole("button", { name: "Connect", exact: true }).click();
    await expect(page.getByTestId("channels-alert-error")).toContainText("Bot Token and Signing Secret are both required.");
  });

  test("Discord: connecting with a blank Bot Token is blocked client-side", async ({ adminPage: page }) => {
    await page.goto("/admin/channels");
    await selectChannelCard(page, "Discord");
    await expect(page.getByRole("heading", { name: "Connect Discord" })).toBeVisible({ timeout: 15_000 });
    await page.getByRole("button", { name: "Connect", exact: true }).click();
    await expect(page.getByTestId("channels-alert-error")).toContainText("Bot Token is required.");
  });

  test("X: registering an app with a blank Client Secret is blocked client-side", async ({ adminPage: page }) => {
    await page.goto("/admin/channels");
    await selectChannelCard(page, "X - DMs");
    await expect(page.getByRole("heading", { name: "Connect X" })).toBeVisible({ timeout: 15_000 });
    await page.getByLabel("Client ID").fill("e2e-fake-client-id");
    await page.getByRole("button", { name: "Register app" }).click();
    await expect(page.getByTestId("channels-alert-error")).toContainText("Client ID and Client Secret are both required.");
  });

  test("X: landing back with ?x=error&reason=consent_declined shows the cancelled-specific message", async ({ adminPage: page }) => {
    await page.goto("/admin/channels?x=error&reason=consent_declined");
    // ChannelsPanel's own handler only distinguishes connected/error, not
    // the reason query param - see its useEffect reading searchParams.get("x").
    await expect(page.getByTestId("channels-alert-error")).toContainText("Could not connect X - please try again.", { timeout: 15_000 });
    await expect(page).toHaveURL(/\/admin\/channels$/);
  });

  test("X: landing back with ?x=connected shows a success banner", async ({ adminPage: page }) => {
    await page.goto("/admin/channels?x=connected");
    await expect(page.getByTestId("channels-alert-success")).toContainText("X account connected.", { timeout: 15_000 });
    await expect(page).toHaveURL(/\/admin\/channels$/);
  });
});
