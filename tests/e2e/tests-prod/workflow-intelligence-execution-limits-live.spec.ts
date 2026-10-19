import { test, expect } from "@playwright/test";
import { requireUserCredentials, requireMutationOptIn } from "../helpers/prod-env";
import { signInToConsole } from "../helpers/prod-session";

/**
 * REAL-TENANT workflow BEHAVIOR validation for the Workflow Intelligence
 * modal's "Execution limits" control - not just proving the Save click
 * fires a PATCH.
 *
 * Same rationale as agent-autonomy-approval-live.spec.ts: the Intelligence
 * modal has several controls (reasoning depth, tuning mode, step caching,
 * cost routing, execution limits), and nothing in this repo proved any of
 * them actually change run behavior rather than just persisting a value.
 * "Max steps per run" is the cheapest one to prove wrong on: set it below
 * what a real workflow needs and a real run must fail exactly there, not
 * complete anyway. Built and manually verified live against a deployed instance on
 * 2026-09-23 before being
 * written up here.
 *
 * Depends on the "E2E Tool-Use Workflow" fixture (Trigger -> Agent -> Output,
 * needs 3 steps to complete) left in the tenant by
 * agent-tool-execution-live.spec.ts's workflow test - skips with a clear
 * reason if it isn't there, mirroring that file's own dependency policy.
 *
 * Resets Max steps per run back to 50 (this workflow's value before this
 * test touched it, and the field's own placeholder/typical default) in a
 * finally block regardless of outcome, so a real run against this fixture
 * doesn't stay artificially capped for whoever probes it next.
 */
test.describe("Workflow Intelligence - real execution-limits behavior (live production scenario)", () => {
  test.setTimeout(3 * 60 * 1000);

  test("Max steps per run actually caps a real run instead of just being a persisted number", async ({
    page,
  }, testInfo) => {
    requireUserCredentials(testInfo);
    requireMutationOptIn(testInfo);
    testInfo.skip(testInfo.project.name !== "chromium", "writes real data - one project only");

    await signInToConsole(page);
    await page.goto("/workflows", { waitUntil: "domcontentloaded" });

    const row = page.locator("table tr, [role=row]").filter({ hasText: "E2E Tool-Use Workflow" }).first();
    const rowAppeared = await row
      .waitFor({ state: "visible", timeout: 15_000 })
      .then(() => true)
      .catch(() => false);
    if (!rowAppeared) {
      test.skip(
        true,
        "no 'E2E Tool-Use Workflow' fixture in this tenant - see agent-tool-execution-live.spec.ts, which creates it"
      );
    }
    await row.click();
    await expect(page.getByRole("button", { name: /run now/i }), "workflow canvas never loaded").toBeVisible({
      timeout: 30_000,
    });

    const openIntelligence = async () => {
      await page.getByRole("button", { name: /^more$/i }).click();
      await page.getByRole("button", { name: /intelligence/i }).last().click();
      const field = page.getByLabel(/max steps per run/i);
      await expect(field, "Execution limits' Max steps field never rendered in the modal").toBeVisible({
        timeout: 15_000,
      });
      return field;
    };

    try {
      // --- Cap it below what this 3-step workflow needs ---
      const maxStepsField = await openIntelligence();
      await maxStepsField.fill("1");
      await page
        .locator("text=Execution limits")
        .locator("xpath=ancestor::div[1]")
        .getByRole("button", { name: /^save$/i })
        .click();
      await expect(
        page.getByText(/success/i).first(),
        "no success confirmation after saving execution limits"
      ).toBeVisible({ timeout: 10_000 });
      // 2026-09-23, reproduced live: Escape does not close this modal - it
      // has two elements matching accessible name "Close" (the icon button's
      // aria-label and a bottom text button), so .first() rather than a
      // strict single match. Skipping this leaves the backdrop intercepting
      // every subsequent click ("Run now" among them).
      await page.getByRole("button", { name: "Close", exact: true }).first().click();

      // --- Run it - must fail at step 1, never reach the Output node ---
      await page.getByRole("button", { name: /run now/i }).click();
      const prompt = page.getByPlaceholder(/e\.g\. a sample inbound message/i);
      await expect(prompt, "Run modal never opened").toBeVisible({ timeout: 15_000 });
      await prompt.fill("Fetch https://example.com and report the exact heading text.");
      await page.getByRole("button", { name: /run$/i }).click();

      await expect(
        page.getByText(/RUN #.*FAILED/i).first(),
        "a run capped at 1 max step should FAIL, not complete - the limit had no effect"
      ).toBeVisible({ timeout: 30_000 });
      await expect(
        page.getByText(/step 1\s*\/\s*3/i),
        "run should have stopped after exactly step 1 of 3, matching the configured cap"
      ).toBeVisible({ timeout: 10_000 });
    } finally {
      // --- Reset the cap so this fixture isn't left artificially broken ---
      // A fresh reload rather than reusing the post-run page state: the
      // FAILED run leaves a backdrop overlay in the DOM that blocks the
      // Intelligence button - reproduced live - and this cleanup must not
      // itself throw and mask whichever assertion above actually failed.
      await page.goto("/workflows", { waitUntil: "domcontentloaded" }).catch(() => {});
      const row = page.locator("table tr, [role=row]").filter({ hasText: "E2E Tool-Use Workflow" }).first();
      await row.click().catch(() => {});
      const maxStepsField = await openIntelligence().catch(() => null);
      if (maxStepsField) {
        await maxStepsField.fill("50").catch(() => {});
        await page
          .locator("text=Execution limits")
          .locator("xpath=ancestor::div[1]")
          .getByRole("button", { name: /^save$/i })
          .click()
          .catch(() => {});
      }
    }
  });
});
