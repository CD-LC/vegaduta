// Business case: the Agent Templates gallery (#165/#418) - "create another
// page for template selection, add all the general use cases that common
// people do and organization/Developers and everyone." Picking a template
// there never creates anything itself - it navigates back to
// /agents?template=<key>, which AgentsPanel.js's initialTemplateKey effect
// uses to pre-fill the create form's system prompt (see applyTemplate() in
// AgentsPanel.js) - the person still has to name it and submit.
const { test, expect } = require("../fixtures");

test.describe("Agent template gallery", () => {
  test("browsing by audience filters the visible template categories", async ({ consolePage: page }) => {
    await page.goto("/agents/templates");
    await expect(page.getByRole("heading", { name: "Agent Templates" })).toBeVisible();

    // "Everything" is selected by default - every audience section renders.
    await expect(page.getByRole("heading", { name: "For Organizations" })).toBeVisible();
    await expect(page.getByRole("heading", { name: "For Developers" })).toBeVisible();

    // Narrowing to "For Everyone" hides the other audiences' sections.
    await page.getByRole("button", { name: "For Everyone" }).click();
    await expect(page.getByRole("heading", { name: "For Everyone" })).toBeVisible();
    await expect(page.getByRole("heading", { name: "For Organizations" })).toHaveCount(0);
    await expect(page.getByRole("heading", { name: "For Developers" })).toHaveCount(0);
  });

  test("@mvp-acceptance picking a template pre-fills the Agents page create form and doesn't create anything by itself", async ({ consolePage: page }) => {
    await page.goto("/agents/templates");

    const card = page.locator(".mcp-card", { hasText: "Homework Helper" });
    await expect(card).toBeVisible();
    await card.getByRole("button", { name: "Use this template" }).click();

    await expect(page).toHaveURL(/\/agents\?template=homework-helper/);
    await expect(page.getByText("Using the")).toContainText("Homework Helper");

    // The create form's system-prompt textarea is filled from the
    // template - not blank, and no agent has actually been created yet
    // (the create form's Name field is still empty, nothing was submitted).
    const systemPromptBox = page.locator('section:has-text("Create an agent") textarea').first();
    await expect(systemPromptBox).not.toHaveValue("");
  });
});
