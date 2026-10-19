import { test, expect, type Page } from "@playwright/test";
import { requireUserCredentials, requireMutationOptIn, API_URL } from "./prod-env";
import { signInToConsole } from "./prod-session";
import { getBearerToken, authHeaders } from "./prod-api";
import type { GraphNode, GraphEdge } from "./workflow-graph";

/**
 * Shared fixture for the control-sweep specs (tests-prod/control-sweep-live*.spec.ts):
 * an authenticated REST helper, agent/workflow factories that record what they
 * create, and the beforeEach/afterEach hooks that sign in and clean up.
 * Call setupControlSweep() once inside a test.describe block.
 */

export type Res = { status: number; text: string; json: any };

export const NO_LEAK = /Exception|\bat com\.|\bat java\.|stack ?trace|NullPointer|\[object Object\]|@@AGENT_/;

export function setupControlSweep() {
  const state: { token: string; template: { providerName: string; model?: string } | null } = { token: "", template: null };
  const agents: string[] = [];
  const workflows: string[] = [];

  async function api(page: Page, method: string, path: string, data?: unknown): Promise<Res> {
    const res = await page.request.fetch(`${API_URL}${path}`, {
      method,
      headers: { ...authHeaders(state.token), "Content-Type": "application/json" },
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

  async function newAgent(page: Page, prompt = "You are a terse assistant. Reply in one short sentence.", extra: Record<string, unknown> = {}) {
    const r = await api(page, "POST", "/api/agents", {
      name: `E2E Sweep ${Date.now()}-${Math.floor(Math.random() * 1e4)}`,
      systemPrompt: prompt,
      providerName: state.template!.providerName,
      model: state.template!.model,
      ...extra,
    });
    expect(r.status, `agent create failed: ${r.text.slice(0, 300)}`).toBe(201);
    agents.push(r.json.id);
    return r.json as { id: string; name: string; [k: string]: any };
  }
  const getAgent = async (page: Page, id: string) => (await api(page, "GET", `/api/agents/${id}`)).json;

  async function newWorkflow(page: Page, label: string, nodes: GraphNode[] = [], edges: GraphEdge[] = []) {
    const name = `E2E Sweep ${label} ${Date.now()}-${Math.floor(Math.random() * 1e4)}`;
    const c = await api(page, "POST", "/api/workflows", { name, description: "", graph: { nodes: [], edges: [] } });
    expect(c.status, `workflow create failed: ${c.text.slice(0, 200)}`).toBeLessThan(300);
    const id = c.json.id as string;
    workflows.push(id);
    if (nodes.length) await putGraph(page, id, name, nodes, edges);
    return { id, name };
  }

  async function putGraph(page: Page, id: string, name: string, nodes: GraphNode[], edges: GraphEdge[], status = "DRAFT") {
    const r = await api(page, "PUT", `/api/workflows/${id}`, {
      name,
      description: "",
      graph: {
        nodes: nodes.map((n, i) => ({
          id: n.id,
          type: n.type,
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
      status,
    });
    expect(r.status, `PUT graph failed: ${r.text.slice(0, 300)}`).toBeLessThan(300);
    return r;
  }

  test.beforeEach(async ({ page }, testInfo) => {
    requireUserCredentials(testInfo);
    requireMutationOptIn(testInfo);
    testInfo.skip(testInfo.project.name !== "chromium", "writes real data - one project only");
    await signInToConsole(page);
    await page.goto("/agents", { waitUntil: "domcontentloaded" });
    state.token = await getBearerToken(page);
    const list = await api(page, "GET", "/api/agents");
    // Prefer an existing agent's provider/model; a fresh throwaway tenant has none, so fall back to
    // the platform-hosted pool (same default the other live specs use) - no credentials involved.
    state.template =
      Array.isArray(list.json) && list.json.length
        ? list.json[0]
        : { providerName: "PLATFORM_HOSTED", model: "gemini-2.5-flash" };
  });

  test.afterEach(async ({ page }) => {
    while (agents.length) {
      const id = agents.pop()!;
      await api(page, "DELETE", `/api/agents/${id}`).catch(() => {});
    }
    while (workflows.length) {
      const id = workflows.pop()!;
      await api(page, "DELETE", `/api/workflows/${id}`).catch(() => {});
    }
  });

  return {
    api,
    gated,
    newAgent,
    getAgent,
    newWorkflow,
    putGraph,
    agents,
    workflows,
    /** Bearer token captured in beforeEach. */
    get token() {
      return state.token;
    },
    /** Provider/model new agents are created with (set in beforeEach). */
    get template() {
      return state.template;
    },
  };
}
