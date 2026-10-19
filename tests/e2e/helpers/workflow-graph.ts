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
  try {
    await expect(page.getByRole("button", { name: /run now/i }), "new workflow's canvas never loaded").toBeVisible({
      timeout: 30_000,
    });
  } catch (e) {
    // SE-78: the shell already exists server-side but the caller never receives the id (this throws), so its
    // finally-block cleanup cannot run. Delete it here instead of leaving a stray workflow behind.
    await deleteWorkflow(page, id);
    throw e;
  }
  return id;
}

/**
 * Builds the auth headers the app itself sends to the API.
 *
 * Used to sniff a real /api/* request off the wire (waitForRequest +
 * goto("/workflows")) - until the perf(api) SWR GET cache shipped, after
 * which a warmed session can render the workflows list WITHOUT firing any
 * fetch, and the sniff timed out intermittently (observed live in verify5:
 * Trigger-Workflow and HTTP-node tests died waiting for a request that
 * never needed to happen). Tokens are memory-only in the console now
 * (web/app/lib/auth.ts), so the bearer is the newest Authorization header
 * this page's own requests carried (prod-api.ts trackBearerToken, registered
 * by signInToConsole). The API authorizes on the bearer alone (verified live:
 * GET /api/agents + share POST/DELETE with only this header). Resolved on
 * every call and never cached: the token is refreshed every few minutes, and
 * a module-level cache would also leak one test's token into the next page.
 */
async function captureApiHeaders(page: Page): Promise<Record<string, string>> {
  const token = await getBearerToken(page);
  return { authorization: `Bearer ${token}`, "content-type": "application/json" };
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
  const headers = await captureApiHeaders(page);
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
    // Fresh headers from the page's newest token (no navigation, so the end-of-test failure screenshot is not
    // overwritten): a failed test must still be able to clean up, and the token may have been refreshed since
    // the graph was PUT.
    const headers = await captureApiHeaders(page);
    await page.request.fetch(`${API_URL}/api/workflows/${id}`, { method: "DELETE", headers });
  } catch {
    // Cleanup must never mask the assertion that actually failed.
  }
}

/**
 * Every text value of the LATEST run of a workflow, read from the run API (`GET /api/workflows/{id}/runs`).
 *
 * Step output is no longer rendered inline on the canvas (the run inspector shows it per step, on demand), so a
 * `page.getByText(<output>)` after runWorkflow() finds nothing even though the run produced it. The run row's
 * `trace` carries, per step: `summary`, `inputJson`, `outputJson` and `logLines`; those plus the run's
 * `errorMessage` are returned here as plain strings. Newest run first by startedAt.
 */
export async function latestRunTexts(page: Page, workflowId: string): Promise<string[]> {
  const { status, text } = await apiCall(page, "GET", `/api/workflows/${workflowId}/runs`);
  expect(status, `GET /api/workflows/${workflowId}/runs`).toBe(200);
  const runs = JSON.parse(text) as Array<{
    startedAt?: string;
    errorMessage?: string | null;
    trace?: Array<{ summary?: string | null; inputJson?: string | null; outputJson?: string | null; logLines?: string[] | null }> | null;
  }>;
  const latest = [...runs].sort((a, b) => String(b.startedAt ?? "").localeCompare(String(a.startedAt ?? "")))[0];
  if (!latest) return [];
  const out: string[] = [];
  if (latest.errorMessage) out.push(latest.errorMessage);
  for (const step of latest.trace ?? []) {
    for (const v of [step.summary, step.inputJson, step.outputJson, ...(step.logLines ?? [])]) {
      if (typeof v === "string" && v.length > 0) out.push(v);
    }
  }
  return out;
}

function runTextMatches(texts: string[], matcher: string | RegExp, exact: boolean): boolean {
  if (matcher instanceof RegExp) return texts.some((t) => matcher.test(t));
  return texts.some((t) => (exact ? t.trim() === matcher : t.includes(matcher)));
}

/**
 * Polls the latest run until one of its step texts matches. A string matches a whole value (`exact`, default) or a
 * substring (`exact: false`); a RegExp is tested against each value. Replaces `expect(page.getByText(...))` on run output.
 */
export async function expectRunOutput(
  page: Page,
  workflowId: string,
  matcher: string | RegExp,
  message: string,
  opts: { exact?: boolean; timeout?: number } = {}
): Promise<void> {
  await expect
    .poll(async () => runTextMatches(await latestRunTexts(page, workflowId), matcher, opts.exact ?? true), {
      message,
      timeout: opts.timeout ?? 15_000,
      intervals: [500, 1000],
    })
    .toBe(true);
}

/** Asserts that NO step text of the latest run contains `substring` (used to prove the other branch did not run). */
export async function expectNoRunOutput(page: Page, workflowId: string, substring: string, message: string): Promise<void> {
  const texts = await latestRunTexts(page, workflowId);
  expect(texts.filter((t) => t.includes(substring)), message).toEqual([]);
}
