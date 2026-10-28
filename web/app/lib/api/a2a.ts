import { apiFetch, toJson, toJsonQuietOn } from "./core";

// A2A admin console surface (core/agents-interop a2a/A2aConsoleController,
// /api/a2a/console/**): tenant-wide task visibility (inbound calls INTO this
// workspace's agents plus outbound a2a_call_agent bookkeeping) and the
// read-only outbound client policy. All reads are quiet on 403/404/405 so an
// older core or a personal-edition install (no A2A_DIRECTORY) degrades to
// "feature off" instead of an error, same posture as agentTrust.ts.

export type A2aDirection = "inbound" | "outbound";

export interface A2aTaskRow {
  id: string;
  direction: A2aDirection;
  agentId: string;
  contextId: string;
  /** Owning caller's JWT subject ("" for anonymous/outbound bookkeeping). */
  subject: string;
  /** Wire name: TASK_STATE_{SUBMITTED,WORKING,COMPLETED,FAILED,CANCELED}. */
  state: string;
  createdAt: string;
  stateTimestamp: string;
  lastTouched: string;
  userText: string;
  /** Full text on the detail endpoint; capped preview on the list endpoint. */
  reply: string;
  replyTruncated: boolean;
  replyLength: number;
  failureMessage: string;
  terminal: boolean;
}

export interface A2aConsolePolicy {
  client: {
    enabled: boolean;
    allowedHosts: string[];
    requireSignedCard: boolean;
    maxCallsPerTurn: number;
    maxWaitSeconds: number;
  };
  server: {
    enabled: boolean;
    jsonRpcPath: string;
    restPath: string;
    agentJsonRpcPath: string;
    agentRestPath: string;
    agentCardPath: string;
    platformCardPath: string;
    jwksPath: string;
  };
}

const QUIET = [401, 403, 404, 405];

export interface A2aTaskQuery {
  direction?: A2aDirection | "";
  state?: string;
  agentId?: string;
  limit?: number;
}

export const a2aApi = {
  // `fresh` skips the 15s GET cache - the task list is inherently live data
  // and the console's Refresh button must actually hit the server.
  a2aListTasks: (query: A2aTaskQuery = {}, fresh = false): Promise<{ tasks: A2aTaskRow[] }> => {
    const params = new URLSearchParams();
    if (query.direction) params.set("direction", query.direction);
    if (query.state) params.set("state", query.state);
    if (query.agentId) params.set("agentId", query.agentId);
    if (query.limit) params.set("limit", String(query.limit));
    const qs = params.toString();
    return apiFetch(
      `/api/a2a/console/tasks${qs ? `?${qs}` : ""}`,
      fresh ? { skipGetCache: true } : {}
    ).then(toJsonQuietOn(QUIET));
  },

  a2aGetTask: (id: string): Promise<A2aTaskRow> =>
    apiFetch(`/api/a2a/console/tasks/${encodeURIComponent(id)}`).then(toJsonQuietOn(QUIET)),

  a2aCancelTask: (id: string): Promise<A2aTaskRow> =>
    apiFetch(`/api/a2a/console/tasks/${encodeURIComponent(id)}:cancel`, { method: "POST" }).then(
      toJson<A2aTaskRow>
    ),

  a2aPolicy: (): Promise<A2aConsolePolicy> =>
    apiFetch(`/api/a2a/console/policy`).then(toJsonQuietOn(QUIET)),
};
