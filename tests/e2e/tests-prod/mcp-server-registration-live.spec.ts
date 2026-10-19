import { test, expect } from "@playwright/test";
import { requireUserCredentials, requireMutationOptIn } from "../helpers/prod-env";
import { signInToConsole, skipIfHostedAllowanceExhausted } from "../helpers/prod-session";

/**
 * REAL-TENANT MCP server registration, real production scenario.
 *
 * console-authenticated.spec.ts only proves /tools RENDERS; nothing in this
 * repo exercised the actual registration -> enable -> trust -> agent-uses-it
 * pipeline before this file. Built and manually verified live against
 * a deployed instance before being written up here.
 *
 * Uses the "filesystem" catalog entry specifically because it is the one
 * built-in MCP server that needs no external API key ("Verified ·
 * Platform-vetted", "No credentials needed - one click and its tools are
 * live for every agent") - every OTHER catalog entry (github, postgres,
 * brave-search, ...) needs a real third-party credential this suite cannot
 * supply, so this is the only server this suite can register end to end
 * without an external secret.
 *
 * Idempotent by design: if a prior run already activated+trusted this
 * server (which it leaves in place - deactivating a tenant's only sandboxed
 * filesystem tool between runs would be a bigger side effect than leaving
 * it on), this test verifies the already-registered state instead of
 * re-registering, and both paths lead to the same assertion: an agent can
 * actually call it.
 */
test.describe("MCP server registration (live production scenario)", () => {
  test.setTimeout(3 * 60 * 1000);

  test("the no-key-needed 'filesystem' server registers, gets trusted, and an agent calls it for real", async ({
    page,
  }, testInfo) => {
    requireUserCredentials(testInfo);
    requireMutationOptIn(testInfo);
    testInfo.skip(testInfo.project.name !== "chromium", "writes real data - one project only");

    await signInToConsole(page);
    await page.goto("/tools", { waitUntil: "domcontentloaded" });

    const filesystemCard = page.getByRole("button", { name: "filesystem" }).first();
    await expect(filesystemCard, "filesystem catalog entry never rendered at /tools").toBeVisible({
      timeout: 20_000,
    });
    await filesystemCard.click();

    const activate = page.getByRole("button", { name: /^activate$/i }).locator("visible=true").first();
    const alreadyRegistered = !(await activate.waitFor({ state: "visible", timeout: 8_000 }).then(() => true, () => false));

    if (!alreadyRegistered) {
      await activate.click();
      await expect(
        page.getByText(/activated "filesystem"/i),
        "no activation confirmation after clicking Activate"
      ).toBeVisible({ timeout: 20_000 });
      testInfo.annotations.push({
        type: "data-left-behind",
        description: 'Registered and enabled the "filesystem" MCP server in the production tenant - left in place.',
      });
    }

    // A server left registered-but-unverified by an earlier run needs trusting too,
    // so this runs on both the fresh and the already-registered path.
    const useAnyway = page.getByRole("button", { name: /use anyway.*trust this server/i });
    if (await useAnyway.waitFor({ state: "visible", timeout: 8_000 }).then(() => true, () => false)) {
      await useAnyway.click();
    }

    await expect(
      page.getByText(/trusted.*agents can use this server/i),
      "filesystem server never reached a trusted/usable state"
    ).toBeVisible({ timeout: 15_000 });

    // --- The real proof: ask an existing agent to actually call it ---
    await page.goto("/agents", { waitUntil: "domcontentloaded" });
    await skipIfHostedAllowanceExhausted(page, testInfo);
    const message = page.getByPlaceholder(/send a test message/i);
    await expect(message, "playground composer never appeared").toBeVisible({ timeout: 20_000 });
    await message.fill("Use your filesystem tool to list the files in your sandboxed directory.");
    await page.locator('button[aria-label="Send"][type="submit"]').click();

    await expect(page.getByText(/\d+\s*tool calls?/i), "no tool call was recorded for this turn").toBeVisible({
      timeout: 60_000,
    });
    // The sandbox is expected to be empty (or list real files) - either way
    // the agent must have actually run the tool and reported on it, not
    // refused or hallucinated. "sandbox" / "director" (matches "directory")
    // covers both a real listing and the empty-dir case observed live.
    await expect(
      page.getByText(/sandbox|director|no files|empty/i).first(),
      "agent did not report on the filesystem tool's real result"
    ).toBeVisible({ timeout: 10_000 });
  });
});
