import { test, expect, type Page, type BrowserContext } from "@playwright/test";
import * as fs from "fs";

/**
 * Platform-admin area against the local kind stage.
 *
 * The stage user (loadtest@vegaduta.test) does NOT hold the Keycloak `platform-admin` realm role, and the
 * harness has no platform-admin credential, so every platform-admin page renders the PlatformAdminGate
 * "Access restricted" screen. This spec therefore verifies the deny path end to end (UI gate on every route,
 * API 401/403 on /api/platform/**, no data in the denied DOM, sign-out) and records the allowed-path pages as
 * blocked. Stage serves the console over http (NEXT_PUBLIC_* are http://); https makes the browser block the
 * http Keycloak/API calls as mixed content, so admin-console.spec.ts (https ADMIN_URL) cannot sign in here.
 * Read-only against the API (GET, plus writes on non-existent ids); never POSTs kill-switch/maintenance.
 */
const D = process.env.E2E_DOMAIN || "127.0.0.1.nip.io";
const ADMIN = `http://admin.${D}`;
const APP = `http://app.${D}`;
const API = `http://api.${D}`;
const EMAIL = process.env.E2E_USER_EMAIL!;
const PASS = process.env.E2E_USER_PASSWORD!;
const OUT = process.env.E2E_OUT || "test-results-stage";

const ROUTES = ["", "/abuse", "/academy-learners", "/ai-reports", "/audit", "/billing", "/budgets", "/demo-sandbox", "/demo-sandboxes", "/diagnostics", "/edge-models", "/email-suppressions", "/fleet", "/fraud", "/funnel", "/impersonation", "/jobs", "/llm-providers", "/maintenance", "/mcp-catalog", "/mcp-review", "/migrations", "/newsletter", "/north-star", "/observability", "/ops", "/plugins", "/reliability", "/safety", "/settings", "/skill-security", "/spend", "/templates", "/tenant-detail", "/tenants", "/tool-credentials", "/users"];

const GET_ENDPOINTS = ["/api/platform/health", "/api/platform/health/dependencies", "/api/platform/tenant-counts", "/api/platform/plan-limits", "/api/platform/spend/summary", "/api/platform/billing/summary", "/api/platform/billing/subscriptions", "/api/platform/abuse-monitor/throttled", "/api/platform/abuse-monitor/scores", "/api/platform/audit/search?q=x", "/api/platform/fleet/health", "/api/platform/jobs", "/api/platform/kill-switch", "/api/platform/maintenance", "/api/platform/mcp-review/pending", "/api/platform/hosted-llm-providers", "/api/platform/feature-flags", "/api/platform/moderation/blocklists", "/api/platform/moderation/review-queue", "/api/platform/migrations", "/api/platform/impersonation", "/api/platform/email/suppressions", "/api/platform/newsletter/recipients", "/api/platform/ai-reports", "/api/platform/issues", "/api/platform/demo-sandboxes", "/api/platform/llm-usage", "/api/platform/hosted-llm-cost"];

const metrics: Record<string, unknown> = {};
const consoleErrors: string[] = [];
const failed: string[] = [];
let ctx: BrowserContext;
let page: Page;
let token = "";

async function keycloak(p: Page) {
  await p.waitForURL(/auth\./, { timeout: 30_000 });
  await p.locator("#username").fill(EMAIL);
  await p.locator("#password").fill(PASS);
  await p.locator("#kc-login").click();
}

test.describe.serial("Platform-admin deny path on the kind stage", () => {
  test.setTimeout(120_000);

  test.beforeAll(async ({ browser }) => {
    ctx = await browser.newContext({ ignoreHTTPSErrors: true });
    page = await ctx.newPage();
    page.on("console", (m) => { if (m.type() === "error") consoleErrors.push(m.text().slice(0, 200)); });
    page.on("requestfailed", (r) => failed.push(`${r.method()} ${r.url().slice(0, 120)} ${r.failure()?.errorText}`));
    page.on("response", (r) => { if (r.status() >= 400) failed.push(`${r.status()} ${r.request().method()} ${r.url().slice(0, 120)}`); });
    // token: log in on the app host and copy the bearer from the console's own API call
    await page.goto(APP);
    await page.getByRole("link", { name: /^sign in$/i }).first().click();
    await page.getByRole("textbox", { name: "Email" }).fill(EMAIL);
    await page.getByRole("button", { name: /continue to secure sign-in/i }).click();
    await keycloak(page);
    const w = page.waitForRequest((r) => r.url().startsWith(API) && !!r.headers()["authorization"], { timeout: 45_000 });
    await page.waitForURL(/app\./, { timeout: 45_000 });
    token = (await w).headers()["authorization"].replace(/^Bearer /i, "");
  });

  test.afterAll(async () => {
    fs.mkdirSync(OUT, { recursive: true });
    fs.writeFileSync(`${OUT}/stage-admin-metrics.json`, JSON.stringify({ metrics, consoleErrors: [...new Set(consoleErrors)], failed: [...new Set(failed)] }, null, 1));
    await ctx.close();
  });

  test("admin host (shared SSO session) shows Access restricted, not the dashboard", async () => {
    const t0 = Date.now();
    await page.goto(`${ADMIN}/platform-admin`, { waitUntil: "domcontentloaded" });
    await expect(page.getByRole("heading", { name: /access restricted/i })).toBeVisible({ timeout: 30_000 });
    metrics.gateVisibleMs = Date.now() - t0;
    await expect(page.getByText(EMAIL)).toBeVisible();
    await expect(page.getByRole("button", { name: /sign out/i })).toBeVisible();
    // nothing admin-shaped rendered behind the gate
    expect(await page.getByRole("navigation").count()).toBe(0);
    expect(await page.locator("body").innerText()).not.toMatch(/tenants|spend|kill switch/i);
  });

  test("every platform-admin route is gated and renders no admin data", async () => {
    const results: Record<string, string> = {};
    const platformCalls: string[] = [];
    const onReq = (r: any) => { if (r.url().includes("/api/platform/")) platformCalls.push(`${r.method()} ${r.url().replace(API, "")}`); };
    page.on("request", onReq);
    for (const r of ROUTES) {
      const t0 = Date.now();
      await page.goto(`${ADMIN}/platform-admin${r}`, { waitUntil: "domcontentloaded" });
      await expect(page.getByRole("heading", { name: /access restricted/i }), `${r || "/"} not gated`).toBeVisible({ timeout: 30_000 });
      const body = await page.locator("body").innerText();
      expect(body, `${r} leaked stack`).not.toMatch(/org\.springframework|java\.lang\.|SQLException|\[object Object\]/);
      expect(body, `${r} application error`).not.toMatch(/Application error: a client-side exception/i);
      results[r || "/"] = `${Date.now() - t0}ms`;
    }
    page.off("request", onReq);
    metrics.routeGateMs = results;
    metrics.platformApiCallsWhileDenied = platformCalls;
    expect(platformCalls, "denied UI must not fire /api/platform calls").toEqual([]);
  });

  test("unknown platform-admin route does not 500 and stays gated or 404s", async () => {
    const res = await page.goto(`${ADMIN}/platform-admin/e2e-admin-nope`, { waitUntil: "domcontentloaded" });
    expect(res!.status()).toBeLessThan(500);
  });

  test("API: /api/platform/** without a token is 401", async () => {
    const codes: Record<string, number> = {};
    for (const e of GET_ENDPOINTS.slice(0, 6)) {
      const r = await ctx.request.get(`${API}${e}`, { failOnStatusCode: false });
      codes[e] = r.status();
      expect(r.status(), e).toBe(401);
    }
    metrics.anonymousCodes = codes;
  });

  test("API: /api/platform/** reads with a non-admin tenant token are 403 and leak no data", async () => {
    const codes: Record<string, number> = {};
    const slow: string[] = [];
    for (const e of GET_ENDPOINTS) {
      const t0 = Date.now();
      const r = await ctx.request.get(`${API}${e}`, { headers: { Authorization: `Bearer ${token}` }, failOnStatusCode: false });
      const ms = Date.now() - t0;
      codes[e] = r.status();
      if (ms > 1000) slow.push(`${ms}ms ${e}`);
      const txt = await r.text();
      expect(r.status(), `${e} -> ${r.status()} ${txt.slice(0, 120)}`).toBe(403);
      expect(txt).not.toMatch(/org\.springframework|java\.lang\.|SQLException|at com\.agenticai/);
    }
    metrics.nonAdminCodes = codes;
    metrics.slowDenied = slow;
  });

  test("API: platform writes on non-existent targets with a non-admin token are 403", async () => {
    const id = "00000000-0000-0000-0000-000000000000";
    const calls: Array<[string, string, unknown?]> = [
      ["POST", `/api/platform/mcp-review/${id}/${id}/approve`],
      ["POST", `/api/platform/mcp-review/${id}/${id}/reject`, { reason: "e2e-admin-x" }],
      ["DELETE", `/api/platform/email/suppressions/e2e-admin-nobody%40example.invalid`],
      ["POST", `/api/platform/abuse-monitor/throttle/${id}/clear`],
      ["POST", `/api/platform/jobs/e2e-admin-nonexistent/run`],
      ["DELETE", `/api/platform/impersonation/${id}`],
    ];
    const out: Record<string, number> = {};
    for (const [m, u, body] of calls) {
      const r = await ctx.request.fetch(`${API}${u}`, { method: m, headers: { Authorization: `Bearer ${token}`, "Content-Type": "application/json" }, data: body, failOnStatusCode: false });
      out[`${m} ${u}`] = r.status();
      expect(r.status(), `${m} ${u}`).toBe(403);
    }
    metrics.nonAdminWrites = out;
  });

  test("API: tenant-scoped /api/agents with the same token still works (gate is role-based, not session-wide)", async () => {
    const r = await ctx.request.get(`${API}/api/agents`, { headers: { Authorization: `Bearer ${token}` }, failOnStatusCode: false });
    expect(r.status()).toBe(200);
  });

  // DEFECT (stage realm): Sign out on admin.<domain> navigates to Keycloak /logout?...post_logout_redirect_uri=http://admin.<domain>
  // which answers 400 "Invalid redirect uri" (same call for app.<domain> is 302), so the SSO session (KEYCLOAK_IDENTITY) stays alive
  // and the "sign in with a different account" advice on the gate cannot be followed. Likely the live agentic-ai-web client lacks the admin
  // host in post.logout.redirect.uris (deploy/helm/charts/keycloak/templates/_realm.tpl sets it; stage realm predates it or was not re-imported).
  test.fixme("Sign out from the gate ends the session and returns to the IdP", async () => {
    await page.goto(`${ADMIN}/platform-admin`, { waitUntil: "domcontentloaded" });
    await page.getByRole("button", { name: /sign out/i }).click();
    await page.waitForURL(/auth\.|\/\/(app|admin)\./, { timeout: 30_000 });
    await page.waitForLoadState("networkidle", { timeout: 15_000 }).catch(() => {});
    await page.goto(`${ADMIN}/platform-admin`, { waitUntil: "domcontentloaded" }).catch(() => {});
    await page.waitForURL(/auth\./, { timeout: 30_000 });
    await expect(page.locator("#username")).toBeVisible();
  });

  // Allowed-path pages need a PLATFORM_ADMIN_ROLE account the stage does not provide.
  for (const r of ["abuse", "audit", "billing", "budgets", "fleet", "fraud", "jobs", "llm-providers", "maintenance", "mcp-catalog", "mcp-review", "observability", "ops", "safety", "settings", "spend", "templates"]) {
    test.fixme(`allowed path /platform-admin/${r} controls (blocked: no platform-admin credential on stage)`, async () => {});
  }
});
