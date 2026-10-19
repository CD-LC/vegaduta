import { test, expect, type BrowserContext, type Page } from "@playwright/test";

/**
 * Shared signed-in session for tests-prod/stage-knowledge-data-full-live.spec.ts:
 * one Keycloak login for the whole (serial) file, the bearer token the console
 * sends, the findings the page observes, the API helper and the afterAll
 * cleanup of every e2e-knowledge-data- entity. The spec reads `page` and
 * `created` through ES live bindings, so call useKnowledgeDataSession() once
 * at the top level of the spec, before any test.
 */

export const D = process.env.E2E_DOMAIN || "127.0.0.1.nip.io";
export const APP = `http://app.${D}`;
export const API = `http://api.${D}`;
export const P = "e2e-knowledge-data-";
export const RUN = Date.now().toString(36);

export let ctx: BrowserContext;
export let page: Page;
export let token = "";
export let welcomeSeen = 0;
export let tourCards = 0;
export const consoleErrors: string[] = [];
export const badResponses: string[] = [];
export const failed4xx: string[] = [];
export const slow: string[] = [];
export const created = { collections: new Set<string>(), facts: new Set<string>(), blocks: new Set<string>() };

export type Res = { status: number; ms: number; headers: Record<string, string>; text: string; json: any };

export async function api(method: string, path: string, body?: unknown, file?: { name: string; mimeType: string; buffer: Buffer }): Promise<Res> {
  const run = async () => {
    const t0 = Date.now();
    const r = await ctx.request.fetch(`${API}${path}`, {
      method,
      headers: { Authorization: `Bearer ${token}` },
      ...(file ? { multipart: { file } } : body !== undefined ? { data: body } : {}),
      timeout: 60_000,
    });
    const text = await r.text();
    let json: any = null;
    try {
      json = JSON.parse(text);
    } catch {
      /* not json */
    }
    return { status: r.status(), ms: Date.now() - t0, headers: r.headers(), text, json } as Res;
  };
  let res = await run();
  if (res.status === 401) {
    // The console refreshes its token in the background; reload to pick up the fresh bearer.
    await page.reload();
    await page.waitForTimeout(3000);
    res = await run();
  }
  return res;
}

export async function skipWelcome(p: Page) {
  const skip = p.getByRole("button", { name: /^skip for now$/i });
  if (await skip.isVisible({ timeout: 1500 }).catch(() => false)) await skip.click().catch(() => {});
  const close = p.getByRole("button", { name: /^close the tour$/i });
  if (await close.isVisible({ timeout: 500 }).catch(() => false)) await close.click().catch(() => {});
}

export async function go(path: string) {
  await page.goto(path);
  await page.waitForLoadState("domcontentloaded");
  await skipWelcome(page);
}

export async function mkCollection(name: string): Promise<string> {
  const r = await api("POST", "/api/knowledge/collections", { name });
  expect(r.status, r.text).toBe(201);
  created.collections.add(r.json.id);
  return r.json.id;
}

export async function waitDocs(collectionId: string, n: number, status = "READY") {
  await expect
    .poll(async () => {
      const r = await api("GET", `/api/knowledge/collections/${collectionId}/documents`);
      return (r.json as any[]).filter((d) => d.status === status).length;
    }, { timeout: 90_000, intervals: [1000] })
    .toBeGreaterThanOrEqual(n);
}

export const MD = `# Zebra Policy ${RUN}\n\nThe ${P}zebra protocol requires that every purple giraffe files form 77B before sunrise.\n\n## Refunds\n\nRefunds for flamingo orders take 14 days.\n`;

/** Registers the shared login (beforeAll) and cleanup + findings log (afterAll). */
export function useKnowledgeDataSession() {
  test.beforeAll(async ({ browser }) => {
    ctx = await browser.newContext({ ignoreHTTPSErrors: true, baseURL: APP });
    page = await ctx.newPage();
    await page.addInitScript(() => {
      try {
        for (const k of ["agentic_language_choice_seen_v1", "vd_lang_prompt_seen", "vd_agents_guide_seen_v1", "vd_demo_tour_seen_v1", "floatingNav.hintSeen.v1"]) localStorage.setItem(k, "1");
        localStorage.setItem("vd_tour_status", "skipped");
        localStorage.setItem("pageGuideSeen", "[]");
      } catch {
        /* storage blocked */
      }
    });
    page.on("request", (r) => {
      const a = r.headers()["authorization"];
      if (a?.startsWith("Bearer ")) token = a.slice(7);
    });
    page.on("console", (m) => {
      if (m.type() === "error") consoleErrors.push(`${new URL(page.url()).pathname} ${m.text().slice(0, 160)}`);
    });
    page.on("response", (r) => {
      const u = r.url();
      if (!u.includes("/api/")) return;
      const path = u.replace(/https?:\/\/[^/]+/, "").slice(0, 100);
      if (r.status() >= 400) failed4xx.push(`${r.request().method()} ${r.status()} ${path}`);
      if (r.status() >= 500) badResponses.push(`${r.request().method()} ${r.status()} ${path}`);
      const t = r.request().timing();
      if (t.responseEnd > 2000) slow.push(`${r.request().method()} ${path} ${Math.round(t.responseEnd)}ms`);
    });
    // The "Build the agent" welcome modal mounts seconds after load and blocks every click; dismiss it whenever it shows.
    await page.addLocatorHandler(page.getByRole("button", { name: /^skip for now$/i }), async (b) => {
      welcomeSeen++;
      await b.click().catch(() => {});
    });
    // Same for the "Turn on two-factor authentication" product-tour card.
    await page.addLocatorHandler(page.getByRole("dialog", { name: "Product tour" }).getByRole("button", { name: /^got it$/i }), async (b) => {
      tourCards++;
      await b.click().catch(() => {});
    });
    await page.goto(`${APP}/login`);
    await page.getByLabel(/email/i).fill(process.env.E2E_USER_EMAIL!);
    await page.getByRole("button", { name: /continue to secure/i }).click();
    await page.locator("#password").fill(process.env.E2E_USER_PASSWORD!);
    await page.locator("#kc-login").click();
    await page.waitForURL(new RegExp(`app\\.${D.replace(/\./g, "\\.")}/`), { timeout: 45_000 });
    await expect(page.getByText("Load Test Org").first()).toBeVisible({ timeout: 30_000 });
    await skipWelcome(page);
    expect(token).not.toBe("");
  });

  test.afterAll(async () => {
    try {
      const cols = await api("GET", "/api/knowledge/collections");
      for (const c of (cols.json as any[]) || []) {
        if (!String(c.name).startsWith(P)) continue;
        const docs = await api("GET", `/api/knowledge/collections/${c.id}/documents`);
        for (const d of (docs.json as any[]) || []) await api("DELETE", `/api/knowledge/documents/${d.id}`);
        await api("DELETE", `/api/knowledge/collections/${c.id}`);
      }
      const mem = await api("GET", "/api/memory");
      for (const f of mem.json?.facts || []) if (String(f.content).startsWith(P)) await api("DELETE", `/api/memory/facts/${f.scope}/${f.id}`);
      for (const b of mem.json?.blocks || []) if (String(b.content).includes(P) || String(b.label).includes(P)) await api("DELETE", `/api/memory/blocks/${encodeURIComponent(b.label)}`);
    } finally {
      // Findings that are not assertions, surfaced in the run log for the report.
      console.log("KD console errors:", JSON.stringify([...new Set(consoleErrors)].slice(0, 20)));
      console.log("KD 5xx responses seen by the page:", JSON.stringify(badResponses));
      console.log("KD 4xx/5xx seen by the page:", JSON.stringify([...new Set(failed4xx)]));
      console.log("KD welcome modal shown", welcomeSeen, "times; 2FA tour card", tourCards, "times");
      console.log("KD slow (>2s) calls:", JSON.stringify(slow));
      await ctx.close();
    }
  });
}
