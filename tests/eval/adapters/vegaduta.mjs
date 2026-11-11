// Adapter for a running VegaDuta stack, through its public REST API only (docs/api/openapi.json).
//
// Configuration (environment only; nothing is hardcoded and no secret lives in the repo):
//   EVAL_BASE_URL            API base, e.g. https://api.example.com        (required)
//   EVAL_TOKEN               Keycloak JWT or a tenant API key (vdak_...)    (required)
//   EVAL_AGENT_ID_QA / _TOOLS / _KB / _APPROVAL     agent ids provisioned per docs/benchmarks/README.md
//   EVAL_WORKFLOW_ID_PIPELINE                       workflow id provisioned per the same doc
//   EVAL_MODEL               free-text label for the model under test (report provenance only)
//   EVAL_TIMEOUT_MS          per-task timeout, default 120000
//
// Task `role` is "agent:<name>" or "workflow:<name>"; it resolves to the env var above.
//
// Endpoints used (all in docs/api/openapi.json):
//   POST /api/agents/{id}/chat (SSE, stepEvents:true)       chat turn + tool-call step events
//   GET  /api/agents/{id}/sessions/{sid}/llm-usage/summary  tokens + cost, platform-reported
//   GET  /api/approvals?status=PENDING, POST /api/approvals/{id}/approve|reject
//   POST /api/workflows/{id}/run, GET /api/workflows/{id}/runs/{runId}   workflow run + node trace
import { parseChatSse, toolCallsFromSteps } from "../lib/sse.mjs";

const TERMINAL = new Set(["COMPLETED", "FAILED", "CANCELLED"]);
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

function need(env, name) {
  const v = env[name];
  if (!v) throw new Error(`${name} is not set (see docs/benchmarks/README.md)`);
  return v;
}

/** Map the platform's session usage summary to the adapter usage shape. Pure. */
export function usageFromSummary(s) {
  if (!s || typeof s !== "object") return undefined;
  return {
    promptTokens: s.promptTokens,
    completionTokens: s.completionTokens,
    totalTokens: s.totalTokens,
    costUsd: s.totalCostUsd,
    unpricedCalls: s.unpricedCalls ?? 0,
  };
}

/** Map a finished workflow run (with node trace) to {text, toolCalls, usage}. Pure. */
export function resultFromWorkflowRun(run) {
  const trace = Array.isArray(run.trace) ? run.trace : [];
  const last = [...trace].reverse().find((n) => n.outputJson || n.summary);
  const text = last ? [last.outputJson, last.summary].filter(Boolean).join("\n") : "";
  const toolCalls = trace.flatMap((n) => (n.toolCalls ?? []).map((c) => ({ tool: c.toolName, args: c.input ?? {} })));
  let prompt = 0;
  let completion = 0;
  let cost = 0;
  let unpriced = 0;
  let any = false;
  for (const n of trace) {
    if (typeof n.tokensIn === "number" || typeof n.tokensOut === "number") {
      any = true;
      prompt += n.tokensIn ?? 0;
      completion += n.tokensOut ?? 0;
      if (typeof n.costUsd === "number") cost += n.costUsd;
      else if ((n.tokensIn ?? 0) + (n.tokensOut ?? 0) > 0) unpriced += 1;
    }
  }
  const usage = any ? { promptTokens: prompt, completionTokens: completion, totalTokens: prompt + completion, costUsd: cost, unpricedCalls: unpriced } : undefined;
  return { text, toolCalls, usage };
}

class Api {
  constructor(base, token, timeoutMs) {
    this.base = base.replace(/\/+$/, "");
    this.token = token;
    this.timeoutMs = timeoutMs;
  }
  async req(method, path, body, accept = "application/json") {
    const res = await fetch(this.base + path, {
      method,
      headers: { Authorization: `Bearer ${this.token}`, Accept: accept, ...(body ? { "Content-Type": "application/json" } : {}) },
      body: body ? JSON.stringify(body) : undefined,
      signal: AbortSignal.timeout(this.timeoutMs),
    });
    const text = await res.text();
    if (!res.ok) throw new Error(`${method} ${path} -> ${res.status}: ${text.slice(0, 200)}`);
    return text;
  }
  async json(method, path, body) {
    const t = await this.req(method, path, body);
    return t ? JSON.parse(t) : null;
  }
}

async function pendingApprovals(api) {
  const list = await api.json("GET", "/api/approvals?status=PENDING&limit=200");
  return Array.isArray(list) ? list : [];
}

async function runAgentTask(task, ctx, agentId) {
  const sessionId = `eval-${task.id}-${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 8)}`;
  const before = new Set((await pendingApprovals(ctx.api)).map((a) => a.id));
  // Latency is the chat request alone, request to end of stream: the harness's own bookkeeping below
  // (listing and deciding approvals, waiting for the cost ledger) is not the platform's answer time.
  const chatStart = performance.now();
  const body = await ctx.api.req(
    "POST",
    `/api/agents/${agentId}/chat`,
    { sessionId, message: task.input, forceFastDepth: false, stepEvents: true },
    "text/event-stream",
  );
  let latencyMs = performance.now() - chatStart;
  const parsed = parseChatSse(body);
  if (parsed.streamErrors.length) return { error: `stream error: ${parsed.streamErrors[0]}`, text: parsed.text };

  // Approvals raised by this turn: pending ids that were not pending before it. Poll briefly, the
  // request may be persisted just after the stream closes.
  let created = [];
  for (let i = 0; i < 6; i++) {
    created = (await pendingApprovals(ctx.api)).filter((a) => !before.has(a.id));
    if (created.length || !task.guardrail?.approval?.expected) break;
    await sleep(500);
  }
  const approvals = [];
  for (const a of created) {
    let finalStatus = a.status;
    const decision = task.guardrail?.approval?.decision;
    if (decision === "approve" || decision === "reject") {
      const r = await ctx.api.json("POST", `/api/approvals/${a.id}/${decision}`, { decidedBy: "eval-harness", reason: "automated evaluation" });
      finalStatus = r?.status ?? finalStatus;
    }
    approvals.push({ actionType: a.actionType, status: a.status, finalStatus });
  }

  // A decided approval makes the platform run a continuation turn that tells the user how it ended; the
  // frameworks' resume is inside their measured run, so the answer time here is chat + decision +
  // continuation reply (the continuation is the session's second logged model call).
  if (approvals.some((x) => x.finalStatus && x.finalStatus !== "PENDING")) {
    for (let i = 0; i < 30; i++) {
      try {
        const s = await ctx.api.json("GET", `/api/agents/${agentId}/sessions/${encodeURIComponent(sessionId)}/llm-usage/summary`);
        if (s && s.callCount >= 2) break;
      } catch {
        /* retry */
      }
      await sleep(100);
    }
    latencyMs = performance.now() - chatStart;
  }

  // Usage is written by the platform's own cost ledger; it can trail the stream by a moment.
  let usage;
  for (let i = 0; i < 6 && !usage; i++) {
    try {
      const s = await ctx.api.json("GET", `/api/agents/${agentId}/sessions/${encodeURIComponent(sessionId)}/llm-usage/summary`);
      if (s && s.callCount > 0) usage = usageFromSummary(s);
    } catch {
      /* retry */
    }
    if (!usage) await sleep(500);
  }
  return { text: parsed.text, toolCalls: toolCallsFromSteps(parsed.stepEvents), approvals, usage, latencyMs };
}

async function runWorkflowTask(task, ctx, workflowId) {
  let run = await ctx.api.json("POST", `/api/workflows/${workflowId}/run`, { input: task.input, requestedBy: "eval-harness" });
  const deadline = Date.now() + ctx.timeoutMs;
  while (!TERMINAL.has(run.status) && run.status !== "AWAITING_APPROVAL") {
    if (Date.now() > deadline) return { error: `workflow still ${run.status} after ${ctx.timeoutMs} ms` };
    await sleep(1000);
    run = await ctx.api.json("GET", `/api/workflows/${workflowId}/runs/${run.id}`);
  }
  if (run.status === "FAILED" || run.status === "CANCELLED") return { error: `workflow ${run.status}: ${run.errorMessage ?? ""}`.trim() };
  const mapped = resultFromWorkflowRun(run);
  const approvals = run.status === "AWAITING_APPROVAL" ? [{ actionType: "workflow", status: "PENDING", finalStatus: "PENDING" }] : [];
  return { ...mapped, approvals };
}

export default {
  name: "vegaduta",
  async init(env = process.env) {
    const timeoutMs = Number(env.EVAL_TIMEOUT_MS ?? 120000);
    return {
      api: new Api(need(env, "EVAL_BASE_URL"), need(env, "EVAL_TOKEN"), timeoutMs),
      env,
      timeoutMs,
    };
  },
  describe(ctx) {
    return { target: ctx.api.base, model: ctx.env.EVAL_MODEL };
  },
  async runTask(task, ctx) {
    const [kind, name] = String(task.role).split(":");
    const envName = `EVAL_${kind === "workflow" ? "WORKFLOW" : "AGENT"}_ID_${String(name).toUpperCase()}`;
    const id = ctx.env[envName];
    if (!id) return { error: `${envName} is not set` };
    return kind === "workflow" ? runWorkflowTask(task, ctx, id) : runAgentTask(task, ctx, id);
  },
};
