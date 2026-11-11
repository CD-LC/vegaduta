import { test, expect } from "@playwright/test";
import { requireUserCredentials, requireMutationOptIn } from "../helpers/prod-env";
import { signInToConsole, skipIfHostedAllowanceExhausted, expandReasoningAutonomySection } from "../helpers/prod-session";
import { fixStageApiBase, LLM_IS_MOCK } from "./local-stage-fix";

/**
 * REAL-TENANT platform-hosted-provider RESPONSE validation across Reasoning
 * depths, using ONLY the platform-hosted LLM pool (Platform LLM (managed) -
 * currently Azure OpenAI gpt-4.1-mini). Deliberately does not touch BYOK:
 * every BYOK provider needs a real third-party API key this suite has no
 * access to, so this file proves out what IS testable without one.
 *
 * WHAT THIS ASSERTS (stable, verified live repeatedly): switching Reasoning
 * depth between Fast and Rigorous persists (the "Reasoning depth updated"
 * confirmation), and a real prompt under EACH depth against the real
 * platform-hosted model returns a real, on-topic reply. That is a genuine
 * regression guard for the platform-provider response path across the two
 * ends of the control - a broken Rigorous pipeline (multi-step verification
 * that errors out, returns empty, or hangs) would fail here.
 *
 * WHAT THIS DELIBERATELY DOES NOT ASSERT, and why - recorded so nobody
 * burns the same hours re-discovering it. The product's own copy claims
 * "Modes deeper than Fast ... disable live streaming - the reply arrives
 * once checking finishes." Proving THAT via DOM polling was attempted four
 * ways in this session against the live platform, and none produced a
 * signal stable enough to ship as a pass/fail assertion:
 *   1. `getByText(/streaming/i)` - the Reasoning-depth section's own
 *      permanent static help paragraph contains the word "streaming", so it
 *      matched instantly under every depth and proved nothing.
 *   2. The turn-scoped "Cancel" button - reproduced live TWICE that it
 *      appears under Rigorous too. It means "a request is outstanding",
 *      true of buffered and streamed requests alike.
 *   3. Counting discrete growth steps in whole-page text length - a
 *      genuinely streaming Fast turn measured 4 steps once and 2 steps
 *      another time (real network chunk coalescing); a step-count
 *      threshold flaked on identical behavior.
 *   4. Measuring the SPAN of time content growth is spread across, scoped
 *      to just the Playground card (to exclude the global header's live
 *      ticking runtime counter): a single manual run showed Rigorous
 *      landing in a 616ms burst vs Fast spread over ~3.1s, but every
 *      automated run measured Rigorous at 5-8 SECONDS - most likely
 *      because Rigorous's multi-step verification updates its run-trace
 *      panel live (intermediate step text appearing over time) even
 *      though the FINAL reply text lands whole, and DOM text-length
 *      cannot tell those two kinds of growth apart.
 * Proving the streaming-vs-atomic claim properly needs instrumentation at
 * the network layer (intercepting the actual chat response and checking
 * whether it's chunked SSE vs a single body) rather than polling rendered
 * DOM text - a job for a follow-up, not a reason to ship a flaky check.
 * Nobody has confirmed the claim is FALSE; only that this suite cannot
 * currently prove it either way.
 *
 * Resets Reasoning depth back to Fast (this fixture's value before this
 * test touched it) regardless of outcome.
 */
test.describe("Agent reasoning depth - real platform-provider responses across depths (live production scenario)", () => {
  test.setTimeout(4 * 60 * 1000);
  test.use({ bypassCSP: true }); // stage fix: console calls api.<domain>; its CSP only allows localhost:8280

  test("Fast and Rigorous both return a real, on-topic reply from the platform-hosted model", async ({
    page,
  }, testInfo) => {
    requireUserCredentials(testInfo);
    requireMutationOptIn(testInfo);
    testInfo.skip(testInfo.project.name !== "chromium", "writes real data - one project only");

    await fixStageApiBase(page);
    await signInToConsole(page);
    await page.goto("/agents", { waitUntil: "domcontentloaded" });
    await skipIfHostedAllowanceExhausted(page, testInfo);

    // Confirm this agent is actually on the platform-hosted pool, not BYOK -
    // the behavior this test covers only applies there. The badge lives in
    // the agent header once a saved agent is auto-selected.
    await expect(
      page.getByText(/platform llm \(managed\)/i).first(),
      "test agent is not on the platform-hosted LLM pool"
    ).toBeVisible({ timeout: 30_000 });

    // The depth pills live inside the collapsed "Reasoning & autonomy"
    // accordion (QA-05) - expand it or the testids never mount.
    await expandReasoningAutonomySection(page);

    const message = page.getByPlaceholder(/send a test message/i);
    const send = page.locator('button[aria-label="Send"][type="submit"]');

    try {
      // --- Fast ---
      // Clicking a pill that's ALREADY the active depth is a no-op (no
      // PATCH, no toast) - this agent starts on Fast, so best-effort only.
      await page.getByTestId("reasoning-depth-fast").click();
      await page
        .getByText(/reasoning depth updated/i)
        .waitFor({ state: "visible", timeout: 5_000 })
        .catch(() => {});

      await expect(message).toBeVisible({ timeout: 20_000 });
      await message.fill("In one sentence, what is special about octopuses?");
      await send.click();
      // The fixed mock LLM ("ok ok ...") never echoes the topic; completion is asserted below.
      if (!LLM_IS_MOCK) {
        await expect(
          page.getByText(/octopus/i).nth(1),
          "Fast reasoning depth never returned a reply about the actual prompt"
        ).toBeVisible({ timeout: 60_000 });
      }
      await expect(page.getByText(/\d+ms/).first(), "Fast turn never reported completion").toBeVisible({
        timeout: 30_000,
      });

      // --- Rigorous ---
      await page.getByTestId("reasoning-depth-rigorous").click();
      await expect(page.getByText(/reasoning depth updated/i)).toBeVisible({ timeout: 15_000 });

      await message.fill("In one sentence, what is special about narwhals?");
      await send.click();
      // Multi-step verification takes materially longer - generous budget.
      if (LLM_IS_MOCK) {
        await expect(page.getByText(/\d+ms/).nth(1), "Rigorous turn never reported completion").toBeVisible({
          timeout: 120_000,
        });
      } else {
        await expect(
          page.getByText(/narwhal/i).nth(1),
          "Rigorous reasoning depth never returned a reply about the actual prompt"
        ).toBeVisible({ timeout: 120_000 });
      }
    } finally {
      await page.getByTestId("reasoning-depth-fast").click().catch(() => {});
    }
  });
});
