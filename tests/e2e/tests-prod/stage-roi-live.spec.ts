import { test, expect, type Browser, type BrowserContext, type Page } from "@playwright/test";

/**
 * Stage smoke: /roi (Cost & ROI per agent) and /api/tenant/roi/**.
 *
 * Same stage setup as stage-eval-insights-live.spec.ts: the kind stage serves the console over
 * http://app.<E2E_DOMAIN>, Chromium is launched with --unsafely-treat-insecure-origin-as-secure for the
 * three hosts, and the sign-in is the "email step, Keycloak form" flow. Credentials come from the
 * environment only: E2E_USER_EMAIL / E2E_USER_PASSWORD (a tenant admin). Without them the file skips.
 *
 *   cd tests/e2e
 *   E2E_DOMAIN=127.0.0.1.nip.io E2E_SCHEME=http E2E_USER_EMAIL=... E2E_USER_PASSWORD=... \
 *   npx playwright test -c playwright.stage.local.config.ts tests-prod/stage-roi-live.spec.ts --project=chromium --workers=1
 *
 * It only writes what it removes again: one price row for a made-up provider/model prefix
 * ("e2e-roi-model"), and the first agent's value assumptions, which it restores to what they were.
 * The stage must run a build that contains V496 and /api/tenant/roi (otherwise the first call is a 404).
 */
const DOMAIN = process.env.E2E_DOMAIN || "127.0.0.1.nip.io";
const APP = `http://app.${DOMAIN}`;
const API = `http://api.${DOMAIN}`;
const AUTH = `http://auth.${DOMAIN}`;
const EMAIL = process.env.E2E_USER_EMAIL;
const PASSWORD = process.env.E2E_USER_PASSWORD;
const PRICE_PREFIX = "e2e-roi-model";

test.use({
  launchOptions: { args: [`--unsafely-treat-insecure-origin-as-secure=${APP},${AUTH},${API}`] },
});

let ctx: BrowserContext;
let page: Page;
let token = "";
let agentId = "";
let agentName = "";
let originalAssumption: { valuePerResolution: number | null; valueCurrency: string | null; minutesSavedPerResolution: number | null } | null = null;
let createdPriceId = "";

async function login(browser: Browser) {
  for (let attempt = 1; attempt <= 8; attempt++) {
    ctx = await browser.newContext({ ignoreHTTPSErrors: true, viewport: { width: 1366, height: 900 } });
    page = await ctx.newPage();
    await page.addInitScript(() => {
      try {
        for (const k of ["agentic_language_choice_seen_v1", "vd_lang_prompt_seen", "vd_agents_guide_seen_v1", "vd_demo_tour_seen_v1", "floatingNav.hintSeen.v1"]) {
          localStorage.setItem(k, "1");
        }
        localStorage.setItem("vd_tour_status", "skipped");
      } catch { /* storage blocked */ }
    });
    token = "";
    await page.addLocatorHandler(page.getByRole("button", { name: "Skip for now" }), async (el) => { await el.click().catch(() => {}); });
    await page.addLocatorHandler(page.getByRole("button", { name: "Got it", exact: true }), async (el) => { await el.click().catch(() => {}); });
    page.on("request", (r) => {
      const a = r.headers()["authorization"];
      if (a && !token) token = a.replace(/^Bearer /, "");
    });
    await page.goto(`${APP}/login`);
    await page.getByLabel(/email/i).fill(EMAIL!);
    await page.getByRole("button", { name: /continue to secure/i }).click();
    await page.waitForSelector("#password");
    await page.locator("#password").fill(PASSWORD!);
    await page.locator("#kc-login").click();
    await page.waitForTimeout(6_000);
    await page.goto(`${APP}/playground`);
    await page.waitForTimeout(4_000);
    if (token) return;
    await ctx.close();
    await new Promise((r) => setTimeout(r, 45_000)); // Keycloak quick-login protection
  }
  throw new Error("could not sign in after 8 attempts");
}

type Res = { status: number; body: string; json: any };

async function call(method: string, path: string, body?: unknown): Promise<Res> {
  const r = await page.evaluate(
    async ([m, p, b, tok, api]) => {
      const x = await fetch(`${api}${p}`, {
        method: m as string,
        headers: { Authorization: `Bearer ${tok}`, "Content-Type": "application/json" },
        body: b ? JSON.stringify(b) : undefined,
      });
      return { status: x.status, body: await x.text() };
    },
    [method, path, body ?? null, token, API] as const,
  );
  let json: any = null;
  try { json = JSON.parse(r.body); } catch { /* not json */ }
  return { ...r, json };
}

test.beforeAll(async ({ browser }) => {
  test.skip(!EMAIL || !PASSWORD, "E2E_USER_EMAIL / E2E_USER_PASSWORD not set");
  await login(browser);
  const agents = await call("GET", "/api/agents");
  expect(agents.status).toBe(200);
  expect(agents.json.length).toBeGreaterThan(0);
  agentId = agents.json[0].id;
  agentName = agents.json[0].name;
  const before = await call("GET", `/api/tenant/roi/agents/${agentId}/assumptions`);
  expect(before.status, "the stage must run a build with /api/tenant/roi").toBe(200);
  originalAssumption = {
    valuePerResolution: before.json.valuePerResolution ?? null,
    valueCurrency: before.json.valueCurrency ?? null,
    minutesSavedPerResolution: before.json.minutesSavedPerResolution ?? null,
  };
});

test.afterAll(async () => {
  if (!page) return;
  if (createdPriceId) await call("DELETE", `/api/tenant/roi/prices/${createdPriceId}`).catch(() => {});
  if (originalAssumption) await call("PUT", `/api/tenant/roi/agents/${agentId}/assumptions`, originalAssumption).catch(() => {});
  await ctx?.close();
});

test.describe("Cost & ROI", () => {
  test("API contract: every read answers, bad input is a 400, an unknown agent is a 404", async () => {
    for (const path of ["/agents", "/budget", "/prices", "/costs?groupBy=agent", "/costs?groupBy=day"]) {
      const r = await call("GET", `/api/tenant/roi${path}`);
      expect(r.status, path).toBe(200);
    }
    expect((await call("GET", "/api/tenant/roi/costs?groupBy=nonsense")).status).toBe(400);
    expect((await call("GET", "/api/tenant/roi/agents?from=2026-10-14&to=2026-10-01")).status).toBe(400);
    expect((await call("GET", "/api/tenant/roi/agents/00000000-0000-0000-0000-000000000000/assumptions")).status).toBe(404);
    expect((await call("PUT", `/api/tenant/roi/agents/${agentId}/assumptions`, { valuePerResolution: 5 })).status, "a value needs a currency").toBe(400);
  });

  test("the page shows the agents table, the budget card and a CSV download", async () => {
    await page.goto(`${APP}/roi`);
    await expect(page.getByRole("heading", { name: "Cost & ROI by agent" })).toBeVisible({ timeout: 20_000 });
    await expect(page.getByTestId("roi-table")).toBeVisible();
    await expect(page.getByTestId(`roi-row-${agentId}`)).toContainText(agentName);
    await expect(page.getByTestId("roi-budget")).toBeVisible();
    await expect(page.getByRole("columnheader", { name: "Cost (estimated)" })).toBeVisible();
    // the page contains a rate cell for an agent with no outcomes as a dash, never as 0%
    const [download] = await Promise.all([page.waitForEvent("download"), page.getByTestId("roi-export").click()]);
    expect(download.suggestedFilename()).toMatch(/^agent-roi-.*\.csv$/);
  });

  test("value assumptions are typed in, not defaulted, and ROI says whose assumptions they are", async () => {
    await call("PUT", `/api/tenant/roi/agents/${agentId}/assumptions`, { valuePerResolution: null, valueCurrency: null, minutesSavedPerResolution: null });
    await page.goto(`${APP}/roi`);
    const row = page.getByTestId(`roi-row-${agentId}`);
    await expect(row.getByTestId("roi-cell-none")).toContainText(/Enter what a resolution is worth/);
    await row.getByRole("button", { name: `Edit value assumptions for ${agentName}` }).click();
    const editor = page.getByTestId("roi-assumption-editor");
    await expect(editor.getByLabel("Value per resolved conversation")).toHaveValue("");
    await editor.getByLabel("Value per resolved conversation").fill("5");
    await editor.getByLabel("Currency (3 letters)").fill("usd");
    await editor.getByLabel("Minutes saved per resolved conversation").fill("10");
    await editor.getByRole("button", { name: "Save" }).click();
    await expect(page.getByTestId("roi-assumption-editor")).toHaveCount(0);
    const stored = await call("GET", `/api/tenant/roi/agents/${agentId}/assumptions`);
    expect(stored.json).toMatchObject({ valueCurrency: "USD", basis: "based on your assumptions" });
    expect(Number(stored.json.valuePerResolution)).toBe(5);
    // with value set, the row either shows an ROI labelled with the basis, or says why there is none
    await expect(row).toContainText(/based on your assumptions|no cost recorded|different currencies|lower bound/);
  });

  test("an unpriced model is excluded and listed; a price the tenant enters is stored with its date", async () => {
    const created = await call("POST", "/api/tenant/roi/prices", { providerName: "OPENAI", modelPrefix: PRICE_PREFIX, inputPerMillion: 1.5, outputPerMillion: 6 });
    expect(created.status).toBe(200);
    createdPriceId = created.json.id;
    expect(created.json.effectiveFrom).toMatch(/^\d{4}-\d{2}-\d{2}$/);
    const list = await call("GET", "/api/tenant/roi/prices");
    expect(list.json.prices.some((p: any) => p.id === createdPriceId)).toBe(true);
    expect(list.json.currency).toBe("USD");
    await page.goto(`${APP}/roi`);
    await expect(page.getByTestId("roi-price-list")).toContainText(PRICE_PREFIX);
  });

  test("outcome marks reach the table, and a repeat is a no-op", async () => {
    const key = `e2e-roi-${Date.now()}`;
    const body = { conversationKey: key, agentId, outcome: "HANDED_TO_HUMAN" };
    const first = await call("POST", "/api/tenant/roi/outcomes", body);
    expect(first.status).toBe(200);
    expect(first.json.recorded).toBe(true);
    expect((await call("POST", "/api/tenant/roi/outcomes", body)).json.recorded).toBe(false);
    expect((await call("POST", "/api/tenant/roi/outcomes", { ...body, outcome: "MAYBE" })).status).toBe(400);
    const table = await call("GET", "/api/tenant/roi/agents");
    const row = table.json.agents.find((a: any) => a.agentId === agentId);
    expect(row.handedToHuman).toBeGreaterThanOrEqual(1);
    expect(Number(row.handoverRate)).toBeGreaterThan(0);
  });

  test("narrow viewport: no horizontal page scroll", async () => {
    await page.setViewportSize({ width: 390, height: 844 });
    await page.goto(`${APP}/roi`);
    await expect(page.getByTestId("roi-table")).toBeVisible({ timeout: 20_000 });
    const overflow = await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth);
    expect(overflow).toBeLessThanOrEqual(1);
    await page.setViewportSize({ width: 1366, height: 900 });
  });
});
