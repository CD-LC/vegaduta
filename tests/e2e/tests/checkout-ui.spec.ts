import { test, expect } from "@playwright/test";
import { signUpFreshTenant } from "../helpers/test-tenant";

// Verifies the FREE-tenant upgrade/checkout UI on the main app's "Plans &
// features" page (web/app/features/page.tsx -> UpgradePrompt.tsx). This is
// the checkout entry point a Keycloak-authenticated tenant (what
// signUpFreshTenant provisions) can actually reach.
//
// The Academy's OWN separate checkout flow
// (vegaduta-webapp-prototype/vegaduta-webapp/ui/src/pages/Upgrade.tsx) is
// checked, not assumed: it does NOT redirect here. It runs in its own
// deployable with its own auth (SessionProvider backed by GET /account +
// /auth/login - see ui/src/lib/session.tsx's useSession(), nothing to do
// with Keycloak) and its own backend checkout endpoint (POST
// /account/upgrade-checkout, vs. this app's POST /api/tenant/upgrade). This
// suite's only tenant-provisioning helper (signUpFreshTenant) creates a
// Keycloak-authenticated MAIN APP tenant, not an Academy learner account, so
// the Academy flow is genuinely out of reach of this spec without a new,
// Academy-specific signup helper - not written here since this task is
// scoped to this one file. Flagging that as a real coverage gap rather than
// silently skipping it.
//
// SAFETY (hard constraint for this spec): only ever confirm the Razorpay
// checkout surface loaded with a plausible plan/amount, then dismiss it
// (Escape - Razorpay Standard Checkout's own documented close affordance)
// WITHOUT focusing, filling, or submitting any field inside Razorpay's own
// iframe, and without clicking any pay/confirm control in Razorpay's UI.
// Clicking the upgrade CTA does make one real backend call
// (POST /api/tenant/upgrade) that opens a real, uncompleted Razorpay
// subscription intent against the throwaway test tenant - that call itself
// never charges anything; only a completed Razorpay checkout would, and this
// spec never completes one.

test.describe("Checkout UI - FREE tenant upgrade flow", () => {
  test("plans page renders real tier differences, and the upgrade CTA opens a real Razorpay checkout without ever submitting payment info", async ({
    page,
  }, testInfo) => {
    // Provisions a real tenant and opens a real (never-completed) Razorpay
    // checkout session - run once, on one project only.
    testInfo.skip(
      testInfo.project.name !== "chromium",
      "provisions a real tenant and a real Razorpay checkout session - run on one project only"
    );
    test.setTimeout(25 * 60 * 1000);

    await signUpFreshTenant(page, "checkout");

    // --- 1. Plans & features page renders real, differentiated content ---
    await page.goto("/features");
    await expect(page.getByRole("heading", { name: "Plans & features" })).toBeVisible();

    // Fresh signup is always FREE - the current-plan callout must say so,
    // and the current-plan badge must land on the Free column.
    await expect(page.getByText(/on the/i).filter({ hasText: "Free" })).toBeVisible();

    const table = page.locator("table");
    await expect(table).toBeVisible();

    // All three tiers with their real prices (cross-checked against
    // UpgradePrompt.tsx / docs/BILLING-TIER-STRATEGY-2026-07-27.md: Free
    // $0, Premium $19/mo, Unlimited $79/mo).
    const headerRow = table.locator("thead tr");
    await expect(headerRow.getByText("Free", { exact: true })).toBeVisible();
    await expect(headerRow.getByText("Premium", { exact: true })).toBeVisible();
    await expect(headerRow.getByText("Unlimited", { exact: true })).toBeVisible();
    await expect(headerRow).toContainText("$0");
    await expect(headerRow).toContainText("$19");
    await expect(headerRow).toContainText("$79");
    // Free is the tenant's real, live current plan.
    await expect(headerRow.getByText("Your plan", { exact: true })).toBeVisible();

    // A real boolean feature-gate row that actually differs per tier: BYOK
    // is off on Free, on for Premium/Unlimited.
    const byokRow = table.locator("tbody tr", { hasText: "Bring your own key & model choice" });
    await expect(byokRow).toBeVisible();
    await expect(byokRow.locator('[aria-label="Not included"]')).toHaveCount(1);
    await expect(byokRow.locator('[aria-label="Included"]')).toHaveCount(2);

    // A real numeric-limit row that differs per tier (2 / 10 / unlimited seats).
    const seatsRow = table.locator("tbody tr", { hasText: "Team members" });
    await expect(seatsRow).toBeVisible();
    await expect(seatsRow).toContainText("2");
    await expect(seatsRow).toContainText("10");
    await expect(seatsRow).toContainText("∞");

    // --- 2. Upgrade CTA opens the real Razorpay checkout ---
    await page.getByRole("button", { name: "Upgrade plan" }).click();
    const dialog = page.getByRole("dialog", { name: "Upgrade your plan" });
    await expect(dialog).toBeVisible();

    const premiumUpgradeButton = dialog.getByRole("button", { name: "Upgrade to Premium" });
    await expect(premiumUpgradeButton).toBeVisible();
    await premiumUpgradeButton.click();

    // Razorpay's Standard Checkout injects its own iframe (served from
    // checkout.razorpay.com / api.razorpay.com) once window.Razorpay(...).open()
    // runs. Wait for that frame to actually load real content.
    const razorpayFrame = page.frameLocator('iframe[src*="razorpay.com"]').first();
    await expect(razorpayFrame.locator("body")).toBeVisible({ timeout: 30_000 });

    // Plausible plan name / amount visible inside the real checkout surface -
    // read-only assertions, no interaction with any field inside this frame.
    await expect(razorpayFrame.getByText(/premium/i).first()).toBeVisible({ timeout: 15_000 });
    await expect(razorpayFrame.getByText(/\d/).first()).toBeVisible();

    // --- 3. Dismiss WITHOUT ever touching a payment field, verify plan unchanged ---
    // Escape is Razorpay Standard Checkout's own documented close affordance -
    // this never focuses, fills, or submits anything inside Razorpay's iframe,
    // and never clicks a pay/confirm control inside it.
    await page.keyboard.press("Escape");
    await expect(page.locator('iframe[src*="razorpay.com"]')).toHaveCount(0, { timeout: 15_000 });

    // Our own dialog may still be open behind it (its ondismiss handler only
    // clears the busy state, it doesn't auto-close) - close it explicitly too.
    if (await dialog.isVisible().catch(() => false)) {
      await dialog.getByRole("button", { name: "Close" }).click();
    }
    await expect(dialog).toBeHidden();

    // Reload the features page: the tenant must still be FREE - no payment
    // was ever submitted, so nothing should have flipped the plan.
    await page.reload();
    await expect(page.getByRole("heading", { name: "Plans & features" })).toBeVisible();
    await expect(page.getByText(/on the/i).filter({ hasText: "Free" })).toBeVisible();
    await expect(page.getByRole("button", { name: "Upgrade plan" })).toBeVisible();
  });
});
