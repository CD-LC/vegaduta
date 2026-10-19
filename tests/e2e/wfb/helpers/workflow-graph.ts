import { type Page, expect } from "@playwright/test";
import { API_URL } from "./prod-env";
import { getBearerToken } from "./prod-api";

/**
 * Helpers for tests that need a workflow with a SPECIFIC graph.
 *
 * Why not build the graph by dragging nodes and wiring handles on the
 * React Flow canvas: that is slow and disproportionately flaky in an
 * automated browser (verified the hard way while building the first
 * workflow spec by hand), and it is not what the node-config tests are
 * about. The workflow SHELL is created through the real "New workflow" UI
 * (so that path stays exercised), the graph is PUT over the same endpoint
 * the canvas's own Save Draft uses (`PUT /api/workflows/{id}` with
 * `{name, description, graph:{nodes, edges}, status}` - payload shape
 * captured from a real Save Draft on 2026-09-23), and everything the tests
 * actually exercise - the per-node config drawer, Save Draft, Run - then
 * goes through the real UI.
 */

export type GraphNode = {
  id: string;
  type: string;
  label: string;
  config: Record<string, unknown>;
  x?: number;
  y?: number;
};
export type GraphEdge = { source: string; target: string; sourceHandle?: string | null };

export const node = (
  id: string,
  type: string,
  config: Record<string, unknown> = {},
  label?: string,
  x?: number,
  y?: number
): GraphNode => ({ id, type, label: label ?? type, config, x, y });

export const edge = (source: string, target: string, sourceHandle: string | null = null): GraphEdge => ({
  source,
  target,
  sourceHandle,
});

/** Creates an empty workflow through the real "New workflow" UI; returns its id. */
export async function createWorkflowViaUi(page: Page, name: string): Promise<string> {
  await page.goto("/workflows", { waitUntil: "domcontentloaded" });
  // KNOWN PRODUCT BUG (web scope): while listWorkflows is in flight the page
  // renders TWO visible "New workflow" buttons - the toolbar CTA and the
  // empty-state CTA - because WorkflowDesignerPanel's `loaded` flag doesn't
  // gate `workflows.length === 0`. Until that is fixed, disambiguate: the
  // toolbar button is first in DOM order. Both call setShowCreate, so the
  // interaction is identical.
  await page.getByRole("button", { name: /^new workflow$/i }).first().click();
  await page.getByPlaceholder(/new workflow name/i).fill(name);
  await page.getByRole("button", { name: /^create workflow$/i }).click();
  await page.waitForURL(/\/workflows\/[0-9a-f-]{36}/, { timeout: 30_000 });
  const id = page.url().match(/\/workflows\/([0-9a-f-]{36})/)![1];
  await expect(page.getByRole("button", { name: /run now/i }), "new workflow's canvas never loaded").toBeVisible({
    timeout: 30_000,
  });
  return id;
}

let cachedApiHeaders: Record<string, string> | null = null;

/**
 * Builds the auth headers the app itself sends to the API.
 *
 * Used to sniff a real /api/* request off the wire (waitForRequest +
 * goto("/workflows")) - until the perf(api) SWR GET cache shipped, after
 * which a warmed session can render the workflows list WITHOUT firing any
 * fetch, and the sniff timed out intermittently (observed live in verify5:
 * Trigger-Workflow and HTTP-node tests died waiting for a request that
 * never needed to happen). The bearer token lives in sessionStorage
 * vd_kc_session (web/app/lib/auth.ts TOKEN_CACHE_KEY); reading it directly
 * is deterministic, and the API authorizes on the bearer alone (verified
 * live: GET /api/agents + share POST/DELETE with only this header).
 */
async function captureApiHeaders(page: Page): Promise<Record<string, string>> {
  const token = await getBearerToken(page);
  const headers = { authorization: `Bearer ${token}`, "content-type": "application/json" };
  cachedApiHeaders = headers;
  return headers;
}

/** Replaces a workflow's graph over the same endpoint the canvas's Save Draft uses. */
export async function putGraph(
  page: Page,
  id: string,
  name: string,
  nodes: GraphNode[],
  edges: GraphEdge[]
): Promise<void> {
  const headers = await captureApiHeaders(page);
  const body = {
    name,
    description: "",
    graph: {
      nodes: nodes.map((n, i) => ({
        id: n.id,
        type: n.type,
        // Auto-layout unless a position was given: every node used to land at
        // (0,0), stacked on top of each other, and the topmost one swallowed
        // every click meant for the others (reproduced live).
        // One row: a second row put nodes under the canvas minimap, which then
        // intercepted the click on them (reproduced live).
        position: { x: n.x ?? i * 260, y: n.y ?? 0 },
        data: { label: n.label, config: n.config },
      })),
      edges: edges.map((e) => ({
        id: `e-${e.source}-x-${e.target}`,
        source: e.source,
        target: e.target,
        sourceHandle: e.sourceHandle ?? null,
        targetHandle: null,
      })),
    },
    status: "DRAFT",
  };
  const res = await page.request.fetch(`${API_URL}/api/workflows/${id}`, {
    method: "PUT",
    headers,
    data: JSON.stringify(body),
  });
  expect(res.status(), `PUT graph failed: ${(await res.text()).slice(0, 300)}`).toBeLessThan(300);
}

/** One authenticated API call using the headers the app itself sends (no page navigation once captured). */
export async function apiCall(
  page: Page,
  method: "GET" | "PUT" | "POST" | "DELETE",
  path: string,
  data?: unknown
): Promise<{ status: number; text: string }> {
  const headers = cachedApiHeaders ?? (await captureApiHeaders(page));
  const res = await page.request.fetch(`${API_URL}${path}`, {
    method,
    headers,
    data: data === undefined ? undefined : JSON.stringify(data),
  });
  return { status: res.status(), text: await res.text() };
}

/** Opens a workflow canvas by id and waits for it to be interactive. */
export async function openWorkflow(page: Page, id: string): Promise<void> {
  await page.goto(`/workflows/${id}`, { waitUntil: "domcontentloaded" });
  await expect(page.getByRole("button", { name: /run now/i })).toBeVisible({ timeout: 30_000 });
  await expect(page.locator(".react-flow__node").first(), "canvas rendered no nodes").toBeVisible({
    timeout: 30_000,
  });
}

/**
 * Deselects any node so the RUN INSPECTOR (step-by-step results) is shown.
 * The right-hand panel is either the selected node's config drawer or the
 * run inspector, never both - reproduced live: a run COMPLETED while the
 * node stayed selected, and every assertion about the step output failed
 * because that output was simply not on screen.
 */
export async function showRunResults(page: Page): Promise<void> {
  const drawerOpen = page.getByRole("button", { name: /^delete node$/i });
  if ((await drawerOpen.count()) === 0) return;
  await page.locator(".react-flow__pane").click({ position: { x: 40, y: 110 } });
  await expect(drawerOpen, "clicking the empty canvas did not close the node drawer").toHaveCount(0, {
    timeout: 5_000,
  });
}

/**
 * Error toasts persist until dismissed by hand (ToastHost.tsx) and the stack
 * sits over the top bar's Run now / Activate / Share buttons, and after a run
 * the canvas stays in run-view ("Back to editing") where the config drawer is
 * replaced by the run inspector. Clear both before touching the canvas.
 */
export async function prepCanvas(page: Page): Promise<void> {
  const dismiss = page.getByRole("button", { name: /^dismiss/i });
  for (let i = 0; i < 6 && (await dismiss.count()) > 0; i++) {
    await dismiss.first().click({ timeout: 3_000 }).catch(() => {});
  }
  const back = page.getByRole("button", { name: /back to editing/i });
  if (await back.isVisible().catch(() => false)) await back.click().catch(() => {});
}

/** Selects a node on the canvas by its graph id so its config drawer opens. */
export async function selectNode(page: Page, nodeId: string): Promise<void> {
  await prepCanvas(page);
  await page.locator(`.react-flow__node[data-id="${nodeId}"]`).click();
}

// "Waiting on a person" is the console's wording for a run paused at an approval gate.
const RUN_STATUS_RE = /RUN #(\w+)\s+(COMPLETED|FAILED|CANCELLED|AWAITING\w*|WAITING ON A PERSON)/gi;

/**
 * Starts a run through the real Run modal and returns the terminal status of
 * THAT run. Only a run id that was not already on screen counts: the canvas
 * keeps showing the previous run's "RUN #<id> COMPLETED" title until the
 * new one lands, so a naive "wait for COMPLETED" returns instantly with a
 * stale answer on every run after the first.
 */
export async function runWorkflow(page: Page, prompt: string): Promise<string> {
  await prepCanvas(page);
  const known = new Set(((await page.locator("body").innerText()).match(/RUN #\w+/gi) ?? []).map((s) => s.toUpperCase()));
  await page.getByRole("button", { name: /run now/i }).click();
  const box = page.getByPlaceholder(/e\.g\. a sample inbound message/i);
  await expect(box, "Run modal never opened").toBeVisible({ timeout: 15_000 });
  await box.fill(prompt);
  // Exact name: after the first run the inspector also shows a "■ Stop run"
  // button, and /run$/ matched both (strict-mode violation, reproduced live).
  await page.getByRole("button", { name: "▶ Run", exact: true }).click();

  let terminal = "";
  await expect
    .poll(
      async () => {
        const text = await page.locator("body").innerText();
        for (const m of text.matchAll(RUN_STATUS_RE)) {
          if (!known.has(`RUN #${m[1]}`.toUpperCase())) {
            terminal = m[2].toUpperCase().startsWith("WAITING") ? "AWAITING_APPROVAL" : m[2].toUpperCase();
            return terminal;
          }
        }
        return "";
      },
      { message: "a NEW run never reached a terminal state", timeout: 90_000, intervals: [500, 1000] }
    )
    .not.toBe("");
  await showRunResults(page);
  return terminal;
}

/** Best-effort cleanup so repeated runs don't pile up workflows in the tenant. */
export async function deleteWorkflow(page: Page, id: string): Promise<void> {
  try {
    // Reuse the headers captured when the graph was PUT: re-capturing
    // navigates the page to /workflows, which overwrote the end-of-test
    // failure screenshot with an unrelated list page (reproduced live).
    // Fall back to a fresh capture only if the cached token has expired.
    let res = cachedApiHeaders
      ? await page.request.fetch(`${API_URL}/api/workflows/${id}`, { method: "DELETE", headers: cachedApiHeaders })
      : null;
    if (!res || res.status() === 401 || res.status() === 403) {
      const headers = await captureApiHeaders(page);
      res = await page.request.fetch(`${API_URL}/api/workflows/${id}`, { method: "DELETE", headers });
    }
  } catch {
    // Cleanup must never mask the assertion that actually failed.
  }
}
