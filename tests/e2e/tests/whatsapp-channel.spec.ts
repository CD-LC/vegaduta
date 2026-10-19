import { test, expect } from "@playwright/test";
import { signUpFreshTenant } from "../helpers/test-tenant";

// WhatsApp channel - connect-method smoke.
//
// WhatsApp connects only through official WhatsApp Business Platform paths:
// Meta's WhatsApp Cloud API (manual credentials or Embedded Signup) and a
// Twilio WhatsApp sender. This spec pins that the console offers those two
// methods to a brand-new tenant. Completing either connection needs real
// Meta/Twilio credentials, which this smoke deliberately does not use.
//
// Selectors pinned by reading the real routed component
// (web/app/components/ChannelsPanel.tsx, rendered at /admin/channels) and
// its translation strings (web/messages/en.json).

test.describe("WhatsApp channel - connect methods", () => {
  test("a fresh tenant sees the Meta Cloud API and Twilio connect methods", async ({ page }, testInfo) => {
    testInfo.skip(testInfo.project.name !== "chromium", "provisions a real tenant - run on one project only");
    // 25min, matching console-core-smoke.spec.ts's convention (sign-up can
    // wait on helpers/mailinator.ts's up-to-20min polling ceiling).
    test.setTimeout(25 * 60 * 1000);

    await signUpFreshTenant(page, "whatsapp");

    await page.goto("/admin/channels");

    // WhatsApp is the default-selected channel card and a brand-new tenant
    // has never connected it.
    const whatsappCard = page.locator('[data-testid="channel-card"][data-channel="whatsapp"]');
    await expect(whatsappCard).toBeVisible({ timeout: 20_000 });
    await expect(whatsappCard).toHaveAttribute("aria-pressed", "true");
    await expect(whatsappCard.getByText("+ connect")).toBeVisible();

    await expect(page.getByRole("heading", { name: "Connect WhatsApp" })).toBeVisible({ timeout: 15_000 });
    await expect(page.getByRole("tab", { name: "Meta Cloud API" })).toBeVisible();
    await expect(page.getByRole("tab", { name: "Twilio" })).toBeVisible();
  });
});
