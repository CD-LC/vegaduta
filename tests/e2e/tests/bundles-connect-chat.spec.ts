import { test, expect } from "@playwright/test";
import { readFileSync } from "node:fs";
import { signUpFreshTenant } from "../helpers/test-tenant";

// Import -> connect -> chat, on ONE freshly signed-up tenant, through the real console and core.
//
//   1. /agents/bundles: preview a ready-made agent (nothing is created), then import it.
//   2. The agent shows up in /agents and exports back to a valid agent file.
//   3. /connect lists every channel and deep-links into the right inspector.
//   4. The imported agent answers a message in the playground.
//
// Chat needs a model behind the platform pool. In CI that is deploy/mock-provider (set
// E2E_MOCK_PROVIDER=1 to assert its exact echo); anywhere else any reply that is not a failure
// counts, so the same spec runs on a local-model install.
//
// Real side effects: it provisions a tenant on whatever E2E_BASE_URL points at.

const MOCK = process.env.E2E_MOCK_PROVIDER === "1";

test.describe("Agent files, Connect and chat", () => {
  test("import a ready-made agent, export it, connect a channel, chat with it", async ({ page }, testInfo) => {
    testInfo.skip(testInfo.project.name !== "chromium", "provisions a real tenant - run on one project only");
    test.setTimeout(15 * 60 * 1000);

    await signUpFreshTenant(page, "bundles");

    let agentId = "";

    await test.step("preview creates nothing, import creates the agent", async () => {
      await page.goto("/agents/bundles");
      await expect(page.getByRole("heading", { name: "Agent files" })).toBeVisible();

      const card = page.getByTestId("ready-personal-assistant");
      await card.getByRole("button", { name: "Preview" }).click();
      const preview = page.getByTestId("bundle-preview");
      await expect(preview).toBeVisible();
      // The tools it may use, and the autonomy it will really get, are on screen before anything exists.
      await expect(preview.getByText("fetch_web_page")).toBeVisible();
      await expect(preview.getByText(/Autonomy: SUPERVISED/)).toBeVisible();

      await page.goto("/agents");
      await expect(page.getByText("Personal Assistant")).toHaveCount(0);

      await page.goto("/agents/bundles");
      await page.getByTestId("ready-personal-assistant").getByRole("button", { name: "Preview" }).click();
      await page.getByLabel("Agent name").fill("E2E Personal Assistant");
      await page.getByRole("button", { name: "Import agent" }).click();

      await page.waitForURL(/\/agents\?.*agentId=/, { timeout: 30_000 });
      agentId = new URL(page.url()).searchParams.get("agentId") || "";
      expect(agentId).not.toBe("");
      await expect(page.getByText("E2E Personal Assistant").first()).toBeVisible({ timeout: 15_000 });
    });

    await test.step("a pasted file that is not an agent file is refused without creating anything", async () => {
      await page.goto("/agents/bundles");
      await page.getByPlaceholder("Paste the agent file here").fill("{not json");
      await page.getByRole("button", { name: "Preview" }).last().click();
      await expect(page.getByTestId("bundle-error")).toContainText("not valid JSON");
      await expect(page.getByTestId("bundle-preview")).toHaveCount(0);
    });

    await test.step("export gives back a valid agent file with no secrets or knowledge-base ids", async () => {
      await page.goto("/agents");
      const download = page.waitForEvent("download");
      await page.getByRole("button", { name: /Export E2E Personal Assistant as a file/ }).click();
      const file = await download;
      expect(file.suggestedFilename()).toBe("e2e-personal-assistant.agent.json");
      const bundle = JSON.parse(readFileSync((await file.path())!, "utf8"));
      expect(bundle.format).toBe("vegaduta.agent-bundle/1");
      expect(bundle.agent.systemPrompt).toContain("personal assistant");
      expect(bundle.agent.allowedToolNames).toContain("fetch_web_page");
      expect(bundle.agent.allowedKnowledgeCollectionIds).toEqual([]);
      expect(JSON.stringify(bundle)).not.toMatch(/sk-[A-Za-z0-9]{16,}|AKIA[0-9A-Z]{16}/);
    });

    await test.step("Connect lists every way in and opens the right inspector", async () => {
      await page.goto("/connect");
      await expect(page.getByRole("heading", { name: "Connect your world" })).toBeVisible();
      for (const id of ["telegram", "whatsapp", "email", "google", "slack", "sms", "knowledge"]) {
        await expect(page.getByTestId(`connect-${id}`)).toBeVisible();
      }
      // A fresh tenant has nothing connected yet.
      await expect(page.getByText("Connected", { exact: true })).toHaveCount(0);

      await page.getByTestId("connect-telegram").getByRole("link", { name: "Connect Telegram" }).click();
      await page.waitForURL(/\/admin\/channels\?channel=telegram/);
      await expect(page.getByText("Connect Telegram").first()).toBeVisible();
      await expect(page.getByLabel("Bot Token")).toBeVisible();
    });

    await test.step("the imported agent answers a message", async () => {
      await page.goto(`/playground?agent=${encodeURIComponent(agentId)}`);
      const message = `ping ${Date.now()}`;
      const box = page.getByPlaceholder("Ask something…");
      await expect(box).toBeVisible({ timeout: 20_000 });
      await box.fill(message);
      await box.press("Enter");

      const log = page.getByRole("log").first();
      if (MOCK) {
        await expect(log).toContainText(`mock-provider received: ${message}`, { timeout: 60_000 });
      } else {
        // The user's bubble holds the message; a reply adds more text and no failure notice.
        await expect(log).toContainText(message, { timeout: 15_000 });
        await expect
          .poll(async () => ((await log.innerText()) || "").replace(message, "").trim().length, { timeout: 180_000 })
          .toBeGreaterThan(20);
      }
      await expect(page.getByTestId("failed-reply")).toHaveCount(0);
    });
  });
});
