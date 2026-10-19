import { publicApiBase } from "../../lib/env";
// Client for the /try Agent Studio backend (core: /api/demo/studio/**).
//
// Same philosophy as ../try/demoClient.ts, and deliberately colocated with
// the same rules: no app/lib/api.ts (no Keycloak on an anonymous page), no
// toast bus, every call resolves to a typed result and nothing throws. The
// backend speaks JSON {code, message} on refusals and JSON-per-line SSE on
// streams; both are surfaced as content by the components, never as an
// error surface.

const API_BASE = publicApiBase();

export type StudioErrorCode =
  | "not_found"
  | "expired"
  | "read_only"
  | "budget"
  | "agent_cap"
  | "workflow_cap"
  | "locked"
  | "live"
  | "doc_cap"
  | "rate_limited"
  | "cooling_down"
  | "connector_cap"
  | "invalid"
  | "content_blocked"
  | "unavailable";

export type StudioError = { code: StudioErrorCode; message: string };

export type StudioAgent = {
  id: string;
  name: string;
  mission: string;
  reasoningDepth: "FAST" | "CAREFUL" | "RIGOROUS" | "PLANNED";
  autonomy: "SUPERVISED" | "SEMI_AUTONOMOUS" | "FULLY_AUTONOMOUS";
  tools: string[];
  toolsOff: boolean;
  guardrails: string[];
  temperature: number;
  maxTokens: number;
  mine: boolean;
  live: boolean;
  createdBySession: string | null;
  createdAt: string;
  updatedAt: string;
};

export type StudioToolOption = {
  name: string;
  label: string;
  description: string;
  group: "hands" | "memory";
  available: boolean;
};

export type StudioPalette = {
  tools: StudioToolOption[];
  guardrails: { id: string; available: boolean }[];
  reasoningDepths: string[];
  autonomyTiers: string[];
  lockedAutonomyTiers: string[];
  maxAgentsPerSession: number;
  /** Absent on an older backend - the Knowledge tab then explains it is not switched on. */
  documents?: DocumentLimits;
  connectorsEnabled?: boolean;
  /** Guardrails switched on for every sandbox agent and not removable. */
  enforcedGuardrails?: string[];
  missionTimeoutSeconds?: number;
};

export type DocumentLimits = { available: boolean; maxDocs: number; maxBytes: number; extensions: string[] };

export type StudioDocument = {
  id: string;
  name: string;
  sizeBytes: number;
  status: "UPLOADED" | "EXTRACTING" | "EMBEDDING" | "READY" | "FAILED" | string;
  chunks: number | null;
  error: string | null;
};

export type RegistryHit = { name: string; title: string; description: string | null; host: string | null };
export type RegistryPage = {
  results: RegistryHit[];
  nextCursor: string | null;
  unavailable: boolean;
  canConnect: boolean;
};
export type StudioConnector = { server: string; title: string | null; host: string | null; tools: string[] };

export type FairUse = {
  missionsPerMinute: number;
  maxConcurrentRuns: number;
  ipDailyCeiling: number;
  usedToday: number;
  usedLastMinute: number;
  /** Seconds left of a cool-down; 0 = not cooling down. */
  coolingSeconds: number;
  strikesLeft: number;
};

export type StudioStatus = {
  messagesUsed: number;
  /** 0 = no lifetime allowance. */
  messageLimit: number;
  readOnly: boolean;
  expired: boolean;
  agentsMax: number;
  workflowsMax: number;
  unlimited?: boolean;
  /** The address recorded with this sandbox - shown back to the visitor on purpose. */
  clientIp?: string | null;
  missionTimeoutSeconds?: number;
  workflowTimeoutMinutes?: number;
  fairUse?: FairUse;
};

export type AgentSpec = {
  name?: string;
  mission?: string;
  reasoningDepth?: string;
  autonomy?: string;
  tools?: string[];
  guardrails?: string[];
  temperature?: number;
  maxTokens?: number;
};

export type WorkflowStepType = "trigger" | "agent" | "condition" | "approval" | "output";

export type WorkflowStep = {
  id: string;
  type: WorkflowStepType;
  label: string;
  config: Record<string, unknown>;
};

export type WorkflowDefinition = { steps: WorkflowStep[] };

export type StudioWorkflow = {
  id: string;
  name: string;
  definition: WorkflowDefinition;
  mine: boolean;
  createdAt: string;
  updatedAt: string;
};

export type StudioRun = {
  id: string;
  status: "RUNNING" | "AWAITING_APPROVAL" | "COMPLETED" | "STOPPED" | "REJECTED" | "FAILED";
  input: string | null;
  output: string | null;
  trace: TraceEntry[];
  startedAt: string;
  finishedAt: string | null;
};

export type TraceEntry = {
  stepId: string;
  type: WorkflowStepType;
  label: string;
  status: string;
  summary?: string;
  output?: string;
  matched?: boolean;
  ms?: number;
};

/** One JSON line of a studio stream. Mission streams and workflow streams share the vocabulary. */
export type StudioEvent =
  | { type: "budget"; used: number; limit: number; sessionId?: string }
  | { type: "token"; text: string }
  | { type: "run_started"; runId?: string; sessionId?: string; agentId?: string; steps?: number }
  | { type: "plan"; text: string; stepId?: string }
  | { type: "tool_call"; id: number; tool: string; args: string; stepId?: string }
  | { type: "tool_result"; id: number; tool: string; preview: string; ms: number; stepId?: string }
  | { type: "error"; id?: number; tool?: string; message: string; ms?: number; stepId?: string }
  | { type: "guardrail"; tool: string; reason: string; stepId?: string }
  | { type: "heartbeat"; ms?: number; waitingFor?: string; stepId?: string }
  | {
      type: "run_finished";
      ms: number;
      runId?: string;
      status?: string;
      output?: string;
      messagesUsed?: number;
      messageLimit?: number;
      stepId?: string;
    }
  | { type: "handoff"; stepId?: string }
  | { type: "swarm"; stepId?: string }
  | { type: "step_started"; stepId: string; stepType: WorkflowStepType; label: string; index: number }
  | { type: "step_output"; stepId: string; text: string }
  | { type: "step_finished"; stepId: string; status: string; ms: number; summary: string }
  | { type: "step_skipped"; stepId: string; label: string }
  | { type: "condition"; stepId: string; matched: boolean; needle: string; onFalse: string }
  | { type: "approval_required"; stepId: string; runId: string; message: string; preview: string }
  | { type: "approval"; stepId: string; approved: boolean; timedOut?: boolean };

// Flat optional-field shape rather than a discriminated union: this project's
// tsconfig runs with strict:false, and without strictNullChecks TypeScript
// will not narrow `{ok:true}|{ok:false}` from an `if (!r.ok)` guard (see the
// same note on CreateResult in ../try/demoClient.ts).
export type Result<T> = { ok: boolean; value?: T; error?: StudioError };

const KNOWN_CODES = new Set<string>([
  "not_found",
  "expired",
  "read_only",
  "budget",
  "agent_cap",
  "workflow_cap",
  "locked",
  "live",
  "doc_cap",
  "connector_cap",
  "rate_limited",
  "cooling_down",
  "invalid",
  "content_blocked",
]);

function headers(token: string, json = true): Record<string, string> {
  const h: Record<string, string> = { "X-Demo-Token": token };
  if (json) h["Content-Type"] = "application/json";
  return h;
}

function timeoutSignal(ms: number): AbortSignal | undefined {
  try {
    return AbortSignal.timeout(ms);
  } catch {
    return undefined;
  }
}

async function readJson(res: Response): Promise<any> {
  try {
    return await res.json();
  } catch {
    return null;
  }
}

async function call<T>(path: string, token: string, init: RequestInit = {}, timeoutMs = 20_000): Promise<Result<T>> {
  try {
    const res = await fetch(`${API_BASE}/api/demo/studio${path}`, {
      ...init,
      headers: {
        ...headers(token, typeof init.body === "string"),
        ...(init.headers as Record<string, string> | undefined),
      },
      signal: timeoutSignal(timeoutMs),
    });
    if (res.status === 204) return { ok: true, value: undefined as T };
    const body = await readJson(res);
    if (res.ok) return { ok: true, value: body as T };
    const code =
      body && typeof body.code === "string" && KNOWN_CODES.has(body.code)
        ? (body.code as StudioErrorCode)
        : "unavailable";
    const message =
      body && typeof body.message === "string"
        ? body.message
        : "The studio couldn't do that just now. Please try again in a moment.";
    return { ok: false, error: { code, message } };
  } catch {
    return {
      ok: false,
      error: { code: "unavailable", message: "We couldn't reach the studio just now. Please try again in a moment." },
    };
  }
}

export const studio = {
  status: (token: string) => call<StudioStatus>("/status", token),
  palette: (token: string, agentId?: string | null) =>
    call<StudioPalette>(`/palette${agentId ? `?agentId=${encodeURIComponent(agentId)}` : ""}`, token),
  listAgents: (token: string) => call<StudioAgent[]>("/agents", token),
  createAgent: (token: string, spec: AgentSpec) =>
    call<StudioAgent>("/agents", token, { method: "POST", body: JSON.stringify(spec) }),
  updateAgent: (token: string, id: string, spec: AgentSpec) =>
    call<StudioAgent>(`/agents/${encodeURIComponent(id)}`, token, { method: "PATCH", body: JSON.stringify(spec) }),
  deleteAgent: (token: string, id: string) =>
    call<void>(`/agents/${encodeURIComponent(id)}`, token, { method: "DELETE" }),
  makeLive: (token: string, id: string) =>
    call<StudioAgent>(`/agents/${encodeURIComponent(id)}/live`, token, { method: "POST", body: "{}" }),
  listWorkflows: (token: string) => call<StudioWorkflow[]>("/workflows", token),
  createWorkflow: (token: string, name: string, definition: WorkflowDefinition) =>
    call<StudioWorkflow>("/workflows", token, { method: "POST", body: JSON.stringify({ name, definition }) }),
  updateWorkflow: (token: string, id: string, name: string, definition: WorkflowDefinition) =>
    call<StudioWorkflow>(`/workflows/${encodeURIComponent(id)}`, token, {
      method: "PUT",
      body: JSON.stringify({ name, definition }),
    }),
  deleteWorkflow: (token: string, id: string) =>
    call<void>(`/workflows/${encodeURIComponent(id)}`, token, { method: "DELETE" }),
  listRuns: (token: string, workflowId: string) =>
    call<StudioRun[]>(`/workflows/${encodeURIComponent(workflowId)}/runs`, token),
  listDocuments: (token: string, agentId: string) =>
    call<StudioDocument[]>(`/agents/${encodeURIComponent(agentId)}/documents`, token),
  // Multipart: the browser sets the boundary, so no Content-Type here (see call()). Embedding a PDF can take a while.
  uploadDocument: (token: string, agentId: string, file: File) => {
    const body = new FormData();
    body.append("file", file, file.name);
    return call<StudioDocument>(
      `/agents/${encodeURIComponent(agentId)}/documents`,
      token,
      { method: "POST", body },
      90_000
    );
  },
  uploadText: (token: string, agentId: string, title: string, text: string) =>
    call<StudioDocument>(
      `/agents/${encodeURIComponent(agentId)}/documents/text`,
      token,
      {
        method: "POST",
        body: JSON.stringify({ title, text }),
      },
      90_000
    ),
  deleteDocument: (token: string, agentId: string, documentId: string) =>
    call<void>(`/agents/${encodeURIComponent(agentId)}/documents/${encodeURIComponent(documentId)}`, token, {
      method: "DELETE",
    }),
  searchMcp: (token: string, q: string, cursor?: string | null) =>
    call<RegistryPage>(
      `/mcp/search?q=${encodeURIComponent(q)}${cursor ? `&cursor=${encodeURIComponent(cursor)}` : ""}`,
      token,
      {},
      30_000
    ),
  listConnectors: (token: string, agentId: string) =>
    call<StudioConnector[]>(`/agents/${encodeURIComponent(agentId)}/connectors`, token),
  connect: (token: string, agentId: string, name: string) =>
    call<StudioConnector>(
      `/agents/${encodeURIComponent(agentId)}/connectors`,
      token,
      {
        method: "POST",
        body: JSON.stringify({ name }),
      },
      45_000
    ),
  disconnect: (token: string, agentId: string, server: string) =>
    call<void>(`/agents/${encodeURIComponent(agentId)}/connectors/${encodeURIComponent(server)}`, token, {
      method: "DELETE",
    }),
  decide: (token: string, runId: string, approved: boolean) =>
    call<{ accepted: boolean }>(`/runs/${encodeURIComponent(runId)}/decision`, token, {
      method: "POST",
      body: JSON.stringify({ approved }),
    }),
};

/**
 * Opens a studio stream (mission or workflow run) and hands every JSON
 * event to `onEvent`. Resolves when the stream ends; never rejects - a
 * transport failure becomes a synthetic `error` event so the trace panel
 * always closes with something the visitor can read.
 *
 * Spring's SSE writer emits `data:<json>` lines (no space after the colon),
 * one per element; JSON never contains a raw newline, so one line is one
 * event. A blank line separates events per the SSE framing.
 */
export async function openStream(
  path: string,
  token: string,
  body: unknown,
  onEvent: (event: StudioEvent) => void,
  signal?: AbortSignal
): Promise<void> {
  let res: Response;
  try {
    res = await fetch(`${API_BASE}/api/demo/studio${path}`, {
      method: "POST",
      headers: { ...headers(token), Accept: "text/event-stream" },
      body: JSON.stringify(body),
      signal,
    });
  } catch {
    onEvent({ type: "error", message: "We couldn't reach the studio just now. Please try again in a moment." });
    return;
  }
  if (!res.ok || !res.body) {
    const payload = await readJson(res);
    onEvent({
      type: "error",
      message:
        payload && typeof payload.message === "string"
          ? payload.message
          : "The studio couldn't start that just now. Please try again in a moment.",
    });
    return;
  }
  const reader = res.body.getReader();
  const decoder = new TextDecoder();
  let buffer = "";
  try {
    for (;;) {
      const { done, value } = await reader.read();
      if (done) break;
      buffer += decoder.decode(value, { stream: true });
      let nl: number;
      while ((nl = buffer.indexOf("\n")) >= 0) {
        const line = buffer.slice(0, nl).replace(/\r$/, "");
        buffer = buffer.slice(nl + 1);
        if (!line.startsWith("data:")) continue;
        const raw = line.slice(5);
        try {
          onEvent(JSON.parse(raw) as StudioEvent);
        } catch {
          // A non-JSON data line is not something the studio emits; ignore
          // rather than break the whole trace over one odd frame.
        }
      }
    }
  } catch {
    if (!signal?.aborted) {
      onEvent({ type: "error", message: "The connection dropped mid-run. What arrived is shown above." });
    }
  }
}

export function runMission(
  token: string,
  agentId: string,
  mission: string,
  sessionId: string | null,
  onEvent: (event: StudioEvent) => void,
  signal?: AbortSignal
): Promise<void> {
  return openStream(`/agents/${encodeURIComponent(agentId)}/mission`, token, { mission, sessionId }, onEvent, signal);
}

export function runWorkflow(
  token: string,
  workflowId: string,
  input: string,
  onEvent: (event: StudioEvent) => void,
  signal?: AbortSignal
): Promise<void> {
  return openStream(`/workflows/${encodeURIComponent(workflowId)}/run`, token, { input }, onEvent, signal);
}
