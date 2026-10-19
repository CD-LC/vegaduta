import { test, expect, type Page } from "@playwright/test";
import { APP_URL, USER_EMAIL, USER_PASSWORD } from "../helpers/prod-env";
import { seedConsoleSeenFlags, captureAuthState } from "../helpers/prod-auth-state";

/** Kind-stage login bootstrap (landing exposes "Sign in" as a link + email step; helpers expect a header button).
 *  Captures the session so signInToConsole() restores it for the existing specs. */
test("stage login bootstrap (workflow-build)", async ({ page }: { page: Page }) => {
  await seedConsoleSeenFlags(page);
  await page.goto(APP_URL);
  await page.getByRole("link", { name: /^sign in$/i }).first().click();
  await page.getByRole("textbox", { name: "Email" }).fill(USER_EMAIL!);
  await page.getByRole("button", { name: /continue to secure sign-in/i }).click();
  await page.waitForURL(/auth\./, { timeout: 30_000 });
  await page.locator("#username").fill(USER_EMAIL!);
  await page.locator("#password").fill(USER_PASSWORD!);
  await page.locator("#kc-login").click();
  await page.waitForURL(/app\./, { timeout: 45_000 });
  await expect(page.locator("header")).toBeVisible({ timeout: 30_000 });
  await page.waitForTimeout(3000);
  await captureAuthState(page);
});
