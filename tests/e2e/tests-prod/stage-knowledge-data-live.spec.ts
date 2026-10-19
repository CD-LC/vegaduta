import { test, expect, type Page, type APIRequestContext, type BrowserContext } from "@playwright/test";

/**
 * Collaborators / knowledge scope, and the memory, tool-call log, reliability, available-tools and code-intel
 * scope endpoints, against the local kind stage (mock LLM: provider OLLAMA, model mock:latest).
 *
 * Same assertions as the two matching tests in control-sweep-live.spec.ts. That file signs in through
 * helpers/prod-session.ts and helpers/prod-api.ts, which are stale against the current console (Sign in is a link
 * with an email step, tokens are memory-only since FE-001), so every test there dies in beforeEach. This spec is
 * self-contained: it logs in the stage way and reads the bearer token off the app's own API calls.
 * The stage serves the console over http, so URLs are http://<host>.${E2E_DOMAIN}.
 * Every agent is prefixed "e2e-knowledge-data-" and removed in afterAll.
 */
const D = process.env.E2E_DOMAIN || "127.0.0.1.nip.io";
const APP = `http://app.${D}`;
const API = `http://api.${D}`;
const EMAIL = process.env.E2E_USER_EMAIL!;
const PASS = process.env.E2E_USER_PASSWORD!;
const PFX = "e2e-knowledge-data-";
const JSON_H = { "Content-Type": "application/json" };
const NO_LEAK = /Exception|\bat com\.|\bat java\.|stack ?trace|NullPointer|\[object Object\]|@@AGENT_/;

let ctx: BrowserContext;
let page: Page;
let token = "";
let provider: { providerName: string; model?: string };
const created: string[] = [];

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

/** Tokens are memory-only: reload and copy the Authorization header from the app's own first API call. */
async function freshToken(): Promise<string> {
  const wait = page.waitForRequest((r) => r.url().startsWith(API) && !!r.headers()["authorization"], { timeout: 30_000 });
  await page.reload({ waitUntil: "domcontentloaded" });
  token = (await wait).headers()["authorization"].replace(/^Bearer /i, "");
  return token;
}
const H = () => ({ Authorization: `Bearer ${token}`, ...JSON_H });

type Res = { status: number; text: string; json: any };
async function api(request: APIRequestContext, method: string, path: string, data?: unknown): Promise<Res> {
  const res = await request.fetch(`${API}${path}`, {
    method,
    headers: H(),
    data: data === undefined ? undefined : JSON.stringify(data),
    failOnStatusCode: false,
    timeout: 60_000,
  });
  const text = await res.text();
  let json: any = null;
  try {
    json = text ? JSON.parse(text) : null;
  } catch {
    /* not JSON */
  }
  return { status: res.status(), text, json };
}

/** 402/403 = the tenant's plan gates this control: annotate, do not fail. */
function gated(r: Res, control: string): boolean {
  if (r.status === 402 || r.status === 403) {
    test.info().annotations.push({ type: "plan-gated", description: `${control} -> HTTP ${r.status}` });
    return true;
  }
  return false;
}

async function newAgent(request: APIRequestContext) {
  const r = await api(request, "POST", "/api/agents", {
    name: `${PFX}${Date.now()}-${Math.floor(Math.random() * 1e4)}`,
    systemPrompt: "You are a terse assistant. Reply in one short sentence.",
    ...provider,
  });
  expect(r.status, `agent create failed: ${r.text.slice(0, 300)}`).toBe(201);
  created.push(r.json.id);
  return r.json as { id: string; name: string };
}
const getAgent = async (request: APIRequestContext, id: string) => (await api(request, "GET", `/api/agents/${id}`)).json;

test.describe.serial("Knowledge scope and agent data endpoints on the kind stage", () => {
  test.setTimeout(120_000);

  test.beforeAll(async ({ browser }) => {
    test.skip(!EMAIL || !PASS, "E2E_USER_EMAIL / E2E_USER_PASSWORD not set");
    ctx = await browser.newContext({ ignoreHTTPSErrors: true });
    page = await ctx.newPage();
    await login(page);
    await page.goto(`${APP}/agents`, { waitUntil: "domcontentloaded" });
    await freshToken();
    const list = (await api(ctx.request, "GET", "/api/agents")).json ?? [];
    const t = list.find((x: any) => x.providerName === "OLLAMA") ?? list[0];
    provider = { providerName: t?.providerName ?? "OLLAMA", model: t?.model ?? "mock:latest" };
  });

  test.afterAll(async () => {
    if (!ctx) return;
    await freshToken().catch(() => token);
    for (const id of created) await api(ctx.request, "DELETE", `/api/agents/${id}`).catch(() => {});
    await ctx.close();
  });

  test("collaborators and knowledge scope: assignments persist and clear; a self-collaborator is not stored", async () => {
    const req = ctx.request;
    const a = await newAgent(req);
    const b = await newAgent(req);
    const set = await api(req, "PATCH", `/api/agents/${a.id}/collaborators`, { collaboratorAgentIds: [b.id] });
    if (!gated(set, "collaborators")) {
      expect(set.status, `${set.status} ${set.text.slice(0, 200)}`).toBeLessThan(300);
      expect((await getAgent(req, a.id)).collaboratorAgentIds, "collaborator not persisted").toContain(b.id);
      const self = await api(req, "PATCH", `/api/agents/${a.id}/collaborators`, { collaboratorAgentIds: [a.id] });
      expect(self.status, "self-collaborator produced a server error").toBeLessThan(500);
      if (self.status < 300) {
        expect((await getAgent(req, a.id)).collaboratorAgentIds, "agent stored itself as its own collaborator").not.toContain(a.id);
      }
      await api(req, "PATCH", `/api/agents/${a.id}/collaborators`, { collaboratorAgentIds: [] });
      expect((await getAgent(req, a.id)).collaboratorAgentIds ?? [], "collaborators not cleared").toEqual([]);
    }

    const cols = await api(req, "GET", "/api/knowledge/collections");
    if (Array.isArray(cols.json) && cols.json.length) {
      const id = cols.json[0].id;
      const k = await api(req, "PATCH", `/api/agents/${a.id}/knowledge-access`, { allowedKnowledgeCollectionIds: [id] });
      if (!gated(k, "knowledge access")) {
        expect(k.status).toBeLessThan(300);
        expect((await getAgent(req, a.id)).allowedKnowledgeCollectionIds, "knowledge scope not persisted").toContain(id);
        await api(req, "PATCH", `/api/agents/${a.id}/knowledge-access`, { allowedKnowledgeCollectionIds: [] });
        expect((await getAgent(req, a.id)).allowedKnowledgeCollectionIds ?? []).toEqual([]);
      }
    } else {
      test.info().annotations.push({ type: "note", description: "tenant has no knowledge collection - knowledge-access scope not exercised" });
    }
  });

  test("memory, tool-call log, reliability and code-intel scope endpoints answer cleanly on a fresh agent", async () => {
    const req = ctx.request;
    const a = await newAgent(req);
    for (const path of [
      `/api/agents/${a.id}/memories?subjectKey=sweep`,
      `/api/agents/${a.id}/tool-calls`,
      `/api/agents/${a.id}/reliability`,
      `/api/agents/${a.id}/available-tools`,
    ]) {
      const r = await api(req, "GET", path);
      expect(r.status, `${path} -> ${r.status} ${r.text.slice(0, 200)}`).toBeLessThan(400);
      expect(r.text).not.toMatch(NO_LEAK);
    }
    const ci = await api(req, "PATCH", `/api/agents/${a.id}/code-intel-repo-access`, { allowedCodeIntelRepoIds: [] });
    if (!gated(ci, "code-intel repo access")) expect(ci.status, `${ci.status} ${ci.text.slice(0, 200)}`).toBeLessThan(400);
    const forget = await api(req, "DELETE", `/api/agents/${a.id}/memories/00000000-0000-0000-0000-000000000000`);
    expect(forget.status, "forgetting a non-existent memory must be a 4xx, not a 5xx").toBeLessThan(500);
  });
});
