import { test, expect } from "@playwright/test";
import { signUpFreshTenant } from "../helpers/test-tenant";
import { groupSave, importAgent, settingRow } from "../helpers/console-controls";

// Runtime settings, end to end on ONE freshly signed-up tenant (its first user is the workspace admin):
//   1. Set "Most tool calls per turn" for the whole workspace at /admin/runtime-settings.
//   2. The agent's Behavior and limits tab shows that value, labelled as inherited from the workspace.
//   3. Overriding it on the agent changes only that agent; Reset to inherited gives the workspace value back.
//   4. Resetting the workspace value returns it to the platform default.
// Real side effects: it provisions a tenant on whatever E2E_BASE_URL points at. No model or provider is needed.

const KEY = "agent.limits.maxToolSteps";
const LABEL = "Most tool calls per turn";

test.describe("Runtime settings: workspace value, agent inheritance, reset", () => {
  test("a workspace setting shows up on the agent as inherited, and an agent override stays on the agent", async ({ page }, testInfo) => {
    testInfo.skip(testInfo.project.name !== "chromium", "provisions a real tenant - run on one project only");
    test.setTimeout(10 * 60 * 1000);

    await signUpFreshTenant(page, "settings");
    const agentId = await importAgent(page, "E2E Settings Agent");

    let platformDefault = "";
    await test.step("the workspace page lists the setting with its source and bounds from the catalog", async () => {
      await page.goto("/admin/runtime-settings");
      await expect(page.getByRole("heading", { level: 1, name: "Runtime settings" })).toBeVisible();
      const row = settingRow(page, KEY);
      await expect(row).toBeVisible({ timeout: 20_000 });
      await expect(row.getByTestId("setting-source")).toHaveAttribute("data-source", /default|platform/);
      platformDefault = await row.getByRole("spinbutton", { name: LABEL }).inputValue();
      expect(Number(platformDefault)).toBeGreaterThan(0);
      // Out of range is refused inline, nothing is sent.
      await row.getByRole("spinbutton", { name: LABEL }).fill("100000");
      await expect(row.getByTestId("setting-validation")).not.toBeEmpty();
      await row.getByRole("spinbutton", { name: LABEL }).fill(platformDefault);
    });

    const workspaceValue = String(Number(platformDefault) === 27 ? 28 : 27);

    await test.step("save a workspace value: it is in effect now and marked as the workspace's", async () => {
      const row = settingRow(page, KEY);
      await row.getByRole("spinbutton", { name: LABEL }).fill(workspaceValue);
      await expect(row.getByTestId("setting-unsaved")).toBeVisible();
      await groupSave(page, "agent-limits").click();
      await expect(row.getByTestId("setting-effective")).toContainText(workspaceValue);
      await expect(row.getByTestId("setting-source")).toHaveAttribute("data-source", "tenant");
      await expect(row.getByTestId("setting-reset")).toBeVisible();
      // It is stored, not just shown: a reload reads it back from the server.
      await page.reload();
      await expect(settingRow(page, KEY).getByRole("spinbutton", { name: LABEL })).toHaveValue(workspaceValue, { timeout: 20_000 });
    });

    await test.step("the agent's Behavior tab shows it as inherited from the workspace", async () => {
      await page.goto(`/agents?agentId=${encodeURIComponent(agentId)}&section=behavior&focus=${KEY}`);
      await expect(page.getByTestId("behavior-limits-tab")).toBeVisible({ timeout: 20_000 });
      const row = settingRow(page, KEY);
      await expect(row.getByRole("spinbutton", { name: LABEL })).toHaveValue(workspaceValue);
      await expect(row.getByTestId("setting-source")).toContainText("Inherited from workspace");
      // Nothing to reset on the agent: it has no value of its own.
      await expect(row.getByTestId("setting-reset")).toHaveCount(0);
    });

    await test.step("an override on the agent wins for that agent only, and reset gives the workspace value back", async () => {
      const row = settingRow(page, KEY);
      await row.getByRole("spinbutton", { name: LABEL }).fill("12");
      await groupSave(page, "agent-limits").click();
      await expect(row.getByTestId("setting-source")).toHaveAttribute("data-source", "agent");
      await expect(row.getByTestId("setting-effective")).toContainText("12");

      // The workspace page is unchanged.
      await page.goto("/admin/runtime-settings");
      await expect(settingRow(page, KEY).getByRole("spinbutton", { name: LABEL })).toHaveValue(workspaceValue, { timeout: 20_000 });

      await page.goto(`/agents?agentId=${encodeURIComponent(agentId)}&section=behavior`);
      const again = settingRow(page, KEY);
      await expect(again.getByRole("spinbutton", { name: LABEL })).toHaveValue("12", { timeout: 20_000 });
      await again.getByTestId("setting-reset").click();
      await expect(again.getByRole("spinbutton", { name: LABEL })).toHaveValue(workspaceValue);
      await expect(again.getByTestId("setting-source")).toContainText("Inherited from workspace");
    });

    await test.step("resetting the workspace value returns the platform default", async () => {
      await page.goto("/admin/runtime-settings");
      const row = settingRow(page, KEY);
      await expect(row.getByTestId("setting-reset")).toBeVisible({ timeout: 20_000 });
      await row.getByTestId("setting-reset").click();
      await expect(row.getByRole("spinbutton", { name: LABEL })).toHaveValue(platformDefault);
      await expect(row.getByTestId("setting-source")).toHaveAttribute("data-source", /default|platform/);
    });
  });
});
