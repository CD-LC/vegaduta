import { test, expect } from "@playwright/test";
import { collectProdErrors } from "../helpers/prod-console";
import { skipIfOssSite } from "../helpers/prod-env";

/**
 * The public billing page (/billing renders for logged-out visitors - it is
 * in PUBLIC_ROUTES).
 *
 * 2026-09-22 free-mode pivot: platform.free-mode.enabled=true replaced the
 * three paid tiers (Free/Premium/Unlimited), the monthly/annual toggle and
 * the ₹/$ price matrix with a single "everything is free" page - re-verified
 * live against a deployed instance (h1 "Everything is free. Really.",
 * re-verified 2026-09-25 after a copy change to "Open source. Free for
 * everyone." - the assertions accept either free-mode h1),
 * one CTA "Start free" -> /signup, no prices anywhere). Do not restore the
 * old tiered-pricing assertions here; confirm with the maintainer before assuming
 * this reverts to paid tiers.
 *
 * Nothing here starts a checkout or touches the payment gateway.
 */
test.describe("Public pricing page", () => {
  test.beforeEach(async ({ page }, testInfo) => {
    // SE-42: /billing is a classic-site route; the OSS site redirects it to /.
    skipIfOssSite(testInfo);
    await page.goto("/billing");
  });

  // A deployment builds the web console in free or paid mode (PUBLIC_FREE_MODE). The page is one or the other, so detect the
  // mode from the h1 and assert that mode's contract instead of hard-coding one.
  const FREE_H1 = /open source|free for everyone|everything is free/i;

  test("renders the plan explanation for the active mode", async ({ page }) => {
    const h1 = page.getByRole("heading", { level: 1 });
    await expect(h1).toBeVisible();
    const body = await page.locator("body").innerText();
    if (FREE_H1.test(await h1.innerText())) {
      for (const feature of [/unlimited agents/i, /bring your own key/i, /whatsapp/i, /telegram/i]) {
        expect(feature.test(body), `free-mode billing page is missing ${feature}`).toBe(true);
      }
    } else {
      await expect(h1).toHaveText(/pricing/i);
      for (const tier of [/free/i, /premium/i, /unlimited/i]) {
        expect(tier.test(body), `paid billing page is missing the ${tier} tier`).toBe(true);
      }
    }
  });

  test("prices match the active mode: none in free mode, real amounts in paid mode", async ({ page }) => {
    await expect(page.getByRole("heading", { level: 1 })).toBeVisible();
    const h1 = await page.getByRole("heading", { level: 1 }).innerText();
    const body = await page.locator("body").innerText();
    if (FREE_H1.test(h1)) {
      // Free mode: a stray amount would mean a paid-tier fragment leaked back in.
      expect(body, "an INR amount leaked onto the free-mode billing page").not.toMatch(/₹\s?[\d,]+/);
      expect(body, "a USD amount leaked onto the free-mode billing page").not.toMatch(/\$\s?[\d,]+/);
    } else {
      // Paid mode: amounts must render as numbers, never placeholders.
      expect(body, "paid billing page shows no price at all").toMatch(/[₹$]\s?[\d,]+/);
      expect(body, "a price rendered as NaN/undefined").not.toMatch(/[₹$]\s?(NaN|undefined)|NaN/);
    }
  });

  test("the primary CTA leads to signup, not a plan picker", async ({ page }) => {
    const cta = page.getByRole("link", { name: /start free/i }).first();
    await expect(cta).toBeVisible();
    await expect(cta).toHaveAttribute("href", /^\/signup/);
  });

  test("a logged-out visitor is asked to sign in rather than shown a checkout", async ({ page }) => {
    // /billing is public, but actually buying requires a session. The CTA
    // must lead to auth/signup, not to a payment form.
    const body = await page.locator("body").innerText();
    expect(body).toMatch(/sign in|start free|get started/i);

    // No card fields on a public page.
    await expect(page.locator('input[autocomplete="cc-number"]')).toHaveCount(0);
    await expect(page.getByPlaceholder(/card number/i)).toHaveCount(0);
  });

  test("loads with no uncaught JavaScript errors", async ({ page }) => {
    const errors = collectProdErrors(page);
    await page.goto("/billing", { waitUntil: "domcontentloaded" });
    await page.waitForLoadState("networkidle", { timeout: 10_000 }).catch(() => {});
    const unexpected = errors.unexpected();
    expect(unexpected, `pricing page produced unexpected errors:\n${unexpected.join("\n")}`).toEqual([]);
  });
});
