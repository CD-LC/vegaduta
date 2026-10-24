import { test, expect } from "@playwright/test";
import { signUpFreshTenant } from "../helpers/test-tenant";
import { importAgent, pickOption } from "../helpers/console-controls";

// Prompt registry: override a prompt for the workspace, then see it in the final prompt an agent would get.
//   1. /admin/instructions > All prompts: open "Current date", append a sentence to its wording and save (the {date}
//      placeholder is kept, so the editor accepts it).
//   2. The list marks it as a workspace version (v1).
//   3. Preview final prompt: build the prompt of an agent; the current-date block shows the override, labelled as such.
//   4. Revert: the preview goes back to the shipped wording.
// The preview builds the prompt and tool list exactly as a turn would, without calling a model, so no provider is needed.
// Real side effects: it provisions a tenant on whatever E2E_BASE_URL points at.

const KEY = "directive.current_date";
const MARKER = `E2E marker ${Date.now()}`;

test.describe("Prompt override shows up in the preview", () => {
  test("a workspace override of a prompt appears in the agent's final prompt, and reverting removes it", async ({ page }, testInfo) => {
    testInfo.skip(testInfo.project.name !== "chromium", "provisions a real tenant - run on one project only");
    test.setTimeout(10 * 60 * 1000);

    await signUpFreshTenant(page, "prompts");
    await importAgent(page, "E2E Prompt Agent");

    await page.goto("/admin/instructions");
    await expect(page.getByRole("heading", { level: 1, name: "Instructions" })).toBeVisible({ timeout: 30_000 });

    await test.step("open the prompt in the registry and save an override at workspace scope", async () => {
      await page.getByRole("tab", { name: "All prompts" }).click();
      const card = page.getByTestId(`prompt-${KEY}`);
      await expect(card).toBeVisible({ timeout: 20_000 });
      await expect(page.getByTestId(`source-${KEY}`)).toHaveText("Shipped default");
      await card.getByRole("button", { name: /^Edit / }).click();

      const editor = page.getByTestId(`editor-${KEY}`);
      await expect(editor).toBeVisible();
      const text = editor.getByRole("textbox").first();
      const shipped = await text.inputValue();
      expect(shipped).toContain("{date}");
      await text.fill(`${shipped} ${MARKER}`);
      await editor.getByLabel("Reason for the change (kept in the history)").fill("e2e override");
      await editor.getByRole("button", { name: "Save", exact: true }).click();
      await expect(page.getByTestId(`source-${KEY}`)).toHaveText(/Workspace v1/, { timeout: 20_000 });
    });

    await test.step("the final prompt of the agent contains the override, tagged as a workspace override", async () => {
      await page.getByRole("tab", { name: "Preview final prompt" }).click();
      await pickOption(page, "Agent", "E2E Prompt Agent");
      await page.getByRole("button", { name: "Build preview" }).click();
      const block = page.getByTestId(`block-${KEY}`);
      await expect(block).toBeVisible({ timeout: 30_000 });
      await expect(block).toContainText(MARKER);
      await expect(block).toContainText(/tenant override v1/);
    });

    await test.step("reverting brings the shipped wording back", async () => {
      await page.getByRole("tab", { name: "All prompts" }).click();
      const card = page.getByTestId(`prompt-${KEY}`);
      await card.getByRole("button", { name: /^Edit / }).click();
      await page.getByTestId(`editor-${KEY}`).getByRole("button", { name: /^Revert to / }).click();
      await expect(page.getByTestId(`source-${KEY}`)).toHaveText("Shipped default", { timeout: 20_000 });

      await page.getByRole("tab", { name: "Preview final prompt" }).click();
      await page.getByRole("button", { name: "Build preview" }).click();
      const block = page.getByTestId(`block-${KEY}`);
      await expect(block).toBeVisible({ timeout: 30_000 });
      await expect(block).not.toContainText(MARKER);
    });
  });
});
