import { test, expect } from "@playwright/test";

// /terms and /privacy (web/app/terms/page.tsx, web/app/privacy/page.tsx) are
// plain public Next.js pages (PUBLIC_ROUTES in lib/auth.ts) - no auth, no
// client-side demo state. Simple, meaningful checks: the route actually
// responds 200 (not a 404/500 from a broken build or routing regression)
// and the expected heading renders.

test.describe("Legal pages", () => {
  test("/terms returns 200 and shows its heading", async ({ page }) => {
    const response = await page.goto("/terms");
    expect(response?.status()).toBe(200);
    await expect(page.getByRole("heading", { level: 1, name: "Terms of Service" })).toBeVisible();
  });

  test("/privacy returns 200 and shows its heading", async ({ page }) => {
    const response = await page.goto("/privacy");
    expect(response?.status()).toBe(200);
    await expect(page.getByRole("heading", { level: 1, name: "Privacy Policy" })).toBeVisible();
  });

  test("/terms links back to /privacy and /signup", async ({ page }) => {
    await page.goto("/terms");
    await expect(page.getByRole("link", { name: "Privacy Policy" })).toHaveAttribute("href", "/privacy");
    await expect(page.getByRole("link", { name: "Back to sign up" })).toHaveAttribute("href", "/signup");
  });

  test("/privacy links back to /terms and /signup", async ({ page }) => {
    await page.goto("/privacy");
    await expect(page.getByRole("link", { name: "Terms of Service" })).toHaveAttribute("href", "/terms");
    await expect(page.getByRole("link", { name: "Back to sign up" })).toHaveAttribute("href", "/signup");
  });
});
