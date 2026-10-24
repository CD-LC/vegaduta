// Pure rules of a dataset's replay mode, kept out of the components so they are testable on their own.
//
// A dataset replays its cases through the full pipeline (the same tool selection, guardrails, approvals and step
// limits a live user gets, in a sandbox: tools that only read run, anything that could change data or send
// something is simulated) or as a quick text-only replay (the agent's tools are withheld). A dataset may pin one of
// the two or pin nothing, in which case its runs follow the workspace default (the full pipeline as shipped); a
// single run can still name its own mode. In the full pipeline a tool call that would wait for a person can be
// approved by the eval sandbox instead ("auto-approve"); the server allows that only for tenants the operator
// declared non-production, and never for shell, coding, scheduling, agent-to-agent, payment or delete tools (core
// EvalSandboxPolicy).

import type { EvalMode } from "../evalDatasetsApi";

export type { EvalMode };

export interface DatasetModeValue {
  /** true pins the full pipeline, false pins a quick text-only replay, null pins nothing (the workspace default). */
  toolExecutionEnabled: boolean | null;
  autoApproveTools: boolean;
}

export const TEXT_ONLY: DatasetModeValue = { toolExecutionEnabled: false, autoApproveTools: false };
export const WORKSPACE_DEFAULT: DatasetModeValue = { toolExecutionEnabled: null, autoApproveTools: false };

/** Auto-approval only means something when tools run; pinning quick can never leave a stale approval on. */
export function normalizeMode(value: DatasetModeValue): DatasetModeValue {
  return value.toolExecutionEnabled === false ? TEXT_ONLY : value;
}

export function sameMode(a: DatasetModeValue, b: DatasetModeValue): boolean {
  const x = normalizeMode(a);
  const y = normalizeMode(b);
  return x.toolExecutionEnabled === y.toolExecutionEnabled && x.autoApproveTools === y.autoApproveTools;
}

/** The `mode` of the upsert request: FULL or QUICK pin it, DEFAULT drops the pin. */
export function modeForRequest(value: DatasetModeValue): "FULL" | "QUICK" | "DEFAULT" {
  if (value.toolExecutionEnabled === true) return "FULL";
  if (value.toolExecutionEnabled === false) return "QUICK";
  return "DEFAULT";
}

/** The mode the server says a run of the dataset takes right now; older servers send none (the pin or text only). */
export function effectiveModeOf(dataset: {
  toolExecutionEnabled?: boolean | null;
  effectiveMode?: EvalMode | null;
}): EvalMode {
  if (dataset.effectiveMode === "FULL" || dataset.effectiveMode === "QUICK") return dataset.effectiveMode;
  return dataset.toolExecutionEnabled === true ? "FULL" : "QUICK";
}

/** The server's refusal of auto-approval: a 400 whose sentence names the setting. Returns that sentence, else null. */
export function autoApproveRefusal(error: unknown): string | null {
  const e = error as { status?: number; message?: string } | null;
  if (!e || (e.status !== 400 && e.status !== 403)) return null;
  const message = typeof e.message === "string" ? e.message : "";
  return /auto-?approv/i.test(message) ? message : null;
}
