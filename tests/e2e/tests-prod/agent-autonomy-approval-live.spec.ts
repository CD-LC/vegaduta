import { test, expect } from "@playwright/test";
import { requireUserCredentials, requireMutationOptIn } from "../helpers/prod-env";
import { signInToConsole, skipIfHostedAllowanceExhausted, expandReasoningAutonomySection } from "../helpers/prod-session";
import { fixStageApiBase } from "./local-stage-fix";

/**
 * REAL-TENANT agent BEHAVIOR validation, not just control-click verification.
 *
 * Every other spec in this suite that touches a workbench control (reasoning
 * depth, autonomy, guardrails...) proves the click fires the right PATCH and
 * the UI reflects it. None of them proved the setting actually changes what
 * the agent DOES. This file closes that gap for Autonomy specifically,
 * because it's the one control with a sharp, cheaply-observable behavioral
 * fork: per the product's own copy, "Supervised: every tool call waits for
 * a human to approve it" vs the default "Semi-autonomous: follows the
 * workspace's approval policy". Built and manually verified live end to end
 * against a deployed instance (see the entry for this in
 * the project changelog) before being written up here.
 *
 * The full loop this test walks, all confirmed real: setting Supervised ->
 * asking the agent to use a tool -> the tool call is intercepted rather than
 * run ("requires human approval, which has been requested") -> a matching
 * PENDING request appears at /admin/approvals with the real tool name and
 * arguments -> clicking Approve there ("Approved - the action will now
 * execute") lets it proceed. Resets the agent back to Semi-autonomous (the
 * documented default for every agent) when done, so it doesn't sit gating
 * future manual probes against this fixture.
 *
 * Depends on the "filesystem" MCP server already being registered+trusted
 * in this tenant (see mcp-server-registration-live.spec.ts, which this
 * suite runs first and which leaves that server in place deliberately for
 * reuse) - if it isn't, this test registers it itself rather than skip,
 * since that's a one-click, no-key action.
 */
test.describe("Agent autonomy tier - real approval-gate behavior (live production scenario)", () => {
  test.setTimeout(3 * 60 * 1000);
  test.use({ bypassCSP: true }); // stage fix: console calls api.<domain>; its CSP only allows localhost:8280

  test("Supervised autonomy actually intercepts a tool call into the approvals queue, and Approve releases it", async ({
    page,
  }, testInfo) => {
    requireUserCredentials(testInfo);
    requireMutationOptIn(testInfo);
    testInfo.skip(testInfo.project.name !== "chromium", "writes real data - one project only");

    await fixStageApiBase(page);
    await signInToConsole(page);

    // --- Make sure the filesystem tool is available to call ---
    await page.goto("/tools", { waitUntil: "domcontentloaded" });
    const filesystemCard = page.getByRole("button", { name: "filesystem" }).first();
    await expect(filesystemCard, "filesystem catalog entry never rendered at /tools").toBeVisible({
      timeout: 20_000,
    });
    await filesystemCard.click();
    const activate = page.getByRole("button", { name: /^activate$/i }).locator("visible=true").first();
    if (await activate.waitFor({ state: "visible", timeout: 8_000 }).then(() => true, () => false)) {
      await activate.click();
      await expect(page.getByText(/activated "filesystem"/i)).toBeVisible({ timeout: 20_000 });
      const useAnyway = page.getByRole("button", { name: /use anyway.*trust this server/i });
      if (await useAnyway.waitFor({ state: "visible", timeout: 8_000 }).then(() => true, () => false)) {
        await useAnyway.click();
      }
    }

    // --- Set this agent to Supervised ---
    await page.goto("/agents", { waitUntil: "domcontentloaded" });
    await skipIfHostedAllowanceExhausted(page, testInfo);
    await expandReasoningAutonomySection(page);
    const supervised = page.getByTestId("autonomy-tier-supervised");
    await expect(supervised, "Autonomy section (Supervised pill) never rendered").toBeVisible({ timeout: 20_000 });
    await supervised.click();
    await expect(page.getByText(/autonomy updated/i), "no confirmation after selecting Supervised").toBeVisible({
      timeout: 15_000,
    });

    try {
      // --- Ask it to use the tool - this must NOT execute immediately ---
      const message = page.getByPlaceholder(/send a test message/i);
      await expect(message).toBeVisible({ timeout: 20_000 });
      await message.fill("Use your filesystem tool to list files in your sandboxed directory.");
      await page.locator('button[aria-label="Send"][type="submit"]').click();

      await expect(
        page.getByText(/requires human approval/i),
        "Supervised autonomy did not intercept the tool call - it should never run without approval"
      ).toBeVisible({ timeout: 30_000 });

      // --- The request must be real, not just a UI-only message ---
      await page.goto("/admin/approvals", { waitUntil: "domcontentloaded" });
      const pendingRow = page.getByText(/mcp_tool\.[a-z_]+/i).first();
      await expect(pendingRow, "no matching PENDING approval request appeared at /admin/approvals").toBeVisible({
        timeout: 20_000,
      });
      await pendingRow.click();

      const approveButton = page.getByRole("button", { name: /^approve$/i });
      await expect(approveButton, "Approve action never rendered for the pending request").toBeVisible({
        timeout: 15_000,
      });
      await approveButton.click();
      await expect(
        page.getByText(/approved.*will now execute/i),
        "clicking Approve did not confirm the action would proceed"
      ).toBeVisible({ timeout: 15_000 });
    } finally {
      // --- Reset to the documented default, whatever happened above ---
      await page.goto("/agents", { waitUntil: "domcontentloaded" });
      await expandReasoningAutonomySection(page);
      const semiAutonomous = page.getByTestId("autonomy-tier-semi_autonomous");
      if (await semiAutonomous.isVisible({ timeout: 10_000 }).catch(() => false)) {
        await semiAutonomous.click();
        await expect(page.getByText(/autonomy updated/i)).toBeVisible({ timeout: 15_000 }).catch(() => {});
      }
    }
  });
});
