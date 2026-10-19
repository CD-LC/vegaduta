import { test, expect, type Browser, type BrowserContext, type Page } from "@playwright/test";
import * as fs from "fs";

/**
 * /workflows + /workflows/{id} (workflow editor) against the local kind STAGE.
 *
 * Self-contained on purpose: the shared helpers (helpers/prod-session.ts, prod-api.ts, prod-env.ts) assume an
 * https production deployment with a "Sign in" header BUTTON and a bearer token in sessionStorage. The stage serves
 * the console over plain http (its issuer/api URLs are http), keeps tokens in memory only (web/app/lib/auth.ts FE-001)
 * and shows "Sign in" as a link plus an email step. So this file logs in the stage way, sniffs the bearer token off
 * the app's own API calls, and treats the http origins as secure contexts (keycloak-js needs crypto.subtle).
 *
 * Run (from tests/e2e):  source <stage.env>; npx playwright test -c playwright.stage.local.config.ts \
 *     tests-prod/stage-workflow-build-live --project=chromium --workers=1
 * Everything created is prefixed "e2e-workflow-build-" and removed in afterAll.
 */
const D = process.env.E2E_DOMAIN || "127.0.0.1.nip.io";
const APP = `http://app.${D}`;
const API = `http://api.${D}`;
const PREFIX = "e2e-workflow-build-";
const OUT = (process.env.E2E_OUT || "test-results-stage") + "/workflow-build";
const SECURE = `--unsafely-treat-insecure-origin-as-secure=${["app", "auth", "api", "admin"].map((h) => `http://${h}.${D}`).join(",")}`;

test.use({ baseURL: APP, ignoreHTTPSErrors: true, launchOptions: { args: [SECURE] } });
test.describe.configure({ mode: "serial" });

type G = { id: string; type: string; label?: string; config?: Record<string, unknown> };
const graph = (nodes: G[], edges: Array<[string, string, string?]> = []) => ({
  nodes: nodes.map((n, i) => ({ id: n.id, type: n.type, position: { x: i * 260, y: 0 }, data: { label: n.label ?? n.id, config: n.config ?? {} } })),
  edges: edges.map(([s, t, h]) => ({ id: `e-${s}-x-${t}`, source: s, target: t, sourceHandle: h ?? null, targetHandle: null })),
});

let ctx: BrowserContext;
let page: Page;
let token = "";
const created = new Set<string>();
const apiLog: Array<{ m: string; u: string; s: number; ms: number }> = [];
const consoleErrors: string[] = [];
const serverErrors: string[] = [];

async function login(browser: Browser) {
  ctx = await browser.newContext({ ignoreHTTPSErrors: true, baseURL: APP, viewport: { width: 1280, height: 800 } });
  // Stage-only: dismiss first-run overlays that sit over the canvas (welcome tour, "Build the agent", 2FA nudge, language).
  await ctx.addInitScript(() => {
    // Stage-only workaround for a product defect (see the fixme test below): plain-http origins have no crypto.randomUUID.
    if (!(window.crypto as any).randomUUID) {
      (window.crypto as any).randomUUID = () => "10000000-1000-4000-8000-100000000000".replace(/[018]/g, (c: any) => (c ^ (crypto.getRandomValues(new Uint8Array(1))[0] & (15 >> (c / 4)))).toString(16));
    }
    try {
      window.localStorage.setItem("agentic_language_choice_seen_v1", "1");
      window.localStorage.setItem("vd_lang_prompt_seen", "1");
      window.localStorage.setItem("floatingNav.hintSeen.v1", "1");
      setInterval(() => {
        for (const b of Array.from(document.querySelectorAll("button"))) {
          if (/^(skip, i'll explore|continue in english|skip for now|got it)$/i.test((b.textContent || "").trim())) (b as HTMLButtonElement).click();
        }
      }, 400);
    } catch {
      /* storage blocked */
    }
  });
  page = await ctx.newPage();
  page.on("request", (r) => {
    const a = r.headers()["authorization"];
    if (a && r.url().startsWith(API)) token = a.replace(/^Bearer\s+/i, "");
  });
  page.on("console", (m) => {
    if (m.type() === "error" && !/requestStorageAccess|insecure context/i.test(m.text())) consoleErrors.push(m.text().slice(0, 240));
  });
  page.on("pageerror", (e) => consoleErrors.push("PAGEERROR " + String(e).slice(0, 240)));
  const reqStart = new Map<unknown, number>();
  page.on("request", (r) => { if (r.url().startsWith(API)) reqStart.set(r, Date.now()); });
  page.on("response", (r) => {
    if (!r.url().startsWith(API)) return;
    const u = r.url().replace(API, "");
    apiLog.push({ m: r.request().method(), u, s: r.status(), ms: Date.now() - (reqStart.get(r.request()) ?? Date.now()) });
    if (r.status() >= 500) serverErrors.push(`${r.request().method()} ${u} ${r.status()}`);
  });
  await page.goto(APP);
  await page.getByRole("link", { name: /^sign in$/i }).first().click();
  await page.getByRole("textbox", { name: "Email" }).fill(process.env.E2E_USER_EMAIL!);
  await page.getByRole("button", { name: /continue to secure sign-in/i }).click();
  await page.waitForURL(/auth\./, { timeout: 30_000 });
  await page.locator("#username").fill(process.env.E2E_USER_EMAIL!);
  await page.locator("#password").fill(process.env.E2E_USER_PASSWORD!);
  await page.locator("#kc-login").click();
  await page.waitForURL(/app\./, { timeout: 45_000 });
  await expect.poll(() => token, { timeout: 30_000 }).not.toBe("");
}

/** Authenticated API call as the signed-in user. The token is re-sniffed from the app's own traffic when it rotates. */
async function api(method: string, path: string, body?: unknown, headers?: Record<string, string>) {
  const res = await page.request.fetch(API + path, {
    method,
    headers: headers ?? { authorization: `Bearer ${token}`, "content-type": "application/json" },
    data: body === undefined ? undefined : JSON.stringify(body),
  });
  let json: any = null;
  const text = await res.text();
  try { json = JSON.parse(text); } catch { /* empty body */ }
  return { status: res.status(), json, text };
}

async function makeWf(suffix: string, g = graph([{ id: "t1", type: "trigger" }])) {
  const r = await api("POST", "/api/workflows", { name: `${PREFIX}${suffix}-${Date.now()}`, description: "stage e2e", graph: g });
  expect(r.status, r.text).toBe(201);
  created.add(r.json.id);
  return r.json as { id: string; name: string; graph: { nodes: any[]; edges: any[] } };
}

async function openCanvas(id: string) {
  await page.goto(`/workflows/${id}`, { waitUntil: "domcontentloaded" });
  await expect(page.getByRole("button", { name: /run now/i })).toBeVisible({ timeout: 30_000 });
  await expect(page.locator(".react-flow__renderer, .react-flow").first()).toBeVisible();
}

test.beforeAll(async ({ browser }) => {
  test.setTimeout(120_000);
  await login(browser);
});

test.afterAll(async () => {
  try {
    // Sweep by prefix too: covers workflows a failed test created through the UI before its id was captured.
    const list = await api("GET", "/api/workflows");
    for (const w of (list.json as any[]) ?? []) if (String(w.name).startsWith(PREFIX)) created.add(w.id);
    for (const id of created) await api("DELETE", `/api/workflows/${id}`);
    fs.mkdirSync(OUT, { recursive: true });
    fs.writeFileSync(`${OUT}/api-log.json`, JSON.stringify({ apiLog, consoleErrors, serverErrors }, null, 1));
  } finally {
    await ctx?.close();
  }
});

test.describe("workflow list and creation", () => {
  test("create through the UI: lands on the canvas, persists across reload, shows in the list", async () => {
    const name = `${PREFIX}ui-create-${Date.now()}`;
    await page.goto("/workflows", { waitUntil: "domcontentloaded" });
    await page.getByRole("button", { name: /^new workflow$/i }).first().click();
    await page.getByPlaceholder(/new workflow name/i).fill(name);
    await page.getByRole("button", { name: /^create workflow$/i }).click();
    await page.waitForURL(/\/workflows\/[0-9a-f-]{36}/, { timeout: 30_000 });
    const id = page.url().match(/\/workflows\/([0-9a-f-]{36})/)![1];
    created.add(id);
    await expect(page.getByRole("button", { name: /run now/i })).toBeVisible({ timeout: 30_000 });
    await page.reload();
    await expect(page.getByRole("button", { name: /run now/i })).toBeVisible({ timeout: 30_000 });
    const got = await api("GET", `/api/workflows/${id}`);
    expect(got.status).toBe(200);
    expect(got.json.name).toBe(name);
    expect(got.json.status).toBe("DRAFT");
    await page.goto("/workflows", { waitUntil: "domcontentloaded" });
    await expect(page.getByText(name).first()).toBeVisible({ timeout: 20_000 });
  });

  test("create form: blank name is rejected in the UI (no workflow is created)", async () => {
    await page.goto("/workflows", { waitUntil: "domcontentloaded" });
    const before = ((await api("GET", "/api/workflows")).json as any[]).length;
    await page.getByRole("button", { name: /^new workflow$/i }).first().click();
    const create = page.getByRole("button", { name: /^create workflow$/i });
    await page.getByPlaceholder(/new workflow name/i).fill("   ");
    if (await create.isEnabled()) await create.click();
    await page.waitForTimeout(1500);
    expect(page.url()).not.toMatch(/\/workflows\/[0-9a-f-]{36}/);
    const after = ((await api("GET", "/api/workflows")).json as any[]).length;
    expect(after).toBe(before);
  });

  test("API contract: auth required, unknown id 404, malformed id 400, blank name 400", async () => {
    expect((await api("GET", "/api/workflows", undefined, { "content-type": "application/json" })).status).toBe(401);
    expect((await api("GET", "/api/workflows/00000000-0000-0000-0000-000000000000")).status).toBe(404);
    expect((await api("GET", "/api/workflows/not-a-uuid")).status).toBe(400);
    expect((await api("POST", "/api/workflows", { name: "", graph: graph([]) })).status).toBe(400);
    expect((await api("POST", "/api/workflows", { name: `${PREFIX}nograph` })).status).toBe(400);
  });

  // DEFECT (core): an over-long workflow name is not validated, the DB write fails and the client gets a generic 500.
  // Observed on the stage: POST /api/workflows {name: 400 x "x"} -> 500 "Something went wrong". Expected 400 validation-failed.
  test.fixme("API: a 400-character name is rejected with 400, not a 500", async () => {
    const r = await api("POST", "/api/workflows", { name: PREFIX + "x".repeat(400), graph: graph([]) });
    if (r.json?.id) created.add(r.json.id);
    expect(r.status).toBe(400);
  });

  test("duplicate from the list: same graph, '(copy)' name, different id; original untouched", async () => {
    const src = await makeWf("dup-src", graph([{ id: "t1", type: "trigger" }, { id: "c", type: "code", config: { operation: "uppercase" } }, { id: "o1", type: "output", config: { channel: "log" } }], [["t1", "c"], ["c", "o1"]]));
    await page.goto("/workflows", { waitUntil: "domcontentloaded" });
    const row = page.getByRole("button", { name: "Edit workflow" }).filter({ hasText: src.name }).first();
    await expect(row).toBeVisible({ timeout: 20_000 });
    await row.getByRole("button", { name: "Duplicate workflow" }).click();
    await expect.poll(async () => ((await api("GET", "/api/workflows")).json as any[]).filter((w) => w.name.startsWith(src.name)).length, { timeout: 15_000 }).toBe(2);
    const all = ((await api("GET", "/api/workflows")).json as any[]).filter((w) => w.name.startsWith(src.name));
    const copy = all.find((w) => w.id !== src.id)!;
    created.add(copy.id);
    expect(copy.name).toBe(`${src.name} (copy)`);
    const full = (await api("GET", `/api/workflows/${copy.id}`)).json;
    expect(full.graph.nodes.map((n: any) => n.id)).toEqual(["t1", "c", "o1"]);
    expect(full.graph.edges).toHaveLength(2);
    expect(full.status).toBe("DRAFT");
    expect((await api("GET", `/api/workflows/${src.id}`)).json.graph.nodes).toHaveLength(3);
  });

  test("delete from the list: confirm is required, cancel keeps it, confirm removes it (404 after reload)", async () => {
    const wf = await makeWf("delete-me");
    await page.goto("/workflows", { waitUntil: "domcontentloaded" });
    const row = () => page.getByRole("button", { name: "Edit workflow" }).filter({ hasText: wf.name }).first();
    await expect(row()).toBeVisible({ timeout: 20_000 });
    await row().getByRole("button", { name: "Delete workflow" }).click();
    // Whatever the confirmation looks like (dialog or native confirm), the first press must not delete by itself.
    await page.screenshot({ path: `${OUT}/delete-confirm.png` }).catch(() => {});
    expect((await api("GET", `/api/workflows/${wf.id}`)).status, "deleted without any confirmation step").toBe(200);
    const dialog = page.getByRole("alertdialog").or(page.getByRole("dialog")).last();
    await expect(dialog).toBeVisible({ timeout: 5_000 });
    await dialog.getByRole("button", { name: /cancel|keep|no/i }).first().click();
    expect((await api("GET", `/api/workflows/${wf.id}`)).status).toBe(200);
    await row().getByRole("button", { name: "Delete workflow" }).click();
    await page.getByRole("alertdialog").or(page.getByRole("dialog")).last().getByRole("button", { name: /^(delete|confirm|yes|remove)/i }).first().click();
    await expect.poll(async () => (await api("GET", `/api/workflows/${wf.id}`)).status, { timeout: 15_000 }).toBe(404);
    await page.reload();
    await expect(page.getByText(wf.name)).toHaveCount(0);
    created.delete(wf.id);
  });

  test("templates page: 40 starters, search and category filter work, 'Use this template' pre-fills the create form and creates a real graph", async () => {
    await page.goto("/workflows/templates", { waitUntil: "domcontentloaded" });
    await expect(page.getByRole("heading", { name: "Workflow Templates" })).toBeVisible({ timeout: 20_000 });
    await expect(page.getByRole("button", { name: /^All \(\d+\)/ })).toContainText("40");
    const total = await page.getByRole("button", { name: "Use this template" }).count();
    expect(total).toBe(40);
    await page.getByRole("searchbox", { name: "Search templates" }).fill("refund");
    await expect.poll(() => page.getByRole("button", { name: "Use this template" }).count()).toBeLessThan(total);
    expect(await page.getByRole("button", { name: "Use this template" }).count()).toBeGreaterThan(0);
    await page.getByRole("searchbox", { name: "Search templates" }).fill("zzzz-no-such-template");
    await expect.poll(() => page.getByRole("button", { name: "Use this template" }).count()).toBe(0);
    await page.getByRole("searchbox", { name: "Search templates" }).fill("");
    await page.getByRole("button", { name: "Healthcare & clinics" }).click();
    const healthcare = await page.getByRole("button", { name: "Use this template" }).count();
    expect(healthcare).toBeGreaterThan(0);
    expect(healthcare).toBeLessThan(total);
    await page.getByRole("button", { name: /^All \(/ }).click();
    await page.getByRole("button", { name: "Use this template" }).first().click();
    await page.waitForURL(/\/workflows\?template=/, { timeout: 15_000 });
    const nameBox = page.getByPlaceholder(/new workflow name/i);
    await expect(nameBox).toBeVisible({ timeout: 15_000 });
    const prefilled = await nameBox.inputValue();
    expect(prefilled.length, "template name should pre-fill the create form").toBeGreaterThan(0);
    await nameBox.fill(`${PREFIX}from-template-${Date.now()}`);
    await page.getByRole("button", { name: /^create workflow$/i }).click();
    await page.waitForURL(/\/workflows\/[0-9a-f-]{36}/, { timeout: 30_000 });
    const id = page.url().match(/\/workflows\/([0-9a-f-]{36})/)![1];
    created.add(id);
    const wf = (await api("GET", `/api/workflows/${id}`)).json;
    expect(wf.graph.nodes.length, "a template must create a pre-wired graph").toBeGreaterThan(1);
    expect(wf.graph.edges.length).toBeGreaterThan(0);
  });
});

test.describe("canvas: nodes, edges, validate, save, undo", () => {
  test("palette lists every node type (34) and each 'Add X node' button puts exactly one node on the canvas", async () => {
    test.setTimeout(240_000);
    const wf = await makeWf("palette", graph([]));
    await openCanvas(wf.id);
    const adders = page.getByRole("button", { name: /^Add .+ node\./ });
    const n = await adders.count();
    expect(n, "palette size").toBe(34);
    const labels: string[] = [];
    for (let i = 0; i < n; i++) labels.push(((await adders.nth(i).getAttribute("aria-label")) ?? "").replace(/^Add (.+?) node\..*$/, "$1"));
    expect(new Set(labels).size).toBe(34);
    const before = await page.locator(".react-flow__node").count();
    for (let i = 0; i < n; i++) {
      await adders.nth(i).click();
      await expect.poll(() => page.locator(".react-flow__node").count(), { message: `after adding ${labels[i]}`, timeout: 5_000 }).toBe(before + i + 1);
    }
    await page.getByRole("button", { name: /^save draft$/i }).click();
    await expect.poll(async () => ((await api("GET", `/api/workflows/${wf.id}`)).json.graph.nodes as any[]).length, { timeout: 15_000 }).toBe(34);
    const types = ((await api("GET", `/api/workflows/${wf.id}`)).json.graph.nodes as any[]).map((x) => x.type).sort();
    expect(new Set(types).size, "every node type is distinct and saved").toBe(34);
    await page.reload();
    await expect(page.getByRole("button", { name: /run now/i })).toBeVisible({ timeout: 30_000 });
    await expect.poll(() => page.locator(".react-flow__node").count(), { timeout: 15_000 }).toBe(34);
  });

  test("clicking each node opens its settings in the inspector without console errors", async () => {
    test.setTimeout(240_000);
    const types = ["trigger", "agent", "knowledge", "condition", "approval", "output", "device_control", "tool", "http", "loop", "code", "workflow", "supervisor", "parallel", "fan_out", "human_input", "sub_flow", "voice_menu", "bulk_email", "crm_lead", "set", "filter", "sort", "aggregate", "limit", "dedupe", "switch", "merge", "question_classifier", "parameter_extractor", "variable_aggregator", "document_extractor", "wait", "data_table"];
    const wf = await makeWf("inspect", graph(types.map((t) => ({ id: `n_${t}`, type: t }))));
    await openCanvas(wf.id);
    const errBefore = consoleErrors.length;
    const failures: string[] = [];
    for (const t of types) {
      const node = page.locator(`.react-flow__node[data-id="n_${t}"]`);
      await node.dispatchEvent("click");
      const settings = page.getByRole("region", { name: "Inspector" });
      const ok = await settings.getByText("Label", { exact: true }).first().isVisible({ timeout: 4_000 }).catch(() => false);
      if (!ok) failures.push(t);
    }
    expect(failures, "node types whose inspector did not show a Label field").toEqual([]);
    expect(consoleErrors.slice(errBefore), "console errors while opening node settings").toEqual([]);
  });

  test("node settings: rename a label, save, reload - label and config persist", async () => {
    const wf = await makeWf("config", graph([{ id: "t1", type: "trigger" }, { id: "c", type: "code", config: { operation: "uppercase" } }], [["t1", "c"]]));
    await openCanvas(wf.id);
    await page.locator('.react-flow__node[data-id="c"]').dispatchEvent("click");
    const label = page.getByRole("region", { name: "Inspector" }).getByRole("textbox", { name: "Transform / Code" });
    // A11Y DEFECT (web): the visible "Label" caption is not associated with the input; its accessible name is the node TYPE
    // ("Transform / Code"), so screen readers announce a textbox named after the type, not "Label".
    await expect(label).toBeVisible({ timeout: 10_000 });
    await label.fill("Shouter");
    await page.getByRole("button", { name: /^save draft$/i }).click();
    await expect.poll(async () => (await api("GET", `/api/workflows/${wf.id}`)).json.graph.nodes.find((n: any) => n.id === "c")?.data?.label, { timeout: 15_000 }).toBe("Shouter");
    const saved = (await api("GET", `/api/workflows/${wf.id}`)).json.graph.nodes.find((n: any) => n.id === "c");
    expect(saved.data.config.operation).toBe("uppercase");
    await page.reload();
    await expect(page.locator('.react-flow__node[data-id="c"]')).toContainText("Shouter", { timeout: 20_000 });
  });

  test("edges: a saved graph renders its edges; deleting one with the keyboard and saving removes it server-side", async () => {
    const wf = await makeWf("edges", graph([{ id: "t1", type: "trigger" }, { id: "c", type: "code", config: { operation: "uppercase" } }, { id: "o1", type: "output", config: { channel: "log" } }], [["t1", "c"], ["c", "o1"]]));
    await openCanvas(wf.id);
    await expect.poll(() => page.locator(".react-flow__edge").count(), { timeout: 15_000 }).toBe(2);
    await page.locator(".react-flow__edge").nth(1).click({ force: true, position: { x: 5, y: 5 } }).catch(() => {});
    await page.locator(".react-flow__edge").nth(1).dispatchEvent("click");
    await page.keyboard.press("Delete");
    await expect.poll(() => page.locator(".react-flow__edge").count(), { timeout: 10_000 }).toBe(1);
    await page.getByRole("button", { name: /^save draft$/i }).click();
    await expect.poll(async () => (await api("GET", `/api/workflows/${wf.id}`)).json.graph.edges.length, { timeout: 15_000 }).toBe(1);
  });

  test("node delete: Delete node in the inspector removes the node and its edges; undo restores it", async () => {
    const wf = await makeWf("nodedel", graph([{ id: "t1", type: "trigger" }, { id: "c", type: "code", config: { operation: "uppercase" } }], [["t1", "c"]]));
    await openCanvas(wf.id);
    await page.locator('.react-flow__node[data-id="c"]').dispatchEvent("click");
    await page.getByRole("button", { name: /^delete node$/i }).click();
    const confirm = page.getByRole("alertdialog").or(page.getByRole("dialog")).last().getByRole("button", { name: /^(delete|confirm|remove|yes)/i });
    if (await confirm.first().isVisible({ timeout: 1500 }).catch(() => false)) await confirm.first().click();
    await expect.poll(() => page.locator(".react-flow__node").count(), { timeout: 10_000 }).toBe(1);
    await expect.poll(() => page.locator(".react-flow__edge").count()).toBe(0);
    await page.getByRole("button", { name: /^undo/i }).click();
    await expect.poll(() => page.locator(".react-flow__node").count(), { timeout: 10_000 }).toBe(2);
    await page.getByRole("button", { name: /^redo/i }).click();
    await expect.poll(() => page.locator(".react-flow__node").count(), { timeout: 10_000 }).toBe(1);
  });

  test("Ctrl+K command palette: search filters the list, Enter adds the node, Escape closes", async () => {
    const wf = await makeWf("cmdk", graph([{ id: "t1", type: "trigger" }]));
    await openCanvas(wf.id);
    await page.locator(".react-flow__pane").click({ position: { x: 300, y: 300 }, force: true });
    await page.keyboard.press("Control+k");
    const dlg = page.getByRole("dialog", { name: "Add a node" });
    await expect(dlg).toBeVisible({ timeout: 5_000 });
    await dlg.getByRole("combobox", { name: "Search nodes to add" }).fill("sort");
    await expect(dlg.getByRole("option").first()).toContainText(/sort/i);
    // fuzzy search: the best match ("Sort") ranks first and the list is narrower than the 34-entry full list
    expect(await dlg.getByRole("option").count()).toBeLessThan(34);
    await page.keyboard.press("Enter");
    await expect(dlg).toBeHidden({ timeout: 5_000 });
    await expect.poll(() => page.locator(".react-flow__node").count(), { timeout: 5_000 }).toBe(2);
    await page.keyboard.press("Control+k");
    await expect(dlg).toBeVisible();
    await page.keyboard.press("Escape");
    await expect(dlg).toBeHidden();
  });

  // DEFECT (web): clicking Validate on a graph the server calls invalid (POST .../validate -> {valid:false, errors:[...]})
  // shows NO toast, banner or dialog (only the pre-existing "3 warnings" chip), while a valid graph toasts
  // "Graph is valid - ready to Activate." Observed on the stage with Tool + HTTP nodes left unconfigured; screenshots at
  // 300ms/1.5s/3s after the response show nothing new. A user cannot tell Validate ran or what is wrong.
  test.fixme("Validate button: an invalid graph gives visible, specific feedback (not silence)", async () => {
    const bad = await makeWf("validate-bad", graph([{ id: "t1", type: "trigger" }, { id: "k", type: "tool" }, { id: "h", type: "http" }], [["t1", "k"]]));
    await openCanvas(bad.id);
    await page.getByRole("button", { name: /^validate$/i }).click();
    await page.waitForResponse((r) => /\/validate$/.test(r.url()), { timeout: 15_000 });
    await expect(page.getByText(/no agent selected|has no URL|no tool selected|not valid|errors?/i).filter({ hasNotText: /^No tool selected$/ }).first()).toBeVisible({ timeout: 5_000 });
  });

  test("Validate button: a valid graph says it is ready to Activate; the server contract behind it (valid/errors)", async () => {
    const bad = await makeWf("validate-bad", graph([{ id: "t1", type: "trigger" }, { id: "k", type: "tool" }, { id: "h", type: "http" }], [["t1", "k"]]));
    const good = await makeWf("validate-good", graph([{ id: "t1", type: "trigger" }, { id: "o1", type: "output", config: { channel: "log" } }], [["t1", "o1"]]));
    await openCanvas(good.id);
    await page.getByRole("button", { name: /^validate$/i }).click();
    await expect(page.getByText(/graph is valid - ready to activate/i).first()).toBeVisible({ timeout: 10_000 });
    const badV = (await api("POST", `/api/workflows/${bad.id}/validate`)).json;
    expect(badV.valid).toBe(false);
    expect(badV.errors.join(" | ")).toMatch(/Tool node 'k' has no agent/);
    expect(badV.errors.join(" | ")).toMatch(/HTTP Request node 'h' has no URL/);
    expect((await api("POST", `/api/workflows/${good.id}/validate`)).json).toEqual({ valid: true, errors: [] });
    const empty = await makeWf("validate-empty", graph([]));
    expect((await api("POST", `/api/workflows/${empty.id}/validate`)).json.errors[0]).toMatch(/no nodes/i);
  });

  test("validate catches a cycle, an unknown node type and a dangling edge", async () => {
    const wf = await makeWf("validate-cycle", graph([{ id: "t1", type: "trigger" }, { id: "a", type: "code" }, { id: "b", type: "code" }], [["t1", "a"], ["a", "b"], ["b", "a"]]));
    expect((await api("POST", `/api/workflows/${wf.id}/validate`)).json.errors.join(" ")).toMatch(/cycle/i);
    const put = await api("PUT", `/api/workflows/${wf.id}`, { name: wf.name, description: "", graph: graph([{ id: "t1", type: "trigger" }, { id: "x", type: "bogus_type" }], [["t1", "x"], ["t1", "nope"]]), status: "DRAFT" });
    expect(put.status).toBe(200);
    const v = (await api("POST", `/api/workflows/${wf.id}/validate`)).json.errors.join(" | ");
    expect(v).toMatch(/unrecognized type/i);
    expect(v).toMatch(/doesn't exist/i);
  });

  test("save: every PUT stores a version and bumps updatedAt; rename round-trips; blank name rejected", async () => {
    const wf = await makeWf("save");
    const put = (name: string) => api("PUT", `/api/workflows/${wf.id}`, { name, description: "d", graph: wf.graph, status: "DRAFT" });
    expect((await put(wf.name + "-renamed")).status).toBe(200);
    expect((await api("GET", `/api/workflows/${wf.id}`)).json.name).toBe(wf.name + "-renamed");
    expect((await put("")).status).toBe(400);
    const versions = (await api("GET", `/api/workflows/${wf.id}/versions`)).json as any[];
    expect(versions.length).toBeGreaterThanOrEqual(2);
    expect(versions[0].versionNumber).toBeGreaterThan(versions[versions.length - 1].versionNumber);
  });
});

test.describe("run, history, activate", () => {
  test("Run now: modal takes input, run COMPLETES, the run record carries per-node output (checked via the API)", async () => {
    const wf = await makeWf("run", graph([{ id: "t1", type: "trigger" }, { id: "c", type: "code", config: { operation: "uppercase" } }, { id: "o1", type: "output", config: { channel: "log" } }], [["t1", "c"], ["c", "o1"]]));
    await openCanvas(wf.id);
    await page.getByRole("button", { name: /run now/i }).click();
    await page.getByPlaceholder(/e\.g\. a sample inbound message/i).fill("stage hello");
    const runResp = page.waitForResponse((r) => /\/api\/workflows\/[^/]+\/run(\?|$)/.test(r.url()) && r.request().method() === "POST", { timeout: 60_000 });
    await page.getByRole("button", { name: "▶ Run", exact: true }).click();
    const rr = await runResp;
    expect(rr.status()).toBe(200);
    const started = await rr.json();
    expect(["RUNNING", "COMPLETED"]).toContain(started.status); // the canvas starts runs in the background
    await expect.poll(async () => (await api("GET", `/api/workflows/${wf.id}/runs/${started.id}`)).json?.status, { timeout: 30_000 }).toBe("COMPLETED");
    const run = (await api("GET", `/api/workflows/${wf.id}/runs/${started.id}`)).json;
    expect(run.trace.map((s: any) => s.nodeType)).toEqual(["trigger", "code", "output"]);
    expect(String(run.trace[1].outputJson)).toContain("STAGE HELLO");
    const runs = (await api("GET", `/api/workflows/${wf.id}/runs`)).json as any[];
    expect(runs[0].id).toBe(run.id);
    await expect(page.getByText(/Viewing run #1/)).toBeVisible({ timeout: 15_000 });
  });

  // DEFECT (web): web/app/components/workflow/WorkflowCanvas.tsx:1856 calls crypto.randomUUID() unguarded. On any non-secure
  // origin (plain http that is not localhost - this stage, a LAN-IP self-host) it is undefined, the click handler throws
  // "TypeError: crypto.randomUUID is not a function", NO request is sent, and the Run modal sits on "Running..." forever with
  // no error shown. lib/requestLifecycle.ts:359 already has the guarded pattern to reuse. Every other test in this file runs
  // with a polyfill injected by login(); this one runs in a clean context without it.
  test.fixme("Run now works on a non-secure (http) origin: no crypto.randomUUID needed", async ({ browser }) => {
    const wf = await makeWf("run-insecure", graph([{ id: "t1", type: "trigger" }, { id: "o1", type: "output", config: { channel: "log" } }], [["t1", "o1"]]));
    const c2 = await browser.newContext({ ignoreHTTPSErrors: true, baseURL: APP, storageState: await ctx.storageState() });
    const p2 = await c2.newPage();
    const errors: string[] = [];
    p2.on("pageerror", (e) => errors.push(String(e)));
    try {
      await p2.goto(`/workflows/${wf.id}`, { waitUntil: "domcontentloaded" });
      await p2.getByRole("button", { name: /run now/i }).click();
      await p2.getByRole("button", { name: "▶ Run", exact: true }).click();
      await expect(p2.getByText(/Viewing run #1|COMPLETED/i).first()).toBeVisible({ timeout: 20_000 });
      expect(errors.filter((e) => /randomUUID/.test(e))).toEqual([]);
    } finally {
      await c2.close();
    }
  });

  test("Run refuses an invalid graph with a human message instead of a stack trace", async () => {
    const wf = await makeWf("run-bad", graph([{ id: "t1", type: "trigger" }, { id: "h", type: "http" }], [["t1", "h"]]));
    const r = await api("POST", `/api/workflows/${wf.id}/run`, { input: "x" });
    expect([200, 400, 409, 422]).toContain(r.status);
    const body = r.text;
    expect(body).not.toMatch(/at com\.agenticai|java\.lang|Exception/);
    expect(body).toMatch(/URL|configured|invalid|FAILED/i);
  });

  test("Activate / revert to draft: status round-trips and survives reload", async () => {
    const wf = await makeWf("activate", graph([{ id: "t1", type: "trigger" }, { id: "o1", type: "output", config: { channel: "log" } }], [["t1", "o1"]]));
    await openCanvas(wf.id);
    await page.getByRole("button", { name: /^activate$/i }).click();
    await expect.poll(async () => (await api("GET", `/api/workflows/${wf.id}`)).json.status, { timeout: 20_000 }).toBe("ACTIVE");
    await page.reload();
    await expect(page.getByRole("button", { name: /run now/i })).toBeVisible({ timeout: 30_000 });
    await expect(page.getByText(/^active$/i).first()).toBeVisible({ timeout: 10_000 });
  });

  test("a workflow someone else owns / does not exist cannot be opened: canvas shows an error state, not a blank canvas", async () => {
    await page.goto("/workflows/00000000-0000-0000-0000-000000000000", { waitUntil: "domcontentloaded" });
    await expect(page.getByText(/not found|couldn't|could not|no longer|doesn't exist|does not exist/i).first()).toBeVisible({ timeout: 20_000 });
    await expect(page.getByRole("button", { name: /run now/i })).toHaveCount(0);
  });
});

test.describe("efficiency", () => {
  test("opening a workflow canvas: API calls under 2s, no 5xx, no console errors, request fan-out recorded", async () => {
    const wf = await makeWf("perf", graph([{ id: "t1", type: "trigger" }, { id: "o1", type: "output", config: { channel: "log" } }], [["t1", "o1"]]));
    const seen: Array<{ u: string; ms: number; s: number; size: number }> = [];
    const started = new Map<unknown, number>();
    const onReq = (r: any) => { if (r.url().startsWith(API)) started.set(r, Date.now()); };
    const onResp = async (r: any) => {
      if (!r.url().startsWith(API)) return;
      const ms = Date.now() - (started.get(r.request()) ?? Date.now());
      const body = await r.body().catch(() => Buffer.alloc(0));
      seen.push({ u: r.request().method() + " " + r.url().replace(API, "").replace(/[0-9a-f-]{36}/g, ":id"), ms, s: r.status(), size: body.length });
    };
    page.on("request", onReq);
    page.on("response", onResp);
    const errBefore = consoleErrors.length;
    const t0 = Date.now();
    await openCanvas(wf.id);
    const loadMs = Date.now() - t0;
    await page.waitForTimeout(3000);
    page.off("response", onResp);
    page.off("request", onReq);
    fs.mkdirSync(OUT, { recursive: true });
    fs.writeFileSync(`${OUT}/canvas-open-requests.json`, JSON.stringify({ loadMs, seen }, null, 1));
    const counts: Record<string, number> = {};
    for (const s of seen) counts[s.u] = (counts[s.u] ?? 0) + 1;
    const dupes = Object.entries(counts).filter(([, n]) => n > 1);
    test.info().annotations.push({ type: "perf", description: `load=${loadMs}ms requests=${seen.length} duplicates=${JSON.stringify(dupes)}` });
    expect(seen.filter((s) => s.s >= 500)).toEqual([]);
    expect(seen.filter((s) => s.ms > 2000), "API calls slower than 2s").toEqual([]);
    expect(seen.filter((s) => s.size > 500_000), "responses over 500 kB").toEqual([]);
    expect(consoleErrors.slice(errBefore)).toEqual([]);
    expect(loadMs, "canvas ready").toBeLessThan(10_000);
    // Soft budget: the same GET repeated more than 2x on one page open is chatty.
    expect(dupes.filter(([, n]) => n > 2), "endpoints fetched more than twice on one open").toEqual([]);
  });

  test("no 5xx anywhere in this spec", async () => {
    expect(serverErrors).toEqual([]);
  });
});
