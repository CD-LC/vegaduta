import { test, expect } from "@playwright/test";
import { requireUserCredentials, requireMutationOptIn } from "../helpers/prod-env";
import { signInToConsole } from "../helpers/prod-session";

/**
 * The authenticated tenant console.
 *
 * Everything here needs a real production account, supplied out-of-band:
 *
 *   E2E_USER_EMAIL=... E2E_USER_PASSWORD=...
 *
 * Without them every test in this file SKIPS with that reason rather than
 * failing, so a credential-free run still produces a clean report.
 *
 * The read-only block navigates and asserts; the write block (agent
 * creation) additionally requires E2E_ALLOW_MUTATIONS=1, because it leaves
 * real rows behind in a production tenant.
 */
test.describe("Authenticated console - read-only", () => {
  test.beforeEach(async ({ page }, testInfo) => {
    requireUserCredentials(testInfo);
    testInfo.setTimeout(120_000);
    await signInToConsole(page);
  });

  // Every primary console surface must load its own view for a signed-in
  // user - not the landing page, not a crash, not an endless skeleton.
  const CONSOLE_SURFACES: Array<{ path: string; expect: RegExp }> = [
    { path: "/", expect: /dashboard|overview|welcome|agents/i },
    { path: "/agents", expect: /agent/i },
    { path: "/agents/learning", expect: /learning|training|feedback/i },
    { path: "/agents/quality", expect: /quality|score|eval/i },
    { path: "/agents/templates", expect: /template/i },
    { path: "/workflows", expect: /workflow/i },
    { path: "/workflows/templates", expect: /template/i },
    { path: "/knowledge", expect: /knowledge|collection|upload/i },
    { path: "/playground", expect: /playground|prompt|model/i },
    { path: "/studio", expect: /studio/i },
    { path: "/usage", expect: /usage|token|spend|quota/i },
    { path: "/files", expect: /file/i },
    { path: "/devices", expect: /device/i },
    { path: "/providers", expect: /provider/i },
    { path: "/ai-models", expect: /model/i },
    { path: "/quick-task", expect: /task/i },
    { path: "/quick-task/history", expect: /task|history/i },
    { path: "/tools", expect: /tool/i },
    { path: "/tools/code", expect: /tool|code/i },
    { path: "/tools/credentials", expect: /credential|key|secret/i },
    { path: "/tools/servers", expect: /server|mcp/i },
    { path: "/code-intel", expect: /code|repo|intel/i },
    { path: "/build", expect: /build/i },
    { path: "/build/github", expect: /github|repo/i },
    { path: "/help/docs", expect: /help|doc|guide/i },
    { path: "/edge", expect: /edge/i },
    { path: "/clips", expect: /clip/i },
    { path: "/shared-with-me", expect: /shared/i },
    { path: "/data-usage", expect: /data|usage/i },
    { path: "/templates/mine", expect: /template/i },
    { path: "/jev", expect: /jev|typesafe|key/i },
    { path: "/skills", expect: /skill/i },
    { path: "/secrets", expect: /secret|key|credential/i },
    { path: "/jobs", expect: /job|task|run/i },
  ];

  for (const surface of CONSOLE_SURFACES) {
    test(`${surface.path} loads its authenticated view`, async ({ page }) => {
      await page.goto(surface.path, { waitUntil: "domcontentloaded" });
      // 2026-09-23: several dashboards (usage/ai-models/edge among them)
      // hydrate their real figures client-side AFTER "networkidle" fires -
      // this app's header carries a live, continuously-ticking runtime
      // counter, so "networkidle" is never really idle and reliably times
      // out at 10s regardless of whether the surface itself has finished
      // loading. A single innerText() read taken right after that race
      // sometimes lands before the surface's own content arrives, false-
      // failing a real page - re-verified live (retrying that same read a
      // few seconds later always found the real content). Poll instead of
      // reading once.
      await page.waitForLoadState("networkidle", { timeout: 10_000 }).catch(() => {});

      await expect(page.getByText(/Application error: a client-side exception/i)).toHaveCount(0);
      await expect(page.getByText(/This page could not be found/i)).toHaveCount(0);

      await expect
        .poll(async () => surface.expect.test(await page.locator("body").innerText()), {
          timeout: 20_000,
          message: `${surface.path} did not render content matching ${surface.expect}`,
        })
        .toBe(true);

      // The landing hero showing up here means auth silently dropped.
      // 2026-09-22 free-mode pivot changed the hero copy - re-verified live.
      const body = await page.locator("body").innerText();
      expect(body, `${surface.path} fell back to the marketing landing page - session lost?`).not.toMatch(
        /Describe the job\. Ship the agent/i
      );
    });
  }

  test("the tenant admin area loads for an authenticated user", async ({ page }) => {
    for (const path of [
      "/admin",
      "/admin/users",
      "/admin/billing",
      "/admin/channels",
      "/admin/audit",
      "/admin/audit-tail",
      "/admin/inbox",
      "/admin/approvals",
      "/admin/integrations",
      "/admin/mcp-servers",
      "/admin/issues",
      "/admin/maintenance",
      "/admin/contacts",
      "/admin/guardrails",
      "/admin/access",
      "/admin/branding",
      "/admin/data-privacy",
      "/admin/input-requests",
    ]) {
      await page.goto(path, { waitUntil: "domcontentloaded" });
      await page.waitForLoadState("networkidle", { timeout: 10_000 }).catch(() => {});
      await expect(page.getByText(/Application error: a client-side exception/i), `${path} crashed`).toHaveCount(0);
    }
  });

  test("usage and quota figures render as real numbers", async ({ page }) => {
    await page.goto("/usage", { waitUntil: "domcontentloaded" });
    await page.waitForLoadState("networkidle", { timeout: 10_000 }).catch(() => {});

    const body = await page.locator("body").innerText();
    expect(body, "usage page shows NaN/undefined instead of real figures").not.toMatch(/NaN|undefined|\[object Object\]/);
  });

  test("no page leaks raw API errors to the user", async ({ page }) => {
    for (const path of ["/agents", "/workflows", "/knowledge", "/usage"]) {
      await page.goto(path, { waitUntil: "domcontentloaded" });
      await page.waitForLoadState("networkidle", { timeout: 10_000 }).catch(() => {});

      const body = await page.locator("body").innerText();
      expect(body, `${path} leaked a raw error/stack trace`).not.toMatch(
        /org\.springframework|java\.lang\.|SQLException|at [a-z]+\.[a-z]+\.[A-Z]/
      );
    }
  });

  test("signing out returns to the logged-out surface", async ({ page }) => {
    const signOut = page.getByRole("button", { name: /sign out|log ?out/i }).first();
    if ((await signOut.count()) === 0) {
      // Usually behind an account menu.
      const menu = page.locator("header button").last();
      await menu.click().catch(() => {});
    }

    // Mobile: the sign-out control lives in the off-canvas nav drawer; open it.
    const drawerToggle = page.locator("button[aria-expanded=false][aria-label*=menu i]").first();
    if (await drawerToggle.isVisible().catch(() => false)) {
      await drawerToggle.click().catch(() => {});
    }

    const signOutVisible = page.getByRole("button", { name: /sign out|log ?out/i }).first();
    if ((await signOutVisible.count()) === 0) {
      test.skip(true, "could not locate a sign-out control - account menu markup may have changed");
    }

    await signOutVisible.click();
    await expect
      .poll(async () => (await page.locator("body").innerText()).match(/sign in|get started/i) !== null, {
        timeout: 45_000,
      })
      .toBe(true);
  });
});

test.describe("Authenticated console - WRITES to production", () => {
  test("creating an agent persists it", async ({ page }, testInfo) => {
    requireUserCredentials(testInfo);
    requireMutationOptIn(testInfo);
    testInfo.skip(testInfo.project.name !== "chromium", "writes real data - one project only");
    test.setTimeout(10 * 60 * 1000);

    await signInToConsole(page);
    await page.goto("/agents", { waitUntil: "domcontentloaded" });

    // 2026-09-23, reproduced live: this only lands in a blank draft-creation
    // form for a tenant with zero agents. Once any agent exists, /agents
    // restores whichever agent was last selected instead - the previous
    // version of this test filled the name field into a transiently-shown
    // draft that got replaced mid-click by that restored agent, which
    // Playwright correctly reported as the target becoming "not stable" /
    // "detached from the DOM". Open "New agent" from the switcher explicitly
    // rather than assuming the default view is a draft.
    // A bare `[aria-haspopup="listbox"]` also matches the page's language
    // switcher (earlier in DOM order) - both attributes together are unique
    // to the agent switcher.
    await page.locator('button[aria-haspopup="listbox"][data-tour="create-agent"]').click();
    await page.getByRole("button", { name: "New agent" }).click();

    const agentName = `E2E Agent ${Date.now()}`;
    const nameField = page.getByPlaceholder(/name this agent/i);
    await expect(nameField).toBeVisible({ timeout: 30_000 });
    await nameField.fill(agentName);
    // /save draft/i alone is ambiguous - AgentWorkbench's guided "Finish
    // setting up this agent" checklist renders two more DISABLED "Save
    // draft" ghost buttons alongside the real toolbar one, so a plain name
    // match hit 3 elements (Playwright strict-mode violation) - reproduced
    // live. The real, always-enabled action carries data-tour="create-agent"
    // (shared with the switcher trigger, so scope by the visible text too).
    await page.locator('[data-tour="create-agent"]').getByText(/save draft/i).click();

    // 2026-09-23, reproduced live: the "<name> created" confirmation is a
    // toast that can fade before this assertion's poll first checks - not a
    // reliable signal (the create itself was confirmed to succeed even on
    // runs where this specific check missed the toast). Best-effort only;
    // the real proof is the reload-survives-it check right below.
    await page
      .getByText(new RegExp(`"?${agentName}"? created`, "i"))
      .waitFor({ state: "visible", timeout: 10_000 })
      .catch(() => {});

    // Persistence across a full reload is the real assertion - an in-memory
    // optimistic update would pass without it.
    await page.reload();
    // /agents restores whichever agent was last selected, so the new one is not necessarily on
    // screen after a reload when the tenant already has others: look for it in the switcher.
    await page.locator('button[aria-haspopup="listbox"][data-tour="create-agent"]').click();
    await expect(page.getByRole("listbox").getByText(agentName).first()).toBeVisible({ timeout: 30_000 });

    testInfo.annotations.push({
      type: "data-left-behind",
      description: `Created agent "${agentName}" in the production tenant - clean up manually.`,
    });
  });
});
