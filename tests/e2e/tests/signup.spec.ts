import { test, expect, type Page } from "@playwright/test";

// web/app/signup/page.tsx - a wizard rendered in-page as IntroStep -> DetailsForm
// -> a result step, with a StepIndicator showing 3 labels (Account / Workspace
// / Launch). The Terms-of-Service checkbox lives on DetailsForm and is enforced
// in JS (handleSubmit checks `agreedToTerms` before anything else, including
// before the POST /api/signup call) - so exercising this validation path is
// side-effect-free: unlike a successful signup, it never reaches the API and
// never provisions a tenant.

async function goToDetailsStep(page: Page) {
  await page.goto("/signup");
  await page.getByRole("button", { name: /create free account/i }).click();
  // DetailsForm is identified by its "Workspace name" field.
  await expect(page.getByLabel("Workspace name")).toBeVisible();
}

test.describe("Signup wizard", () => {
  test("renders the multi-step wizard with its step indicator", async ({ page }) => {
    await page.goto("/signup");
    await expect(page.getByRole("heading", { name: /Create your free workspace/i })).toBeVisible();
    // Step labels from StepIndicator (Account / Workspace / Launch).
    await expect(page.getByText("Account", { exact: true })).toBeVisible();
    await expect(page.getByText("Workspace", { exact: true })).toBeVisible();
    await expect(page.getByText("Launch", { exact: true })).toBeVisible();

    await goToDetailsStepFields(page);
  });

  test("submitting without accepting Terms blocks submission and does not navigate", async ({ page }) => {
    await goToDetailsStep(page);

    // Fill every field the native HTML5 `required`/`minLength` attributes
    // would otherwise block on, so the submit actually reaches handleSubmit's
    // JS-level Terms check rather than stalling on a browser validation
    // tooltip for an unrelated empty field.
    await page.getByLabel("Workspace name").fill("Playwright Test Co");
    await page.getByLabel("First name").fill("Ada");
    await page.getByLabel("Last name").fill("Lovelace");
    await page.getByLabel(/^email$/i).fill(`e2e-${Date.now()}@example.com`);
    await page.getByLabel("Password", { exact: true }).fill("SuperSecret123!");
    await page.getByLabel("Confirm password").fill("SuperSecret123!");

    // Terms checkbox intentionally left unchecked. It's an implicit-label
    // checkbox (wrapped by a <label>, not a separate htmlFor/id pair), so
    // getByLabel matches it via the label's own text content.
    const termsCheckbox = page.getByLabel(/I agree to the/i);
    await expect(termsCheckbox).not.toBeChecked();

    await page.getByRole("button", { name: /create my workspace/i }).click();

    await expect(
      page.getByText("Please agree to the Terms of Service and Privacy Policy to continue.")
    ).toBeVisible();

    // Must still be on the signup page, on the details step (not the
    // post-signup result step, and no navigation away from /signup).
    await expect(page).toHaveURL(/\/signup$/);
    await expect(page.getByLabel("Workspace name")).toBeVisible();
  });

  test("Terms of Service and Privacy Policy links resolve", async ({ page }) => {
    await goToDetailsStep(page);

    const termsHref = await page.getByRole("link", { name: "Terms of Service" }).getAttribute("href");
    const privacyHref = await page.getByRole("link", { name: "Privacy Policy" }).getAttribute("href");
    expect(termsHref).toBeTruthy();
    expect(privacyHref).toBeTruthy();

    const termsResp = await page.request.get(new URL(termsHref!, page.url()).toString());
    expect(termsResp.status()).toBe(200);

    const privacyResp = await page.request.get(new URL(privacyHref!, page.url()).toString());
    expect(privacyResp.status()).toBe(200);
  });
});

// Small helper used only by the "renders the wizard" test above, kept
// separate from goToDetailsStep because that test wants to assert the intro
// step's own content first, then move forward.
async function goToDetailsStepFields(page: Page) {
  await page.getByRole("button", { name: /create free account/i }).click();
  await expect(page.getByLabel("Workspace name")).toBeVisible();
  await expect(page.getByLabel("First name")).toBeVisible();
  await expect(page.getByLabel("Last name")).toBeVisible();
  await expect(page.getByLabel(/^email$/i)).toBeVisible();
  await expect(page.getByLabel("Confirm password")).toBeVisible();
  await expect(page.getByText("I agree to the")).toBeVisible();
}
