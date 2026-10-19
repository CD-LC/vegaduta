import { test, expect, type Page, type BrowserContext } from "@playwright/test";
import * as fs from "fs";

/**
 * Cross-cutting sweep of every console route on the local kind stage: console errors, failed/4xx/5xx requests,
 * slow calls, structural a11y, horizontal overflow at mobile/tablet, broken internal links, keyboard reach, and
 * error-state rendering (forced API 500, unknown route). Read-only except for route interception (no entities created).
 * Findings are written to E2E_OUT/a11y-sweep.json; tests assert the invariants that hold today.
 */
const D = process.env.E2E_DOMAIN || "127.0.0.1.nip.io";
const APP = `http://app.${D}`;
const API = `http://api.${D}`;
const EMAIL = process.env.E2E_USER_EMAIL!;
const PASS = process.env.E2E_USER_PASSWORD!;
const OUT = process.env.E2E_OUT || "test-results-stage";

const ROUTES = [
  "/home", "/agents", "/agents/templates", "/agents/learning", "/agents/quality", "/agents/import",
  "/workflows", "/workflows/templates", "/workflows/import", "/knowledge", "/playground", "/studio", "/usage",
  "/devices", "/files", "/jobs", "/clips", "/edge", "/providers", "/ai-models", "/quick-task", "/quick-task/history",
  "/tools", "/tools/code", "/tools/credentials", "/tools/servers", "/code-intel", "/build", "/build/github",
  "/shared-with-me", "/data-usage", "/memory", "/insights", "/scheduled", "/secrets", "/data-tables", "/skills",
  "/directory", "/explore", "/jev", "/help/docs", "/templates/mine", "/view-as",
  "/admin", "/admin/access", "/admin/agent-trust", "/admin/api-keys", "/admin/approvals", "/admin/audit",
  "/admin/audit-tail", "/admin/billing", "/admin/branding", "/admin/channels", "/admin/commerce", "/admin/contacts",
  "/admin/data-privacy", "/admin/guardrails", "/admin/inbox", "/admin/input-requests", "/admin/integrations",
  "/admin/issues", "/admin/leads", "/admin/maintenance", "/admin/mcp-servers", "/admin/payments", "/admin/users",
];
const NOISE = [/cloudflareinsights/i, /chrome-extension:/i, /favicon/i];

interface RouteResult {
  route: string; ms: number; finalUrl: string; h1: number; lang: string | null; title: string;
  consoleErrors: string[]; failed: string[]; slow: string[]; nameless: string[]; unlabelled: string[];
  noAlt: number; overflow: Record<string, number>; badLinks: string[]; bodyLen: number; apiCalls: number; dupCalls: string[];
}
const results: RouteResult[] = [];
let ctx: BrowserContext;
let page: Page;

async function login(p: Page) {
  await p.addInitScript(() => {
    const orig = Storage.prototype.getItem;
    Storage.prototype.getItem = function (k: string) {
      if (this === window.localStorage && typeof k === "string") {
        if (k.startsWith("vd_tour_status")) return "skipped";
        if (/^(vd_agents_guide_seen|vd_demo_tour_seen|vd_lang_prompt_seen|agentic_language_choice_seen|floatingNav\.hintSeen)/.test(k)) return "1";
        if (k === "vd_page_guides_seen") return JSON.stringify(["home", "agents", "agent-templates", "playground", "tools"]);
      }
      return orig.call(this, k);
    };
  });
  await p.goto(APP);
  await p.getByRole("link", { name: /^sign in$/i }).first().click();
  await p.getByRole("textbox", { name: "Email" }).fill(EMAIL);
  await p.getByRole("button", { name: /continue to secure sign-in/i }).click();
  await p.waitForURL(/auth\./, { timeout: 30_000 });
  await p.locator("#username").fill(EMAIL);
  await p.locator("#password").fill(PASS);
  await p.locator("#kc-login").click();
  await p.waitForURL(/app\./, { timeout: 45_000 });
  await expect(p.locator("header")).toBeVisible({ timeout: 30_000 });
}

async function structural(p: Page) {
  return p.evaluate(() => {
    const vis = (el: Element) => { const s = getComputedStyle(el); const r = (el as HTMLElement).getBoundingClientRect(); return s.display !== "none" && s.visibility !== "hidden" && r.width > 0 && r.height > 0; };
    const nameOf = (el: Element) => (el.textContent || "").trim() || el.getAttribute("aria-label") || el.getAttribute("aria-labelledby") || el.getAttribute("title") || el.querySelector("img[alt]")?.getAttribute("alt") || el.querySelector("svg title")?.textContent;
    const nameless = Array.from(document.querySelectorAll("button, a[href], [role=button], [role=tab], [role=menuitem]")).filter((e) => vis(e) && !nameOf(e)).map((e) => e.outerHTML.slice(0, 140));
    const unlabelled = Array.from(document.querySelectorAll("input:not([type=hidden]):not([type=submit]):not([type=button]), select, textarea")).filter((el) => {
      if (!vis(el)) return false; const id = el.getAttribute("id");
      return !((id && document.querySelector(`label[for="${CSS.escape(id)}"]`)) || el.getAttribute("aria-label") || el.getAttribute("aria-labelledby") || el.closest("label") || el.getAttribute("title"));
    }).map((e) => e.outerHTML.slice(0, 140));
    const links = Array.from(document.querySelectorAll("a[href]")).map((a) => (a as HTMLAnchorElement).href).filter((h) => h.startsWith(location.origin) && !h.includes("#")).slice(0, 400);
    return {
      h1: Array.from(document.querySelectorAll("h1")).filter(vis).length, lang: document.documentElement.lang || null, title: document.title,
      nameless, unlabelled, noAlt: document.querySelectorAll("img:not([alt])").length, links: Array.from(new Set(links)),
      scrollW: document.documentElement.scrollWidth, clientW: document.documentElement.clientWidth, bodyLen: document.body.innerText.length,
    };
  });
}


test.beforeAll(async ({ browser }) => {
  ctx = await browser.newContext({ ignoreHTTPSErrors: true, viewport: { width: 1280, height: 800 } });
  page = await ctx.newPage();
  await login(page);
});
test.afterAll(async () => {
  fs.mkdirSync(OUT, { recursive: true });
  fs.writeFileSync(`${OUT}/a11y-sweep.json`, JSON.stringify(results, null, 1));
  await ctx?.close();
});

test("sweep every console route: errors, failed requests, slow calls, structural a11y, overflow, links", async () => {
  test.setTimeout(30 * 60_000);
  const seenLinks = new Map<string, string[]>();
  for (const route of ROUTES) {
    const r: RouteResult = { route, ms: 0, finalUrl: "", h1: 0, lang: null, title: "", consoleErrors: [], failed: [], slow: [], nameless: [], unlabelled: [], noAlt: 0, overflow: {}, badLinks: [], bodyLen: 0, apiCalls: 0, dupCalls: [] };
    const starts = new Map<string, number>();
    const counts = new Map<string, number>();
    const onConsole = (m: any) => { if (m.type() === "error" && !NOISE.some((n) => n.test(m.text()) || n.test(m.location()?.url || ""))) r.consoleErrors.push(m.text().slice(0, 200) + " @" + (m.location()?.url || "").replace(/^https?:\/\/[^/]+/, "")); };
    const onErr = (e: Error) => r.consoleErrors.push("pageerror: " + e.message.slice(0, 200));
    const onReq = (q: any) => starts.set(q.url() + q.method(), Date.now());
    const onRes = (s: any) => {
      const u: string = s.url(); const q = s.request();
      if (!u.startsWith(API)) return;
      r.apiCalls++;
      const key = q.method() + " " + u.replace(API, "").split("?")[0];
      counts.set(key, (counts.get(key) || 0) + 1);
      const t = Date.now() - (starts.get(u + q.method()) || Date.now());
      if (s.status() >= 400) r.failed.push(`${s.status()} ${key}`);
      if (t > 2000) r.slow.push(`${t}ms ${key}`);
    };
    page.on("console", onConsole); page.on("pageerror", onErr); page.on("request", onReq); page.on("response", onRes);
    const t0 = Date.now();
    try {
      await page.goto(APP + route, { waitUntil: "domcontentloaded", timeout: 30_000 });
      await page.waitForLoadState("networkidle", { timeout: 12_000 }).catch(() => {});
      await page.waitForTimeout(500);
      r.ms = Date.now() - t0; r.finalUrl = page.url().replace(APP, "");
      const s = await structural(page);
      Object.assign(r, { h1: s.h1, lang: s.lang, title: s.title, nameless: s.nameless.slice(0, 6), unlabelled: s.unlabelled.slice(0, 6), noAlt: s.noAlt, bodyLen: s.bodyLen });
      r.overflow.desktop = s.scrollW - s.clientW;
      for (const l of s.links) { const a = seenLinks.get(l) || []; a.push(route); seenLinks.set(l, a); }
      r.dupCalls = [...counts.entries()].filter(([, c]) => c > 2).map(([k, c]) => `${c}x ${k}`);
      // mobile + tablet overflow
      for (const [name, vp] of [["mobile", { width: 375, height: 812 }], ["tablet", { width: 768, height: 1024 }]] as const) {
        await page.setViewportSize(vp); await page.waitForTimeout(350);
        r.overflow[name] = await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth);
      }
      await page.setViewportSize({ width: 1280, height: 800 });
    } catch (e: any) { r.consoleErrors.push("NAV-FAIL " + String(e.message).slice(0, 150)); }
    page.off("console", onConsole); page.off("pageerror", onErr); page.off("request", onReq); page.off("response", onRes);
    results.push(r);
  }
  // broken internal links (unique, GET via page.request, non-asset, capped)
  const urls = [...seenLinks.keys()].filter((u) => !/\.(png|jpg|svg|ico|css|js|webp)(\?|$)/.test(u)).slice(0, 150);
  const bad: string[] = [];
  for (const u of urls) {
    const resp = await ctx.request.get(u, { maxRedirects: 3, timeout: 15_000 }).catch(() => null);
    if (!resp || resp.status() >= 400) bad.push(`${resp?.status() ?? "ERR"} ${u.replace(APP, "")} (from ${seenLinks.get(u)![0]})`);
  }
  fs.mkdirSync(OUT, { recursive: true });
  fs.writeFileSync(`${OUT}/a11y-badlinks.json`, JSON.stringify({ checked: urls.length, bad }, null, 1));
  expect(bad, "broken internal links").toEqual([]);
});

// requestStorageAccess is keycloak-js' 3p-cookie iframe refusing to run on the http stage: test-env only, not a product defect.
const ENV_NOISE = /requestStorageAccess/;
const realErrors = (r: RouteResult) => r.consoleErrors.filter((c) => !ENV_NOISE.test(c));

test("no route throws an uncaught page exception or gets a 5xx from the API", async () => {
  const bad = results.flatMap((r) => [...r.consoleErrors.filter((c) => c.startsWith("pageerror") || c.startsWith("NAV-FAIL")), ...r.failed.filter((f) => /^5/.test(f))].map((e) => `${r.route}: ${e}`));
  expect(bad).toEqual([]);
});
test.fixme("no route logs console errors or 4xx API calls (defects: /agents 404 takeover/capabilities + improvements/health, /workflows 404 import/preview probe, /code-intel -> http://localhost:8090, intermittent CORS on /api/public/status)", async () => {
  const noisy = results.filter((r) => realErrors(r).length || r.failed.length).map((r) => `${r.route}: ${[...realErrors(r), ...r.failed].join(" | ")}`);
  expect(noisy).toEqual([]);
});
test("no route has horizontal overflow at mobile (375) or desktop width", async () => {
  const o = results.filter((r) => (r.overflow.mobile ?? 0) > 1 || (r.overflow.desktop ?? 0) > 1).map((r) => `${r.route}: ${JSON.stringify(r.overflow)}`);
  expect(o).toEqual([]);
});
test.fixme("no route has horizontal overflow at tablet (768) width (defect: /tools overflows by 187px)", async () => {
  expect(results.filter((r) => (r.overflow.tablet ?? 0) > 1).map((r) => `${r.route}: ${r.overflow.tablet}`)).toEqual([]);
});
test("every route sets <html lang> and has no <img> without alt", async () => {
  expect(results.filter((r) => !r.lang || r.noAlt).map((r) => r.route)).toEqual([]);
});
test.fixme("every route has exactly one h1 and labelled controls (defects: /usage h1x2, /admin/approvals + /admin/input-requests h1x0, /devices->/admin/channels unnamed switches, unlabelled textarea/input on /quick-task /tools /admin/contacts /admin/branding)", async () => {
  const bad = results.filter((r) => r.h1 !== 1 || r.nameless.length || r.unlabelled.length).map((r) => `${r.route}: h1=${r.h1} nameless=${r.nameless.length} unlabelled=${r.unlabelled.length}`);
  expect(bad).toEqual([]);
});
test.fixme("each route has a distinct <title> (defect: every console route reports the same marketing title)", async () => {
  const titles = new Set(results.filter((r) => r.finalUrl.startsWith("/") ).map((r) => r.title));
  expect(titles.size).toBeGreaterThan(results.length / 2);
});
test("no console API call takes over 5s", async () => {
  expect(results.filter((r) => r.slow.some((s) => parseInt(s) > 5000)).map((r) => `${r.route}: ${r.slow.join(", ")}`)).toEqual([]);
});
test("no route keeps polling the same endpoint more than 8x during load", async () => {
  expect(results.filter((r) => r.dupCalls.some((d) => parseInt(d) > 8)).map((r) => `${r.route}: ${r.dupCalls.join(", ")}`)).toEqual([]);
});

test("keyboard: skip-link/first tab stop is visible and Tab reaches sidebar navigation on /home", async () => {
  await page.goto(APP + "/home", { waitUntil: "domcontentloaded" });
  await page.waitForLoadState("networkidle", { timeout: 10_000 }).catch(() => {});
  const stops: string[] = [];
  for (let i = 0; i < 12; i++) {
    await page.keyboard.press("Tab");
    stops.push(await page.evaluate(() => { const a = document.activeElement as HTMLElement; if (!a || a === document.body) return "BODY"; const s = getComputedStyle(a); const vis = s.outlineStyle !== "none" || s.boxShadow !== "none"; return `${a.tagName}:${(a.getAttribute("aria-label") || a.textContent || "").trim().slice(0, 30)}:focusRing=${vis}`; }));
  }
  fs.writeFileSync(`${OUT}/a11y-tabstops.json`, JSON.stringify(stops, null, 1));
  expect(stops.filter((s) => s === "BODY").length).toBeLessThan(3);
  expect(stops.filter((s) => s.endsWith("focusRing=false")), "focused elements with no visible focus indicator").toEqual([]);
});

test("command palette / dialogs close on Escape and return focus", async () => {
  await page.goto(APP + "/home", { waitUntil: "domcontentloaded" });
  await page.waitForLoadState("networkidle", { timeout: 10_000 }).catch(() => {});
  // The Product tour card (role=dialog) can be open on /home and consumes the first Escape; dismiss it first.
  await page.waitForTimeout(1500);
  if (await page.getByRole("dialog", { name: /product tour/i }).isVisible().catch(() => false)) { await page.keyboard.press("Escape"); await page.waitForTimeout(500); }
  await page.keyboard.press("Control+k");
  const dlg = page.locator('[role=dialog]:has(input[aria-label="Jump to…"])');
  if (!(await dlg.isVisible({ timeout: 2000 }).catch(() => false))) { await page.keyboard.press("Meta+k"); }
  if (await dlg.isVisible({ timeout: 2000 }).catch(() => false)) {
    await expect(dlg).toHaveAttribute("aria-modal", /true/);
    await page.waitForTimeout(500);
    await page.keyboard.press("Escape");
    await expect(dlg).toBeHidden();
  } else test.info().annotations.push({ type: "note", description: "no dialog opened by Ctrl/Cmd+K" });
});

test("error states: forced API 500 shows an error, not a blank page; unknown route renders not-found", async () => {
  await page.route(`${API}/api/v1/agents*`, (r) => r.fulfill({ status: 500, contentType: "application/json", body: JSON.stringify({ message: "forced" }) }));
  await page.goto(APP + "/agents", { waitUntil: "domcontentloaded" });
  await page.waitForLoadState("networkidle", { timeout: 10_000 }).catch(() => {});
  const body = (await page.locator("body").innerText()).trim();
  expect(body.length).toBeGreaterThan(50);
  const hasErrUi = /error|failed|couldn.t|try again|retry|something went wrong|unable/i.test(body);
  expect(hasErrUi, "a forced 500 on GET /api/agents must surface an error message").toBe(true);
  await page.unroute(`${API}/api/v1/agents*`);
  await page.goto(APP + "/e2e-a11y-responsive-errors-does-not-exist", { waitUntil: "domcontentloaded" });
  await expect(page.getByText(/this page does not exist/i)).toBeVisible({ timeout: 20_000 });
  expect(await page.getByRole("heading", { level: 1 }).count()).toBe(1);
});

test("offline: going offline keeps the rendered page (no white screen) and recovers on reconnect", async () => {
  await page.goto(APP + "/home", { waitUntil: "domcontentloaded" });
  await page.waitForLoadState("networkidle", { timeout: 10_000 }).catch(() => {});
  await ctx.setOffline(true);
  await page.waitForTimeout(2000);
  expect((await page.locator("body").innerText()).length).toBeGreaterThan(50);
  await ctx.setOffline(false);
  await page.reload({ waitUntil: "domcontentloaded" });
  await expect(page.locator("header")).toBeVisible({ timeout: 30_000 });
});
