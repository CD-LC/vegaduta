import { apiFetch, apiFetchOnce, friendlyStatusFallback, toJson, toJsonQuietOn, jsonBody } from "./core";
import type { LlmRequestSnapshot } from "../../components/agents/RequestInspector";
import type {
  WorkflowResponse,
  WorkflowRunResponse,
  WorkflowScheduleResponse,
  WorkflowValidationResult,
  GenerateGraphResponse,
  WorkflowDiagramResponse,
  NodeTriggerChannel,
  NodeWebhookSignature,
  NodePollTrigger,
  NodePollTriggerInput,
} from "./types";
import { testWorkflowNode } from "../../components/workflow/stepTest";
import { filenameFromContentDisposition } from "../contentDisposition";
import { pagedQuery, toPaged } from "./paged";

// Canvas Phase 3/4 endpoints (lint, eval summary, node test) ship ahead of
// the core that serves them, so their wrappers never throw and never toast:
// `unsupported` = the route is missing (older core) and the UI hides the
// feature for the session; a plain failure is retried on the next trigger.
// Optional mirror fields: without strictNullChecks, `r.ok` does not narrow the union.
export type FeatureResult<T> =
  { ok: true; data: T; unsupported?: undefined } | { ok: false; unsupported: boolean; data?: undefined };

const MISSING_ROUTE = new Set([404, 405, 501]);

async function featureJson<T>(res: Response): Promise<FeatureResult<T>> {
  if (!res.ok) return { ok: false, unsupported: MISSING_ROUTE.has(res.status) };
  try {
    return { ok: true, data: (await res.json()) as T };
  } catch {
    // A 200 that is not JSON is a proxy/SPA fallback page, not this API.
    return { ok: false, unsupported: true };
  }
}

export type EvalVerdict = "PASSED" | "FAILED" | "INCONCLUSIVE";

export type WorkflowEvalNodeStats = {
  runs: number;
  visits?: number;
  successRate: number | null;
  errorRate?: number | null;
  p50DurationMs: number | null;
  avgCostUsd: number | null;
  lastStatus?: string | null;
  evalScore?: number | null;
  evalVerdict?: EvalVerdict | null;
  evalRunAt?: string | null;
};

export type WorkflowEvalSummary = {
  workflow: {
    runs: number;
    runs30d?: number;
    days?: number;
    // Core sends a boolean: true when the window held more runs than it scanned.
    sampled?: boolean | number;
    successRate: number | null;
    successRate30d?: number | null;
    passRate: number | null;
    p50DurationMs: number | null;
    avgCostUsd: number | null;
    lastRunAt?: string | null;
    lastRunStatus?: string | null;
  } | null;
  nodes: Record<string, WorkflowEvalNodeStats>;
  generatedAt?: string;
};

export const workflowsApi = {
  // POST /workflows/{id}/lint - `graph` omitted lints the saved graph. Sent via
  // apiFetchOnce: a lint is a read, and apiFetch's write path would flush the
  // whole GET cache on every debounced edit. Raw JSON; parse with lintMerge.parseLintResponse.
  lintWorkflow: async (
    workflowId: string,
    graph?: { nodes: unknown[]; edges: unknown[] }
  ): Promise<FeatureResult<unknown>> => {
    try {
      const res = await apiFetchOnce(`/api/workflows/${workflowId}/lint`, {
        method: "POST",
        ...jsonBody(graph ? { graph } : {}),
      });
      return featureJson<unknown>(res);
    } catch {
      return { ok: false, unsupported: false };
    }
  },
  // GET /workflows/{id}/eval-summary - per-node success/latency/cost/eval badges.
  getWorkflowEvalSummary: async (workflowId: string, days = 30): Promise<FeatureResult<WorkflowEvalSummary>> => {
    try {
      const res = await apiFetch(`/api/workflows/${workflowId}/eval-summary?days=${days}`, { skipGetCache: true });
      return featureJson<WorkflowEvalSummary>(res);
    } catch {
      return { ok: false, unsupported: false };
    }
  },
  // POST /workflows/{id}/nodes/{nodeId}/test - single source in stepTest.ts
  // (it owns the 404-vs-missing-route classification the panel relies on).
  testWorkflowNode,
  // Interactive Workflow Designer (Phase 1) - WorkflowController.java.
  // graph is the literal {nodes, edges} shape @xyflow/react's canvas
  // produces/consumes, round-tripped with zero translation.
  listWorkflows: () => apiFetch(`/api/workflows`).then(toJson<WorkflowResponse[]>),
  createWorkflow: (payload: object) =>
    apiFetch(`/api/workflows`, {
      method: "POST",
      ...jsonBody(payload),
    }).then(toJson<WorkflowResponse>),
  // Same create call for the "Describe it, automate it" composer (nlflow W3),
  // quiet on 403: at the plan's workflow cap the composer shows the server's
  // message with an inline upgrade affordance, so no global toast on top.
  createWorkflowQuietOnCap: (payload: object) =>
    apiFetch(`/api/workflows`, {
      method: "POST",
      ...jsonBody(payload),
    }).then(toJsonQuietOn<WorkflowResponse>([403])),
  getWorkflow: (workflowId: string) => apiFetch(`/api/workflows/${workflowId}`).then(toJson<WorkflowResponse>),
  updateWorkflow: (workflowId: string, payload: object) =>
    apiFetch(`/api/workflows/${workflowId}`, {
      method: "PUT",
      ...jsonBody(payload),
    }).then(toJson<WorkflowResponse>),
  deleteWorkflow: (workflowId: string) => apiFetch(`/api/workflows/${workflowId}`, { method: "DELETE" }).then(toJson),
  // Workflow Intelligence - mirrors the five Agent Intelligence PATCH
  // endpoints above (setAgentReasoningDepth/setAgentTuningMode/
  // setAgentCostRouting/etc.), pointed at WorkflowController.java's five
  // per-workflow tuning PATCH endpoints instead (lines 326-371). Every one
  // returns the full updated WorkflowResponse, so callers just
  // setWorkflow(response) - no separate refetch needed, same pattern the
  // agent equivalents use.
  setWorkflowReasoningDepth: (workflowId: string, reasoningDepth: string) =>
    apiFetch(`/api/workflows/${workflowId}/reasoning-depth`, {
      method: "PATCH",
      ...jsonBody({ reasoningDepth }),
    }).then(toJson<WorkflowResponse>),
  setWorkflowTuningMode: (workflowId: string, tuningMode: string) =>
    apiFetch(`/api/workflows/${workflowId}/tuning-mode`, {
      method: "PATCH",
      ...jsonBody({ tuningMode }),
    }).then(toJson<WorkflowResponse>),
  setWorkflowStepCaching: (workflowId: string, enabled: boolean) =>
    apiFetch(`/api/workflows/${workflowId}/step-caching`, {
      method: "PATCH",
      ...jsonBody({ enabled }),
    }).then(toJson<WorkflowResponse>),
  // economyProviderName: null clears the economy model, same "explicit
  // clear" shape as setAgentCostRouting above.
  setWorkflowCostRouting: (workflowId: string, enabled: boolean, economyProviderName: string, economyModel: string) =>
    apiFetch(`/api/workflows/${workflowId}/cost-routing`, {
      method: "PATCH",
      ...jsonBody({ enabled, economyProviderName, economyModel }),
    }).then(toJson<WorkflowResponse>),
  setWorkflowExecutionLimits: (
    workflowId: string,
    maxStepsPerRun: number,
    stepTimeoutSeconds: number,
    maxRetryAttempts: number
  ) =>
    apiFetch(`/api/workflows/${workflowId}/execution-limits`, {
      method: "PATCH",
      ...jsonBody({ maxStepsPerRun, stepTimeoutSeconds, maxRetryAttempts }),
    }).then(toJson<WorkflowResponse>),
  // Subscription Plan & GTM Strategy doc, Section 4 - a FREE tenant's only
  // way to change a locked (24h+) workflow: duplicate it into a fresh,
  // editable DRAFT copy (triggers are not copied - see WorkflowService's
  // javadoc). Available to every plan.
  duplicateWorkflow: (workflowId: string) =>
    apiFetch(`/api/workflows/${workflowId}/duplicate`, { method: "POST" }).then(toJson<WorkflowResponse>),
  // Structural governance check (OrchestratorService#validate) - does not run anything.
  validateWorkflow: (workflowId: string) =>
    apiFetch(`/api/workflows/${workflowId}/validate`, { method: "POST" }).then(toJson<WorkflowValidationResult>),
  // AI workflow generator (#392) - one instruction in, a full {graph, notes}
  // out. workflowId isn't read server-side (this never persists anything -
  // see WorkflowGraphGeneratorService's javadoc), just kept in the URL for
  // consistency with every other nested workflow-designer action. A
  // structurally invalid generation rejects with the same {errors} shape
  // Validate Workflow already produces - apiFetch already attaches that to
  // err.errors, so the canvas's existing validationErrors modal handles it
  // with no new error-handling path.
  // currentGraph (nlflow W4): sent only for "change this workflow" - core then
  // edits it instead of drafting from scratch and still answers with the
  // COMPLETE graph. Omitted (not null) for a fresh draft / "Start over", so
  // the body is byte-identical to the old call against an older core.
  generateWorkflowGraph: (
    workflowId: string,
    instruction: string,
    currentGraph?: { nodes: unknown[]; edges: unknown[] } | null
  ) =>
    apiFetch(`/api/workflows/${workflowId}/generate-graph`, {
      method: "POST",
      ...jsonBody(currentGraph && currentGraph.nodes.length > 0 ? { instruction, currentGraph } : { instruction }),
    }).then(toJson<GenerateGraphResponse>),
  // The canvas's "Play" button. clientRunId (real-time log viewer, #393) is
  // optional - when supplied, the caller can start polling
  // getWorkflowRun(workflowId, clientRunId) immediately, concurrently with
  // this still-open call, and see the trace grow live instead of only
  // getting anything back once the whole run finishes.
  // background: true (2026-08-17) makes the POST return as soon as the run
  // row exists (status RUNNING) with execution continuing server-side - the
  // caller then polls getWorkflowRun to a terminal status. This is what
  // keeps multi-minute runs from dying at Cloudflare's ~100s request
  // ceiling with a 524 while the run is actually still going.
  // mode: "draft" (canvas Run only) makes the engine honour data pins; absent
  // = production, which never does. Every other caller leaves it out.
  // simulateSends (nlflow W7): a draft run in which outgoing emails, messages
  // and non-GET http calls are logged as "DRY RUN - would ..." instead of
  // dispatched. Core only accepts it with mode "draft" (400 otherwise, 409
  // when the server has it switched off - nothing runs in either case), so it
  // is dropped here unless the mode is draft.
  runWorkflow: (
    workflowId: string,
    input: unknown,
    requestedBy: string,
    clientRunId: string,
    background?: boolean,
    mode?: "draft",
    simulateSends?: boolean
  ) =>
    apiFetch(`/api/workflows/${workflowId}/run`, {
      method: "POST",
      ...jsonBody({
        input: input || undefined,
        requestedBy: requestedBy || undefined,
        clientRunId: clientRunId || undefined,
        background: background || undefined,
        mode: mode || undefined,
        simulateSends: (mode === "draft" && simulateSends) || undefined,
      }),
    }).then(toJson<WorkflowRunResponse>),
  listWorkflowRuns: (workflowId: string) =>
    apiFetch(`/api/workflows/${workflowId}/runs`).then(toJson<WorkflowRunResponse[]>),
  // One page of run history, newest first. The server caps each response, so the runs page and
  // the canvas History tab page with offset and read X-Total-Count / X-Has-More ("Load older").
  // skipGetCache on purpose: runs gain rows and live statuses while the page is open.
  listWorkflowRunsPage: (workflowId: string, offset = 0, limit = 100) =>
    apiFetch(`/api/workflows/${workflowId}/runs?${pagedQuery(new URLSearchParams(), offset, limit)}`, {
      skipGetCache: true,
    }).then((res) => toPaged<WorkflowRunResponse>(res, offset, limit)),
  // Canvas flight log: rows gain new runs and live statuses while the canvas
  // is open, so a 15s cached list would hide the run that was just started.
  // Each row is a full WorkflowRunResponse (trace included).
  listWorkflowRunsFresh: (workflowId: string) =>
    apiFetch(`/api/workflows/${workflowId}/runs`, { skipGetCache: true }).then(toJson<WorkflowRunResponse[]>),
  // skipGetCache: a run's status/trace mutates continuously while it executes
  // (WorkflowExecutionEngine rewrites traceJson after every node), and both
  // live readers of this endpoint - the canvas run inspector's 1200ms poll
  // and the run detail page's identical poll - only work if each tick hits
  // the network. Through the default GET cache they each replayed the same
  // buffered snapshot for the whole 15s TTL, which is what made the
  // step-by-step view appear frozen until a manual page reload.
  getWorkflowRun: (workflowId: string, runId: string) =>
    apiFetch(`/api/workflows/${workflowId}/runs/${runId}`, { skipGetCache: true }).then(toJson<WorkflowRunResponse>),
  // What the run's agent/supervisor nodes actually sent to the model (final
  // prompt, options, tools). EDITOR-gated server-side - a 403 is an entitlement
  // state the inspector words itself, so it is thrown quietly (err.status).
  // from/to (ISO instants) narrow to one node visit's time window.
  getWorkflowRunLlmRequests: (workflowId: string, runId: string, from?: string, to?: string) => {
    const q = new URLSearchParams();
    if (from) q.set("from", from);
    if (to) q.set("to", to);
    const qs = q.toString();
    return apiFetch(`/api/workflows/${workflowId}/runs/${runId}/llm-requests${qs ? `?${qs}` : ""}`, {
      skipGetCache: true,
    }).then(toJsonQuietOn<LlmRequestSnapshot[]>([403, 404]));
  },
  // Stop Run - a best-effort cancellation signal, not a guaranteed instant
  // stop (checked only between node executions server-side - see
  // WorkflowService#cancelRun's javadoc). Returns the run's current status;
  // cancelling a run that isn't RUNNING is a no-op, not an error.
  cancelWorkflowRun: (workflowId: string, runId: string) =>
    apiFetch(`/api/workflows/${workflowId}/runs/${runId}/cancel`, { method: "POST" }).then(toJson<WorkflowRunResponse>),
  // Pause Run - a best-effort park signal in the same family as cancel
  // above: observed between node executions server-side, not a guaranteed
  // instant freeze. Returns the run's current status; pausing a run that
  // isn't RUNNING is a no-op, not an error.
  pauseWorkflowRun: (workflowId: string, runId: string) =>
    apiFetch(`/api/workflows/${workflowId}/runs/${runId}/pause`, { method: "POST" }).then(toJson<WorkflowRunResponse>),
  // Resume Run - a PAUSED run flips back to RUNNING and continues from the
  // node it parked on. Same "returns the run's current status" contract.
  // quietOn: statuses the caller reports inline instead of via the global toast.
  resumeWorkflowRun: (workflowId: string, runId: string, quietOn?: number[]) =>
    apiFetch(`/api/workflows/${workflowId}/runs/${runId}/resume`, { method: "POST" }).then(
      quietOn ? toJsonQuietOn<WorkflowRunResponse>(quietOn) : toJson<WorkflowRunResponse>
    ),
  // Workflow schedule trigger (#166) - the other half of "how do customers
  // trigger it": fires automatically forever, no external caller needed.
  // cron is a plain 5-field cron string built client-side from a simple
  // frequency preset (see WorkflowCanvas.js) - the person configuring it
  // never types cron syntax themselves.
  getWorkflowSchedule: (workflowId: string) =>
    apiFetch(`/api/workflows/${workflowId}/schedule`).then(toJson<WorkflowScheduleResponse>),
  enableWorkflowSchedule: (workflowId: string, cron: string) =>
    apiFetch(`/api/workflows/${workflowId}/schedule`, { method: "PUT", ...jsonBody({ cron }) }).then(
      toJson<WorkflowScheduleResponse>
    ),
  disableWorkflowSchedule: (workflowId: string) =>
    apiFetch(`/api/workflows/${workflowId}/schedule`, { method: "DELETE" }).then(toJson<WorkflowScheduleResponse>),
  // Multi-trigger graphs - per-node equivalents of the workflow-wide
  // trigger-channel/webhook endpoints that used to sit above (removed
  // server-side when a single workflow gained the ability to hold more
  // than one Trigger node, each independently bound to its own inbound
  // channel). Same base paths with `/nodes/{nodeId}` inserted in the same
  // relative position, same request/response shape (the PUT/GET response
  // now also carries `nodeId`).
  // getNodeWebhook mirrors the (removed) workflow-wide webhook GET at the
  // same relative path - needed to hydrate a re-opened Trigger node's
  // drawer with its already-enabled
  // webhook (enabled/token/oidcTriggerPath aren't part of the saved graph,
  // so the PUT/GET trigger-channel response alone can't supply them).
  getNodeTriggerChannel: (workflowId: string, nodeId: string) =>
    apiFetch(`/api/workflows/${workflowId}/nodes/${nodeId}/trigger-channel`).then(toJson<NodeTriggerChannel>),
  setNodeTriggerChannel: (workflowId: string, nodeId: string, channel: string, webhookAuthMode: string) =>
    apiFetch(`/api/workflows/${workflowId}/nodes/${nodeId}/trigger-channel`, {
      method: "PUT",
      ...jsonBody({ channel, webhookAuthMode: webhookAuthMode || undefined }),
    }).then(toJson<NodeTriggerChannel>),
  getNodeWebhook: (workflowId: string, nodeId: string) =>
    apiFetch(`/api/workflows/${workflowId}/nodes/${nodeId}/webhook`).then(toJson),
  enableNodeWebhook: (workflowId: string, nodeId: string) =>
    apiFetch(`/api/workflows/${workflowId}/nodes/${nodeId}/webhook`, { method: "POST" }).then(toJson),
  disableNodeWebhook: (workflowId: string, nodeId: string) =>
    apiFetch(`/api/workflows/${workflowId}/nodes/${nodeId}/webhook`, { method: "DELETE" }).then(toJson),
  // Webhook sender-signature verification (V482) - GITHUB/STRIPE/SLACK/HMAC_SHA256
  // presets verified over the raw request body. The secret is write-only:
  // GET returns mode + configured, never the value.
  getNodeWebhookSignature: (workflowId: string, nodeId: string) =>
    apiFetch(`/api/workflows/${workflowId}/nodes/${nodeId}/webhook/signature`).then(toJson<NodeWebhookSignature>),
  setNodeWebhookSignature: (workflowId: string, nodeId: string, mode: string, secret: string) =>
    apiFetch(`/api/workflows/${workflowId}/nodes/${nodeId}/webhook/signature`, {
      method: "PUT",
      ...jsonBody({ mode, secret }),
    }).then(toJson<NodeWebhookSignature>),
  clearNodeWebhookSignature: (workflowId: string, nodeId: string) =>
    apiFetch(`/api/workflows/${workflowId}/nodes/${nodeId}/webhook/signature`, { method: "DELETE" }).then(
      toJson<NodeWebhookSignature>
    ),
  // HTTP poll trigger (V482) - the trigger node polls a URL on an interval
  // and fires a run per new item (dedup on idPath). PUT (re)configures +
  // enables; DELETE removes the binding entirely.
  getNodePollTrigger: (workflowId: string, nodeId: string) =>
    apiFetch(`/api/workflows/${workflowId}/nodes/${nodeId}/poll-trigger`).then(toJson<NodePollTrigger>),
  setNodePollTrigger: (workflowId: string, nodeId: string, input: NodePollTriggerInput) =>
    apiFetch(`/api/workflows/${workflowId}/nodes/${nodeId}/poll-trigger`, {
      method: "PUT",
      ...jsonBody(input),
    }).then(toJson<NodePollTrigger>),
  deleteNodePollTrigger: (workflowId: string, nodeId: string) =>
    apiFetch(`/api/workflows/${workflowId}/nodes/${nodeId}/poll-trigger`, { method: "DELETE" }).then(
      toJson<NodePollTrigger>
    ),
  // Output node "local file" destination (#152) - a plain <a href> can't
  // carry the bearer token apiFetch attaches, so this fetches the bytes
  // through the normal authenticated path and hands back a Blob + filename
  // for the caller to turn into a browser download via a temporary
  // object-URL anchor (see WorkflowCanvas.js's downloadRunOutput).
  downloadWorkflowRunOutput: async (workflowId: string, runId: string) => {
    const res = await apiFetch(`/api/workflows/${workflowId}/runs/${runId}/download`);
    if (!res.ok) {
      throw new Error(res.status === 404 ? "This run has no file output." : friendlyStatusFallback(res.status));
    }
    const filename = filenameFromContentDisposition(res.headers.get("Content-Disposition"), "workflow-output.txt");
    const blob = await res.blob();
    return { blob, filename };
  },

  // Workflow diagram export - "generate a diagram of the existing workflow
  // including agents, tools and all configs". Mermaid/PlantUML come back as
  // plain source text (rendered/copyable client-side); the PDF variant is a
  // real boxes-and-arrows document, fetched as a Blob the same authenticated-
  // download-anchor pattern as downloadWorkflowRunOutput above.
  getWorkflowDiagram: (workflowId: string, format: "mermaid" | "plantuml" = "mermaid") =>
    apiFetch(`/api/workflows/${workflowId}/diagram?format=${format}`).then(toJson<WorkflowDiagramResponse>),
  downloadWorkflowDiagramPdf: async (workflowId: string) => {
    const res = await apiFetch(`/api/workflows/${workflowId}/diagram/pdf`);
    if (!res.ok) {
      throw new Error(friendlyStatusFallback(res.status));
    }
    const filename = filenameFromContentDisposition(res.headers.get("Content-Disposition"), "workflow-diagram.pdf");
    const blob = await res.blob();
    return { blob, filename };
  },
};

// ---------------------------------------------------------------------------
// Canvas comment threads - WorkflowCommentController
// (/api/workflows/{id}/comments). Standalone exports (not spread into `api`)
// so they can be imported directly by the comment layer. None of them throw:
// the thread card reports failures inline, so the statuses it expects
// (400 validation, 403 not-yours, 404 gone, 409, 410 deleted) raise no global
// toast; anything else still goes through toJson's toast. `unsupported`
// = the route is missing on this core, the UI hides comments quietly.
// (Import kept here, not at the top: this block is an append-only addition.)
import {
  normalizeComment,
  normalizeThreads,
  type CommentView,
  type CreateCommentInput,
  type UpdateCommentInput,
} from "../../components/workflow/comments";

export type CommentResult<T> =
  | { ok: true; data: T; unsupported?: undefined; status?: undefined; message?: undefined }
  | { ok: false; unsupported: boolean; status: number | null; message: string; data?: undefined };

const COMMENT_QUIET = [400, 403, 404, 405, 409, 410, 413, 422, 501];
const COMMENT_MISSING_ROUTE = new Set([405, 501]);

function commentsPath(workflowId: string, commentId?: string): string {
  const base = `/api/workflows/${encodeURIComponent(workflowId)}/comments`;
  return commentId ? `${base}/${encodeURIComponent(commentId)}` : base;
}

async function commentCall<T>(
  run: () => Promise<Response>,
  parse: (raw: unknown) => T | null
): Promise<CommentResult<T>> {
  let res: Response;
  try {
    res = await run();
  } catch {
    return {
      ok: false,
      unsupported: false,
      status: null,
      message: "Couldn't reach the server - check your connection and try again.",
    };
  }
  try {
    const raw: unknown = await toJsonQuietOn(COMMENT_QUIET)(res);
    const data = parse(raw);
    // A 2xx that is not the expected shape is a proxy/SPA fallback, not this API.
    if (data === null)
      return { ok: false, unsupported: true, status: res.status, message: "Comments are unavailable." };
    return { ok: true, data };
  } catch (err) {
    const status =
      typeof (err as { status?: unknown })?.status === "number" ? (err as { status: number }).status : res.status;
    const message = err instanceof Error && err.message ? err.message : friendlyStatusFallback(status);
    return { ok: false, unsupported: COMMENT_MISSING_ROUTE.has(status), status, message };
  }
}

/** GET - every thread (roots in creation order, replies nested). 404/405 => unsupported. */
export async function listWorkflowComments(workflowId: string): Promise<FeatureResult<CommentView[]>> {
  try {
    const res = await apiFetch(commentsPath(workflowId), { skipGetCache: true });
    const r = await featureJson<unknown>(res);
    if (!r.ok) return r.unsupported ? { ok: false, unsupported: true } : { ok: false, unsupported: false };
    if (!Array.isArray(r.data)) return { ok: false, unsupported: true };
    return { ok: true, data: normalizeThreads(r.data) };
  } catch {
    return { ok: false, unsupported: false };
  }
}

/** POST - a root needs nodeId OR both x,y (flow coords); a reply needs parentId. */
export function createWorkflowComment(
  workflowId: string,
  input: CreateCommentInput
): Promise<CommentResult<CommentView>> {
  return commentCall(
    () => apiFetch(commentsPath(workflowId), { method: "POST", ...jsonBody(input) }),
    normalizeComment
  );
}

/** PATCH - body/mentions (author only) and/or resolved (thread author or editor; applies to the root). */
export function updateWorkflowComment(
  workflowId: string,
  commentId: string,
  input: UpdateCommentInput
): Promise<CommentResult<CommentView>> {
  return commentCall(
    () => apiFetch(commentsPath(workflowId, commentId), { method: "PATCH", ...jsonBody(input) }),
    normalizeComment
  );
}

/** DELETE - author only; 204. A root with live replies is blanked server-side, not removed. */
export function deleteWorkflowComment(workflowId: string, commentId: string): Promise<CommentResult<true>> {
  return commentCall(
    () => apiFetch(commentsPath(workflowId, commentId), { method: "DELETE" }),
    () => true
  );
}
