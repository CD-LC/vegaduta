import { test, expect, type Page } from "@playwright/test";
import { requireUserCredentials, requireMutationOptIn } from "../helpers/prod-env";
import { signInToConsole } from "../helpers/prod-session";
import { collectProdErrors } from "../helpers/prod-console";

/**
 * REAL-TENANT public share surfaces, happy path: /agent/<shareId> (a shared
 * chat AGENT) and /workflow/<shareId> (a shared WORKFLOW run).
 *
 * Coverage gap this file closes: galleries.spec.ts's "Public share surfaces"
 * block only ever hits these two routes with a deliberately-invalid id (fail
 * -closed check). Unlike /a/<shareId> (the anonymous demo sandbox, which has
 * its own dedicated share-viewer.spec.ts + share-abuse.spec.ts happy-path
 * suite), nothing exercises a VALID /agent or /workflow share end to end -
 * mint the link, load it as a stranger, get a real chat reply / workflow run
 * result, and file a report. /agent and /workflow are the share surface for
 * a real registered tenant's own content (higher stakes than the anonymous
 * sandbox /a/ covers), so a regression in AgentShareController's chat path or
 * WorkflowVoicePanel's run path would otherwise ship undetected.
 *
 * Both tests are real writes against production:
 *   - they mint a share link for an EXISTING agent/workflow already in the
 *     signed-in tenant (never create one just to share it - that would pile
 *     a second real-data footprint on top of this one)
 *   - they send one real chat message / run one real workflow input as an
 *     anonymous visitor, which counts against the tenant's normal usage
 *   - they file one real abuse report against their own resource
 *   - if THIS test run was the one that turned sharing on, it turns it back
 *     off afterward; if the resource was already shared before this test
 *     ran, sharing is left exactly as found (this suite must not silently
 *     revoke a link some other part of the product depends on)
 *
 * So both require the full double gate: E2E_USER_EMAIL/E2E_USER_PASSWORD
 * (needs a real signed-in tenant to own the shared resource) AND
 * E2E_ALLOW_MUTATIONS=1. Neither is set in a default run - both tests SKIP,
 * visibly, with the reason.
 *
 * Structural assumption, stated once here rather than re-derived per test:
 * these tests drive the REAL owner-side share UI (ShareAgentPanel.tsx /
 * ShareWorkflowPanel.tsx via WorkflowCanvas.tsx's share modal) rather than
 * hitting the owner-side share endpoints directly, so a broken "Share"
 * button is itself a finding, not just the public viewer. Each requires at
 * least one pre-existing agent/workflow in the account - if the tenant has
 * none, the test SKIPS with that reason (this suite provisions ZERO net-new
 * top-level resources; console-authenticated.spec.ts already covers agent
 * creation as its own write test).
 */

const REPORT_REASON_LABEL = "Something else";
const REPORT_REASON_ID = "other";

/**
 * The owner-side share panel (ShareAgentPanel.tsx and ShareWorkflowPanel.tsx
 * share this exact structure: a status line reading "Shared publicly" or
 * "Not shared", and - as its sibling in the same flex row - a single
 * "Share"/"Stop sharing" button). Scoping the button search to that row (via
 * the nearest ancestor carrying the row's own layout class) avoids colliding
 * with the unrelated "Share as template" icon button that sits elsewhere on
 * both the agent and workflow surfaces.
 */
async function readSharePanel(page: Page) {
  const statusText = page.getByText(/^(Shared publicly|Not shared)$/).first();
  await expect(statusText, "owner-side share panel never rendered a status line").toBeVisible({
    timeout: 30_000,
  });
  const row = statusText.locator("xpath=ancestor::div[contains(@class,'justify-between')][1]");
  const button = row.getByRole("button");
  const activeBefore = ((await statusText.textContent()) || "").trim() === "Shared publicly";
  return { statusText, button, activeBefore };
}

/** Reads the share URL rendered as a font-mono line once the panel is active. */
async function readShareUrl(page: Page, urlSubstring: string): Promise<string> {
  const urlText = page.getByText(new RegExp(`^https?://.*${urlSubstring}/`)).first();
  await expect(urlText, "share panel says 'Shared publicly' but rendered no share URL").toBeVisible({
    timeout: 15_000,
  });
  const url = (await urlText.textContent())?.trim();
  expect(url && url.length > 0, "share URL text was empty").toBeTruthy();
  return url!;
}

test.describe("Agent share surface (/agent/<shareId>) - real tenant happy path", () => {
  test("mint a share link for an existing agent, chat with it anonymously, and report it", async (
    { page, browser },
    testInfo
  ) => {
    requireUserCredentials(testInfo);
    requireMutationOptIn(testInfo);
    testInfo.skip(
      testInfo.project.name !== "chromium",
      "writes real data (a chat message + a report) - one project only"
    );
    testInfo.setTimeout(180_000);

    await signInToConsole(page);
    await page.goto("/agents", { waitUntil: "domcontentloaded" });
    await page.waitForLoadState("networkidle", { timeout: 15_000 }).catch(() => {});

    // AgentWorkbench auto-selects an existing agent (the most recently
    // created one) as soon as the tenant has at least one - no click needed.
    // If the tenant has zero agents, ShareAgentPanel never mounts and the
    // status line below times out; that is this test's "nothing to share"
    // skip condition, not a failure.
    const panel = await readSharePanel(page).catch(() => null);
    testInfo.skip(!panel, "tenant has no existing agent to share - this test provisions none itself");

    let createdShareThisRun = false;
    if (!panel!.activeBefore) {
      // Capture the owner-side share POST so a backend failure is named in
      // the assertion, not hidden behind a missing status line. Observed
      // live 2026-09-25: POST /api/agents/{id}/share returns 500 on
      // share-after-revoke (uq_agent_share_agent_id duplicate - the queued
      // delete is flushed after the insert).
      const sharePost = page.waitForResponse(
        (r) => r.request().method() === "POST" && /\/api\/agents\/[^/]+\/share$/.test(r.url()),
        { timeout: 30_000 }
      ).catch(() => null);
      await panel!.button.click();
      const shareRes = await sharePost;
      await expect(
        page.getByText("Shared publicly", { exact: true }),
        `share activation failed - POST /api/agents/{id}/share answered ${shareRes?.status() ?? "no request observed"}`
      ).toBeVisible({ timeout: 30_000 });
      createdShareThisRun = true;
    }

    const shareUrl = await readShareUrl(page, "/agent");

    // --- Anonymous visitor, fresh browser context: no cookies, no session ---
    const visitorContext = await browser.newContext();
    const visitorPage = await visitorContext.newPage();
    const errors = collectProdErrors(visitorPage);

    try {
      const response = await visitorPage.goto(shareUrl, { waitUntil: "domcontentloaded" });
      expect(response, `no response loading minted share URL ${shareUrl}`).toBeTruthy();
      expect(response!.status(), `share URL ${shareUrl} returned an error status`).toBeLessThan(400);

      await expect(visitorPage.getByText(/Application error: a client-side exception/i)).toHaveCount(0);

      // Provenance banner - present in both the branded and white-labeled
      // variants of ViewerHeader, just with different wording/logo. Assert
      // the header badge (whichever variant rendered) rather than assuming
      // one specific entitlement state.
      const brandedBadge = visitorPage.getByText("Shared agent", { exact: true });
      const whiteLabelBadge = visitorPage.getByText("AI agent", { exact: true });
      await expect(brandedBadge.or(whiteLabelBadge)).toBeVisible({ timeout: 20_000 });
      testInfo.annotations.push({
        type: "white-label-state",
        description: (await whiteLabelBadge.count()) > 0 ? "white-labeled" : "platform-branded",
      });

      // The report valve is always visible, in every variant.
      const reportButton = visitorPage.getByRole("button", { name: "Report this agent" });
      await expect(reportButton).toBeVisible();

      // The composer's aria-label carries the real agent name straight from
      // the public endpoint - resolve it dynamically rather than assuming.
      const composer = visitorPage.locator('textarea[aria-label^="Message "]');
      await expect(composer, "share viewer never rendered a message composer").toBeVisible({ timeout: 20_000 });
      const composerLabel = (await composer.getAttribute("aria-label")) || "";
      const agentName = composerLabel.replace(/^Message /, "");

      const testMessage = `[e2e-prod-share-check ${Date.now()}] Are you open right now?`;
      await composer.fill(testMessage);

      const chatResponse = visitorPage.waitForResponse(
        (r) => /\/api\/share\/agents\/[^/]+\/chat$/.test(r.url()) && r.request().method() === "POST",
        { timeout: 60_000 }
      );
      await visitorPage.getByRole("button", { name: "Send", exact: true }).click();
      await expect(visitorPage.getByText(testMessage)).toBeVisible();

      const chatRes = await chatResponse;
      expect(chatRes.ok(), `POST /api/share/agents/.../chat returned ${chatRes.status()}`).toBe(true);
      const chatBody = await chatRes.json();
      expect(typeof chatBody.reply, "chat response carried no 'reply' string").toBe("string");
      const replySnippet = (chatBody.reply as string)
        .split("\n")
        .map((l: string) => l.trim())
        .filter(Boolean)[0]
        ?.slice(0, 60);
      expect(replySnippet && replySnippet.length > 0, "agent reply was empty").toBeTruthy();
      await expect(visitorPage.getByText(replySnippet!, { exact: false }).first()).toBeVisible({ timeout: 15_000 });

      // --- Abuse report flow ---
      await reportButton.click();
      await expect(visitorPage.getByText("What's wrong with this agent?")).toBeVisible();
      const reasonButton = visitorPage.getByRole("button", { name: REPORT_REASON_LABEL });
      await expect(reasonButton).toBeVisible();

      const reportRequest = visitorPage.waitForRequest(
        (r) => /\/api\/share\/agents\/[^/]+\/report$/.test(r.url()) && r.method() === "POST"
      );
      const reportResponse = visitorPage.waitForResponse(
        (r) => /\/api\/share\/agents\/[^/]+\/report$/.test(r.url()) && r.request().method() === "POST"
      );
      await reasonButton.click();

      const reportReq = await reportRequest;
      expect(reportReq.postDataJSON()).toMatchObject({ reason: REPORT_REASON_ID });
      const reportRes = await reportResponse;
      expect(reportRes.ok(), `POST /api/share/agents/.../report returned ${reportRes.status()}`).toBe(true);

      await expect(
        visitorPage.getByText(/Thanks — your report has been logged and this agent will be reviewed\./)
      ).toBeVisible();

      const unexpected = errors.unexpected();
      expect(unexpected, `/agent/<shareId> produced unexpected errors:\n${unexpected.join("\n")}`).toEqual([]);

      testInfo.annotations.push({
        type: "data-left-behind",
        description: `Sent one real chat message to agent "${agentName}" (owner: E2E_USER_EMAIL's tenant) via its share link, and filed one "${REPORT_REASON_LABEL}" report against it. Both are real rows in production - clean up manually if desired.`,
      });
    } finally {
      await visitorContext.close();
    }

    // Only revoke if THIS run turned sharing on - never touch a link the
    // tenant already had active before this test started.
    if (createdShareThisRun) {
      await panel!.button.click();
      await expect(page.getByText("Not shared", { exact: true })).toBeVisible({ timeout: 20_000 });
    }
  });
});

test.describe("Workflow share surface (/workflow/<shareId>) - real tenant happy path", () => {
  test("mint a share link for an existing workflow, run it anonymously, and report it", async (
    { page, browser },
    testInfo
  ) => {
    requireUserCredentials(testInfo);
    requireMutationOptIn(testInfo);
    testInfo.skip(
      testInfo.project.name !== "chromium",
      "writes real data (a workflow run + a report) - one project only"
    );
    testInfo.setTimeout(180_000);

    await signInToConsole(page);
    await page.goto("/workflows", { waitUntil: "domcontentloaded" });
    await page.waitForLoadState("networkidle", { timeout: 15_000 }).catch(() => {});

    const firstRow = page.locator("table tbody tr").first();
    const hasWorkflow = (await firstRow.count().catch(() => 0)) > 0;
    testInfo.skip(!hasWorkflow, "tenant has no existing workflow to share - this test provisions none itself");
    await firstRow.click();

    // WorkflowCanvas's own toolbar "Share" button opens the share modal -
    // there is no per-workflow sidebar the way the agent playground has, so
    // (unlike the agent flow) the panel is not visible until this click.
    const openShareModal = page.getByRole("button", { name: /^share$/i });
    await expect(openShareModal, "workflow canvas toolbar never rendered a Share button").toBeVisible({
      timeout: 30_000,
    });
    await openShareModal.click();

    await expect(page.getByRole("dialog")).toBeVisible({ timeout: 10_000 });
    const panel = await readSharePanel(page);

    let createdShareThisRun = false;
    if (!panel.activeBefore) {
      // Capture the owner-side share POST so a backend failure is named in
      // the assertion. Observed live 2026-09-25: POST /api/workflows/{id}/share
      // returns 500 on share-after-revoke (uq_workflow_share_workflow_id
      // duplicate - queued delete flushes after the insert); see
      // project changelog.
      const sharePost = page.waitForResponse(
        (r) => r.request().method() === "POST" && /\/api\/workflows\/[^/]+\/share$/.test(r.url()),
        { timeout: 30_000 }
      ).catch(() => null);
      await panel.button.click();
      const shareRes = await sharePost;
      await expect(
        page.getByText("Shared publicly", { exact: true }),
        `share activation failed - POST /api/workflows/{id}/share answered ${shareRes?.status() ?? "no request observed"}`
      ).toBeVisible({ timeout: 30_000 });
      createdShareThisRun = true;
    }

    const shareUrl = await readShareUrl(page, "/workflow");

    // --- Anonymous visitor, fresh browser context: no cookies, no session ---
    const visitorContext = await browser.newContext();
    const visitorPage = await visitorContext.newPage();
    const errors = collectProdErrors(visitorPage);

    try {
      const response = await visitorPage.goto(shareUrl, { waitUntil: "domcontentloaded" });
      expect(response, `no response loading minted share URL ${shareUrl}`).toBeTruthy();
      expect(response!.status(), `share URL ${shareUrl} returned an error status`).toBeLessThan(400);

      await expect(visitorPage.getByText(/Application error: a client-side exception/i)).toHaveCount(0);

      const brandedBadge = visitorPage.getByText("Shared workflow", { exact: true });
      const whiteLabelBadge = visitorPage.getByText("Workflow", { exact: true });
      await expect(brandedBadge.or(whiteLabelBadge)).toBeVisible({ timeout: 20_000 });
      testInfo.annotations.push({
        type: "white-label-state",
        description: (await brandedBadge.count()) > 0 ? "platform-branded" : "white-labeled",
      });

      const reportButton = visitorPage.getByRole("button", { name: "Report this workflow" });
      await expect(reportButton).toBeVisible();

      const inputBox = visitorPage.locator('input[aria-label^="Input for "]');
      await expect(inputBox, "share viewer never rendered a run input").toBeVisible({ timeout: 20_000 });
      const inputLabel = (await inputBox.getAttribute("aria-label")) || "";
      const workflowName = inputLabel.replace(/^Input for /, "").replace(/…$/, "");

      const testInput = `[e2e-prod-share-check ${Date.now()}] test input`;
      await inputBox.fill(testInput);

      const runResponse = visitorPage.waitForResponse(
        (r) => /\/api\/share\/workflows\/[^/]+\/run$/.test(r.url()) && r.request().method() === "POST",
        { timeout: 170_000 }
      );
      await visitorPage.getByRole("button", { name: "Run", exact: true }).click();

      const runStarted = Date.now();
      const runRes = await runResponse;
      testInfo.annotations.push({ type: "share-run-latency-ms", description: String(Date.now() - runStarted) });
      expect(runRes.ok(), `POST /api/share/workflows/.../run returned ${runRes.status()}`).toBe(true);
      const runBody = await runRes.json();
      expect(typeof runBody.resultText, "workflow run response carried no 'resultText' string").toBe("string");
      expect((runBody.resultText as string).length > 0, "workflow run result was empty").toBeTruthy();

      // The result renders as the most recent bubble pair (input echo + result).
      await expect(visitorPage.getByText(testInput)).toBeVisible({ timeout: 15_000 });

      // --- Abuse report flow ---
      await reportButton.click();
      await expect(visitorPage.getByText("What's wrong with this workflow?")).toBeVisible();
      const reasonButton = visitorPage.getByRole("button", { name: REPORT_REASON_LABEL });
      await expect(reasonButton).toBeVisible();

      const reportRequest = visitorPage.waitForRequest(
        (r) => /\/api\/share\/workflows\/[^/]+\/report$/.test(r.url()) && r.method() === "POST"
      );
      const reportResponse = visitorPage.waitForResponse(
        (r) => /\/api\/share\/workflows\/[^/]+\/report$/.test(r.url()) && r.request().method() === "POST"
      );
      await reasonButton.click();

      const reportReq = await reportRequest;
      expect(reportReq.postDataJSON()).toMatchObject({ reason: REPORT_REASON_ID });
      const reportRes = await reportResponse;
      expect(reportRes.ok(), `POST /api/share/workflows/.../report returned ${reportRes.status()}`).toBe(true);

      await expect(
        visitorPage.getByText(/Thanks — your report has been logged and this workflow will be reviewed\./)
      ).toBeVisible();

      const unexpected = errors.unexpected();
      expect(unexpected, `/workflow/<shareId> produced unexpected errors:\n${unexpected.join("\n")}`).toEqual([]);

      testInfo.annotations.push({
        type: "data-left-behind",
        description: `Ran workflow "${workflowName}" (owner: E2E_USER_EMAIL's tenant) once via its share link, and filed one "${REPORT_REASON_LABEL}" report against it. Both are real rows in production - clean up manually if desired.`,
      });
    } finally {
      await visitorContext.close();
    }

    // Only revoke if THIS run turned sharing on - never touch a link the
    // tenant already had active before this test started.
    if (createdShareThisRun) {
      await panel.button.click();
      await expect(page.getByText("Not shared", { exact: true })).toBeVisible({ timeout: 20_000 });
    }
  });
});
