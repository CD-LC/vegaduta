import { test, expect } from "@playwright/test";
import { IS_OSS_SITE, assertLocalhostFree } from "../helpers/prod-env";

/**
 * The OSS site's landing page (E2E_SITE_MODE=oss). The classic marketing landing is covered by landing.spec.ts and
 * is skipped in OSS mode; this is its counterpart: the open-source landing must render its own hero, link into the
 * product, and send the visitor to /login via a header LINK (not the classic header button).
 */
test.describe("OSS landing page", () => {
  test.beforeEach(({}, testInfo) => {
    testInfo.skip(!IS_OSS_SITE, "only meaningful with E2E_SITE_MODE=oss");
  });

  test("renders the open-source hero and the primary calls to action", async ({ page }) => {
    await page.goto("/", { waitUntil: "domcontentloaded" });
    await expect(page).toHaveTitle(/VegaD.*(open[- ]source|platform for AI agents)/i);

    const h1 = page.getByRole("heading", { level: 1 }).first();
    await expect(h1).toBeVisible();
    await expect(h1).toHaveText(/build, govern and run AI agents/i);

    await expect(page.getByRole("link", { name: /read the docs/i }).first()).toHaveAttribute("href", /\/docs/);
    await expect(page.getByRole("link", { name: /view on github/i }).first()).toBeVisible();
  });

  test("the header Sign in is a link to /login and Get started goes to /signup", async ({ page }) => {
    await page.goto("/", { waitUntil: "domcontentloaded" });
    const header = page.locator("header");
    await expect(header.getByRole("link", { name: /^sign in$/i }).first()).toHaveAttribute("href", "/login");
    await expect(header.getByRole("link", { name: /get started/i }).first()).toHaveAttribute("href", "/signup");
    // The classic site's Sign in is a button; the OSS one must not be.
    await expect(header.getByRole("button", { name: /^sign in$/i })).toHaveCount(0);
  });

  test("the landing HTML carries no localhost link", async ({ page }) => {
    await page.goto("/", { waitUntil: "domcontentloaded" });
    const hrefs = await page.locator("a[href]").evaluateAll((els) => els.map((el) => el.getAttribute("href") || ""));
    assertLocalhostFree(hrefs.join("\n"), "OSS landing anchor hrefs");
  });

  test("classic-only routes redirect to the landing instead of rendering marketing pages", async ({ request }) => {
    for (const path of ["/compare", "/hire", "/earn", "/why-vegaduta"]) {
      const res = await request.get(path, { maxRedirects: 0 });
      expect(res.status(), `${path} should redirect to / on the OSS site`).toBeGreaterThanOrEqual(300);
      expect(res.status(), `${path} should redirect, not error`).toBeLessThan(400);
      expect(new URL(res.headers()["location"] ?? "", "http://x").pathname).toBe("/");
    }
  });
});
