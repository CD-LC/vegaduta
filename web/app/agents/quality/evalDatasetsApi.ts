"use client";

import { api } from "../../lib/api";
import type { JudgeCalibrationInfo } from "./judgeCalibration";

/**
 * Typed client contract for the eval-datasets backend surface
 * (core/quality's EvalDatasetController, mounted at
 * /api/agents/{agentId}/quality/datasets...).
 *
 * WHY THIS FILE EXISTS INSTEAD OF METHODS IN app/lib/api.ts
 * ----------------------------------------------------------
 * app/lib/api.ts is the single shared client (bearer token, global error
 * toasts, quiet-status handling) and is OWNED BY A CONCURRENT WORKSTREAM in
 * this working tree - this workstream may not edit it, and hand-rolling a
 * fetch() here would bypass the shared auth/error handling (explicitly
 * forbidden). So this module does the only honest thing available:
 *
 *  1. It DECLARES the exact method contract the api.ts owner needs to add
 *     (see the reference implementation block at the bottom of this file).
 *  2. resolveEvalApi() FEATURE-DETECTS those methods on the shared `api`
 *     object at runtime. The moment the owner lands them, this whole
 *     surface lights up with zero further changes here; until then
 *     EvalDatasetsSection renders an explicit "client not wired yet"
 *     notice instead of broken calls - never a fake-empty state.
 *
 * Cross-workstream dependency: recorded in the workstream report; the
 * api.ts additions are a ~12-line follow-up for whoever owns that file.
 */

// ---------------------------------------------------------------------------
// Response shapes (mirror EvalDatasetDtos on the backend, camelCase JSON)
// ---------------------------------------------------------------------------

export interface EvalDatasetSummary {
  id: string;
  agentId: string;
  name: string;
  description: string | null;
  passThreshold: number;
  /** The dataset's pinned mode as a boolean: true = full pipeline, false = quick (text only), null = it pins none. */
  toolExecutionEnabled: boolean | null;
  /** The pinned mode by name; null/absent = none, runs follow the workspace default. */
  mode?: EvalMode | null;
  /** The mode a run of this dataset takes right now when it names none: the pin, else the workspace default. */
  effectiveMode?: EvalMode;
  /** With tools: a call that would wait for a person is approved by the eval sandbox (non-production tenants only). */
  autoApproveTools?: boolean;
  caseCount: number;
  createdBy: string | null;
  createdAt: string;
  updatedAt: string;
}

export interface EvalCase {
  id: string;
  userMessage: string;
  expectedOutput: string | null;
  expectedTools: string[];
  position: number;
  createdAt: string;
  updatedAt: string;
}

export interface EvalDatasetDetail {
  dataset: EvalDatasetSummary;
  cases: EvalCase[];
}

/** How a run replays its cases: the production pipeline in the eval sandbox, or text only with the tools withheld. */
export type EvalMode = "FULL" | "QUICK";

export type EvalRunStatus = "QUEUED" | "RUNNING" | "SUCCEEDED" | "FAILED" | "CANCELLED";
export type EvalRunVerdict = "PASSED" | "FAILED" | "INCONCLUSIVE";

/** V343 run options - all optional; server defaults apply (strictJudge off,
 *  1 repeat, USD 5 budget, catalog judge). idempotencyKey rides the
 *  Idempotency-Key header, not the body. */
export interface EvalRunOptions {
  judgeModel?: string;
  strictJudge?: boolean;
  repeats?: number;
  budgetUsd?: number;
  idempotencyKey?: string;
  /** FULL or QUICK; absent = the dataset's pinned mode, else the workspace default (the full pipeline as shipped). */
  mode?: EvalMode;
}

export interface EvalRunSummary {
  id: string;
  datasetId: string;
  agentId: string;
  /** Which stored agent_version configuration this run replayed (V260). */
  agentVersionId?: string | null;
  status: EvalRunStatus;
  verdict: EvalRunVerdict | null;
  passThreshold: number;
  overallScore: number | null;
  caseCount: number;
  scoredCaseCount: number;
  failedAssertionCount: number;
  erroredCaseCount: number;
  judgeModel: string | null;
  /** V343: the dataset content hash snapshotted at trigger - the gate only
   *  accepts a run whose hash still matches the dataset's current content. */
  datasetHash?: string | null;
  /** V343: the judge override the caller requested (resolved model is in
   *  judgeModel). */
  requestedJudgeModel?: string | null;
  strictJudge?: boolean;
  /** How the run replayed its cases (frozen when it was triggered). Absent on an older server (text only then). */
  mode?: EvalMode;
  /** The run replayed under the eval sandbox's auto-approval: a person decided none of its tool calls. */
  autoApproved?: boolean;
  repeats?: number;
  budgetUsd?: number | null;
  /** Observed replay spend accumulated during the run (V343). */
  costUsd?: number | null;
  errorMessage: string | null;
  requestedBy: string | null;
  cancelRequested: boolean;
  createdAt: string;
  startedAt: string | null;
  finishedAt: string | null;
  /** Diff-vs-previous, precomputed server-side; all null = no baseline (first
   *  measured run) - "nothing to compare against", never zero. */
  baselineRunId: string | null;
  baselineScore: number | null;
  scoreDelta: number | null;
  /** Deep link into Langfuse for the replay's OTel trace (2026-09-09, evals
   *  hooks). Optional on purpose: only a core that captures the trace id
   *  during replay AND has Langfuse configured sends it - absent/null means
   *  "no link", never an error. */
  langfuseTraceUrl?: string | null;
  /** Wave E: whether the LLM judge that scored this run was ever calibrated against human labels. Absent unless
   *  the core has app.quality.eval.calibration.enabled and the run used a judge. Informational - never changes
   *  the verdict. */
  judgeCalibration?: JudgeCalibrationInfo | null;
}

export interface EvalDimensionScore {
  score: number | null;
  status: string | null;
  feedback: string | null;
}

export interface EvalCaseResult {
  id: string;
  caseId: string | null;
  position: number;
  userMessage: string;
  expectedOutput: string | null;
  expectedTools: string[];
  agentResponse: string | null;
  /** status: how the turn recorded the call's end (OK, FAILED, NOT_RUN, AWAITING_APPROVAL, REPLAYED, SIMULATED); absent on older results. */
  toolCalls: { toolName: string | null; input: string | null; output: string | null; status?: string | null }[];
  scores: Record<string, EvalDimensionScore>;
  overallScore: number | null;
  expectedToolsSatisfied: boolean | null;
  /** V343 case-level verdict: true passed / false failed / null not
   *  evaluable (unavailable judges, majority-undecided repeats). */
  passed?: boolean | null;
  status: "SCORED" | "UNAVAILABLE" | "ERROR";
  errorMessage: string | null;
  finishedAt: string | null;
  /** Per-case Langfuse trace link - same optionality rule as
   *  EvalRunSummary.langfuseTraceUrl. */
  langfuseTraceUrl?: string | null;
}

/** Body of POST .../datasets/{datasetId}/cases/import ("Save as eval case"
 *  on a chat turn). sessionId+turnRef identify the persisted turn; the two
 *  text fields are what the browser rendered, a fallback for a core that
 *  cannot resolve the ref (see api.ts's importEvalDatasetCase comment). */
export interface ImportEvalCasePayload {
  sessionId: string | null;
  turnRef: string;
  userMessage?: string;
  agentResponse?: string;
}

export interface EvalRunDetail {
  run: EvalRunSummary;
  caseResults: EvalCaseResult[];
  /** The tools the sandbox answered with a simulated result in at least one case (they could change something). */
  simulatedTools?: string[];
}

export interface UpsertEvalDatasetPayload {
  name: string;
  description?: string | null;
  passThreshold?: number | null;
  /** Older boolean spelling of `mode` (true = FULL, false = QUICK); used by the server only when `mode` is absent. */
  toolExecutionEnabled?: boolean | null;
  /** FULL or QUICK pins the dataset's mode, DEFAULT drops the pin (runs follow the workspace default); absent = unchanged. */
  mode?: EvalMode | "DEFAULT" | null;
  autoApproveTools?: boolean | null;
}

export interface UpsertEvalCasePayload {
  userMessage: string;
  expectedOutput?: string | null;
  expectedTools?: string[] | null;
  position?: number | null;
}

// ---------------------------------------------------------------------------
// The contract app/lib/api.ts needs to satisfy
// ---------------------------------------------------------------------------

export interface EvalDatasetApi {
  listEvalDatasets(agentId: string): Promise<EvalDatasetSummary[]>;
  createEvalDataset(agentId: string, payload: UpsertEvalDatasetPayload): Promise<EvalDatasetSummary>;
  getEvalDataset(agentId: string, datasetId: string): Promise<EvalDatasetDetail>;
  updateEvalDataset(agentId: string, datasetId: string, payload: UpsertEvalDatasetPayload): Promise<EvalDatasetSummary>;
  deleteEvalDataset(agentId: string, datasetId: string): Promise<void>;
  addEvalDatasetCase(agentId: string, datasetId: string, payload: UpsertEvalCasePayload): Promise<EvalCase>;
  updateEvalDatasetCase(agentId: string, datasetId: string, caseId: string, payload: UpsertEvalCasePayload): Promise<EvalCase>;
  deleteEvalDatasetCase(agentId: string, datasetId: string, caseId: string): Promise<void>;
  triggerEvalRun(agentId: string, datasetId: string, options?: EvalRunOptions): Promise<EvalRunSummary>;
  listEvalRuns(agentId: string, datasetId: string, limit?: number): Promise<EvalRunSummary[]>;
  getEvalRun(agentId: string, datasetId: string, runId: string): Promise<EvalRunDetail>;
  cancelEvalRun(agentId: string, datasetId: string, runId: string): Promise<EvalRunSummary>;
}

const REQUIRED_METHODS: (keyof EvalDatasetApi)[] = [
  "listEvalDatasets",
  "createEvalDataset",
  "getEvalDataset",
  "updateEvalDataset",
  "deleteEvalDataset",
  "addEvalDatasetCase",
  "updateEvalDatasetCase",
  "deleteEvalDatasetCase",
  "triggerEvalRun",
  "listEvalRuns",
  "getEvalRun",
  "cancelEvalRun",
];

/**
 * Feature-detects the contract on the shared client. Returns null while the
 * api.ts owner has not yet landed the methods - the UI renders an explicit
 * pending-wiring notice for that case (never a silent empty state).
 */
export function resolveEvalApi(): EvalDatasetApi | null {
  const candidate = api as unknown as Record<string, unknown>;
  const complete = REQUIRED_METHODS.every((method) => typeof candidate[method] === "function");
  return complete ? (api as unknown as EvalDatasetApi) : null;
}

// ---------------------------------------------------------------------------
// Evals hooks (2026-09-09) - the two newer endpoints, feature-detected
// SEPARATELY from the twelve above because they have a second gate: the
// client method can exist while the CORE route does not (an older core
// answers 404). So each control that uses one hides itself on the first 404
// and stays hidden for the rest of the page's life - the route is not going
// to appear between two clicks, and re-probing on every turn would spam the
// backend with requests we already know the answer to.
// ---------------------------------------------------------------------------

export interface EvalHooksApi {
  importEvalDatasetCase(agentId: string, datasetId: string, payload: ImportEvalCasePayload): Promise<EvalCase>;
  seedEvalDatasetFromGolden(agentId: string): Promise<EvalDatasetSummary>;
}

/** Null when api.ts lacks either hook method (an older web build). */
export function resolveEvalHooksApi(): EvalHooksApi | null {
  const candidate = api as unknown as Record<string, unknown>;
  const complete = ["importEvalDatasetCase", "seedEvalDatasetFromGolden"].every(
    (method) => typeof candidate[method] === "function"
  );
  return complete ? (api as unknown as EvalHooksApi) : null;
}

/** Module-level "the core told us this route does not exist" memo, keyed
 *  per hook so a missing seed-golden route does not also hide the import
 *  action (they can land in core independently). Sticky for the page's
 *  lifetime by design - see the section comment above. */
const unsupportedHooks = new Set<keyof EvalHooksApi>();

export function isEvalHookUnsupported(hook: keyof EvalHooksApi): boolean {
  return unsupportedHooks.has(hook);
}

/** Call from a catch block: records a 404 (route not mounted on this core)
 *  or 403 (plan gate) as "unsupported" and returns true when it did, so the
 *  caller can hide the control instead of surfacing an error. Any other
 *  failure returns false and is the caller's to handle. */
export function markEvalHookUnsupportedIf(hook: keyof EvalHooksApi, error: unknown): boolean {
  const status = (error as { status?: number } | null)?.status;
  if (status === 404 || status === 403) {
    unsupportedHooks.add(hook);
    return true;
  }
  return false;
}

/*
 * REFERENCE IMPLEMENTATION for the api.ts owner - paste into the `api`
 * object literal (uses the module-private apiFetch/toJson/toJsonQuietOn/
 * jsonBody helpers exactly like the four existing quality methods around
 * api.ts:2793). Reads go through toJsonQuietOn([403]) because a FREE-plan
 * 403 is an expected state the panel renders inline, same as
 * getAgentQualitySummary:
 *
 *   listEvalDatasets: (agentId) =>
 *     apiFetch(`/api/agents/${agentId}/quality/datasets`).then(toJsonQuietOn([403])),
 *   createEvalDataset: (agentId, payload) =>
 *     apiFetch(`/api/agents/${agentId}/quality/datasets`, { method: "POST", ...jsonBody(payload) }).then(toJson),
 *   getEvalDataset: (agentId, datasetId) =>
 *     apiFetch(`/api/agents/${agentId}/quality/datasets/${datasetId}`).then(toJsonQuietOn([403])),
 *   updateEvalDataset: (agentId, datasetId, payload) =>
 *     apiFetch(`/api/agents/${agentId}/quality/datasets/${datasetId}`, { method: "PUT", ...jsonBody(payload) }).then(toJson),
 *   deleteEvalDataset: (agentId, datasetId) =>
 *     apiFetch(`/api/agents/${agentId}/quality/datasets/${datasetId}`, { method: "DELETE" }).then(toJson),
 *   addEvalDatasetCase: (agentId, datasetId, payload) =>
 *     apiFetch(`/api/agents/${agentId}/quality/datasets/${datasetId}/cases`, { method: "POST", ...jsonBody(payload) }).then(toJson),
 *   updateEvalDatasetCase: (agentId, datasetId, caseId, payload) =>
 *     apiFetch(`/api/agents/${agentId}/quality/datasets/${datasetId}/cases/${caseId}`, { method: "PUT", ...jsonBody(payload) }).then(toJson),
 *   deleteEvalDatasetCase: (agentId, datasetId, caseId) =>
 *     apiFetch(`/api/agents/${agentId}/quality/datasets/${datasetId}/cases/${caseId}`, { method: "DELETE" }).then(toJson),
 *   triggerEvalRun: (agentId, datasetId) =>
 *     apiFetch(`/api/agents/${agentId}/quality/datasets/${datasetId}/runs`, { method: "POST" }).then(toJson),
 *   listEvalRuns: (agentId, datasetId, limit = 25) =>
 *     apiFetch(`/api/agents/${agentId}/quality/datasets/${datasetId}/runs?limit=${limit}`).then(toJsonQuietOn([403])),
 *   getEvalRun: (agentId, datasetId, runId) =>
 *     apiFetch(`/api/agents/${agentId}/quality/datasets/${datasetId}/runs/${runId}`).then(toJsonQuietOn([403])),
 *   cancelEvalRun: (agentId, datasetId, runId) =>
 *     apiFetch(`/api/agents/${agentId}/quality/datasets/${datasetId}/runs/${runId}/cancel`, { method: "POST" }).then(toJson),
 */
