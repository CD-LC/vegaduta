import { test, expect, type Page } from "@playwright/test";
import { API_URL, requireUserCredentials } from "../helpers/prod-env";
import { signInToConsole } from "../helpers/prod-session";
import { collectProdErrors } from "../helpers/prod-console";
import { authHeaders, getBearerToken, pickAnyAgent } from "../helpers/prod-api";
import {
  analyseSendGates,
  newRunMarker,
  overlappingPairs,
  requireSpendOptIn,
  unexpectedErrors,
  type Box,
} from "../helpers/six-for-everyone";

/**
 * SIGNED-IN smoke for the six "for everyone" surfaces, against production:
 *
 *   Research (/research), Tutor (/learn/tutor), Memory (/memory),
 *   Describe-it-automate-it (/workflows), Attack test
 *   (/agents/quality?tab=simulations) and the Playground header on a phone.
 *
 * Credentials: the SAME mechanism as every other prod spec -
 *   E2E_USER_EMAIL / E2E_USER_PASSWORD (see helpers/prod-env.ts), reusing the
 *   captured session from helpers/prod-auth-state.ts. Without them every test
 *   here SKIPS with that reason.
 *
 * Posture:
 *   - Page loads, the two status probes and the header geometry are read-only.
 *   - The memory test writes ONE clearly-marked fact and deletes it again
 *     (cleanup in `finally`, by that fact's own id - the "forget all" endpoint
 *     is never called). It runs only when the memory page reports self-service
 *     as enabled, and skips cleanly otherwise.
 *   - The Describe-it flow calls a hosted model, so it needs E2E_ALLOW_SPEND=1
 *     in addition. It creates one DRAFT workflow named after a unique run
 *     marker and deletes it in `finally` (only after re-checking the marker).
 *   - Nothing here sends a message, touches payments, or deletes data this run
 *     did not create. Every host derives from E2E_DOMAIN (default vegaduta.ai);
 *     there is no localhost URL anywhere.
 *
 * Chromium project only: one real sign-in's worth of load on production is
 * enough, and the phone-width check sets its own 400px viewport.
 */
test.describe("Six for everyone - signed-in smoke (live)", () => {
  test.beforeEach(async ({ page }, testInfo) => {
    requireUserCredentials(testInfo);
    testInfo.skip(testInfo.project.name !== "chromium", "signed-in smoke runs once, on the desktop project");
    testInfo.setTimeout(150_000);
    await signInToConsole(page);
  });

  // ------------------------------------------------------------------ (a)

  const PAGES: Array<{ path: string; label: string; expect: RegExp }> = [
    { path: "/research", label: "Research", expect: /research/i },
    { path: "/learn/tutor", label: "Tutor", expect: /tutor|study|learn/i },
    { path: "/memory", label: "Memory", expect: /memor/i },
    { path: "/workflows", label: "Workflows", expect: /workflow/i },
    { path: "/agents/quality?tab=simulations", label: "Quality simulations", expect: /quality|simulation/i },
  ];

  for (const p of PAGES) {
    test(`${p.label} (${p.path}) loads signed-in without console or network errors`, async ({ page }) => {
      const errors = collectProdErrors(page);
      await page.goto(p.path, { waitUntil: "domcontentloaded" });
      // The header carries a ticking live counter, so networkidle never truly
      // settles; it is only a best-effort wait before the content polls below.
      await page.waitForLoadState("networkidle", { timeout: 10_000 }).catch(() => {});

      await expect(page.getByText(/Application error: a client-side exception/i)).toHaveCount(0);
      await expect(page.getByText(/This page could not be found/i)).toHaveCount(0);
      await expect
        .poll(async () => p.expect.test(await page.locator("body").innerText()), {
          timeout: 20_000,
          message: `${p.path} did not render content matching ${p.expect}`,
        })
        .toBe(true);

      const body = await page.locator("body").innerText();
      expect(body, `${p.path} fell back to the marketing landing page - session lost?`).not.toMatch(
        /Describe the job\. Ship the agent/i
      );
      expect(body, `${p.path} leaked a raw error or stack trace`).not.toMatch(
        /org\.springframework|java\.lang\.|SQLException|\[object Object\]/
      );

      if (p.path === "/workflows") {
        await expect(
          page.getByTestId("workflow-describe-composer"),
          'the "Describe it, automate it" composer is not visible on /workflows'
        ).toBeVisible({ timeout: 30_000 });
        await expect(page.getByTestId("workflow-describe-input")).toBeVisible();
      }

      if (p.path.startsWith("/agents/quality")) {
        // The Attack test self-hides when core has the scenarios off (or the
        // tenant has no agent to aim it at). Either state is valid; a crash,
        // or a half-rendered card (heading without the launcher, or the
        // reverse), is not.
        await page.waitForTimeout(3_000);
        const launcherVisible = await page.getByTestId("attack-launch").isVisible();
        const headingVisible = (await page.getByText("Attack my agent", { exact: true }).count()) > 0;
        expect(
          launcherVisible,
          `Attack test is half-rendered: launcher visible=${launcherVisible}, heading present=${headingVisible}`
        ).toBe(headingVisible);
        test.info().annotations.push({
          type: "attack-test",
          description: launcherVisible
            ? "Attack test card present (enabled in this core)"
            : "Attack test card absent (core flag off, older core, or no agent) - coherent",
        });
      }

      const unexpected = unexpectedErrors(errors.unexpected());
      expect(unexpected, `unexpected console/network errors on ${p.path}:\n${unexpected.join("\n")}`).toEqual([]);
    });
  }

  // ------------------------------------------------------------------ (b)

  test("GET /api/tutor/status and /api/research/status answer JSON with an enabled field", async ({ page, request }) => {
    const token = await getBearerToken(page);
    for (const path of ["/api/tutor/status", "/api/research/status"]) {
      const res = await request.get(`${API_URL}${path}`, { headers: authHeaders(token), failOnStatusCode: false });
      const status = res.status();

      if (status === 404 || status === 503) {
        // A core without the feature (or with its flag off) answers 404 on the
        // whole tree and the pages degrade to "not available" - a coherent
        // state, recorded rather than failed.
        test.info().annotations.push({
          type: "feature-off",
          description: `${path} answered ${status}: feature not enabled in this core`,
        });
        continue;
      }

      expect(status, `${path} answered ${status} for a signed-in user`).toBe(200);
      expect(res.headers()["content-type"] ?? "", `${path} did not answer JSON`).toMatch(/json/i);
      const json = (await res.json()) as Record<string, unknown>;
      expect(typeof json.enabled, `${path} has no boolean "enabled" field`).toBe("boolean");
      test.info().annotations.push({ type: "feature-state", description: `${path} enabled=${String(json.enabled)}` });
    }
  });

  // ------------------------------------------------------------------ (c)

  test("Playground header at 400px has no overlapping title, agent name or buttons", async ({ page, request }) => {
    const token = await getBearerToken(page);
    const agent = await pickAnyAgent(request, token);
    test.skip(!agent, "this tenant has no agent - the workbench Playground pane needs one to be meaningful");

    await page.setViewportSize({ width: 400, height: 900 });
    await page.goto("/agents", { waitUntil: "domcontentloaded" });
    await page.waitForLoadState("networkidle", { timeout: 10_000 }).catch(() => {});

    const header = page.getByTestId("playground-header");
    await expect(header, "Playground header never rendered on /agents").toBeVisible({ timeout: 30_000 });
    await header.scrollIntoViewIfNeeded();

    const named: Array<{ name: string; box: Box }> = [];
    const add = async (name: string, locator: ReturnType<Page["locator"]>) => {
      if (!(await locator.isVisible().catch(() => false))) return;
      const box = await locator.boundingBox();
      if (box && box.width > 0 && box.height > 0) named.push({ name, box });
    };

    await add("title", page.getByTestId("playground-title"));
    await add("agent-name", page.getByTestId("playground-agent-name"));
    // Direct children of the actions row (cost chip, lab launcher, mute, clear
    // context). Nested descendants are skipped on purpose: a button sits inside
    // its wrapper by design, which is containment, not overlap.
    const actionChildren = page.getByTestId("playground-actions").locator(":scope > *");
    const childCount = await actionChildren.count();
    for (let i = 0; i < childCount; i++) await add(`action-${i}`, actionChildren.nth(i));

    expect(named.length, "found no measurable header elements - the selectors drifted").toBeGreaterThanOrEqual(3);
    const overlaps = overlappingPairs(named);
    expect(overlaps, `Playground header boxes overlap at 400px: ${overlaps.join(", ")}`).toEqual([]);

    // The header's two direct groups (title group, actions row) must not overlap either.
    const groups = header.locator(":scope > *");
    const groupBoxes: Array<{ name: string; box: Box }> = [];
    for (let i = 0; i < (await groups.count()); i++) {
      const box = await groups.nth(i).boundingBox();
      if (box && box.width > 0 && box.height > 0) groupBoxes.push({ name: `group-${i}`, box });
    }
    expect(overlappingPairs(groupBoxes), "Playground header groups overlap at 400px").toEqual([]);

    // Nothing in the header may spill past the right edge of the 400px viewport.
    for (const { name, box } of named) {
      expect.soft(box.x + box.width, `${name} overflows the 400px viewport`).toBeLessThanOrEqual(401);
    }
  });

  // ------------------------------------------------------------------ (d)

  test("Memory: add a fact, see it, delete it (cleanup in finally)", async ({ page, request }, testInfo) => {
    const token = await getBearerToken(page);
    const marker = newRunMarker();
    // The page confirms deletes with window.confirm().
    page.on("dialog", (d) => void d.accept().catch(() => {}));

    let created: { id: string; scope: string } | null = null;
    try {
      await page.goto("/memory", { waitUntil: "domcontentloaded" });
      const form = page.getByTestId("memory-add-fact");
      const selfService = await form
        .waitFor({ state: "visible", timeout: 30_000 })
        .then(() => true)
        .catch(() => false);
      testInfo.skip(
        !selfService,
        "memory self-service is not enabled in this core (no add-a-fact form) - nothing to exercise"
      );

      await form.locator("textarea").first().fill(`${marker} smoke-test fact, safe to delete`);
      const added = page.waitForResponse(
        (r) => r.request().method() === "POST" && new URL(r.url()).pathname === "/api/memory/facts",
        { timeout: 30_000 }
      );
      await form.getByRole("button", { name: /add memory/i }).click();
      const res = await added;
      expect(res.status(), "adding a memory failed").toBeLessThan(300);
      const body = (await res.json()) as { id?: string; scope?: string };
      expect(body.id, "add-fact response carried no id").toBeTruthy();
      created = { id: String(body.id), scope: String(body.scope || "shared") };

      // Narrow the list to this run's row (a busy tenant paginates).
      const search = page.getByTestId("memory-facts-toolbar").locator("input").first();
      if (await search.isVisible().catch(() => false)) await search.fill(marker);

      const row = page.getByTestId("memory-fact-row").filter({ hasText: marker });
      await expect(row, "the new memory did not appear in the list").toHaveCount(1, { timeout: 20_000 });

      await row.getByRole("button", { name: /delete this memory/i }).click();
      await expect(row, "the memory was not removed after delete").toHaveCount(0, { timeout: 20_000 });
      created = null;
    } finally {
      if (created) {
        // Delete by this run's own fact id only - never the forget-all endpoint.
        await request
          .delete(`${API_URL}/api/memory/facts/${encodeURIComponent(created.scope)}/${encodeURIComponent(created.id)}`, {
            headers: authHeaders(token),
            failOnStatusCode: false,
          })
          .catch(() => {});
      }
    }
  });

  // ------------------------------------------------------------------ (e)

  test("Describe-it flow: the generated draft has an approval before any send (needs E2E_ALLOW_SPEND=1)", async ({
    page,
    request,
  }, testInfo) => {
    requireSpendOptIn(testInfo);
    testInfo.setTimeout(240_000);

    const token = await getBearerToken(page);
    const marker = newRunMarker();
    page.on("dialog", (d) => void d.accept().catch(() => {}));
    let workflowId: string | null = null;

    try {
      await page.goto("/workflows", { waitUntil: "domcontentloaded" });
      const composer = page.getByTestId("workflow-describe-composer");
      await expect(composer).toBeVisible({ timeout: 30_000 });

      // The workflow name is the first six words of the sentence, so leading
      // with the marker makes the draft recognisable (and lets cleanup verify it).
      await page
        .getByTestId("workflow-describe-input")
        .fill(`${marker} every Monday morning email a polite payment reminder to each customer with an unpaid invoice`);

      const created = page.waitForResponse(
        (r) => r.request().method() === "POST" && new URL(r.url()).pathname === "/api/workflows",
        { timeout: 60_000 }
      );
      const generated = page.waitForResponse(
        (r) => r.request().method() === "POST" && /^\/api\/workflows\/[^/]+\/generate-graph$/.test(new URL(r.url()).pathname),
        { timeout: 180_000 }
      );
      await composer.getByRole("button", { name: /build draft/i }).click();

      const createdRes = await created;
      if (createdRes.status() === 403) {
        testInfo.skip(true, "workflow cap reached on this tenant's plan (403 on create) - nothing to generate");
      }
      expect(createdRes.status(), "creating the draft workflow failed").toBeLessThan(300);
      workflowId = String(((await createdRes.json()) as { id?: string }).id || "") || null;

      const genRes = await generated;
      if ([402, 403, 429].includes(genRes.status())) {
        testInfo.skip(true, `draft generation refused with ${genRes.status()} (plan or allowance limit) - not a defect`);
      }
      expect(genRes.status(), "draft generation failed").toBe(200);
      const result = (await genRes.json()) as { graph?: { nodes?: unknown[]; edges?: unknown[] } };
      expect(result.graph?.nodes?.length, "generated graph has no nodes").toBeGreaterThan(1);

      const gates = analyseSendGates(result.graph as Parameters<typeof analyseSendGates>[0]);
      expect(
        gates.ungatedSends,
        `send node(s) reachable from the Trigger with no approval before them: ${gates.ungatedSends.join(", ")}`
      ).toEqual([]);
      if (gates.sends.length > 0) {
        expect(gates.approvalNodes, "the draft sends but has no approval node at all").toBeGreaterThan(0);
      } else {
        // The model chose a send-free design for this prompt; the ordering rule
        // then holds vacuously. Recorded so a run of "no sends ever" is visible.
        testInfo.annotations.push({
          type: "note",
          description: "generated draft contained no outbound send node - approval-before-send held vacuously",
        });
      }
    } finally {
      // Leave the canvas first (an unsaved-changes prompt must not block cleanup).
      await page.goto("about:blank").catch(() => {});
      if (workflowId) {
        const headers = authHeaders(token);
        const got = await request
          .get(`${API_URL}/api/workflows/${encodeURIComponent(workflowId)}`, { headers, failOnStatusCode: false })
          .catch(() => null);
        const wf = got && got.ok() ? ((await got.json().catch(() => null)) as { name?: string } | null) : null;
        // Only ever delete the draft this run created.
        if (wf?.name && wf.name.startsWith(marker)) {
          await request
            .delete(`${API_URL}/api/workflows/${encodeURIComponent(workflowId)}`, { headers, failOnStatusCode: false })
            .catch(() => {});
        }
      }
    }
  });
});
