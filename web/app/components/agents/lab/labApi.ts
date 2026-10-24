// Agent Lab - the HTTP seam for the lab instruments (AgentLabController in
// core/agents, base /api/agents/{agentId}/lab) plus the one existing chat
// endpoint the run graph's fork uses (session rewind). Deliberately NOT part
// of the `api` barrel (app/lib/api.ts): the lab is self-contained under this
// folder and nothing outside it should reach for these.
//
// Every expected STATE status (plan gate 403, "already running" 409, expired
// run 404, ...) goes through toJsonQuietOn so it raises no global toast; the
// error still THROWS with .status and .message (toJsonQuietOn never returns
// null - core.ts), and each panel renders it inline. Only
// getLabCapabilities swallows errors.
import { apiFetch, toJsonQuietOn, jsonBody } from "../../../lib/api/core";

export type LabRunKind = "KNOCKOUT" | "CONSISTENCY";
export type LabRunStatus = "QUEUED" | "RUNNING" | "SUCCEEDED" | "FAILED" | "CANCELLED";
export interface LabCapabilities {
  allowed: boolean; reason: "PLAN" | null;
  maxCells: number; maxCallsPerRun: number; maxSystemPrompts: number;
  maxSimilarityCells: number; runTtlMinutes: number;
  /** Whether a run may use the agent's real tools (app.agent-lab.with-tools.enabled and the tool pipeline present).
   *  Always sent by the current core; optional only so an older core still parses (the toggle then stays usable and a
   *  refused run explains itself). */
  withToolsAvailable?: boolean;
}
export interface LabMessage { role: "user" | "assistant"; content: string }
export interface LabCellRequest {
  key: string; promptIndex: number | null; message: string;
  history?: LabMessage[]; expected?: string | null;
}
export interface LabRunRequest {
  kind: LabRunKind; systemPrompts: string[]; cells: LabCellRequest[];
  scoring: "NONE" | "JUDGE"; similarity: "NONE" | "JUDGE";
  /** true = answer with the agent's real tools (the chat turn pipeline); omitted = the tool-less completion. */
  withTools?: boolean;
}
export interface LabCellResult {
  key: string; status: "PENDING" | "DONE" | "ERROR";
  answer: string | null; answerTruncated: boolean;
  score: number | null; scoreStatus: "NONE" | "PENDING" | "SCORED" | "UNAVAILABLE";
  scoreFeedback: string | null; errorMessage: string | null; ms: number | null;
}
export interface LabSimilarity {
  method: "JUDGE"; status: "PENDING" | "PARTIAL" | "DONE";
  keys: string[]; matrix: (number | null)[][];
}
export interface LabRun {
  runId: string; agentId: string; kind: LabRunKind; status: LabRunStatus;
  plannedCalls: number; completedCalls: number; cancelRequested: boolean;
  errorMessage: string | null; createdAtMs: number; updatedAtMs: number; finishedAtMs: number | null;
  cells: LabCellResult[]; similarity: LabSimilarity | null;
}

/** Integer.MAX_VALUE: a keepTurns no session can exceed, so rewind returns
 * before it deletes anything and just reports the stored-entry count. */
export const REWIND_PROBE_KEEP = 2147483647;
export const LAB_DEFAULT_MAX_CALLS = 120;
// Mirrors of the server limits in AgentLabRunService.
export const LAB_MAX_PROMPT_CHARS = 20000;
export const LAB_MAX_MESSAGE_CHARS = 8000;
export const LAB_MAX_EXPECTED_CHARS = 4000;
export const LAB_MAX_HISTORY_MESSAGES = 20;
export const LAB_MAX_HISTORY_CHARS = 4000;

/** Same formula as the server: n*(scoring===JUDGE?2:1) + (similarity===JUDGE ? n*(n-1)/2 : 0). */
export function plannedCalls(request: Pick<LabRunRequest, "cells" | "scoring" | "similarity">): number {
  const n = request.cells.length;
  const answersAndScores = n * (request.scoring === "JUDGE" ? 2 : 1);
  const comparisons = request.similarity === "JUDGE" ? (n * (n - 1)) / 2 : 0;
  return answersAndScores + comparisons;
}

const labBase = (agentId: string) => `/api/agents/${agentId}/lab`;

export const labApi = {
  /** Uses toJsonQuietOn([403, 404]) inside try/catch. Returns null on ANY error or when the
   *  body lacks a boolean `allowed`. Never throws. */
  async getLabCapabilities(agentId: string): Promise<LabCapabilities | null> {
    try {
      // 404 is the expected answer from a core that predates the lab routes.
      const body = await apiFetch(`${labBase(agentId)}/capabilities`).then(toJsonQuietOn<{ allowed?: unknown } | null>([403, 404]));
      return typeof body?.allowed === "boolean" ? (body as LabCapabilities) : null;
    } catch {
      return null;
    }
  },

  /** POST, toJsonQuietOn([403, 409, 429, 503]). Throws with .status and .message. */
  startLabRun(agentId: string, request: LabRunRequest): Promise<LabRun> {
    return apiFetch(`${labBase(agentId)}/runs`, { method: "POST", ...jsonBody(request) }).then(
      toJsonQuietOn([403, 409, 429, 503])
    );
  },

  /** GET { skipGetCache: true }, toJsonQuietOn([404]). Throws. */
  getLabRun(agentId: string, runId: string): Promise<LabRun> {
    // skipGetCache: a run's progress mutates continuously while it executes,
    // and plain GETs are otherwise shared/cached for 15s (core.ts).
    return apiFetch(`${labBase(agentId)}/runs/${runId}`, { skipGetCache: true }).then(toJsonQuietOn([404]));
  },

  /** POST, toJsonQuietOn([404]). Throws. */
  cancelLabRun(agentId: string, runId: string): Promise<{ runId: string; cancelRequested: boolean }> {
    return apiFetch(`${labBase(agentId)}/runs/${runId}/cancel`, { method: "POST" }).then(toJsonQuietOn([404]));
  },

  /** POST, toJsonQuietOn([404]). Throws. */
  rewindChatSession(
    agentId: string,
    sessionId: string,
    keepTurns: number
  ): Promise<{ sessionId: string; keepTurns: number; droppedTurns: number }> {
    return apiFetch(`/api/agents/${agentId}/chat/sessions/${encodeURIComponent(sessionId)}/rewind`, {
      method: "POST",
      ...jsonBody({ keepTurns }),
    }).then(toJsonQuietOn([404]));
  },

  /** rewindChatSession(..., REWIND_PROBE_KEEP).keepTurns - non-destructive (SessionMemoryService.java:559-561). */
  async probeSessionEntryCount(agentId: string, sessionId: string): Promise<number> {
    const result = await labApi.rewindChatSession(agentId, sessionId, REWIND_PROBE_KEEP);
    return result.keepTurns;
  },
};
