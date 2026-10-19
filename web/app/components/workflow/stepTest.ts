// "Run this step" - single-node test of the SAVED workflow graph.
// POST /api/workflows/{id}/nodes/{nodeId}/test (core WorkflowNodeTestService)
// returns one NodeTraceEntry and persists nothing as a run.
//
// Everything here except testWorkflowNode is pure, so the testability rules,
// upstream lookup and mock store unit-test without a network. The rules mirror
// WorkflowNodeTestService#refusalReason; the server stays the authority (a 409
// still comes back as kind "blocked"), this only avoids offering a button the
// server would refuse.

import { apiFetch, jsonBody } from "../../lib/api/core";
import { isWriteOperation } from "./dataTableNode";

// ---------------------------------------------------------------------------
// Wire shapes
// ---------------------------------------------------------------------------

export type StepToolCall = {
  toolName?: string | null;
  input?: string | null;
  output?: string | null;
  error?: string | null;
  startedAt?: string | null;
  durationMs?: number | null;
};

/** One NodeTraceEntry as the test endpoint returns it; newer fields are nullable (null = unknown). */
export type StepTestEntry = {
  nodeId?: string | null;
  nodeType?: string | null;
  label?: string | null;
  status?: string | null;
  summary?: string | null;
  startedAt?: string | null;
  completedAt?: string | null;
  logLines?: string[] | null;
  durationMs?: number | null;
  inputJson?: string | null;
  outputJson?: string | null;
  inputTruncated?: boolean | null;
  outputTruncated?: boolean | null;
  tokensIn?: number | null;
  tokensOut?: number | null;
  costUsd?: number | string | null;
  toolCalls?: StepToolCall[] | null;
};

export type StepTestBody = {
  input?: string;
  pinnedInputs?: Record<string, unknown>;
};

export type StepTestFailureKind = "blocked" | "unsupported" | "not-found" | "error";

// Optional mirror fields: the project tsconfig has no strictNullChecks, so
// `r.ok` alone does not narrow and every member must declare every field.
export type StepTestResult =
  | { ok: true; entry: StepTestEntry; kind?: undefined; message?: undefined }
  | { ok: false; kind: StepTestFailureKind; message: string; entry?: undefined };

// ---------------------------------------------------------------------------
// Testability (mirrors WorkflowNodeTestService#refusalReason)
// ---------------------------------------------------------------------------

/** Node types the endpoint accepts; http and media are further narrowed by config. */
export const TESTABLE_TYPES: ReadonlySet<string> = new Set([
  "agent",
  "supervisor",
  "code",
  "condition",
  "knowledge",
  "trigger",
  "http",
  "media",
  // The pure data nodes (core WorkflowDataOps): no side effects, deterministic, safe to run alone.
  "set",
  "filter",
  "sort",
  "aggregate",
  "limit",
  "dedupe",
  "switch",
  "merge",
  "data_table",
]);

const MULTI_NODE_TYPES = new Set(["loop", "parallel", "fan_out", "sub_flow"]);
const NEEDS_REAL_RUN_TYPES = new Set(["approval", "human_input", "voice_menu"]);
const SIDE_EFFECT_TYPES = new Set(["bulk_email", "crm_lead", "device_control", "tool", "output", "workflow"]);

type Config = Record<string, unknown> | null | undefined;

function str(v: unknown): string {
  return v == null ? "" : String(v).trim();
}

/** Null when the node can be tested alone; otherwise a plain-language reason (the side effect it would cause). */
export function untestableReason(nodeType: string | null | undefined, config?: Config): string | null {
  const type = str(nodeType).toLowerCase();
  const cfg = config ?? {};
  if (MULTI_NODE_TYPES.has(type)) {
    return "This node runs other nodes, so it can't be tested on its own. Run the workflow instead.";
  }
  if (type === "wait") {
    return "A Wait node pauses a real run until its time or resume call arrives, so it can't be tested on its own. Run the workflow instead.";
  }
  if (NEEDS_REAL_RUN_TYPES.has(type) || cfg.requiresApproval === true) {
    return "This node waits for a person (approval or input), which needs a real run. Run the workflow instead.";
  }
  if (SIDE_EFFECT_TYPES.has(type)) {
    return "This node has real side effects (it sends, writes or triggers something) and has no dry-run mode, so it is never run in a test.";
  }
  if (type === "data_table" && isWriteOperation(str(cfg.operation))) {
    return "This data table step writes to a table and has no dry-run mode, so it is not run in a test. Run the workflow instead (reading rows or counting can be tested).";
  }
  if (type === "http") {
    const method = str(cfg.method).toUpperCase() || "GET";
    if (method !== "GET") {
      return `Only GET requests can be tested. This node uses ${method}, which may change data on the other side.`;
    }
  }
  if (type === "media" && str(cfg.operation).toLowerCase() === "render") {
    return "A media render starts a real render job, so it is not run in a test.";
  }
  if (type === "agent") {
    const mapping = cfg.inputMapping;
    if (mapping && typeof mapping === "object" && !Array.isArray(mapping) && Object.keys(mapping).length > 0) {
      return "Agent nodes with structured input mapping record their result against a real run, so they can't be tested on their own yet.";
    }
  }
  if (!TESTABLE_TYPES.has(type)) {
    return "This node type can't be tested on its own yet. Run the workflow instead.";
  }
  return null;
}

export function isTestable(nodeType: string | null | undefined, config?: Config): boolean {
  return untestableReason(nodeType, config) === null;
}

// ---------------------------------------------------------------------------
// Upstream lookup for the pinned-inputs editor
// ---------------------------------------------------------------------------

type EdgeLike = { source?: string | null; target?: string | null };

/**
 * Direct upstream node ids of nodeId, in edge order and de-duplicated. Edge
 * order matters: the server takes the FIRST incoming edge whose source has a
 * pinned value, so the editor lists them in the order they'd win.
 */
export function upstreamIds(nodeId: string, edges: readonly EdgeLike[] | null | undefined): string[] {
  const out: string[] = [];
  for (const e of edges ?? []) {
    const s = e?.source;
    if (e?.target === nodeId && s && s !== nodeId && !out.includes(s)) out.push(s);
  }
  return out;
}

// ---------------------------------------------------------------------------
// Mock-data store (CLIENT-ONLY: only ever sent as pinnedInputs of a step
// test, never attached to a real run)
// ---------------------------------------------------------------------------

export const MOCK_KEY_PREFIX = "vd.wf.mocks.";

export type MockMap = Record<string, string>;

function storage(): Storage | null {
  try {
    return typeof window !== "undefined" ? window.localStorage : null;
  } catch {
    return null;
  }
}

export function loadMocks(workflowId: string): MockMap {
  try {
    const raw = storage()?.getItem(MOCK_KEY_PREFIX + workflowId);
    if (!raw) return {};
    const parsed: unknown = JSON.parse(raw);
    if (!parsed || typeof parsed !== "object" || Array.isArray(parsed)) return {};
    const out: MockMap = {};
    for (const [k, v] of Object.entries(parsed as Record<string, unknown>)) {
      if (typeof v === "string") out[k] = v;
    }
    return out;
  } catch {
    return {};
  }
}

/** Replaces the whole map; an empty map removes the key. Returns false when storage is unavailable/full. */
export function saveMocks(workflowId: string, mocks: MockMap): boolean {
  try {
    const s = storage();
    if (!s) return false;
    const key = MOCK_KEY_PREFIX + workflowId;
    if (Object.keys(mocks).length === 0) s.removeItem(key);
    else s.setItem(key, JSON.stringify(mocks));
    return true;
  } catch {
    return false;
  }
}

/** Sets or (with null/empty) clears one node's mock, returning the new map. */
export function setMock(workflowId: string, nodeId: string, value: string | null): MockMap {
  const next = { ...loadMocks(workflowId) };
  if (value == null || value === "") delete next[nodeId];
  else next[nodeId] = value;
  saveMocks(workflowId, next);
  return next;
}

/**
 * The request body: typed input wins (the server ignores pinnedInputs when
 * input is non-null, so a blank box is OMITTED rather than sent as ""), and
 * only non-empty pinned values are sent.
 */
export function buildTestBody(input: string, pinned: Record<string, string>): StepTestBody {
  const body: StepTestBody = {};
  if (input.trim() !== "") body.input = input;
  const p: Record<string, string> = {};
  for (const [k, v] of Object.entries(pinned)) if (v.trim() !== "") p[k] = v;
  if (Object.keys(p).length) body.pinnedInputs = p;
  return body;
}

// ---------------------------------------------------------------------------
// Network
// ---------------------------------------------------------------------------

// GlobalExceptionHandler#handleNoResourceFound's message - a route that does
// not exist on this (older) core, as opposed to "Node 'x' is not in ...".
const NO_ROUTE_MESSAGE = "No endpoint matches this request.";

async function readBody(res: Response): Promise<{ json: Record<string, unknown> | null; isJson: boolean }> {
  try {
    const text = await res.text();
    if (!text) return { json: null, isJson: false };
    const parsed: unknown = JSON.parse(text);
    return parsed && typeof parsed === "object" && !Array.isArray(parsed)
      ? { json: parsed as Record<string, unknown>, isJson: true }
      : { json: null, isJson: true };
  } catch {
    return { json: null, isJson: false };
  }
}

/** Classifies a non-2xx test response. Exported for tests. */
export function classifyFailure(status: number, json: Record<string, unknown> | null, isJson: boolean): StepTestResult {
  const serverMsg = typeof json?.error === "string" ? json.error : typeof json?.message === "string" ? json.message : "";
  if (status === 409) {
    return { ok: false, kind: "blocked", message: serverMsg || "This step can't be tested on its own." };
  }
  if (status === 405 || status === 501) {
    return { ok: false, kind: "unsupported", message: "Step testing isn't available on this server yet." };
  }
  if (status === 404) {
    if (!isJson || serverMsg === NO_ROUTE_MESSAGE || serverMsg === "" || serverMsg === "Not Found") {
      return { ok: false, kind: "unsupported", message: "Step testing isn't available on this server yet." };
    }
    return { ok: false, kind: "not-found", message: serverMsg };
  }
  if (status === 401) return { ok: false, kind: "error", message: "Your session has expired. Please sign in again." };
  if (status === 403) return { ok: false, kind: "error", message: serverMsg || "You need run access to test steps." };
  if (status === 429) return { ok: false, kind: "error", message: serverMsg || "Too many requests - wait a moment and try again." };
  return { ok: false, kind: "error", message: serverMsg || `The test failed (HTTP ${status}).` };
}

/**
 * Runs one node of the saved graph. Never throws and never raises the global
 * error toast (a missing route on an older core is an expected state, not an
 * alarm) - callers switch on the result.
 */
export async function testWorkflowNode(
  workflowId: string,
  nodeId: string,
  body: StepTestBody,
  signal?: AbortSignal,
): Promise<StepTestResult> {
  let res: Response;
  try {
    res = await apiFetch(
      `/api/workflows/${encodeURIComponent(workflowId)}/nodes/${encodeURIComponent(nodeId)}/test`,
      { method: "POST", ...jsonBody(body), signal },
    );
  } catch (e) {
    if ((e as { name?: string } | null)?.name === "AbortError") {
      return { ok: false, kind: "error", message: "Test cancelled." };
    }
    return { ok: false, kind: "error", message: "Couldn't reach the server - check your connection and try again." };
  }
  const { json, isJson } = await readBody(res);
  if (!res.ok) return classifyFailure(res.status, json, isJson);
  if (!json) return { ok: false, kind: "unsupported", message: "The server returned an unexpected response." };
  return { ok: true, entry: json as StepTestEntry };
}

// ---------------------------------------------------------------------------
// Display helpers
// ---------------------------------------------------------------------------

export type StepStatusKey = "ok" | "error" | "skipped" | "approval" | "other";

export function stepStatusKey(status: string | null | undefined): StepStatusKey {
  const s = str(status).toUpperCase();
  if (s === "OK" || s === "SUCCESS" || s === "COMPLETED" || s === "PINNED") return "ok";
  if (s === "ERROR" || s === "FAILED") return "error";
  if (s === "SKIPPED") return "skipped";
  if (s.startsWith("AWAITING")) return "approval";
  return "other";
}

export function toNumberOrNull(v: unknown): number | null {
  if (v == null || v === "") return null;
  const n = typeof v === "number" ? v : Number(v);
  return Number.isFinite(n) ? n : null;
}
