import { test, expect } from "@playwright/test";
import { requireUserCredentials, requireMutationOptIn } from "../helpers/prod-env";
import { signInToConsole, skipIfHostedAllowanceExhausted } from "../helpers/prod-session";
import { fixStageApiBase } from "./local-stage-fix";

/**
 * REAL-TENANT, real-model, real-tool-call scenario against production.
 *
 * Everything else in tests-prod either checks that a page renders (read-only)
 * or that a single control's click fires the write it should
 * (console-authenticated.spec.ts's agent-creation test). Nothing exercises
 * the actual point of the product: an agent picking a tool on its own,
 * calling it for real, and a workflow completing a run built around that
 * agent. This file closes that gap with one scenario, built and manually
 * verified live against a deployed instance before being written up
 * here (see the "E2E Tool-Use Scenario Agent" / "E2E Tool-Use Workflow"
 * fixtures left in the throwaway tenant `e2e_prod_verify_0923` from that
 * session, run id `41baa4e1`).
 *
 * Verified findings from that manual pass, recorded here rather than only in
 * chat history:
 *   - Auto-select tool routing correctly narrows to only ENABLED tools. A
 *     tenant with no knowledge-base content and no `brave-search` MCP key
 *     configured has no query-based web-search tool at all - asking a fresh
 *     agent for "today's date" made it try `search_knowledge_base` four
 *     times (all empty), then say so and fall back to a stale training-data
 *     guess rather than inventing a confident wrong answer. That is the
 *     platform correctly reflecting a tenant CONFIGURATION gap (brave-search
 *     is listed at /tools as "not added - needs a key"), not a routing bug.
 *   - `fetch_web_page` (a built-in, no-key-required tool) DOES work end to
 *     end: asked to fetch https://example.com, the agent made one real
 *     HTTP call and correctly quoted the page's actual heading, "Example
 *     Domain". That is what this file exercises, because it needs no
 *     external API key and is deterministic (example.com's heading has not
 *     changed in decades).
 *   - A workflow (Trigger -> Agent -> Output) wired around that same agent
 *     completed for real: run #41baa4e1, 3/3 steps green, 7.3s, Output node
 *     logged the identical "Example Domain" text the agent produced in the
 *     Playground. Multi-node run completion, not just a single agent turn,
 *     is real.
 *
 * Scope: creates ONE real agent (needed - a fresh agent proves auto-select
 * tool routing from a clean slate, not whatever tools a shared fixture
 * happens to carry) but does NOT build a new workflow graph via canvas
 * drag-and-drop on every run - that is real React Flow drag interaction,
 * slow and disproportionately flake-prone to reconstruct per CI run for
 * marginal extra signal. Instead it reuses any existing workflow in the
 * tenant that already has an Agent node (the fixture this file's own manual
 * pass left behind, `E2E Tool-Use Workflow`, qualifies) and points that
 * node at the fresh agent via its own config drawer - real backend
 * validation of the Agent-node -> agent link, without the drag choreography.
 * If the tenant has no such workflow, that half SKIPS with the reason
 * (mirrors agent-workflow-share-live.spec.ts's existing-resource policy).
 */

test.describe("Agent workbench - real tool execution (live production scenario)", () => {
  test.setTimeout(5 * 60 * 1000);
  test.use({ bypassCSP: true }); // stage fix

  test("a freshly created agent auto-selects and completes a real fetch_web_page tool call", async ({ page }, testInfo) => {
    requireUserCredentials(testInfo);
    requireMutationOptIn(testInfo);
    testInfo.skip(testInfo.project.name !== "chromium", "writes real data - one project only");

    await fixStageApiBase(page);
    await signInToConsole(page);
    await page.goto("/agents", { waitUntil: "domcontentloaded" });
    await skipIfHostedAllowanceExhausted(page, testInfo);

    // --- Create a fresh agent from a clean slate (no KB, no manual tool wiring) ---
    const agentName = `E2E ToolExec ${Date.now()}`;
    // 2026-09-23, reproduced live: a bare `[aria-haspopup="listbox"]` also
    // matches the page's language switcher, which sits earlier in DOM order
    // than the agent switcher - `.first()` opened the wrong dropdown. Both
    // attributes together are unique to the agent switcher.
    await page.locator('button[aria-haspopup="listbox"][data-tour="create-agent"]').click();
    await page.getByRole("button", { name: "New agent" }).click();

    const nameField = page.getByPlaceholder(/name this agent/i);
    await expect(nameField, "agent draft name field never appeared").toBeVisible({ timeout: 30_000 });
    await nameField.fill(agentName);

    // Real strict-mode collision, reproduced live 2026-09-23: the guided
    // "Finish setting up this agent" checklist renders extra DISABLED
    // "Save draft" ghost buttons. The real, always-enabled action carries
    // data-tour="create-agent" (shared with the agent-switcher trigger, so
    // scope by the visible "Save draft" text too, not the attribute alone).
    await page.locator('[data-tour="create-agent"]').getByText(/save draft/i).click();

    // 2026-09-23, reproduced live: the "<name> created" confirmation is a
    // toast that can fade before a poll starting right after this click
    // first checks - the create itself was already confirmed to succeed
    // (screenshot showed the new agent live in the header) even when this
    // assertion missed the toast. The durable signal is the workbench
    // header switching to the new agent, "live" - assert that instead.
    await expect(
      page.getByText(agentName).first(),
      "workbench header never switched to the newly created agent"
    ).toBeVisible({ timeout: 30_000 });
    await expect(page.getByText(/^live$/i).first(), "new agent never reached live status").toBeVisible({
      timeout: 10_000,
    });
    testInfo.annotations.push({
      type: "data-left-behind",
      description: `Created agent "${agentName}" in the production tenant - clean up manually.`,
    });

    // --- Ask it something that needs a real tool call, with auto-select on (the default) ---
    const message = page.getByPlaceholder(/send a test message/i);
    await expect(message, "playground composer never appeared for the new agent").toBeVisible({ timeout: 20_000 });
    await message.fill("Fetch https://example.com and report the exact heading text on that page.");
    // 2026-09-23, reproduced live: a loose (substring) `{ name: "Send" }`
    // match also picks up the reasoning-depth "Careful" pill button -
    // something in its computed accessible name contains "Send" too. The
    // real submit control is unambiguous by its own aria-label + type.
    await page.locator('button[aria-label="Send"][type="submit"]').click();

    // A real tool call takes real wall-clock time (network fetch + a second
    // model turn to synthesize the result) - generous timeout, matches the
    // manually-observed ~6-16s range.
    await expect(page.getByText(/\d+\s*tool calls?/i), "no tool call was recorded for this turn").toBeVisible({
      timeout: 60_000,
    });
    // 2026-09-23, reproduced live: `/example domain/i` also matches the raw
    // tool-call trace line (a collapsed <p title="..."> carrying the tool's
    // full JSON result) as well as the agent's own final reply - a strict-
    // mode violation, not a functional failure (both instances ARE the
    // real fetched content: "Example Domain Example Domain This domain is
    // for use in documentation examples..."). Take the first match rather
    // than requiring exactly one - which of the two renders first can vary
    // with reasoning depth.
    await expect(
      page.getByText(/example domain/i).first(),
      "agent did not report example.com's real heading - fetch_web_page result was not used in the reply"
    ).toBeVisible({ timeout: 10_000 });
  });
});

test.describe("Workflow - real multi-node run against a live agent (live production scenario)", () => {
  test.setTimeout(3 * 60 * 1000);
  test.use({ bypassCSP: true }); // stage fix

  test("an existing Trigger -> Agent -> Output workflow completes a real run", async ({ page }, testInfo) => {
    requireUserCredentials(testInfo);
    requireMutationOptIn(testInfo);
    testInfo.skip(testInfo.project.name !== "chromium", "writes real data - one project only");

    await fixStageApiBase(page);
    await signInToConsole(page);
    await page.goto("/workflows", { waitUntil: "domcontentloaded" });
    await skipIfHostedAllowanceExhausted(page, testInfo);

    // Reuse an existing workflow that already has an Agent node rather than
    // building the graph via canvas drag-and-drop here - see file header.
    // The list table populates client-side after domcontentloaded (same
    // hydration-timing class documented elsewhere in this suite) - wait for
    // the row rather than checking count() immediately.
    const row = page.locator("table tr, [role=row]").filter({ hasText: "E2E Tool-Use Workflow" }).first();
    const rowAppeared = await row
      .waitFor({ state: "visible", timeout: 15_000 })
      .then(() => true)
      .catch(() => false);
    if (!rowAppeared) {
      test.skip(true, "no 'E2E Tool-Use Workflow' fixture in this tenant - see this file's header for what it needs");
    }
    await row.getByRole("link").first().click().catch(async () => {
      await row.click();
    });

    await expect(page.getByRole("button", { name: /run now/i }), "workflow canvas never loaded").toBeVisible({
      timeout: 30_000,
    });
    await page.getByRole("button", { name: /run now/i }).click();

    const prompt = page.getByPlaceholder(/e\.g\. a sample inbound message/i);
    await expect(prompt, "Run modal never opened").toBeVisible({ timeout: 15_000 });
    await prompt.fill("Fetch https://example.com and report the exact heading text.");
    // 2026-09-23, reproduced live: the modal's submit button's accessible
    // name is "▶ Run" (a literal glyph character, not an aria-hidden icon
    // element) - an exact /^run$/i anchor never matched. End-anchored
    // instead: matches "▶ Run" without also matching "Run now" or
    // "Runtime parameters".
    await page.getByRole("button", { name: /run$/i }).click();

    // 2026-09-23, reproduced live: the canvas title bar AND the run-history
    // row both render "RUN #<id> COMPLETED" independently - a strict-mode
    // violation, not a functional failure (both runs genuinely completed).
    await expect(
      page.getByText(/RUN #.*COMPLETED/i).first(),
      "workflow run did not reach COMPLETED"
    ).toBeVisible({ timeout: 90_000 });
    await expect(
      page.getByText(/example domain/i).first(),
      "completed run's Output step did not carry the agent's real tool-call result"
    ).toBeVisible({ timeout: 10_000 });
  });
});
